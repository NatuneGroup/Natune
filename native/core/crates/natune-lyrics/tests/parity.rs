// SPDX-License-Identifier: GPL-3.0-only
mod support;
use natune_lyrics::parse_ttml;
use serde_json::Value;

#[test]
fn kotlin_goldens_match_every_field() {
    let inputs: Value =
        serde_json::from_str(include_str!("../../../fixtures/ttml-inputs.json")).unwrap();
    let golden_path = std::env::var("TTML_GOLDENS").unwrap_or_else(|_| {
        format!(
            "{}/../../fixtures/ttml-goldens.json",
            env!("CARGO_MANIFEST_DIR")
        )
    });
    let goldens: Value =
        serde_json::from_str(&std::fs::read_to_string(golden_path).unwrap()).unwrap();
    for case in inputs.as_array().unwrap() {
        let id = case["id"].as_str().unwrap();
        let actual =
            support::snapshot(parse_ttml(case["xml"].as_str().unwrap(), || false).unwrap());
        if let Some(expected) = goldens.get(id) {
            compare(&actual, expected, id);
        } else if std::env::var_os("TTML_REQUIRE_ALL_GOLDENS").is_some() {
            panic!("Missing Kotlin golden: {id}");
        }
    }
}
fn compare(actual: &Value, expected: &Value, path: &str) {
    match (actual, expected) {
        (Value::Number(a), Value::Number(b)) if a.is_f64() || b.is_f64() => {
            let (a, b) = (a.as_f64().unwrap(), b.as_f64().unwrap());
            assert!((a - b).abs() <= 0.000001, "{path}: {a} != {b}");
            assert_eq!(
                (a * 1000.0) as i64,
                (b * 1000.0) as i64,
                "millisecond truncation: {path}"
            );
        }
        (Value::Array(a), Value::Array(b)) => {
            assert_eq!(a.len(), b.len(), "{path}");
            for (i, (a, b)) in a.iter().zip(b).enumerate() {
                compare(a, b, &format!("{path}/{i}"));
            }
        }
        (Value::Object(a), Value::Object(b)) => {
            assert_eq!(a.len(), b.len(), "{path}");
            for (k, v) in a {
                compare(v, &b[k], &format!("{path}/{k}"));
            }
        }
        _ => assert_eq!(actual, expected, "{path}"),
    }
}

#[test]
fn bounds_refusal_and_cancellation() {
    for xml in [
        format!(
            "<tt>{}<p begin=\"0\">Deep</p>{}</tt>",
            "<div>".repeat(64),
            "</div>".repeat(64)
        ),
        format!(
            "<tt><p begin=\"0\">Bound</p>{}</tt>",
            "<a/>".repeat(100_000)
        ),
        format!("<tt><p begin=\"0\">{}</p></tt>", "a".repeat(2_000_000)),
        "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///private'>]><tt/>".to_owned(),
    ] {
        assert!(parse_ttml(&xml, || false).unwrap().lines.is_empty());
    }
    let text = "𠀀".repeat(600_000); // >2 MB UTF-8 but <2 million UTF-16 units: accepted.
    let xml = format!("<tt><p begin=\"0\">{text}</p></tt>");
    assert_eq!(parse_ttml(&xml, || false).unwrap().lines[0].text, text);
    let calls = std::cell::Cell::new(0);
    assert!(
        parse_ttml(&xml, || {
            calls.set(calls.get() + 1);
            calls.get() > 5
        })
        .is_err()
    );
}

#[test]
fn bounded_mutation_corpus_never_panics() {
    let seed = b"<tt><p begin=\"1s\"><span begin=\"1s\" end=\"2s\">A&amp;B</span></p></tt>";
    for i in 0..seed.len() {
        for byte in [0, b'<', b'>', b'&', b'/', b'\'', b'"'] {
            let mut input = seed.to_vec();
            input[i] = byte;
            parse_ttml(std::str::from_utf8(&input).unwrap(), || false).unwrap();
        }
        parse_ttml(std::str::from_utf8(&seed[..i]).unwrap(), || false).unwrap();
    }
}
