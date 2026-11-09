package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneSpotifyStreamingSettingsReference = PhoneSettingsReference(Res.string.m5pq_source_spotify_stream, listOf(
    PhoneSettingReference("group:spotify_stream_account", Res.string.m6_spotify_stream_account, null, PhoneSettingKind.HEADER, setOf("spotify_stream_account"), null, setOf(Res.string.m6_spotify_stream_account), false),
    PhoneSettingReference("extensions_spotify_official", Res.string.phone_reference_extensions_spotify_official, null, PhoneSettingKind.TOGGLE, setOf("extensions_spotify_official"), null, setOf(), false),
    PhoneSettingReference("spotify_stream_account", Res.string.phone_reference_spotify_stream_librespot_signed_in, null, PhoneSettingKind.ACTION, setOf("spotify_stream_librespot_signed_in"), null, setOf(), true),
    PhoneSettingReference("spotify_stream_librespot_sign_out", Res.string.phone_reference_spotify_stream_librespot_sign_out, Res.drawable.phone_reference_logout, PhoneSettingKind.ACTION, setOf("spotify_stream_librespot_sign_out"), null, setOf(), true),
    PhoneSettingReference("spotify_stream_sign_out", Res.string.m6_spotify_stream_sign_out, Res.drawable.phone_reference_logout, PhoneSettingKind.ACTION, setOf("spotify_stream_sign_out"), null, setOf(Res.string.m6_spotify_stream_sign_out), true),
    PhoneSettingReference("group:source_spotify_stream", Res.string.m5pq_source_spotify_stream, null, PhoneSettingKind.HEADER, setOf("source_spotify_stream"), null, setOf(Res.string.m5pq_source_spotify_stream, Res.string.m6_dev_source_spotify_streaming, Res.string.m6_integration_spotify_streaming, Res.string.m6_settings_home_spotify_streaming), false),
    PhoneSettingReference("spotify_stream_engine", Res.string.m6_spotify_stream_engine, null, PhoneSettingKind.VALUE, setOf("spotify_stream_engine"), "spotifyStreamEngine", setOf(Res.string.m6_spotify_stream_engine), false),
    PhoneSettingReference("spotify_stream_binding", Res.string.m6_spotify_stream_binding, Res.drawable.phone_reference_info, PhoneSettingKind.ACTION, setOf("spotify_stream_binding"), null, setOf(Res.string.m6_spotify_stream_binding), false),
    PhoneSettingReference("spotify_stream_plan", Res.string.m6_spotify_stream_plan, Res.drawable.phone_reference_info, PhoneSettingKind.ACTION, setOf("spotify_stream_plan"), null, setOf(Res.string.m6_spotify_stream_plan), false),
    PhoneSettingReference("spotify_stream_engine_degraded_title", Res.string.m6_spotify_stream_engine_degraded_title, Res.drawable.phone_reference_info, PhoneSettingKind.ACTION, setOf("spotify_stream_engine_degraded_title"), null, setOf(Res.string.m6_spotify_stream_engine_degraded_title), true),
    PhoneSettingReference("spotify_stream_method", Res.string.m6_spotify_stream_method, Res.drawable.phone_reference_equalizer, PhoneSettingKind.VALUE, setOf("spotify_stream_method"), "spotifyStreamResolveMethod", setOf(Res.string.m6_spotify_stream_method), true),
    PhoneSettingReference("spotify_stream_method_note_title", Res.string.m6_spotify_stream_method_note_title, Res.drawable.phone_reference_info, PhoneSettingKind.ACTION, setOf("spotify_stream_method_note_title"), null, setOf(Res.string.m6_spotify_stream_method_note_title), true),
))
