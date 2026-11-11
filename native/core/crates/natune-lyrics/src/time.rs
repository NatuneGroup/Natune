// SPDX-License-Identifier: GPL-3.0-only
use crate::reader::{Element, ends_ignore_case, kotlin_space, regex_space};

pub struct Timing {
    pub frame: f64,
    pub tick: f64,
}
pub fn at_least(value: f64, min: f64) -> f64 {
    if value < min { min } else { value }
}
fn number(raw: &str) -> Option<f64> {
    let raw = raw.trim_matches(|c: char| c <= ' ');
    match raw {
        "NaN" | "+NaN" | "-NaN" => return Some(f64::NAN),
        "Infinity" | "+Infinity" => return Some(f64::INFINITY),
        "-Infinity" => return Some(f64::NEG_INFINITY),
        _ => (),
    }
    // Kotlin/JVM toDoubleOrNull accepts Java float suffixes and hexadecimal floats, but only
    // case-sensitive NaN/Infinity. Rust's shorter inf/nan spellings would change odd-time rows.
    let raw = raw.strip_suffix(['f', 'F', 'd', 'D']).unwrap_or(raw);
    let (negative, unsigned) = if let Some(v) = raw.strip_prefix('-') {
        (true, v)
    } else {
        (false, raw.strip_prefix('+').unwrap_or(raw))
    };
    if unsigned.eq_ignore_ascii_case("nan")
        || unsigned.eq_ignore_ascii_case("inf")
        || unsigned.eq_ignore_ascii_case("infinity")
    {
        return None;
    }
    if let Some(hex) = unsigned
        .strip_prefix("0x")
        .or_else(|| unsigned.strip_prefix("0X"))
    {
        let (digits, exponent) = hex.split_once(['p', 'P'])?;
        let exponent: i32 = exponent.parse().ok()?;
        let mut value = 0.0;
        let mut fractional = None;
        let mut count = 0;
        for c in digits.chars() {
            if c == '.' {
                if fractional.is_some() {
                    return None;
                }
                fractional = Some(0);
            } else {
                value = value * 16.0 + f64::from(c.to_digit(16)?);
                count += 1;
                if let Some(ref mut n) = fractional {
                    *n += 1;
                }
            }
        }
        if count == 0 {
            return None;
        }
        let value = value * 2.0_f64.powi(exponent.saturating_sub(4 * fractional.unwrap_or(0)));
        return Some(if negative { -value } else { value });
    }
    raw.parse().ok()
}
pub fn timing(root: &Element) -> Timing {
    let suffix = |s: &str| {
        root.attributes.iter().find_map(|(n, v)| {
            let v = v.trim_matches(kotlin_space);
            (ends_ignore_case(n, s) && !v.is_empty()).then_some(v)
        })
    };
    let base = suffix("framerate").and_then(number).unwrap_or(30.0);
    let multiplier: Vec<_> = suffix("frameratemultiplier")
        .unwrap_or("")
        .split(regex_space)
        .filter_map(number)
        .collect();
    let multiplier = if multiplier.len() == 2 && multiplier[1] != 0.0 {
        multiplier[0] / multiplier[1]
    } else {
        1.0
    };
    let frame = at_least(base * multiplier, 1.0);
    let tick = suffix("tickrate").and_then(number).unwrap_or(frame);
    Timing { frame, tick }
}
pub fn parse(raw: &str, timing: &Timing) -> f64 {
    let raw = raw.trim_matches(kotlin_space);
    let split = raw.find(|c: char| c.is_ascii_alphabetic());
    if let Some(i) = split {
        let digits = &raw[..i];
        let parts: Vec<_> = digits.split('.').collect();
        let valid = (parts.len() == 1 || parts.len() == 2)
            && parts
                .iter()
                .all(|s| !s.is_empty() && s.chars().all(|c| c.is_ascii_digit()));
        if valid && let Some(n) = number(digits) {
            match raw[i..].to_ascii_lowercase().as_str() {
                "h" => return n * 3600.0,
                "m" => return n * 60.0,
                "s" => return n,
                "ms" => return n / 1000.0,
                "f" => return n / timing.frame,
                "t" => return n / timing.tick,
                _ => (),
            }
        }
    }
    let clean = raw.replace(';', ":");
    let clean = clean.trim_end_matches(char::is_alphabetic);
    if clean.contains(':') {
        let p: Vec<_> = clean.split(':').map(|s| number(s).unwrap_or(0.0)).collect();
        match p.as_slice() {
            [m, s] => m * 60.0 + s,
            [h, m, s] => h * 3600.0 + m * 60.0 + s,
            [h, m, s, f] => h * 3600.0 + m * 60.0 + s + f / timing.frame,
            _ => number(clean).unwrap_or(0.0),
        }
    } else {
        number(raw).unwrap_or(0.0)
    }
}
pub fn child(raw: f64, start: f64, end: f64, fallback: f64) -> f64 {
    if !raw.is_finite() {
        return fallback;
    }
    let duration = at_least(end - start, 0.0);
    let adjusted = if raw < start - 0.25 && raw <= duration + 1.0 {
        start + raw
    } else {
        raw
    };
    let min = at_least(start, 0.0);
    let max = at_least(end, start);
    // Kotlin coerceIn throws on an invalid interval, causing the whole row pass to return empty.
    if min > max {
        return f64::NAN;
    }
    if adjusted < min {
        min
    } else if adjusted > max {
        max
    } else {
        adjusted
    }
}
