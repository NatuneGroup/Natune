// SPDX-License-Identifier: GPL-3.0-only
#[derive(Debug, Default, Clone, PartialEq)]
pub struct Document {
    pub lines: Vec<Line>,
    pub credits: Credits,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Credits {
    pub songwriters: Vec<String>,
    pub translation_auto_created: bool,
    pub transliteration_auto_created: bool,
}

/// Times deliberately retain the Kotlin reader's IEEE-754 arithmetic and millisecond truncation.
/// A later unified model can use microseconds after a separate timing compatibility gate.
#[derive(Debug, Clone, PartialEq)]
pub struct Line {
    pub time_ms: i64,
    pub text: String,
    pub words: Vec<Word>,
    pub agent: Option<String>,
    pub provider_romanized_text: Option<String>,
    pub provider_romanized_words: Option<Vec<String>>,
    pub provider_romanized_language: Option<String>,
    pub provider_translation_text: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Word {
    pub text: String,
    pub start_time: f64,
    pub end_time: f64,
    pub is_background: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cancelled;
