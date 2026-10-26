/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.persistence

/** Passive Java-stream values. No runtime classes, constructors or serialization hooks are invoked. */
sealed interface LegacyValue {
    data object Null : LegacyValue
    /** Integral value or exact IEEE bits, according to the Java serialization type code. */
    data class Primitive(val type: Char, val bits: Long) : LegacyValue
    data class Reference(val handle: Int) : LegacyValue
    data class Block(val bytes: List<Byte>) : LegacyValue
}

data class LegacyClassDescriptor(
    val name: String,
    val serialVersionUid: Long,
    val flags: Int,
    val fields: Map<String, String>,
    val parentHandle: Int?,
)

sealed interface LegacyNode {
    data class Text(val value: String) : LegacyNode
    data class Object(
        val descriptorHandle: Int,
        /** Keys are declaring-class#field, preserving inherited fields without name collisions. */
        val fields: Map<String, LegacyValue>,
        val annotations: Map<String, List<LegacyValue>>,
    ) : LegacyNode
    data class Array(val descriptorHandle: Int, val values: List<LegacyValue>) : LegacyNode
    data class Enum(val descriptorHandle: Int, val name: String) : LegacyNode
}

/**
 * Lossless compatibility payload, NOT a smaller playback DTO or queue owner. Unknown fields remain
 * visible in the graph and the exact original stream remains available for the authoritative host.
 * Legacy files contain no revision: a storage revision belongs to a new envelope, never to history.
 */
class LegacyQueueDocument(
    val root: LegacyValue.Reference,
    val descriptors: Map<Int, LegacyClassDescriptor>,
    val nodes: Map<Int, LegacyNode>,
    sourceBytes: ByteArray,
) {
    private val source = sourceBytes.copyOf()
    fun originalBytes(): ByteArray = source.copyOf()
    fun node(value: LegacyValue): LegacyNode = nodes.getValue((value as LegacyValue.Reference).handle)
    fun objectNode(value: LegacyValue): LegacyNode.Object = node(value) as LegacyNode.Object
    fun className(value: LegacyNode.Object): String = descriptors.getValue(value.descriptorHandle).name
    fun field(value: LegacyNode.Object, name: String): LegacyValue? {
        val found = value.fields.filterKeys { it.substringAfterLast('#') == name }.values
        require(found.size <= 1) { "Ambiguous inherited legacy field" }
        return found.singleOrNull()
    }
    fun text(value: LegacyValue): String? = if (value == LegacyValue.Null) null else (node(value) as LegacyNode.Text).value
}
