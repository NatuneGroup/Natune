// SPDX-License-Identifier: GPL-3.0-only
//! Only DTO conversion, cancellation and stable error mapping belong in this crate.
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
uniffi::setup_scaffolding!();

#[derive(uniffi::Object, Default)]
pub struct ParseCancellation {
    cancelled: AtomicBool,
}
#[uniffi::export]
impl ParseCancellation {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }
    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("cancelled")]
    Cancelled,
    #[error("internal")]
    Internal,
}

#[derive(uniffi::Record)]
pub struct TtmlDocumentRecord {
    pub lines: Vec<TtmlLineRecord>,
    pub songwriters: Vec<String>,
    pub translation_auto_created: bool,
    pub transliteration_auto_created: bool,
}
#[derive(uniffi::Record)]
pub struct TtmlLineRecord {
    pub time_ms: i64,
    pub text: String,
    pub words: Vec<TtmlWordRecord>,
    pub agent: Option<String>,
    pub provider_romanized_text: Option<String>,
    pub provider_romanized_words: Option<Vec<String>>,
    pub provider_romanized_language: Option<String>,
    pub provider_translation_text: Option<String>,
}
#[derive(uniffi::Record)]
pub struct TtmlWordRecord {
    pub text: String,
    pub start_time: f64,
    pub end_time: f64,
    pub is_background: bool,
}

#[uniffi::export]
pub fn core_available() -> bool {
    true
}

#[uniffi::export]
pub fn parse_ttml(
    xml: String,
    cancellation: Arc<ParseCancellation>,
) -> Result<TtmlDocumentRecord, CoreError> {
    let doc = std::panic::catch_unwind(|| {
        natune_lyrics::parse_ttml(&xml, || cancellation.cancelled.load(Ordering::Relaxed))
    })
    .map_err(|_| CoreError::Internal)?
    .map_err(|_| CoreError::Cancelled)?;
    Ok(TtmlDocumentRecord {
        lines: doc
            .lines
            .into_iter()
            .map(|l| TtmlLineRecord {
                time_ms: l.time_ms,
                text: l.text,
                agent: l.agent,
                words: l
                    .words
                    .into_iter()
                    .map(|w| TtmlWordRecord {
                        text: w.text,
                        start_time: w.start_time,
                        end_time: w.end_time,
                        is_background: w.is_background,
                    })
                    .collect(),
                provider_romanized_text: l.provider_romanized_text,
                provider_romanized_words: l.provider_romanized_words,
                provider_romanized_language: l.provider_romanized_language,
                provider_translation_text: l.provider_translation_text,
            })
            .collect(),
        songwriters: doc.credits.songwriters,
        translation_auto_created: doc.credits.translation_auto_created,
        transliteration_auto_created: doc.credits.transliteration_auto_created,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancellation_and_refusal_cross_the_facade() {
        let token = ParseCancellation::new();
        token.cancel();
        assert!(matches!(
            parse_ttml("<tt/>".into(), token),
            Err(CoreError::Cancelled)
        ));
        let doc = parse_ttml("<!DOCTYPE tt><tt/>".into(), ParseCancellation::new()).unwrap();
        assert!(doc.lines.is_empty() && doc.songwriters.is_empty());
    }
}
