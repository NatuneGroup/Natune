// SPDX-License-Identifier: GPL-3.0-only
use crate::{
    Cancelled, Credits, Document, Line, Word,
    reader::{self, Arena, Node, kotlin_space, regex_space},
    time::{self, Timing},
};
use std::collections::HashMap;

#[derive(Clone)]
struct Romanization {
    text: String,
    words: Vec<String>,
    language: Option<String>,
}

fn collapse(raw: &str) -> String {
    let mut result = String::with_capacity(raw.len());
    let mut gap = false;
    for c in raw.chars() {
        if regex_space(c) {
            if !gap {
                result.push(' ');
            }
            gap = true;
        } else {
            result.push(c);
            gap = false;
        }
    }
    result
}
fn provided(raw: &str) -> Option<String> {
    let value = collapse(raw).trim_matches(kotlin_space).to_owned();
    (!value.is_empty()).then_some(value)
}
fn auto(arena: &Arena, id: usize) -> bool {
    let flag = |id: usize| {
        arena[id].attributes.iter().any(|(n, v)| {
            n.rsplit(':')
                .next()
                .unwrap_or(n)
                .replace('-', "")
                .eq_ignore_ascii_case("automaticallyCreated")
                && matches!(
                    v.trim_matches(kotlin_space).to_ascii_lowercase().as_str(),
                    "true" | "1" | "yes"
                )
        })
    };
    flag(id) || (id + 1..arena[id].subtree_end).any(|c| arena[c].tag("text") && flag(c))
}
fn roman_nodes(arena: &Arena, id: usize, text: &mut String, words: &mut Vec<String>) {
    for node in &arena[id].children {
        match node {
            Node::Text(t) => text.push_str(t),
            Node::Element(c) if arena[*c].tag("span") => {
                let raw = reader::content(arena, *c, false);
                text.push_str(&raw);
                if let Some(w) = provided(&raw) {
                    words.push(w);
                }
            }
            Node::Element(c) => roman_nodes(arena, *c, text, words),
            _ => (),
        }
    }
}

pub fn document(arena: &Arena, cancelled: &impl Fn() -> bool) -> Result<Document, Cancelled> {
    let mut credits = Credits::default();
    let mut latin = HashMap::new();
    let mut romanizations = HashMap::new();
    let mut translations = HashMap::new();
    // One XML scan; one preorder metadata pass for credits and supplements, including metadata
    // after the body. No reparsing for credits or per-line descendant-vector allocations.
    for (id, e) in arena.iter().enumerate() {
        if cancelled() {
            return Err(Cancelled);
        }
        if e.tag("songwriter") {
            let raw = reader::content(arena, id, false);
            let name = raw.trim_matches(kotlin_space);
            let name = if name.is_empty() {
                e.suffix("name")
            } else {
                Some(name.to_owned())
            };
            if let Some(name) = name
                && !credits.songwriters.contains(&name)
            {
                credits.songwriters.push(name);
            }
        } else if e.tag("transliteration") {
            credits.transliteration_auto_created |= auto(arena, id);
        } else if e.tag("translation") {
            credits.translation_auto_created |= auto(arena, id);
        }
        if id == 0 {
            continue;
        } // Root is excluded from Kotlin's metadata descendant traversal.
        if e.tag("transliteration") || e.tag("translation") {
            let language = e.suffix("lang");
            let target = if language
                .as_ref()
                .is_some_and(|l| l.to_ascii_lowercase().contains("latn"))
            {
                &mut latin
            } else {
                &mut romanizations
            };
            for c in id + 1..e.subtree_end {
                if cancelled() {
                    return Err(Cancelled);
                }
                if !arena[c].tag("text") {
                    continue;
                }
                let Some(key) = arena[c].suffix("for") else {
                    continue;
                };
                if e.tag("translation") {
                    if let Some(t) = provided(&reader::content(arena, c, false)) {
                        translations.insert(key, t);
                    }
                } else {
                    let mut text = String::new();
                    let mut words = Vec::new();
                    roman_nodes(arena, c, &mut text, &mut words);
                    if let Some(text) = provided(&text) {
                        target.entry(key).or_insert(Romanization {
                            text,
                            words,
                            language: language.clone(),
                        });
                    }
                }
            }
        }
    }
    romanizations.extend(latin);
    let timing = time::timing(&arena[0]);
    let mut parsed: HashMap<usize, (f64, Line)> = HashMap::new();
    let mut invalid_times = false;
    for (id, e) in arena.iter().enumerate() {
        if cancelled() {
            return Err(Cancelled);
        }
        if !e.tag("p") || e.attr("begin").is_empty() {
            continue;
        }
        let start = time::parse(e.attr("begin"), &timing);
        let end = if !e.attr("end").is_empty() {
            time::parse(e.attr("end"), &timing)
        } else if !e.attr("dur").is_empty() {
            start + time::parse(e.attr("dur"), &timing)
        } else {
            start + 5.0
        };
        let mut words = Vec::new();
        let mut text = String::new();
        spans(
            arena,
            id,
            &mut words,
            &mut text,
            (start, end),
            false,
            &timing,
            cancelled,
        )?;
        if let Some(last) = words.last_mut() {
            last.text = last.text.trim_end_matches(kotlin_space).to_owned();
        }
        if words
            .iter()
            .any(|w| w.start_time.is_nan() || w.end_time.is_nan())
            && time::at_least(start, 0.0) > time::at_least(end, start)
        {
            invalid_times = true;
        }
        if text.is_empty() {
            text = reader::content(arena, id, true)
                .trim_matches(kotlin_space)
                .to_owned();
        }
        if text.is_empty() {
            continue;
        }
        let raw_agent = if !e.attr("ttm:agent").is_empty() {
            e.attr("ttm:agent")
        } else {
            e.attributes
                .iter()
                .find(|(n, _)| n.to_ascii_lowercase().ends_with("agent"))
                .map_or("", |(_, v)| v)
        };
        let mut ids = Vec::new();
        for agent in raw_agent.trim_matches(kotlin_space).split(regex_space) {
            let agent = agent.strip_prefix('#').unwrap_or(agent);
            if !agent.is_empty() && !ids.contains(&agent) {
                ids.push(agent);
            }
        }
        let agent = match ids.as_slice() {
            [] => None,
            [id] => Some((*id).to_owned()),
            _ => Some("group".to_owned()),
        };
        let key = e.suffix("key");
        let roman = key.as_ref().and_then(|k| romanizations.get(k));
        parsed.insert(
            id,
            (
                start,
                Line {
                    time_ms: (start * 1000.0) as i64,
                    text: text.trim_matches(kotlin_space).to_owned(),
                    words: words.into_iter().filter(|w| !w.text.is_empty()).collect(),
                    agent,
                    provider_romanized_text: roman.map(|r| r.text.clone()),
                    provider_romanized_words: roman.map(|r| r.words.clone()),
                    provider_romanized_language: roman.and_then(|r| r.language.clone()),
                    provider_translation_text: key
                        .as_ref()
                        .and_then(|k| translations.get(k))
                        .cloned(),
                },
            ),
        );
    }
    let mut lines = Vec::new();
    if !invalid_times {
        for (id, e) in arena.iter().enumerate() {
            if !e.tag("div") {
                continue;
            }
            for c in id + 1..e.subtree_end {
                if cancelled() {
                    return Err(Cancelled);
                }
                if let Some(line) = parsed.get(&c) {
                    lines.push(line.clone());
                }
            }
        }
        if lines.is_empty() {
            for id in 0..arena.len() {
                if let Some(line) = parsed.remove(&id) {
                    lines.push(line);
                }
            }
        }
        lines.sort_by(|a, b| a.0.total_cmp(&b.0));
        // Kotlin sorts by Double start, then stably by the truncated millisecond field.
        lines.sort_by_key(|(_, l)| l.time_ms);
    }
    Ok(Document {
        lines: lines.into_iter().map(|(_, l)| l).collect(),
        credits,
    })
}

