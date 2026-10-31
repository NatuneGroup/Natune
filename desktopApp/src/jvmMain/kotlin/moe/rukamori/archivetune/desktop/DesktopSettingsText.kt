package moe.rukamori.archivetune.desktop

import javax.xml.parsers.DocumentBuilderFactory

/** The desktop settings editors' strings, kept in their own resource next to `desktop_strings.xml`. */
internal object DesktopSettingsText {
    private val values: Map<String, String> by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        DesktopSettingsText::class.java.getResourceAsStream(RESOURCE)!!.use { stream ->
            val nodes = factory.newDocumentBuilder().parse(stream).getElementsByTagName("string")
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }
    }

    operator fun get(key: String): String = values.getValue(key)

    fun format(key: String, vararg args: Any): String = get(key).format(*args)

    /** True when [key] names a string, for labels built from enum names. */
    fun has(key: String): Boolean = key in values

    private const val RESOURCE = "/desktop_settings_strings.xml"
}
