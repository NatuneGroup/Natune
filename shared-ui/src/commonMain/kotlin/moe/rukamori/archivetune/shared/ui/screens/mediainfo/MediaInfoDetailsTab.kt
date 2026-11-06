package moe.rukamori.archivetune.shared.ui.screens.mediainfo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_android_builtin_output
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_bit_depth_value
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_bit_out
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_bit_perfect_mixer
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_bit_perfect_usb_title
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_bitrate
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_channels_count
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_channels_mono
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_channels_stereo
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_codecs
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_conversion_resample
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_conversion_same
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_decoder_none
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_default_mixer
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_details
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_file_size
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_float_out
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_float_pcm
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_float_usb_out
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_integer_pcm
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_integer_pcm_bits
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_itag
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_local_file
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_local_library
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_lossy_source
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_loudness
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_mime_type
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_normalization_factor
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_normalization_off
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_on_device_file
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_output_conversion
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_output_device
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_output_device_rates
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_output_mix_rate
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_output_no_conversion
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_please_wait
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_amazon
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_amazon_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_apple
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_apple_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_deezer
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_deezer_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_jiosaavn
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_jiosaavn_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_qobuz
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_qobuz_backup
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_qobuz_backup_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_qobuz_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_qq
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_qq_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_spotify
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_spotify_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_tidal
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_tidal_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_youtube
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_provider_youtube_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_quality_high
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_quality_standard
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_sample_rate
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_aspect_ratio_linear
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_code
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_database
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_hash
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_headphones
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_hertz
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_ruler
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_server_linear
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_speed
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_volume
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_wave
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_bit_depth
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_channels
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_dac_device
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_decoder
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_engine
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_engine_off
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_engine_pending
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_hardware_clock
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_normalization
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_pipeline_state
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_protocol
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_quality
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_section_dac
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_section_playback
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_section_providers
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_signal_path
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_standby
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_stream_delivery
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_usb_id
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_worked
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_worked_provider
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_timer
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_unknown
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_usb_exclusive_direct
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_usb_out
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_volume
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_graphic_eq
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_bolt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Details tab, in baseline order: the live audio-chain pill for the playing track, the technical
 * stream rows (or a loading card), then for the playing track the playback, provider, DAC and
 * pipeline sections, and finally the output-route card whenever the platform probe observed one.
 * Live facts are read from [MediaInfoUi.live]; the host supplies them for the playing track only.
 */
@Composable
internal fun MediaInfoDetailsTab(
    state: MediaInfoUi,
    actions: MediaInfoActions,
    liveAudioChain: (@Composable () -> Unit)?,
) {
    if (state.isLiveTrack && liveAudioChain != null) {
        liveAudioChain()
    }
    MediaInfoTechnicalCard(state = state, onCopy = actions.onCopy)
    if (state.isLiveTrack) {
        MediaInfoPlaybackSection(state = state)
        if (!state.isLocal) {
            MediaInfoProvidersSection(state = state)
        }
        MediaInfoDacSection(state = state)
    }
    MediaInfoOutputSection(state = state)
}

/**
 * Stream format rows: itag, MIME type, codecs, bitrate, sample rate, loudness, volume, file size,
 * then the live bit depth, channel count and quality tier. Every row copies its value.
 */