#[allow(clippy::too_many_arguments)]
fn spans(
    arena: &Arena,
    id: usize,
    words: &mut Vec<Word>,
    text: &mut String,
    bounds: (f64, f64),
    background: bool,
    timing: &Timing,
    cancelled: &impl Fn() -> bool,
) -> Result<(), Cancelled> {
    let (start, end) = bounds;
    for node in &arena[id].children {
        if cancelled() {
            return Err(Cancelled);
        }
        match node {
            Node::Element(c) if arena[*c].tag("span") => {
                let e = &arena[*c];
                let role = if e.attr("role").is_empty() {
                    e.attr("ttm:role")
                } else {
                    e.attr("role")
                };
                let bg = background || role == "x-bg";
                let timed = !e.attr("begin").is_empty()
                    && (!e.attr("end").is_empty() || !e.attr("dur").is_empty());
                let nested = e.children.iter().any(|n| matches!(n, Node::Element(_)));
                let child_span = e
                    .children
                    .iter()
                    .any(|n| matches!(n, Node::Element(i) if arena[*i].tag("span")));
                if nested && child_span && !timed {
                    spans(arena, *c, words, text, bounds, bg, timing, cancelled)?;
                } else {
                    let raw = reader::content(arena, *c, !nested);
                    if raw.is_empty() {
                        continue;
                    }
                    let spaced = collapse(&raw);
                    text.push_str(&spaced);
                    if timed {
                        let raw_start = time::parse(e.attr("begin"), timing);
                        let raw_end = if e.attr("end").is_empty() {
                            raw_start + time::parse(e.attr("dur"), timing)
                        } else {
                            time::parse(e.attr("end"), timing)
                        };
                        let word_start = time::child(raw_start, start, end, start);
                        let word_end =
                            time::at_least(time::child(raw_end, start, end, end), word_start);
                        if spaced.starts_with(' ')
                            && let Some(last) = words.last_mut()
                            && !last.text.ends_with(' ')
                        {
                            last.text.push(' ');
                        }
                        let word = spaced.trim_start_matches(kotlin_space);
                        if !word.trim_matches(kotlin_space).is_empty() {
                            words.push(Word {
                                text: word.to_owned(),
                                start_time: word_start,
                                end_time: word_end,
                                is_background: bg,
                            });
                        }
                    }
                }
            }
            Node::Text(t) => {
                if !t.trim_matches(kotlin_space).is_empty() {
                    text.push_str(t);
                } else if !t.is_empty()
                    && !t.contains('\n')
                    && let Some(last) = words.last_mut()
                    && !last.text.ends_with(' ')
                {
                    last.text.push(' ');
                    text.push(' ');
                }
            }
            _ => (),
        }
    }
    Ok(())
}
