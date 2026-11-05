# Shared DSP chain

`shared-domain` owns the one software DSP chain the platforms share: the ordered graph, the settings value,
the stored document and the readers for every equalizer format the app already wrote. The chain is
implemented and unit-tested. Android feeds it through one Media3 processor on the standard route
(`UnifiedDspAudioProcessor`), and the desktop and iOS hosts read their equalizer, normalization and preamp
from that one value; the desktop engine and the iOS shell render no `DspGraph` block yet.

Source: `shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/dsp/`.

## Chain order

`DspGraph` processes interleaved float32 frames in this order and no other:

1. Input gain: the track's measured normalization correction (`linearGain`, applied only while
   normalization is on) times the user preamp (`preampDb`, applied only while its own switch
   `preampEnabled` is on). A host that folds its preamp into its own normalization gain leaves `preampDb`
   at 0 rather than counting the same decibels twice.
2. Equalizer cascade: one biquad section per band (`eq/PortableEqualizer`), glided by default.
3. Clarity contour: the fixed six-section Studio Master Clarity preset (`clarity/ClarityStage`).
4. DC blocker (`DcBlocker`), then soft-knee limiter (`SoftKneeLimiter`).
5. Tempo and pitch (`tempo/TempoPitchStage`); this is the only stage that can change the frame count.
6. Transition gain ramp (`TransitionGainRamp`), then the full-scale clamp to ±1.
7. Optional TPDF dither (`TpdfDither`) at the configured bit depth.

Every stage is gated twice: `SoftwareStagePlan` says what the route allows, `DspSettings` says what the
listener asked for, and a stage runs only when both allow it. Notes that matter to an adapter:

- `plan.bypass` skips everything: the graph copies input to output, bit for bit, and clears every stage's
  history when it enters bypass so a later engaged block cannot inherit it.
- Neutral settings (`DspSettings.isNeutral` with a steady unity transition gain) also hand samples straight
  through, apart from the clamp.
- The clarity contour is treated as a tone stage, so `plan.equalizer` gates it together with the user
  equalizer; `settings.clarity == null` keeps it out of the chain.
- `push`/`pull` are allocation-free once block sizes settle. Size the output with
  `requiredOutputCapacity(inputFrames)`, drain until `pendingFrames == 0`, call `finish()` at end of stream,
  `reset()` at a seek, and `configure(DspFormat)` only off the audio thread because it allocates.
- `chain/PortableDspChain` is a façade kept for its existing one-shot callers (`DspPortableChainTest`,
  `desktopApp/.../DesktopTransitionDspIntegrationTest`). New callers use `DspGraph`.

## Settings storage

`DspSettings` is one immutable value with a neutral default (`linearGain = 1`, no bands, no clarity, no
limiter, no dither, speed/pitch neutral); its `init` rejects anything the chain cannot run. `linearGain` is
the normalization correction alone: normalization being switched off must not also drop the user preamp, so
the two factors are gated separately and `preampDb` keeps its stored value while `preampEnabled` is off.

`DspSettingsCodec` stores it as one versioned JSON object under a single key (`FORMAT_VERSION = 1`), with an
explicit field for every setting: `version`, `linearGain`, `preampDb`, `preamp` (the preamp switch, absent in
an older document reads as on), `normalization`, `equalizer`, `bands`
(`type`/`hz`/`gainDb`/`q` plus optional `rightHz`/`rightGainDb`/`rightQ`), `clarity` (preset id),
`dcBlocker`, `limiter`, `limiterCeilingDb`, `speed`, `pitchSemitones`, `transitionGain`, and `dither`
(`enabled`/`bitDepth`/`seed`).

`DspSettingsDocument` is the host's one read of that value, under the key `dspSettingsJson`: it returns the
stored document when there is one, migrates the legacy keys only while there is none, and writes the document
back on every change. The legacy keys are never rewritten, so an older build keeps reading them. Text that is
not one JSON object counts as absent rather than as a neutral value, so a damaged document falls back to the
keys the host already stored instead of silently flattening the chain.