@Composable
private fun MediaInfoTechnicalCard(
    state: MediaInfoUi,
    onCopy: (String) -> Unit,
) {
    val pleaseWaitText = stringResource(Res.string.m9_media_info_please_wait)
    val detailsTitle = stringResource(Res.string.m9_media_info_details)
    val itagLabel = stringResource(Res.string.m9_media_info_itag)
    val mimeTypeLabel = stringResource(Res.string.m9_media_info_mime_type)
    val codecsLabel = stringResource(Res.string.m9_media_info_codecs)
    val bitrateLabel = stringResource(Res.string.m9_media_info_bitrate)
    val sampleRateLabel = stringResource(Res.string.m9_media_info_sample_rate)
    val loudnessLabel = stringResource(Res.string.m9_media_info_loudness)
    val volumeLabel = stringResource(Res.string.m9_media_info_volume)
    val fileSizeLabel = stringResource(Res.string.m9_media_info_file_size)
    val bitDepthLabel = stringResource(Res.string.m9_media_info_ti_bit_depth)
    val channelsRowLabel = stringResource(Res.string.m9_media_info_ti_channels)
    val qualityRowLabel = stringResource(Res.string.m9_media_info_ti_quality)

    val format = state.format
    val stream = state.live?.stream
    val pipeline = state.live?.pipeline
    val bitrateKbps = format?.bitrateBps?.takeIf { it > 0 }?.let { it / 1000 }
    val bitDepth = stream?.bitDepth?.takeIf { it > 0 } ?: pipeline?.decodedBits?.takeIf { it > 0 }
    val channels = pipeline?.chainChannels ?: pipeline?.decodedChannels
    val qualityValue = state.streamQualityLabel ?: qualityTierText(bitrateKbps)
    val bitDepthValue =
        if (bitDepth != null) stringResource(Res.string.m9_media_info_bit_depth_value, bitDepth) else null

    val rows = mutableListOf<MediaInfoDetailRow>()
    val itag = format?.itag ?: 0
    if (itag > 0) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_ruler, itagLabel, itag.toString()))
    }
    val mimeType = format?.mimeType?.takeIf { it.isNotBlank() }
    if (mimeType != null) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_database, mimeTypeLabel, mimeType))
    }
    val codecs = format?.codecs?.takeIf { it.isNotBlank() }
    if (codecs != null) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_code, codecsLabel, codecs))
    }
    if (bitrateKbps != null) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_speed, bitrateLabel, "$bitrateKbps Kbps"))
    }
    val sampleRateHz = format?.sampleRateHz?.takeIf { it > 0 }
    if (sampleRateHz != null) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_hertz, sampleRateLabel, "$sampleRateHz Hz"))
    }
    val loudnessDb = format?.loudnessDb
    if (loudnessDb != null) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_volume, loudnessLabel, "$loudnessDb dB"))
    }
    val volume = state.playbackVolume
    if (volume != null) {
        rows.add(
            MediaInfoDetailRow(
                Res.drawable.m9_media_info_solar_headphones,
                volumeLabel,
                "${(volume * 100f).toInt()}%",
            ),
        )
    }
    val fileSize = mediaInfoFileSizeText(state)
    if (fileSize != null) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_database, fileSizeLabel, fileSize))
    }
    if (bitDepthValue != null) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_ruler, bitDepthLabel, bitDepthValue))
    }
    if (channels != null) {
        rows.add(
            MediaInfoDetailRow(
                Res.drawable.m9_media_info_solar_headphones,
                channelsRowLabel,
                channelsText(channels),
            ),
        )
    }
    if (qualityValue != null) {
        rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_wave, qualityRowLabel, qualityValue))
    }

    if (rows.isEmpty()) {
        MediaInfoPendingCard(title = detailsTitle, message = pleaseWaitText)
    } else {
        MediaInfoRowsCard(rows = rows, onCopy = onCopy)
    }
}

/**
 * Playback section for the playing track: engine, worked provider, stream delivery, protocol and
 * normalization. Local files name the on-device library instead of a provider.
 */
@Composable
private fun MediaInfoPlaybackSection(state: MediaInfoUi) {
    val live = state.live
    val stream = live?.stream
    val pipeline = live?.pipeline
    val unknownText = stringResource(Res.string.m9_media_info_unknown)
    val title = stringResource(Res.string.m9_media_info_ti_section_playback)
    val engineLabel = stringResource(Res.string.m9_media_info_ti_engine)
    val engineOffText = stringResource(Res.string.m9_media_info_ti_engine_off)
    val workedProviderLabel = stringResource(Res.string.m9_media_info_ti_worked_provider)
    val localLibraryText = stringResource(Res.string.m9_media_info_local_library)
    val deliveryLabel = stringResource(Res.string.m9_media_info_ti_stream_delivery)
    val onDeviceText = stringResource(Res.string.m9_media_info_on_device_file)
    val protocolLabel = stringResource(Res.string.m9_media_info_ti_protocol)
    val localFileText = stringResource(Res.string.m9_media_info_local_file)
    val normalizationLabel = stringResource(Res.string.m9_media_info_ti_normalization)
    val normalizationOffText = stringResource(Res.string.m9_media_info_normalization_off)

    val pendingEngine = pipeline?.pendingEngineName
    val engineName = pipeline?.engineName
    val engineValue: String =
        if (pendingEngine != null) {
            stringResource(Res.string.m9_media_info_ti_engine_pending, pendingEngine)
        } else {
            engineName ?: engineOffText
        }
    val source = stream?.source
    val providerValue: String =
        if (source != null) {
            providerDisplayName(source)
        } else if (state.isLocal) {
            localLibraryText
        } else {
            unknownText
        }
    val deliveryValue = stream?.deliveryLabel ?: if (state.isLocal) onDeviceText else unknownText
    val protocolValue = stream?.protocol ?: if (state.isLocal) localFileText else unknownText
    val normalizeFactor = live?.normalizeFactor ?: 1f
    val normalizationValue: String =
        if (normalizeFactor != 1f) {
            stringResource(
                Res.string.m9_media_info_normalization_factor,
                formatFixedDecimal(normalizeFactor.toDouble(), 3),
            )
        } else {
            normalizationOffText
        }

    MediaInfoSectionCard(
        title = title,
        rows =
            listOf(
                MediaInfoDetailRow(Res.drawable.m9_media_info_graphic_eq, engineLabel, engineValue),
                MediaInfoDetailRow(Res.drawable.m9_media_info_bolt, workedProviderLabel, providerValue),
                MediaInfoDetailRow(Res.drawable.m9_media_info_solar_server_linear, deliveryLabel, deliveryValue),
                MediaInfoDetailRow(Res.drawable.m9_media_info_solar_code, protocolLabel, protocolValue),
                MediaInfoDetailRow(Res.drawable.m9_media_info_solar_volume, normalizationLabel, normalizationValue),
            ),
    )
}

