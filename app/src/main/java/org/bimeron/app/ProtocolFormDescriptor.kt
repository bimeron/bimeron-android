package org.bimeron.app

internal enum class ProtocolKeyboardType { TEXT, NUMBER, PASSWORD }

internal data class ProtocolField(
    val key: String,
    val labelRes: Int,
    val secret: Boolean = false,
    val removable: Boolean = false,
    val options: List<String>? = null,
    val keyboardType: ProtocolKeyboardType = if (secret) ProtocolKeyboardType.PASSWORD else ProtocolKeyboardType.TEXT,
) {
    /**
     * The secret keyboard of this text field, or null for a public one. A field is secret when the form masks
     * it or asks for a password keyboard, or when the conflict panel classes its key as secret
     * ([ProfileMerge.isSecret]); masking itself still follows [secret] only.
     */
    val secretKeyboard: SecretKeyboard? get() = when {
        options != null -> null
        secret || keyboardType == ProtocolKeyboardType.PASSWORD -> SecretKeyboard.Masked
        !ProfileMerge.isSecret("AccessKey", key) -> null
        keyboardType == ProtocolKeyboardType.NUMBER -> SecretKeyboard.Number
        else -> SecretKeyboard.Clear
    }
}

/** Protocol hooks run only for structured documents; shared field and endpoint checks live in the facade. */
internal interface ProtocolFormDescriptor {
    val types: Set<String>
    val sourceSchemes: Set<String>
    val fields: List<ProtocolField>
    fun fields(doc: ConfigDocument): List<ProtocolField> = fields
    val hintRes: Int? get() = null
    fun rawOnly(doc: ConfigDocument): Boolean
    fun invalid(doc: ConfigDocument): Boolean
    /** A message for field [key] of [doc] in the draft owning [imports], shown at the field; null when none. */
    fun fieldError(doc: ConfigDocument, key: String, imports: SshDraftResources): Int? = null
}