`GraphicEqualizer` holds the ten-band layout a host without a device band list shows (centres 31.25 Hz..16 kHz,
one octave of width, millibel levels), so both hosts store and apply the same bands; a document another host
wrote with other bands shows flat until the first edit writes this layout. `DspSettingsOwner` (shared-ui) is
the settings surface those hosts mount: it reads the document once, applies each value to the playback port and
saves one blob per edit. It publishes two things from that stored value: what the panel shows, and what the
host's engine renders. The Playback page's one **Audio processing** switch gates the second alone — while it is
off the owner hands the engine `DspSettings.withoutProcessing()` (flat equalizer, unity preamp, no
normalization), while the stored document keeps the presets it will render again when the switch returns. The
Playback page's normalization row writes its own legacy key, so the owner mirrors the one document value into
that key on every save and adopts the row's edit as a document change; the document stays the authority, and
the row shows the value the chain applies.

Decoding is tolerant on purpose, because a stored blob must never be able to stop playback: a blank,
unreadable or non-object document reads as `DspSettings()`; a missing, non-numeric or out-of-range field
keeps its default; bounded values are clamped (preamp ±12 dB, limiter ceiling −24..0 dB, speed 0.5..4,
pitch ±12 semitones, transition gain 0..4, dither bit depth 8..32); a band without a usable shape is
dropped, its gain clamped to ±24 dB and its Q floored at 0.1; the band list is capped at
`MAX_EQUALIZER_BANDS = 16`; an unknown clarity id drops the contour; unknown keys are ignored.

## Reading the legacy formats

`DspSettingsMapper` reads what the app already persists, without the host having to migrate anything.
`PersistedDspValues` names the preference each field comes from; `fromPersisted(values, bandCentresHz)`
returns one `DspSettings`.

| Stored key | Reader | Behaviour |
|---|---|---|
| `equalizerBandLevelsMb` | `bandsFromLevelsMb` | Millibel JSON array (`EqualizerBandLevels`), resampled by index onto the caller's band centres; blank or unreadable reads flat |
| `equalizerOutputGainMb` | `fromPersisted` | Preamp in millibels, read only while `equalizerOutputGainEnabled` is on, clamped to ±12 dB |
| `equalizerOutputGainEnabled` | `fromPersisted` | The preamp's own switch, carried into `DspSettings.preampEnabled`; off leaves the stored gain unapplied but keeps a retired stage's own preamp, because that stage applied it whenever it ran |
| `lw_eq_gains` | `bandsFromLastWaveGains` | Fifteen comma-separated decibels on their ISO centres (25 Hz..16 kHz); any other width is not guessed at and yields no bands; each gain clamped to ±24 dB |
| `lw_eq_enabled`, `lastwaveAudioProcessing` | `fromPersisted` | LastWave's fifteen gains run only while both the engine and its equalizer substage were switched on |
| `tryptifyAudioProcessing`, `eq_enabled`, `param_eq_enabled` | `fromPersisted` | A Tryptify stage runs only while the engine and that stage were switched on: `eq_enabled` is the graphic stage, `param_eq_enabled` the parametric one |
| `param_eq_bands_json`, `eq_bands_json` | `bandsFromTryptifyJson` | `{type, freq, gain, q, enabled}`; `LOWSHELF`/`HIGHSHELF` map to the shared shelf shapes, a missing or `PEAKING` type stays peaking, anything else, a disabled band or an invalid entry is dropped, gain clamped and Q floored at 0.1, capped at 16 |
| `eq_stereo_mode`, `eq_bands_r_json` | `withRightOverrides` | The graphic stage's right-channel graph becomes each band's `rightHz`/`rightGainDb`/`rightQ` override when it has the same shape as the left one; a linked (identical) band keeps no override |
| `eq_preamp`, `param_eq_preamp` | `migratedStagePreampDb` | The preamp of the stage that supplies the bands is added to the shared output gain, then clamped to ±12 dB |
| `lw_music_enhancer`, `lw_clarity_preset` | `clarityFromStored` | The preset index (out of range reads as Reference) only while the LastWave engine and the switch are both on; off means no contour |
| `floatDspEnabled` | `fromPersisted` | The native float chain owned the DC blocker, limiter and TPDF dither, so the model enables exactly those three |
| `playbackSpeed`, `playbackPitch` | `fromPersisted` | Multipliers bounded to 0.5..4 speed and 0.25..2 pitch (converted to semitones by `PitchConversion`); a non-positive pitch multiplier is neutral |
| `audioNormalization` | `fromPersisted` | The normalization switch |
| `dsp_state_json` | none | Tryptify's multi-bus preset graph is deliberately not mapped: it describes a plugin bus graph the unified chain has no place for |