/**
 * Real-providers card for streamed tracks: every provider the resolver could use, with the one
 * that served the stream marked WORKED. Nothing is shown when no provider list was supplied.
 */
@Composable
private fun MediaInfoProvidersSection(state: MediaInfoUi) {
    val available = state.live?.availableProviders.orEmpty()
    if (available.isEmpty()) return
    val title = stringResource(Res.string.m9_media_info_ti_section_providers)
    val workedLabel = stringResource(Res.string.m9_media_info_ti_worked)
    val standbyLabel = stringResource(Res.string.m9_media_info_ti_standby)
    val servedBy = state.live?.stream?.source
    val providers =
        available.map { source ->
            MediaInfoProviderItem(
                name = providerDisplayName(source),
                note = providerNote(source),
                worked = source == servedBy,
            )
        }
    MediaInfoProvidersCard(
        title = title,
        providers = providers,
        workedLabel = workedLabel,
        standbyLabel = standbyLabel,
    )
}

/**
 * Hardware DAC and output route for the playing track: the routed device, the signal path,
 * the hardware clock, the USB identifier when the host reported one, the bit-perfect pipeline
 * state when the source rate is known, and the decoder.
 */
@Composable
private fun MediaInfoDacSection(state: MediaInfoUi) {
    val live = state.live
    val pipeline = live?.pipeline
    val bitPerfect = live?.bitPerfect
    val unknownText = stringResource(Res.string.m9_media_info_unknown)
    val title = stringResource(Res.string.m9_media_info_ti_section_dac)
    val dacDeviceLabel = stringResource(Res.string.m9_media_info_ti_dac_device)
    val builtInOutputText = stringResource(Res.string.m9_media_info_android_builtin_output)
    val signalPathLabel = stringResource(Res.string.m9_media_info_ti_signal_path)
    val usbExclusiveText = stringResource(Res.string.m9_media_info_usb_exclusive_direct)
    val bitPerfectUsbTitle = stringResource(Res.string.m9_media_info_bit_perfect_usb_title)
    val mixerText = stringResource(Res.string.m9_media_info_default_mixer)
    val hardwareClockLabel = stringResource(Res.string.m9_media_info_ti_hardware_clock)
    val usbIdLabel = stringResource(Res.string.m9_media_info_ti_usb_id)
    val pipelineStateLabel = stringResource(Res.string.m9_media_info_ti_pipeline_state)
    val decoderLabel = stringResource(Res.string.m9_media_info_ti_decoder)
    val decoderNoneText = stringResource(Res.string.m9_media_info_decoder_none)

    val signalPathValue: String =
        when {
            pipeline?.usbExclusive == true -> usbExclusiveText
            live?.bitPerfectUsbActive == true -> bitPerfectUsbTitle
            else -> mixerText
        }
    val liveSampleRateHz: Int? =
        live?.stream?.sampleRateHz?.takeIf { it > 0 }
            ?: state.format?.sampleRateHz?.takeIf { it > 0 }
    val hardwareClockHz: Int? =
        pipeline?.usbExclusiveRateHz?.takeIf { it > 0 }
            ?: pipeline?.halSampleRateHz
            ?: liveSampleRateHz
    val hardwareClockValue = if (hardwareClockHz != null) formatHz(hardwareClockHz) else unknownText

    val rows = mutableListOf<MediaInfoDetailRow>()
    rows.add(
        MediaInfoDetailRow(
            Res.drawable.m9_media_info_solar_headphones,
            dacDeviceLabel,
            pipeline?.routedName ?: builtInOutputText,
        ),
    )
    rows.add(
        MediaInfoDetailRow(
            Res.drawable.m9_media_info_solar_aspect_ratio_linear,
            signalPathLabel,
            signalPathValue,
        ),
    )
    rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_timer, hardwareClockLabel, hardwareClockValue))
    if (pipeline != null && pipeline.usbVendorId >= 0 && pipeline.usbProductId >= 0) {
        rows.add(
            MediaInfoDetailRow(
                Res.drawable.m9_media_info_solar_hash,
                usbIdLabel,
                formatUsbHardwareId(pipeline.usbVendorId, pipeline.usbProductId),
            ),
        )
    }
    // Only the USB exclusive output publishes the bit-perfect status; without a source rate the
    // row would describe stock-path defaults, so it stays hidden.
    if (bitPerfect != null && bitPerfect.sourceSampleRateHz > 0) {
        rows.add(
            MediaInfoDetailRow(
                Res.drawable.m9_media_info_solar_wave,
                pipelineStateLabel,
                mediaInfoPipelineStateText(bitPerfect, pipeline, live?.usbWireBitDepth),
            ),
        )
    }
    rows.add(
        MediaInfoDetailRow(
            Res.drawable.m9_media_info_solar_code,
            decoderLabel,
            pipeline?.decoderName ?: decoderNoneText,
        ),
    )
    MediaInfoSectionCard(title = title, rows = rows)
}

