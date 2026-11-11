// SPDX-License-Identifier: GPL-3.0-only
use natune_lyrics::Document;
use serde_json::{Value, json};
pub fn snapshot(d: Document) -> Value {
    json!({
        "lines": d.lines.into_iter().map(|l| json!({
            "time": l.time_ms, "text": l.text, "agent": l.agent,
            "isInstrumental": false, "durationMs": 0,
            "providerRomanizedText": l.provider_romanized_text,
            "providerRomanizedWords": l.provider_romanized_words,
            "providerRomanizedLanguage": l.provider_romanized_language,
            "providerTranslationText": l.provider_translation_text,
            "words": if l.words.is_empty() { Value::Null } else { json!(l.words.into_iter().map(|w| json!({
                "text": w.text, "startTime": w.start_time, "endTime": w.end_time, "isBackground": w.is_background,
            })).collect::<Vec<_>>()) },
        })).collect::<Vec<_>>(),
        "credits": { "songwriters": d.credits.songwriters, "translationAutoCreated": d.credits.translation_auto_created, "transliterationAutoCreated": d.credits.transliteration_auto_created }
    })
}
