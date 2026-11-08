package moe.rukamori.archivetune.shared.data.backup

import moe.rukamori.archivetune.shared.domain.settings.*

/** Strict reader for the retained writer's XML vocabulary. DTDs and external entities are refused. */
internal object AndroidBackupSettingsXml {
    private class Node(val tag: String, val attributes: Map<String, String>) {
        val children = mutableListOf<Node>()
        val text = StringBuilder()
    }
    fun parse(bytes: ByteArray): PreferenceSnapshot {
        val xml = bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        val stack = mutableListOf<Node>()
        var root: Node? = null
        var position = 0
        for (token in TOKEN.findAll(xml)) {
            val text = xml.substring(position, token.range.first)
            if (stack.isEmpty()) require(text.isBlank()) else stack.last().text.append(unescape(text))
            position = token.range.last + 1
            val value = token.value
            if (value.startsWith("<?")) { require(root == null && value.startsWith("<?xml ")); continue }
            if (value.startsWith("<!--")) continue
            if (value.startsWith("</")) {
                require(stack.isNotEmpty() && stack.last().tag == value.substring(2, value.length - 1).trim())
                stack.removeAt(stack.lastIndex)
                continue
            }
            require(!value.startsWith("<!"))
            val inside = value.substring(1, value.length - 1).trim().removeSuffix("/").trim()
            val tag = inside.takeWhile { !it.isWhitespace() }
            require(tag in setOf("ArchiveTuneBackup", "Settings", "boolean", "string", "int", "long", "float", "double", "string-set", "item"))
            val attributes = linkedMapOf<String, String>()
            var tail = inside.removePrefix(tag)
            while (tail.isNotBlank()) {
                val match = ATTRIBUTE.find(tail) ?: error("Invalid XML attribute")
                require(match.range.first == 0)
                val name = match.groupValues[1]
                require(name !in attributes)
                attributes[name] = unescape(match.groupValues[3].ifEmpty { match.groupValues[4] })
                tail = tail.substring(match.range.last + 1)
            }
            val node = Node(tag, attributes)
            if (stack.isEmpty()) { require(root == null); root = node } else stack.last().children.add(node)
            if (!value.endsWith("/>")) stack.add(node)
            require(stack.size <= 5)
        }
        require(stack.isEmpty() && xml.substring(position).isBlank())
        val outer = requireNotNull(root)
        require(outer.tag == "ArchiveTuneBackup" && outer.attributes.isEmpty() && outer.text.isBlank())
        val settings = outer.children.single()
        require(settings.tag == "Settings" && settings.attributes.isEmpty() && settings.text.isBlank())
        val rows = settings.children.map { node ->
            val name = requireNotNull(node.attributes["name"])
            require(name.isNotBlank() && node.text.isBlank() && node.attributes.keys.all { it == "name" || it == "value" })
            val value = node.attributes["value"].orEmpty()
            if (node.tag != "string-set") require(node.children.isEmpty())
            val row: Pair<PreferenceKey<*>, Any> = when (node.tag) {
                "boolean" -> BooleanPreferenceKey(name) to value.toBooleanStrict()
                "string" -> StringPreferenceKey(name) to value
                "int" -> IntPreferenceKey(name) to value.toInt()
                "long" -> LongPreferenceKey(name) to value.toLong()
                "float" -> FloatPreferenceKey(name) to value.toFloat().also { require(it.isFinite()) }
                "double" -> DoublePreferenceKey(name) to value.toDouble().also { require(it.isFinite()) }
                "string-set" -> StringSetPreferenceKey(name) to node.children.map {
                    require(it.tag == "item" && it.attributes.isEmpty() && it.children.isEmpty()); it.text.toString()
                }.toSet()
                else -> error("Unknown preference type")
            }
            row
        }
        require(rows.map { it.first.name }.distinct().size == rows.size)
        return PreferenceSnapshot.of(*rows.toTypedArray())
    }
    private fun unescape(value: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char != '&') { require(char != '<'); out.append(char); continue }
            val end = value.indexOf(';', index)
            require(end >= index && end - index <= 16)
            val entity = value.substring(index, end)
            out.append(when (entity) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val code = if (entity.startsWith("#x")) entity.drop(2).toInt(16)
                        else { require(entity.startsWith("#")); entity.drop(1).toInt() }
                    require(code in 1..0x10ffff && code !in 0xd800..0xdfff)
                    if (code <= 0xffff) code.toChar().toString() else {
                        val n = code - 0x10000
                        "${(0xd800 + (n shr 10)).toChar()}${(0xdc00 + (n and 1023)).toChar()}"
                    }
                }
            })
            index = end + 1
        }
        return out.toString()
    }
    private val TOKEN = Regex("""<!--(?s:.*?)-->|<\?.*?\?>|</?[^<>]+>""")
    private val ATTRIBUTE = Regex("""^\s+([A-Za-z_-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
}