/**
 * Output route card: the device, its supported rates, the Android mix rate and the sample-rate
 * conversion between the source and the route. Shown only when the platform probe observed data.
 */
@Composable
private fun MediaInfoOutputSection(state: MediaInfoUi) {
    val stats = state.outputStats?.takeIf { it.hasData } ?: return
    val deviceLabel = stringResource(Res.string.m9_media_info_output_device)
    val deviceRatesLabel = stringResource(Res.string.m9_media_info_output_device_rates)
    val mixRateLabel = stringResource(Res.string.m9_media_info_output_mix_rate)
    val conversionLabel = stringResource(Res.string.m9_media_info_output_conversion)
    val noConversionText = stringResource(Res.string.m9_media_info_output_no_conversion)

    val sourceRateHz = state.format?.sampleRateHz?.takeIf { it > 0 }
    val routeRateHz = outputRouteRateHz(stats)
    val conversionValue: String =
        if (sourceRateHz != null && routeRateHz != null) {
            if (sourceRateHz == routeRateHz) {
                stringResource(Res.string.m9_media_info_conversion_same, routeRateHz)
            } else {
                stringResource(Res.string.m9_media_info_conversion_resample, sourceRateHz, routeRateHz)
            }
        } else {
            noConversionText
        }

    val rows = mutableListOf<MediaInfoDetailRow>()
    rows.add(
        MediaInfoDetailRow(
            Res.drawable.m9_media_info_solar_headphones,
            deviceLabel,
            "${stats.deviceLabel} · ${stats.deviceTypeLabel}",
        ),
    )
    if (stats.deviceSampleRates.isNotEmpty()) {
        rows.add(
            MediaInfoDetailRow(
                Res.drawable.m9_media_info_solar_hertz,
                deviceRatesLabel,
                stats.deviceSampleRates.joinToString(" / ") { "$it Hz" },
            ),
        )
    }
    if (stats.mixSampleRateHz > 0) {
        rows.add(
            MediaInfoDetailRow(
                Res.drawable.m9_media_info_solar_wave,
                mixRateLabel,
                "${stats.mixSampleRateHz} Hz",
            ),
        )
    }
    rows.add(MediaInfoDetailRow(Res.drawable.m9_media_info_solar_speed, conversionLabel, conversionValue))

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { index, row ->
                MediaInfoRow(
                    icon = row.icon,
                    label = row.label,
                    value = row.value,
                    showDivider = index != rows.lastIndex,
                    onClick = null,
                )
            }
        }
    }
}

/**
 * Pipeline state as "in form → out form": the decoded or source PCM on the left and the output
 * format (USB wire, bit-perfect mixer, or Android output) on the right.
 */