Band precedence is the parametric Tryptify stage, then the graphic Tryptify stage, then the legacy fifteen
gains, then the shared millibel levels — but only over the stages that were switched on. A stage that was off
keeps its stored curve on disk and contributes nothing, so switching it on again in an older build still
restores it.

### Known migration limitations

The shared model carries one band list, one preamp and one right-channel override per band. Three stored
shapes therefore cannot be represented, and they are left inactive rather than approximated:

- **Both Tryptify stages enabled at once.** The parametric stage is migrated, because it is the more specific
  curve, and the graphic curve stays stored but inactive. A user who then disables the parametric stage gets
  the graphic curve back.
- **A right-channel graph of another width.** `eq_stereo_mode`'s `eq_bands_r_json` is paired band for band
  only when both lists decode to the same count; otherwise the bands run linked and the stored right graph
  stays untouched.
- **A right-channel band of another type.** The shared override carries frequency, gain and Q, not a second
  band type, so a stereo pair whose right band is a shelf while the left is peaking migrates with the left
  channel's type on both channels.

A desktop or iOS host does not read that table itself: `DspSettingsDocument.legacyOf(snapshot)` fills
`PersistedDspValues` from the shared keys those hosts already wrote (`equalizerEnabled`,
`equalizerBandLevelsMb`, `equalizerOutputGainMb` and `equalizerOutputGainEnabled`, and `audioNormalization`),
and the result is used only until the document exists. A host that never wrote them reads the normalization
default the Playback page declares, a flat equalizer and a preamp that is off.

## What the platform adapters do next

- **Android**: `UnifiedDspAudioProcessor` wraps `DspGraph` in one Media3 `AudioProcessor` and replaces the
  Tryptify, LastWave and float-DSP engines on the standard route. It takes 16-bit or float input and always
  writes 16-bit PCM, because `DefaultAudioSink` appends Media3's `SilenceSkippingAudioProcessor` and the
  project's `TransitionFilterProcessor` after the supplied processors and both reject every other encoding in
  `onConfigure` — including while silence skipping is switched off, since the sink configures the whole chain.
  The reduction applies the graph's full-scale clamp and, when the settings ask for it, its TPDF dither once at
  the 16-bit grid. It reconfigures on a format change off the audio thread, drains the stretch tail at end of
  stream, and resets on flush; a configure that arrives while the previous stream still drains is held until
  the sink flushes. `UnifiedDspSettingsSource` reads the stored values through `DspSettingsMapper` and
  publishes one settings value plus its plan to every live processor. The plan is bypassed whenever the
  bit-perfect gate holds the route, and the single Audio processing switch publishes
  `DspSettings.withoutProcessing()` with that bypassed plan, the same neutral rendering the shared owner applies,
  so no reader sees an enabled-looking setting next to a bypassed chain. Tempo and pitch stay with ExoPlayer's
  playback parameters and `SonicAudioProcessor`, so the shared chain's stretch stage is not requested and speed
  cannot apply twice. A bit-perfect route, or an encoding the adapter cannot read, leaves the processor
  inactive so Media3 skips it.
  `MusicService` keeps the session `android.media.audiofx.Equalizer` disabled and gives it no band levels: the
  graph owns `EqualizerEnabledKey` and `equalizerBandLevelsMb`, so applying them there as well would add the
  stored curve twice, on the device's own band centres. Bass boost, the virtualizer and automatic headroom
  still run on the session effects behind their stored switches; the output gain does not, because it is the
  graph's preamp (`FrameworkAudioEffectPolicy` decides that split).
