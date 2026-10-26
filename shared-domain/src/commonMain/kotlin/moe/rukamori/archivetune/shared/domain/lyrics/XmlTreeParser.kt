package moe.rukamori.archivetune.shared.domain.lyrics

/** Any malformed, refused or over-bound XML input. Callers treat it as "no document". */
internal class XmlSyntaxException(message: String) : RuntimeException(message)

/**
 * Pure-Kotlin XML reader for the TTML path. It is deliberately not a validating parser: any
 * `<!DOCTYPE` or `<!ENTITY` is refused, the five predefined entities and character references are the
 * only references decoded, and nothing is ever fetched. Hard bounds keep hostile payloads cheap to reject.
 */
internal object XmlTreeParser {
    const val MAX_INPUT_LENGTH = 2_000_000
    const val MAX_DEPTH = 64
    const val MAX_ELEMENTS = 100_000
    private const val MAX_REFERENCE_LENGTH = 32

    fun parse(input: String): XmlDocument {
        if (input.length > MAX_INPUT_LENGTH) {
            throw XmlSyntaxException("XML input exceeds $MAX_INPUT_LENGTH characters")
        }
        val withoutBom = input.removePrefix("\uFEFF")
        // XML line-end normalisation: CRLF and lone CR both read as LF.
        val normalized = if ('\r' in withoutBom) withoutBom.replace("\r\n", "\n").replace('\r', '\n') else withoutBom
        return Scanner(normalized).parseDocument()
    }

    internal fun isXmlCodePoint(codePoint: Int): Boolean =
        codePoint == 0x9 ||
            codePoint == 0xA ||
            codePoint == 0xD ||
            codePoint in 0x20..0xD7FF ||
            codePoint in 0xE000..0xFFFD ||
            codePoint in 0x10000..0x10FFFF

    private class Scanner(private val src: String) {
        private val length = src.length
        private var pos = 0
        private var elementCount = 0

        fun parseDocument(): XmlDocument {
            skipProlog()
            val root = parseElement(depth = 1)
            skipMisc()
            return XmlDocument(root)
        }

        private fun skipProlog() {
            while (true) {
                skipWhitespace()
                if (pos >= length) throw XmlSyntaxException("document has no root element")
                when {
                    src.startsWith("<?", pos) -> readProcessingInstruction()
                    src.startsWith("<!--", pos) -> readComment()
                    src.startsWith("<!", pos) -> throw XmlSyntaxException(declarationError())
                    src[pos] == '<' -> return
                    else -> throw XmlSyntaxException("text before the root element")
                }
            }
        }

        private fun skipMisc() {
            while (true) {
                skipWhitespace()
                if (pos >= length) return
                when {
                    src.startsWith("<?", pos) -> readProcessingInstruction()
                    src.startsWith("<!--", pos) -> readComment()
                    else -> throw XmlSyntaxException("content after the root element")
                }
            }
        }

        private fun declarationError(): String =
            when {
                src.startsWith("<!DOCTYPE", pos) -> "DTD declarations are not supported"
                src.startsWith("<!ENTITY", pos) -> "entity declarations are not supported"
                else -> "markup declarations are not supported"
            }

        private fun parseElement(depth: Int): XmlElement {
            if (depth > MAX_DEPTH) throw XmlSyntaxException("element nesting exceeds $MAX_DEPTH")
            if (++elementCount > MAX_ELEMENTS) throw XmlSyntaxException("element count exceeds $MAX_ELEMENTS")
            expect('<')
            val name = readName()
            val attributes = ArrayList<XmlAttribute>()
            var selfClosing = false
            while (true) {
                val separated = skipWhitespace()
                val next = peek()
                if (next == '>') {
                    pos++
                    break
                }
                if (next == '/') {
                    expect("/>")
                    selfClosing = true
                    break
                }
                if (!separated) throw XmlSyntaxException("expected whitespace before an attribute in <$name>")
                val attributeName = readName()
                skipWhitespace()
                expect('=')
                skipWhitespace()
                val value = readAttributeValue()
                if (attributes.any { it.name == attributeName }) {
                    throw XmlSyntaxException("duplicate attribute $attributeName on <$name>")
                }
                attributes.add(XmlAttribute(attributeName, value))
            }
            val element = XmlElement(name, attributes)
            if (!selfClosing) parseContent(element, depth)
            return element
        }