@Composable
private fun mediaInfoPipelineStateText(
    bitPerfect: MediaInfoBitPerfectStatus,
    pipeline: MediaInfoPipelineFacts?,
    usbWireBitDepth: Int?,
): String {
    val decodedBits = pipeline?.decodedBits?.takeIf { it > 0 }
    val inForm: String =
        when {
            bitPerfect.sourceIsLossy -> stringResource(Res.string.m9_media_info_lossy_source)
            bitPerfect.sourceFloatEncoding -> stringResource(Res.string.m9_media_info_float_pcm)
            bitPerfect.sourceBitDepth > 0 ->
                stringResource(Res.string.m9_media_info_integer_pcm_bits, bitPerfect.sourceBitDepth)
            pipeline?.decodedFloat == true -> stringResource(Res.string.m9_media_info_float_pcm)
            decodedBits != null -> stringResource(Res.string.m9_media_info_integer_pcm_bits, decodedBits)
            else -> stringResource(Res.string.m9_media_info_integer_pcm)
        }
    val outBits = bitPerfect.outputBitDepth
    val outForm: String =
        when {
            pipeline?.usbExclusive == true -> {
                val wireBits = usbWireBitDepth?.takeIf { it > 0 } ?: outBits
                if (bitPerfect.outputFloatEncoding) {
                    stringResource(Res.string.m9_media_info_float_usb_out, wireBits)
                } else {
                    stringResource(Res.string.m9_media_info_usb_out, wireBits)
                }
            }
            bitPerfect.mixerBitPerfectActive ->
                stringResource(Res.string.m9_media_info_bit_perfect_mixer, outBits)
            bitPerfect.outputFloatEncoding -> stringResource(Res.string.m9_media_info_float_out, outBits)
            else -> stringResource(Res.string.m9_media_info_bit_out, outBits)
        }
    return "$inForm → $outForm"
}

@Composable
private fun qualityTierText(bitrateKbps: Int?): String? {
    val kbps = bitrateKbps ?: 0
    return when {
        kbps >= 320 -> stringResource(Res.string.m9_media_info_quality_high)
        kbps > 0 -> stringResource(Res.string.m9_media_info_quality_standard)
        else -> null
    }
}

@Composable
private fun channelsText(channels: Int): String =
    when (channels) {
        1 -> stringResource(Res.string.m9_media_info_channels_mono)
        2 -> stringResource(Res.string.m9_media_info_channels_stereo)
        else -> stringResource(Res.string.m9_media_info_channels_count, channels)
    }

@Composable
private fun providerDisplayName(source: MediaProviderKey): String =
    stringResource(providerNameResource(source))

@Composable
private fun providerNote(source: MediaProviderKey): String =
    stringResource(providerNoteResource(source))

private fun providerNameResource(source: MediaProviderKey): StringResource =
    when (source) {
        MediaProviderKey.TIDAL -> Res.string.m9_media_info_provider_tidal
        MediaProviderKey.QOBUZ -> Res.string.m9_media_info_provider_qobuz
        MediaProviderKey.QOBUZ_BACKUP -> Res.string.m9_media_info_provider_qobuz_backup
        MediaProviderKey.DEEZER -> Res.string.m9_media_info_provider_deezer
        MediaProviderKey.APPLE -> Res.string.m9_media_info_provider_apple
        MediaProviderKey.AMAZON -> Res.string.m9_media_info_provider_amazon
        MediaProviderKey.QQ -> Res.string.m9_media_info_provider_qq
        MediaProviderKey.SPOTIFY -> Res.string.m9_media_info_provider_spotify
        MediaProviderKey.JIOSAAVN -> Res.string.m9_media_info_provider_jiosaavn
        MediaProviderKey.YOUTUBE -> Res.string.m9_media_info_provider_youtube
    }

private fun providerNoteResource(source: MediaProviderKey): StringResource =
    when (source) {
        MediaProviderKey.TIDAL -> Res.string.m9_media_info_provider_tidal_note
        MediaProviderKey.QOBUZ -> Res.string.m9_media_info_provider_qobuz_note
        MediaProviderKey.QOBUZ_BACKUP -> Res.string.m9_media_info_provider_qobuz_backup_note
        MediaProviderKey.DEEZER -> Res.string.m9_media_info_provider_deezer_note
        MediaProviderKey.APPLE -> Res.string.m9_media_info_provider_apple_note
        MediaProviderKey.AMAZON -> Res.string.m9_media_info_provider_amazon_note
        MediaProviderKey.QQ -> Res.string.m9_media_info_provider_qq_note
        MediaProviderKey.SPOTIFY -> Res.string.m9_media_info_provider_spotify_note
        MediaProviderKey.JIOSAAVN -> Res.string.m9_media_info_provider_jiosaavn_note
        MediaProviderKey.YOUTUBE -> Res.string.m9_media_info_provider_youtube_note
    }
