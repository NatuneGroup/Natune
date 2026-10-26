/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.persistence

import moe.rukamori.archivetune.shared.domain.persistence.*

/** Portable convenience access only; the full document remains authoritative, including unknown fields. */
class LegacyQueueAccess(val document: LegacyQueueDocument) {
    val queue: LegacyNode.Object get() = document.objectNode(document.root)
    fun field(node: LegacyNode.Object, name: String): LegacyValue =
        requireNotNull(document.field(node, name)) { "Missing legacy field: $name" }
    fun number(node: LegacyNode.Object, name: String): Long = (field(node, name) as LegacyValue.Primitive).bits
    fun items(): List<LegacyNode.Object> = collection(field(queue, "items")).map(document::objectNode).also { items ->
        require(items.all { document.className(it) == "moe.rukamori.archivetune.models.MediaMetadata" })
    }
    fun collection(value: LegacyValue): List<LegacyValue> {
        return when (val node = document.node(value)) {
            is LegacyNode.Array -> node.values
            is LegacyNode.Object -> when (document.className(node)) {
                "java.util.Arrays\$ArrayList" -> collection(field(node, "a"))
                "java.util.Collections\$SingletonList", "java.util.Collections\$SingletonSet" -> listOf(field(node, "element"))
                "java.util.Collections\$EmptyList", "java.util.Collections\$EmptySet",
                "kotlin.collections.EmptyList", "kotlin.collections.EmptySet" -> emptyList()
                "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet", "java.util.LinkedHashSet",
                "java.util.CollSer", "kotlin.collections.builders.SerializedCollection" ->
                    node.annotations.values.flatten().filterNot { it is LegacyValue.Block }
                else -> throw IllegalArgumentException("Unsupported legacy collection")
            }
            else -> throw IllegalArgumentException("Legacy value is not a collection")
        }
    }
}