- **Desktop**: `DesktopPlaybackPort.applyDspSettings` takes the stored `DspSettings` and hands the mixer
  coefficients designed only by `BiquadDesign` (`equalizerCoefficients` packs b0, b1, b2, a1, a2 per band); the
  mixer itself is a runner, and mixing, fades, scheduling and decoding stay native. Normalization gain comes
  from `NormalizationGain`, the preamp from `DspSettings.preampGain`, and tempo stays with the transport.
  A decoded block still does not pass through a `DspGraph`: the stages the desktop runs are the ones its
  mixer owns, so the remaining work is to decide whether the shared graph replaces them.
- **iOS**: `IosPlaybackPort.applyDspSettings` takes the same value and `IosEqBands` converts its band list for
  the `AVAudioUnitEQ` the output and the blend decks already own, with `EqualizerBandwidth` as the one
  Q-to-octaves mapping; the host carries no band model or coefficient design of its own. The user preamp rides
  that same unit's global gain (`at_audio_preamp` on the output, `at_graph_eq_gain` on a deck, both clamped to
  ±24 dB) and keeps its own switch, so turning the equalizer off does not also drop it. Each blend deck applies
  the preamp on its own unit, and a promoted deck's output carries it once more, so a crossfade never changes
  level. Pitch stays on `AVAudioUnitTimePitch` via `PitchConversion`. `AVAudioEngine` remains the I/O, so the
  remaining dynamics and the normalization correction are not rendered by `DspGraph`.
- **All**: keep buffer sizing from `requiredOutputCapacity`, one graph per output stream, and no allocation
  or locking on the render thread.

## Routes this work does not touch

- **Bit-perfect**: bypass is conditional and only `BitPerfectGate` decides it (request, no altering stage, a
  direct route that plays the format, matching output rate). `SoftwareStagePlan.resolve` narrows the request;
  adapters report facts, never bypass.
- **AAudio / USB exclusive output**: `UsbExclusiveAudioOutputProvider` and `AaudioExclusiveAudioOutput` keep
  their own stream, fallback and session reporting. Being exclusive is about who owns the DAC, not about the
  samples: this route is the standard sink's output provider, so the standard processor chain — and therefore
  the shared graph and the Audio processing switch — still runs ahead of it. Only the bit-perfect USB sink is
  built without a processor chain, and that is the one route where nothing touches the decoder's samples.
- **Retired LastWave / Tryptify engines**: their stored `lw_*`, `eq_bands_json` and `param_eq_bands_json`
  keys stay readable and are what the unified chain reads for tone and clarity, and `dsp_state_json` stays on
  disk even though nothing reads its bus graph. The engine classes, their native targets and the vendored soxr
  are gone; `archivetune_dsp` keeps only the AAudio exclusive stream.

## Checks

`./gradlew :shared-domain:jvmTest` (or `:shared-domain:allTests`) covers the graph, the biquad golden
vectors, the codec, the mapper, the stored document, the graphic layout and the clarity presets.
`:desktopApp:jvmTest` covers the coefficients and the packed layout the desktop host hands its native mixer
(`DesktopEqualizerCoefficientsTest`). Unit tests are not device audio
evidence: no physical output, DAC, USB or bit-perfect route is verified by them, and neither host has been
run on a device since the adapters changed.
