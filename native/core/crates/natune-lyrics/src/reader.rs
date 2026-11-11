// SPDX-License-Identifier: GPL-3.0-only
use quick_xml::{Reader, events::Event};

pub const MAX_INPUT_UTF16: usize = 2_000_000;
pub const MAX_DEPTH: usize = 64;
pub const MAX_ELEMENTS: usize = 100_000;

pub enum ReadError {
    Malformed,
    Cancelled,
}

/// Element indexes are preorder; subtree_end replaces allocating descendant lists.
pub struct Element {
    pub name: String,
    pub attributes: Vec<(String, String)>,
    pub children: Vec<Node>,
    pub subtree_end: usize,
}
pub enum Node {
    Element(usize),
    Text(String),
    CData(String),
    Boundary,
}
pub type Arena = Vec<Element>;

impl Element {
    pub fn tag(&self, suffix: &str) -> bool {
        self.name.to_ascii_lowercase().ends_with(suffix)
    }
    pub fn attr(&self, name: &str) -> &str {
        self.attributes
            .iter()
            .find(|(n, _)| n == name)
            .map_or("", |(_, v)| v)
    }
    pub fn suffix(&self, name: &str) -> Option<String> {
        let direct = self.attr(name);
        if !direct.trim_matches(kotlin_space).is_empty() {
            return Some(direct.to_owned());
        }
        self.attributes.iter().find_map(|(n, v)| {
            let n = n.to_ascii_lowercase();
            let v = v.trim_matches(kotlin_space);
            ((n == name || n.ends_with(&format!(":{name}"))) && !v.is_empty()).then(|| v.to_owned())
        })
    }
}

pub fn kotlin_space(c: char) -> bool {
    c.is_whitespace() || c == '\u{1c}' || c == '\u{1d}' || c == '\u{1e}' || c == '\u{1f}'
}
pub fn regex_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\r' | '\u{b}' | '\u{c}')
}
fn valid(c: char) -> bool {
    matches!(c, '\t' | '\n' | '\r')
        || matches!(c as u32, 0x20..=0xd7ff | 0xe000..=0xfffd | 0x10000..=0x10ffff)
}
fn name_valid(name: &str) -> bool {
    let mut chars = name.chars();
    chars
        .next()
        .is_some_and(|c| c.is_alphabetic() || c == '_' || c == ':')
        && chars.all(|c| c.is_alphanumeric() || matches!(c, '-' | '.' | '_' | ':' | '\u{b7}'))
}
fn reference(body: &str) -> Result<String, ReadError> {
    if body.len() + 1 > 32 {
        return Err(ReadError::Malformed);
    }
    match body {
        "amp" => Ok("&".into()),
        "lt" => Ok("<".into()),
        "gt" => Ok(">".into()),
        "quot" => Ok("\"".into()),
        "apos" => Ok("'".into()),
        _ => {
            let (digits, radix) = if let Some(d) = body.strip_prefix("#x") {
                (d, 16)
            } else if let Some(d) = body.strip_prefix('#') {
                (d, 10)
            } else {
                return Err(ReadError::Malformed);
            };
            if digits.is_empty() || !digits.chars().all(|c| c.is_digit(radix)) {
                return Err(ReadError::Malformed);
            }
            let c = u32::from_str_radix(digits, radix)
                .ok()
                .and_then(char::from_u32)
                .filter(|&c| valid(c))
                .ok_or(ReadError::Malformed)?;
            Ok(c.to_string())
        }
    }
}
fn decode(raw: &str) -> Result<String, ReadError> {
    let mut out = String::with_capacity(raw.len());
    let mut rest = raw;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i + 1..];
        let end = rest.find(';').ok_or(ReadError::Malformed)?;
        out.push_str(&reference(&rest[..end])?);
        rest = &rest[end + 1..];
    }
    out.push_str(rest);
    if !out.chars().all(valid) {
        return Err(ReadError::Malformed);
    }
    Ok(out)
}

fn text(arena: &mut Arena, stack: &[usize], value: String, merge: bool) -> Result<(), ReadError> {
    if let Some(&parent) = stack.last() {
        if merge && let Some(Node::Text(last)) = arena[parent].children.last_mut() {
            last.push_str(&value);
        } else if !value.is_empty() {
            arena[parent].children.push(Node::Text(value));
        }
    } else if !value.chars().all(|c| matches!(c, ' ' | '\t' | '\n' | '\r')) {
        return Err(ReadError::Malformed);
    }
    Ok(())
}

