package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneStorageSettingsReference = PhoneSettingsReference(Res.string.m6_settings_home_group_storage, listOf(
    PhoneSettingReference("group:song_cache", Res.string.phone_reference_song_cache, null, PhoneSettingKind.HEADER, setOf("song_cache"), null, setOf(), false),
    PhoneSettingReference("max_song_cache_size", Res.string.phone_reference_max_song_cache_size, Res.drawable.phone_reference_ic_music, PhoneSettingKind.VALUE, setOf("max_song_cache_size"), null, setOf(), false),
    PhoneSettingReference("clear_song_cache", Res.string.m6_clear_song_cache, null, PhoneSettingKind.ACTION, setOf("clear_song_cache"), null, setOf(Res.string.m6_clear_song_cache), false),
    PhoneSettingReference("group:image_cache", Res.string.phone_reference_image_cache, null, PhoneSettingKind.HEADER, setOf("image_cache"), null, setOf(), false),
    PhoneSettingReference("max_image_cache_size", Res.string.phone_reference_max_image_cache_size, Res.drawable.phone_reference_image, PhoneSettingKind.VALUE, setOf("max_image_cache_size"), null, setOf(), false),
    PhoneSettingReference("clear_image_cache", Res.string.m6_clear_image_cache, null, PhoneSettingKind.ACTION, setOf("clear_image_cache"), null, setOf(Res.string.m6_clear_image_cache), false),
    PhoneSettingReference("group:canvas_cache", Res.string.m6_canvas_cache, null, PhoneSettingKind.HEADER, setOf("canvas_cache"), null, setOf(Res.string.m6_canvas_cache), false),
    PhoneSettingReference("max_cache_size", Res.string.phone_reference_max_cache_size, Res.drawable.phone_reference_motion_photos_on, PhoneSettingKind.VALUE, setOf("max_cache_size"), null, setOf(), false),
    PhoneSettingReference("clear_canvas_cache", Res.string.m6_clear_canvas_cache, null, PhoneSettingKind.ACTION, setOf("clear_canvas_cache"), null, setOf(Res.string.m6_clear_canvas_cache), false),
    PhoneSettingReference("group:lyrics", Res.string.desktop_shell_lyrics_pane, null, PhoneSettingKind.HEADER, setOf("lyrics"), null, setOf(Res.string.desktop_shell_lyrics_pane, Res.string.m5_lyrics, Res.string.m6_settings_home_lyrics), false),
    PhoneSettingReference("clear_lyrics_cache", Res.string.m6_clear_lyrics_cache, null, PhoneSettingKind.ACTION, setOf("clear_lyrics_cache"), null, setOf(Res.string.m6_clear_lyrics_cache), false),
    PhoneSettingReference("group:storage_folder", Res.string.phone_reference_storage_folder, null, PhoneSettingKind.HEADER, setOf("storage_folder"), null, setOf(), false),
    PhoneSettingReference("storage_folder_pick", Res.string.phone_reference_storage_folder_pick, Res.drawable.phone_reference_snippet_folder, PhoneSettingKind.ACTION, setOf("storage_folder_pick"), null, setOf(), false),
    PhoneSettingReference("smart_trimmer", Res.string.phone_reference_smart_trimmer, null, PhoneSettingKind.TOGGLE, setOf("smart_trimmer"), null, setOf(), false),
    PhoneSettingReference("size_used", Res.string.phone_reference_size_used, null, PhoneSettingKind.ACTION, setOf("size_used"), null, setOf(), false),
))
