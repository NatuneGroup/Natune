// SPDX-License-Identifier: GPL-3.0-only
//! Bounded, synchronous TTML document reader. No FFI, runtime, I/O, or provider policy.
mod model;
mod reader;
mod time;
mod ttml;
pub use model::*;

/// Cancellation is supplied by the caller; malformed/refused XML returns an empty document.
pub fn parse_ttml(xml: &str, cancelled: impl Fn() -> bool) -> Result<Document, Cancelled> {
    let arena = match reader::read(xml, &cancelled) {
        Ok(arena) => arena,
        Err(reader::ReadError::Cancelled) => return Err(Cancelled),
        Err(reader::ReadError::Malformed) => return Ok(Document::default()),
    };
    ttml::document(&arena, &cancelled)
}