pub fn read(xml: &str, cancelled: &impl Fn() -> bool) -> Result<Arena, ReadError> {
    if cancelled() {
        return Err(ReadError::Cancelled);
    }
    // Count UTF-16, not UTF-8 bytes, and stop scanning as soon as the cap is exceeded.
    let mut length = 0;
    for (i, c) in xml.chars().enumerate() {
        if i % 4096 == 0 && cancelled() {
            return Err(ReadError::Cancelled);
        }
        length += c.len_utf16();
        if length > MAX_INPUT_UTF16 {
            return Err(ReadError::Malformed);
        }
    }
    let normalized = xml
        .strip_prefix('\u{feff}')
        .unwrap_or(xml)
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    let mut reader = Reader::from_str(&normalized);
    reader.config_mut().check_comments = true;
    let mut arena: Arena = Vec::new();
    let mut stack: Vec<usize> = Vec::new();
    let mut root_closed = false;
    loop {
        if cancelled() {
            return Err(ReadError::Cancelled);
        }
        let event = reader.read_event().map_err(|_| ReadError::Malformed)?;
        match event {
            Event::Start(ref start) | Event::Empty(ref start) => {
                if root_closed || stack.len() >= MAX_DEPTH || arena.len() >= MAX_ELEMENTS {
                    return Err(ReadError::Malformed);
                }
                let name = start.name().as_ref().to_owned();
                if !name_valid(&name) {
                    return Err(ReadError::Malformed);
                }
                let mut attributes = Vec::new();
                for attr in start.attributes() {
                    if cancelled() {
                        return Err(ReadError::Cancelled);
                    }
                    let attr = attr.map_err(|_| ReadError::Malformed)?;
                    let name = attr.key.as_ref().to_owned();
                    if !name_valid(&name) {
                        return Err(ReadError::Malformed);
                    }
                    let raw = &attr.value;
                    if raw.contains('<') {
                        return Err(ReadError::Malformed);
                    }
                    let value = decode(&raw.replace(['\t', '\n'], " "))?;
                    attributes.push((name, value));
                }
                attributes.sort_by(|a, b| a.0.encode_utf16().cmp(b.0.encode_utf16()));
                let index = arena.len();
                arena.push(Element {
                    name,
                    attributes,
                    children: Vec::new(),
                    subtree_end: index + 1,
                });
                if let Some(&parent) = stack.last() {
                    arena[parent].children.push(Node::Element(index));
                }
                if matches!(event, Event::Start(_)) {
                    stack.push(index);
                } else if stack.is_empty() {
                    root_closed = true;
                }
            }
            Event::End(_) => {
                let index = stack.pop().ok_or(ReadError::Malformed)?;
                arena[index].subtree_end = arena.len();
                if stack.is_empty() {
                    root_closed = true;
                }
            }
            Event::Text(t) => {
                let raw = &*t;
                if raw.contains("]]>") {
                    return Err(ReadError::Malformed);
                }
                text(&mut arena, &stack, decode(raw)?, true)?;
            }
            Event::GeneralRef(r) => {
                if stack.is_empty() {
                    return Err(ReadError::Malformed);
                }
                let raw = &*r;
                text(&mut arena, &stack, reference(raw)?, true)?;
            }
            Event::CData(t) => {
                let parent = *stack.last().ok_or(ReadError::Malformed)?;
                let value = &*t;
                if !value.chars().all(valid) {
                    return Err(ReadError::Malformed);
                }
                arena[parent].children.push(Node::CData(value.to_owned()));
            }
            Event::Comment(_) | Event::PI(_) | Event::Decl(_) => {
                if let Some(&parent) = stack.last() {
                    arena[parent].children.push(Node::Boundary);
                }
            }
            Event::DocType(_) => return Err(ReadError::Malformed),
            Event::Eof => break,
        }
    }
    if arena.is_empty() || !stack.is_empty() {
        return Err(ReadError::Malformed);
    }
    Ok(arena)
}

pub fn content(arena: &Arena, id: usize, direct: bool) -> String {
    let mut result = String::new();
    append_content(arena, id, direct, &mut result);
    result
}
fn append_content(arena: &Arena, id: usize, direct: bool, result: &mut String) {
    for node in &arena[id].children {
        match node {
            Node::Text(t) => result.push_str(t),
            Node::CData(t) if !direct => result.push_str(t),
            Node::Element(child) if !direct => append_content(arena, *child, false, result),
            _ => (),
        }
    }
}