        private fun parseContent(
            element: XmlElement,
            depth: Int,
        ) {
            val text = StringBuilder()
            while (true) {
                if (pos >= length) throw XmlSyntaxException("unclosed element <${element.tagName}>")
                val c = src[pos]
                when {
                    c == '<' -> {
                        flushText(element, text)
                        when {
                            src.startsWith("</", pos) -> {
                                pos += 2
                                val endName = readName()
                                skipWhitespace()
                                expect('>')
                                if (endName != element.tagName) {
                                    throw XmlSyntaxException("end tag </$endName> does not match <${element.tagName}>")
                                }
                                return
                            }
                            src.startsWith("<!--", pos) -> element.appendChild(XmlComment(readComment()))
                            src.startsWith("<![CDATA[", pos) -> element.appendChild(XmlCData(readCData()))
                            src.startsWith("<?", pos) -> element.appendChild(readProcessingInstruction())
                            src.startsWith("<!", pos) -> throw XmlSyntaxException(declarationError())
                            else -> element.appendChild(parseElement(depth + 1))
                        }
                    }
                    c == '&' -> readReference(text)
                    c == ']' && src.startsWith("]]>", pos) ->
                        throw XmlSyntaxException("']]>' is not allowed in character data")
                    else -> appendCharacter(text)
                }
            }
        }

        private fun flushText(
            element: XmlElement,
            text: StringBuilder,
        ) {
            if (text.isEmpty()) return
            element.appendChild(XmlText(text.toString()))
            text.setLength(0)
        }

