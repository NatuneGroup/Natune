package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneLyricsRomanisationSettingsReference = PhoneSettingsReference(Res.string.phone_reference_romanization, listOf(
    PhoneSettingReference("group:romanization", Res.string.phone_reference_romanization, null, PhoneSettingKind.HEADER, setOf("romanization"), null, setOf(), false),
    PhoneSettingReference("lyrics_romanize_japanese", Res.string.phone_reference_lyrics_romanize_japanese, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("lyrics_romanize_japanese"), "lyricsRomanizeJapanese", setOf(), false),
    PhoneSettingReference("lyrics_romanize_korean", Res.string.phone_reference_lyrics_romanize_korean, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("lyrics_romanize_korean"), "lyricsRomanizeKorean", setOf(), false),
    PhoneSettingReference("lyrics_romanize_chinese", Res.string.phone_reference_lyrics_romanize_chinese, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("lyrics_romanize_chinese"), "lyricsRomanizeChinese", setOf(), false),
    PhoneSettingReference("lyrics_romanize_hindi", Res.string.phone_reference_lyrics_romanize_hindi, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("lyrics_romanize_hindi"), "lyricsRomanizeHindi", setOf(), false),
    PhoneSettingReference("lyrics_romanize_other_languages", Res.string.phone_reference_lyrics_romanize_other_languages, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("lyrics_romanize_other_languages"), "lyricsRomanizeOtherLanguages", setOf(), false),
))
