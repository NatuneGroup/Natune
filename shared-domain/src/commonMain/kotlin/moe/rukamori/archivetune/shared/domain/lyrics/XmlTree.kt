package moe.rukamori.archivetune.shared.domain.lyrics

/**
 * Node kinds of the parsed XML tree. They mirror the DOM node types the TTML reader distinguishes:
 * only [XmlText] is a TEXT node, [XmlCData] is a CDATA section, and comments and processing
 * instructions split adjacent text exactly as they do in a DOM.
 */
internal sealed interface XmlNode

internal class XmlText(val data: String) : XmlNode

internal class XmlCData(val data: String) : XmlNode

internal class XmlComment(val data: String) : XmlNode

internal class XmlProcessingInstruction(val target: String, val data: String) : XmlNode

internal class XmlAttribute(val name: String, val value: String)

/**
 * An element with qualified names kept as written (no namespace resolution). Attributes are sorted by
 * name because a DOM NamedNodeMap is, and the TTML reader takes "the first attribute whose name ends
 * with" in that order.
 */
internal class XmlElement(
    val tagName: String,
    attributes: List<XmlAttribute>,
) : XmlNode {
    val attributes: List<XmlAttribute> = attributes.sortedBy { it.name }

    private val children = ArrayList<XmlNode>()

    val childNodes: List<XmlNode>
        get() = children

    internal fun appendChild(node: XmlNode) {
        children.add(node)
    }

    /** DOM getAttribute: the value of the named attribute, or "" when it is absent. */
    fun getAttribute(name: String): String = attributes.firstOrNull { it.name == name }?.value.orEmpty()

    /** DOM getElementsByTagName("*") on this element: descendants in document order, self excluded. */
    fun descendantElements(): List<XmlElement> {
        val out = ArrayList<XmlElement>()
        collectDescendants(out)
        return out
    }

    /** True when at least one descendant element exists (a child element exists iff a descendant does). */
    fun hasDescendantElement(): Boolean = children.any { it is XmlElement }

    /** DOM textContent: data of descendant TEXT and CDATA nodes in order; comments and PIs excluded. */
    fun textContent(): String {
        val out = StringBuilder()
        appendTextContent(out)
        return out.toString()
    }

    /** Concatenated data of direct TEXT children only. CDATA sections are not TEXT nodes. */
    fun directTextContent(): String = children.filterIsInstance<XmlText>().joinToString(separator = "") { it.data }

    private fun collectDescendants(out: MutableList<XmlElement>) {
        for (child in children) {
            if (child is XmlElement) {
                out.add(child)
                child.collectDescendants(out)
            }
        }
    }

    private fun appendTextContent(out: StringBuilder) {
        for (child in children) {
            when (child) {
                is XmlText -> out.append(child.data)
                is XmlCData -> out.append(child.data)
                is XmlElement -> child.appendTextContent(out)
                else -> Unit
            }
        }
    }
}

internal class XmlDocument(val documentElement: XmlElement) {
    /** DOM getElementsByTagName("*") on the document: every element in document order, root included. */
    fun allElements(): List<XmlElement> = listOf(documentElement) + documentElement.descendantElements()
}