        private fun readAttributeValue(): String {
            val quote = peek()
            if (quote != '"' && quote != '\'') throw XmlSyntaxException("attribute value must be quoted")
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= length) throw XmlSyntaxException("unterminated attribute value")
                val c = src[pos]
                when {
                    c == quote -> {
                        pos++
                        return out.toString()
                    }
                    c == '<' -> throw XmlSyntaxException("'<' is not allowed in attribute values")
                    c == '&' -> readReference(out)
                    // Attribute-value normalisation: literal tab and newline become a space.
                    c == '\t' || c == '\n' -> {
                        out.append(' ')
                        pos++
                    }
                    else -> appendCharacter(out)
                }
            }
        }

        private fun readReference(out: StringBuilder) {
            val semicolon = src.indexOf(';', startIndex = pos + 1)
            if (semicolon < 0 || semicolon - pos > MAX_REFERENCE_LENGTH) {
                throw XmlSyntaxException("malformed reference at $pos")
            }
            val body = src.substring(pos + 1, semicolon)
            val codePoint =
                when (body) {
                    "lt" -> '<'.code
                    "gt" -> '>'.code
                    "amp" -> '&'.code
                    "quot" -> '"'.code
                    "apos" -> '\''.code
                    else ->
                        when {
                            body.startsWith("#x") -> characterReference(body.substring(2), radix = 16)
                            body.startsWith("#") -> characterReference(body.substring(1), radix = 10)
                            else -> throw XmlSyntaxException("unknown entity &$body;")
                        }
                }
            out.append(codePointToString(codePoint))
            pos = semicolon + 1
        }

        private fun characterReference(
            digits: String,
            radix: Int,
        ): Int {
            if (digits.isEmpty()) throw XmlSyntaxException("empty character reference")
            var value = 0
            for (ch in digits) {
                val digit =
                    when (ch) {
                        in '0'..'9' -> ch - '0'
                        in 'a'..'f' -> ch - 'a' + 10
                        in 'A'..'F' -> ch - 'A' + 10
                        else -> -1
                    }
                if (digit < 0 || digit >= radix) throw XmlSyntaxException("invalid character reference digit '$ch'")
                value = value * radix + digit
                if (value > 0x10FFFF) throw XmlSyntaxException("character reference out of range")
            }
            if (!isXmlCodePoint(value)) throw XmlSyntaxException("character reference is not an XML character")
            return value
        }

        /** Consumes one character (or one surrogate pair) of character data, rejecting XML-illegal input. */
        private fun appendCharacter(out: StringBuilder) {
            val c = src[pos]
            when {
                c.isHighSurrogate() -> {
                    if (pos + 1 >= length || !src[pos + 1].isLowSurrogate()) {
                        throw XmlSyntaxException("unpaired surrogate in XML text")
                    }
                    out.append(c).append(src[pos + 1])
                    pos += 2
                    return
                }
                c.isLowSurrogate() -> throw XmlSyntaxException("unpaired surrogate in XML text")
                c == '\t' || c == '\n' -> out.append(c)
                c < ' ' || c == '\uFFFE' || c == '\uFFFF' ->
                    throw XmlSyntaxException("character U+${c.code.toString(16)} is not allowed in XML")
                else -> out.append(c)
            }
            pos++
        }

        private fun readComment(): String {
            val start = pos + 4
            val end = src.indexOf("-->", startIndex = start)
            if (end < 0) throw XmlSyntaxException("unterminated comment")
            val body = src.substring(start, end)
            if (body.contains("--") || body.endsWith("-")) {
                throw XmlSyntaxException("'--' is not allowed inside a comment")
            }
            pos = end + 3
            return body
        }

        private fun readCData(): String {
            val start = pos + 9
            val end = src.indexOf("]]>", startIndex = start)
            if (end < 0) throw XmlSyntaxException("unterminated CDATA section")
            val body = src.substring(start, end)
            validateCharacters(body)
            pos = end + 3
            return body
        }

        private fun validateCharacters(text: String) {
            var index = 0
            while (index < text.length) {
                val c = text[index]
                if (c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    index += 2
                    continue
                }
                if (c.isSurrogate() || (c < ' ' && c != '\t' && c != '\n') || c == '\uFFFE' || c == '\uFFFF') {
                    throw XmlSyntaxException("character U+${c.code.toString(16)} is not allowed in XML")
                }
                index++
            }
        }

        private fun readProcessingInstruction(): XmlProcessingInstruction {
            pos += 2
            val target = readName()
            val end = src.indexOf("?>", startIndex = pos)
            if (end < 0) throw XmlSyntaxException("unterminated processing instruction")
            val data = src.substring(pos, end).trimStart()
            pos = end + 2
            return XmlProcessingInstruction(target, data)
        }

        private fun readName(): String {
            val start = pos
            if (pos >= length || !isNameStart(src[pos])) throw XmlSyntaxException("expected a name at offset $pos")
            pos++
            while (pos < length && isNameChar(src[pos])) pos++
            return src.substring(start, pos)
        }

        private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'

        private fun isNameChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '-' || c == '.' || c == '_' || c == ':' || c == '\u00B7'

        private fun skipWhitespace(): Boolean {
            val start = pos
            while (pos < length && isSpace(src[pos])) pos++
            return pos > start
        }

        private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

        private fun peek(): Char {
            if (pos >= length) throw XmlSyntaxException("unexpected end of XML input")
            return src[pos]
        }

        private fun expect(c: Char) {
            if (peek() != c) throw XmlSyntaxException("expected '$c' at offset $pos")
            pos++
        }

        private fun expect(literal: String) {
            if (!src.startsWith(literal, pos)) throw XmlSyntaxException("expected '$literal' at offset $pos")
            pos += literal.length
        }
    }
}
