# Shared DSP chain

`shared-domain` owns the one software DSP chain the platforms share: the ordered graph, the settings value,
the stored document and the readers for every equalizer format the app already wrote. The chain is
implemented and unit-tested. Android feeds it through one Media3 processor on the standard route
(`UnifiedDspAudioProcessor`), and the desktop and iOS hosts read their equalizer, normalization and preamp
from that one value; the desktop engine and the iOS shell render no `DspGraph` block yet.

Source: `shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/dsp/`.

## Chain order

`DspGraph` processes interleaved float32 frames in this order and no other:

1. Input gain: track normalization gain `×` user preamp (`DspSettings.linearInputGain`).
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
limiter, no dither, speed/pitch neutral); its `init` rejects anything the chain cannot run.

`DspSettingsCodec` stores it as one versioned JSON object under a single key (`FORMAT_VERSION = 1`), with an
explicit field for every setting: `version`, `linearGain`, `preampDb`, `normalization`, `equalizer`, `bands`
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
saves one blob per edit.

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
| `equalizerOutputGainMb` | `fromPersisted` | Preamp in millibels, clamped to ±12 dB |
| `lw_eq_gains` | `bandsFromLastWaveGains` | Fifteen comma-separated decibels on their ISO centres (25 Hz..16 kHz); any other width is not guessed at and yields no bands; each gain clamped to ±24 dB |
| `lw_eq_enabled`, `equalizerEnabled`, Tryptify switches | `fromPersisted` | The model's equalizer switch is the OR of them |
| `param_eq_bands_json`, `eq_bands_json` | `bandsFromTryptifyJson` | `{type, freq, gain, q, enabled}`; `LOWSHELF`/`HIGHSHELF` map to the shared shelf shapes, a missing or `PEAKING` type stays peaking, anything else, a disabled band or an invalid entry is dropped, gain clamped and Q floored at 0.1, capped at 16 |
| `lw_music_enhancer`, `lw_clarity_preset` | `clarityFromStored` | The preset index (out of range reads as Reference) only while the switch is on; off means no contour |
| `floatDspEnabled` | `fromPersisted` | The native float chain owned the DC blocker, limiter and TPDF dither, so the model enables exactly those three |
| `playbackSpeed`, `playbackPitch` | `fromPersisted` | Multipliers bounded to 0.5..4 speed and 0.25..2 pitch (converted to semitones by `PitchConversion`); a non-positive pitch multiplier is neutral |
| `audioNormalization` | `fromPersisted` | The normalization switch |
| `dsp_state_json` | none | Tryptify's multi-bus preset graph is deliberately not mapped: it describes a plugin bus graph the unified chain has no place for |

Band precedence is parametric Tryptify bands, then Tryptify graphic bands, then the legacy fifteen gains,
then the shared millibel levels.

A desktop or iOS host does not read that table itself: `DspSettingsDocument.legacyOf(snapshot)` fills
`PersistedDspValues` from the shared keys those hosts already wrote (`equalizerEnabled`,
`equalizerBandLevelsMb`, `equalizerOutputGainMb` while `equalizerOutputGainEnabled` is on, and
`audioNormalization`), and the result is used only until the document exists. A host that never wrote them
reads the neutral value, which keeps the desktop's pre-document behaviour (no normalization, no equalizer).

## What the platform adapters do next

- **Android**: `UnifiedDspAudioProcessor` wraps `DspGraph` in one Media3 `AudioProcessor` and replaces the
  Tryptify, LastWave and float-DSP engines on the standard route. It takes 16-bit or float input and writes
  float, reconfigures on a format change off the audio thread, drains the stretch tail at end of stream, and
  resets on flush. `UnifiedDspSettingsSource` reads the stored values through `DspSettingsMapper` and
  publishes one settings value plus its plan to every live processor, and the plan is bypassed whenever the
  bit-perfect gate holds the route. Tempo and pitch stay with ExoPlayer's playback parameters and
  `SonicAudioProcessor`, so the shared chain's stretch stage is not requested and speed cannot apply twice.
  A bit-perfect route, or an encoding the adapter cannot read, leaves the processor inactive so Media3 skips it.
- **Desktop**: `DesktopPlaybackPort.applyDspSettings` takes the stored `DspSettings` and hands the mixer
  coefficients designed only by `BiquadDesign` (`equalizerCoefficients` packs b0, b1, b2, a1, a2 per band); the
  mixer itself is a runner, and mixing, fades, scheduling and decoding stay native. Normalization gain comes
  from `NormalizationGain`, the preamp from `DspSettings.preampGain`, and tempo stays with the transport.
  A decoded block still does not pass through a `DspGraph`: the stages the desktop runs are the ones its
  mixer owns, so the remaining work is to decide whether the shared graph replaces them.
- **iOS**: `IosPlaybackPort.applyDspSettings` takes the same value and `IosEqBands` converts its band list for
  the `AVAudioUnitEQ` the output and the blend decks already own, with `EqualizerBandwidth` as the one
  Q-to-octaves mapping; the host carries no band model or coefficient design of its own. Pitch stays on
  `AVAudioUnitTimePitch` via `PitchConversion`. `AVAudioEngine` remains the I/O, so tone and dynamics are not
  rendered by `DspGraph`.
- **All**: keep buffer sizing from `requiredOutputCapacity`, one graph per output stream, and no allocation
  or locking on the render thread.

## Routes this work does not touch

- **Bit-perfect**: bypass is conditional and only `BitPerfectGate` decides it (request, no altering stage, a
  direct route that plays the format, matching output rate). `SoftwareStagePlan.resolve` narrows the request;
  adapters report facts, never bypass.
- **AAudio / USB exclusive output**: `UsbExclusiveAudioOutputProvider` and `AaudioExclusiveAudioOutput` keep
  their own stream, fallback and session reporting. The shared chain does not open, configure or replace them.
- **LastWave / Tryptify native engines**: their stored `lw_*`, `eq_bands_json` and `param_eq_bands_json`
  keys stay readable and are what the unified chain reads for tone and clarity, and `dsp_state_json` stays on
  disk even though nothing reads its bus graph. The engine classes and their native targets remain in the
  tree, but `AudioEngineHost` no longer builds, gates or routes audio through them.

## Checks

`./gradlew :shared-domain:jvmTest` (or `:shared-domain:allTests`) covers the graph, the biquad golden
vectors, the codec, the mapper, the stored document, the graphic layout and the clarity presets.
`:desktopApp:jvmTest` covers the coefficients and the packed layout the desktop host hands its native mixer
(`DesktopEqualizerCoefficientsTest`). Unit tests are not device audio
evidence: no physical output, DAC, USB or bit-perfect route is verified by them, and neither host has been
run on a device since the adapters changed.
