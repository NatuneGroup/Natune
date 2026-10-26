package moe.rukamori.archivetune.desktop.integration

import javax.xml.parsers.DocumentBuilderFactory

object DesktopStrings {
    private val values by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        DesktopStrings::class.java.getResourceAsStream("/desktop_strings.xml")!!.use {
            val nodes = factory.newDocumentBuilder().parse(it).getElementsByTagName("string")
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }
    }
    operator fun get(key: String): String = values.getValue(key)
}
