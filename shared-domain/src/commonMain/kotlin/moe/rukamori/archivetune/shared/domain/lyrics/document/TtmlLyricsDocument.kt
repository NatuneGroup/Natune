package moe.rukamori.archivetune.shared.domain.lyrics.document

import moe.rukamori.archivetune.shared.domain.lyrics.XmlCData
import moe.rukamori.archivetune.shared.domain.lyrics.XmlComment
import moe.rukamori.archivetune.shared.domain.lyrics.XmlElement
import moe.rukamori.archivetune.shared.domain.lyrics.XmlNode
import moe.rukamori.archivetune.shared.domain.lyrics.XmlProcessingInstruction
import moe.rukamori.archivetune.shared.domain.lyrics.XmlText
import moe.rukamori.archivetune.shared.domain.lyrics.XmlTreeParser

/** The existing bounded XML reader refuses DTD/XXE; no DOM/JVM-only parser is introduced. */
internal class TtmlLyricsDocument(private val raw: String) : LyricsDocument {
    private val tree = XmlTreeParser.parse(raw)
    private val elements = tree.allElements()
    private val body = elements.firstOrNull { it.localName == "body" }
    private val paragraphs = (body?.descendantElements() ?: elements)
        .filter { it.localName == "p" && it.textContent().isNotBlank() }
    private val prefix = tree.documentElement.tagName.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
    private val keys = buildMap {
        val used = elements.mapNotNull { it.key }.toMutableSet()
        paragraphs.forEachIndexed { index, p ->
            val key = p.key ?: run {
                var candidate = "archivetune-translation-$index"
                while (!used.add(candidate)) candidate += "-next"
                candidate
            }
            put(p, key)
        }
    }
    override val formatName: String = "TTML"
    override val segments: List<LyricsDocumentSegment> =
        paragraphs.mapIndexed { index, p ->
            LyricsDocumentSegment(index, p.textContent().replace(Regex("\\s+"), " ").trim())
        }

    override fun rebuild(translations: Map<Int, String>): String {
        val translated = segments.mapNotNull { segment ->
            translations[segment.id]?.trim()?.takeIf { it.isNotEmpty() && !it.equals(segment.text, true) }
                ?.let { keys.getValue(paragraphs[segment.id]) to it }
        }
        if (translated.isEmpty()) return raw
        val annotation = buildString {
            append("<translation data-archivetune=\"translation\">")
            translated.forEach { (key, text) ->
                append("<text for=\"").append(escapeLyricsXml(key)).append("\">")
                append(escapeLyricsXml(text)).append("</text>")
            }
            append("</translation>")
        }
        val metadata = elements.firstOrNull { it.localName == "metadata" }
        val head = elements.firstOrNull { it.localName == "head" }
        return buildString {
            if (raw.trimStart().startsWith("<?xml", true)) append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            fun write(node: XmlNode) {
                when (node) {
                    is XmlText -> append(escapeLyricsXml(node.data))
                    is XmlCData -> append("<![CDATA[").append(node.data).append("]]>")
                    is XmlComment -> append("<!--").append(node.data).append("-->")
                    is XmlProcessingInstruction ->
                        append("<?").append(node.target).append(' ').append(node.data).append("?>")
                    is XmlElement -> {
                        if (node.localName == "translation" && node.getAttribute("data-archivetune") == "translation") return
                        append('<').append(node.tagName)
                        node.attributes.forEach { attr ->
                            append(' ').append(attr.name).append("=\"").append(escapeLyricsXml(attr.value)).append('"')
                        }
                        if (node in keys && node.key == null) {
                            append(" key=\"").append(escapeLyricsXml(keys.getValue(node))).append('"')
                        }
                        append('>')
                        if (node === tree.documentElement && head == null && metadata == null) {
                            append("<${prefix}head><${prefix}metadata>").append(annotation).append("</${prefix}metadata></${prefix}head>")
                        }
                        node.childNodes.forEach(::write)
                        if (node === metadata) append(annotation)
                        if (node === head && metadata == null) append("<${prefix}metadata>").append(annotation).append("</${prefix}metadata>")
                        append("</").append(node.tagName).append('>')
                    }
                }
            }
            write(tree.documentElement)
        }
    }
}

private val XmlElement.localName: String get() = tagName.substringAfter(':')
private val XmlElement.key: String?
    get() = attributes.firstOrNull { it.name.substringAfter(':') == "key" }?.value?.takeIf(String::isNotBlank)
