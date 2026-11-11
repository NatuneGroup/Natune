// SPDX-License-Identifier: GPL-3.0-only
use std::{hint::black_box, time::Instant};
fn main() {
    for kib in [5, 50, 500] {
        let paragraph = "<p begin=\"10s\" end=\"14s\" ttm:agent=\"v1\"><span begin=\"0s\" end=\"1s\">Hello </span><span begin=\"1s\" end=\"2s\">world</span><span role=\"x-bg\" begin=\"2s\" end=\"3s\"> echo</span></p>";
        let xml = format!(
            "<tt><head><songwriter>Fixture Writer</songwriter></head><body><div>{}</div></body></tt>",
            paragraph.repeat(kib * 1024 / paragraph.len())
        );
        let mut samples = Vec::new();
        for _ in 0..20 {
            black_box(natune_lyrics::parse_ttml(&xml, || false).unwrap());
        }
        for _ in 0..100 {
            let start = Instant::now();
            black_box(natune_lyrics::parse_ttml(black_box(&xml), || false).unwrap());
            samples.push(start.elapsed().as_nanos());
        }
        samples.sort_unstable();
        println!(
            "synthetic_bytes={}, p50_us={}, p95_us={}",
            xml.len(),
            samples[50] / 1000,
            samples[95] / 1000
        );
    }
}
