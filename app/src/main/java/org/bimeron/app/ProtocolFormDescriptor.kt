package org.bimeron.app

internal enum class ProtocolKeyboardType { TEXT, NUMBER, PASSWORD }

internal data class ProtocolField(
    val key: String,
    val labelRes: Int,
    val secret: Boolean = false,
    val removable: Boolean = false,
    val options: List<String>? = null,
    val keyboardType: ProtocolKeyboardType = if (secret) ProtocolKeyboardType.PASSWORD else ProtocolKeyboardType.TEXT,
)

/** Protocol hooks run only for structured documents; shared field and endpoint checks live in the facade. */
internal interface ProtocolFormDescriptor {
    val types: Set<String>
    val sourceSchemes: Set<String>
    val fields: List<ProtocolField>
    fun fields(doc: ConfigDocument): List<ProtocolField> = fields
    val hintRes: Int? get() = null
    fun rawOnly(doc: ConfigDocument): Boolean
    fun invalid(doc: ConfigDocument): Boolean
}
