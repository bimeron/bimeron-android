package org.bimeron.app

/** A line-preserving document; only explicitly edited fields are rewritten. */
class ConfigDocument private constructor(private val blocks: List<Block>) {
    data class Block(val section: String, val lines: List<String>, val disabled: Boolean = false, val begin: String = BEGIN, val end: String = END) {
        fun values(): Map<String, String> = lines.drop(if (section.isEmpty()) 0 else 1).mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';')) null
            else trimmed.substringBefore('=').trim() to Conf.unquote(trimmed.substringAfter('=').trim())
        }.toMap()
        fun withValue(key: String, value: String?, oldKey: String = key): Block {
            val result = lines.toMutableList()
            val index = result.indexOfFirst { it.substringBefore('=', "").trim() == oldKey }
            if (value == null) { if (index >= 0) result.removeAt(index) }
            else if (index >= 0) {
                val ending = if (result[index].endsWith("\r")) "\r" else ""
                val line = result[index].removeSuffix("\r")
                val left = line.substringBefore('=')
                val right = line.substringAfter('=')
                val leading = right.takeWhile { it.isWhitespace() }
                val trailing = right.drop(leading.length).takeLastWhile { it.isWhitespace() }
                result[index] = left.takeWhile { it.isWhitespace() } + key +
                    left.takeLastWhile { it.isWhitespace() } + "=" + leading + Conf.quote(value) + trailing + ending
            }
            else result.add(1, "$key = ${Conf.quote(value)}" + if (lines.firstOrNull()?.endsWith("\r") == true) "\r" else "")
            return copy(lines = result)
        }
    }
    val sections get() = blocks.map { it.section }.filter { it.isNotEmpty() }.toSet()
    val rules get() = blocks.filter { it.section == "Rule" }
    fun value(section: String, key: String, default: String = ""): String = blocks.firstOrNull { it.section == section }?.values()?.get(key) ?: default
    fun values(section: String): Map<String, String> = blocks.firstOrNull { it.section == section }?.values().orEmpty()
    fun remove(section: String, key: String): ConfigDocument = ConfigDocument(blocks.map {
        if (it.section == section) it.withValue(key, null) else it
    })
    fun removeSection(section: String): ConfigDocument = ConfigDocument(blocks.filterNot { it.section == section })
    fun source(): Pair<String, String>? = blocks.firstOrNull { it.section == "AccessKey" }?.values()?.entries?.firstOrNull { it.key in Conf.sources }?.let { it.key to it.value }
    fun set(section: String, key: String, value: String, oldKey: String = key): ConfigDocument {
        val index = blocks.indexOfFirst { it.section == section }
        val result = blocks.toMutableList()
        if (index < 0) result += Block(section, listOf("[$section]", "$key = ${Conf.quote(value)}", ""))
        else result[index] = result[index].withValue(key, value, oldKey)
        return ConfigDocument(result)
    }
    fun editRule(index: Int, key: String, value: String): ConfigDocument {
        val target = blocks.indices.filter { blocks[it].section == "Rule" }[index]
        val result = blocks.toMutableList()
        var rule = result[target]
        var oldKey = key
        if (key == "CIDR" || key == "Domain") {
            val opposite = if (key == "CIDR") "Domain" else "CIDR"
            if (key in rule.values()) rule = rule.withValue(opposite, null)
            else if (opposite in rule.values()) oldKey = opposite
        }
        result[target] = rule.withValue(key, value, oldKey)
        return ConfigDocument(result)
    }
    fun enableRule(index: Int, enabled: Boolean): ConfigDocument {
        val target = blocks.indices.filter { blocks[it].section == "Rule" }[index]
        return ConfigDocument(blocks.mapIndexed { i, block -> if (i == target) block.copy(disabled = !enabled) else block })
    }
    fun addRule() = ConfigDocument(blocks + Block("Rule", listOf("[Rule]", "Domain = example.com", "Action = proxy", "")))
    /** Appends an empty [section] header unless it already exists. */
    fun addSection(section: String): ConfigDocument =
        if (blocks.any { it.section == section }) this else ConfigDocument(blocks + Block(section, listOf("[$section]", "")))
    /** Replaces all rule blocks with [rules], kept verbatim, at the position of the first current rule (else at the end). */
    fun withRules(rules: List<Block>): ConfigDocument {
        require(rules.all { it.section == "Rule" })
        val at = blocks.indexOfFirst { it.section == "Rule" }.takeIf { it >= 0 } ?: blocks.size
        return ConfigDocument(blocks.take(at) + rules + blocks.drop(at).filterNot { it.section == "Rule" })
    }
    /** Trimmed comment lines of every block, including comments inside rules. */
    fun comments(): List<String> = blocks.flatMap { block ->
        block.lines.map { it.trim() }.filter { it.startsWith('#') || it.startsWith(';') }
    }
    fun removeRule(index: Int): ConfigDocument {
        val target = blocks.indices.filter { blocks[it].section == "Rule" }[index]
        return ConfigDocument(blocks.filterIndexed { i, _ -> i != target })
    }
    fun moveRule(index: Int, delta: Int): ConfigDocument {
        val positions = blocks.indices.filter { blocks[it].section == "Rule" }
        if (index + delta !in positions.indices) return this
        val result = blocks.toMutableList()
        val reordered = positions.map { blocks[it] }.toMutableList()
        reordered.add(index + delta, reordered.removeAt(index))
        positions.forEachIndexed { i, position -> result[position] = reordered[i] }
        return ConfigDocument(result)
    }
    fun render(includeDisabled: Boolean = true): String = blocks.flatMap { block ->
        if (!block.disabled) block.lines
        else if (!includeDisabled) emptyList()
        else listOf(block.begin) + block.lines.map { "#| $it" } + block.end
    }.joinToString("\n")
    fun formatted(): ConfigDocument = ConfigDocument(blocks.map { block -> block.copy(lines = block.lines.map { line ->
        val trim = line.trim()
        if (trim.isEmpty() || trim.startsWith('#') || trim.startsWith(';') || trim.startsWith('[')) line
        else "${trim.substringBefore('=').trim()} = ${trim.substringAfter('=').trim()}"
    }) })
    fun effective(): String = blocks.filterNot { it.disabled || it.section.isEmpty() }.joinToString("\n") { block ->
        "[${block.section}]\n" + block.values().filterKeys { block.section != "Profile" || it != "Name" }.toSortedMap().entries.joinToString("\n") { "${it.key}=${Conf.quote(it.value)}" }
    }
    fun validate(): Profile {
        val profile = Conf.parse(render(false))
        // Disabled rules must remain valid so enabling them cannot reveal stale metadata.
        rules.filter { it.disabled }.forEach { rule -> Conf.parse(render(false) + "\n" + rule.lines.joinToString("\n")) }
        return profile.copy(text = render())
    }
    companion object {
        private const val BEGIN = "# bimeron:disabled-rule"
        private const val END = "# bimeron:end-rule"
        fun parse(text: String): ConfigDocument {
            if (text.toByteArray(Charsets.UTF_8).size > 65536) throw ProfileError(R.string.profile_error_file_too_large)
            val blocks = mutableListOf<Block>()
            var section = ""
            var lines = mutableListOf<String>()
            var disabled = false
            var inDisabled = false
            var begin = BEGIN
            var end = END
            val sections = mutableSetOf<String>()
            var keys = mutableSetOf<String>()
            fun flush() { if (lines.isNotEmpty()) blocks += Block(section, lines.toList(), disabled, begin, end); lines = mutableListOf() }
            text.split('\n').forEachIndexed { index, original ->
                fun fail(): Nothing = throw ProfileError(R.string.profile_error_config_line, index + 1)
                if (original.removeSuffix("\r") == BEGIN) {
                    if (inDisabled) fail()
                    flush(); begin = original; inDisabled = true; disabled = true; section = ""; keys = mutableSetOf()
                    return@forEachIndexed
                }
                if (original.removeSuffix("\r") == END) {
                    if (!inDisabled || section != "Rule") fail()
                    end = original; flush(); inDisabled = false; disabled = false; section = ""; keys = mutableSetOf()
                    return@forEachIndexed
                }
                val line = if (inDisabled) { if (!original.startsWith("#| ")) fail(); original.removePrefix("#| ") } else original
                val trim = (if (index == 0) line.removePrefix("\uFEFF") else line).trim()
                if (trim.startsWith('[')) {
                    if (!trim.endsWith(']')) fail()
                    val next = trim.substring(1, trim.lastIndex).trim()
                    if (inDisabled && (next != "Rule" || section.isNotEmpty())) fail()
                    if (next.isEmpty() || next.any { it == '[' || it == ']' || it.isISOControl() }) fail()
                    if (next != "Rule" && !sections.add(next)) fail()
                    flush(); section = next; keys = mutableSetOf()
                } else if (trim.isNotEmpty() && !trim.startsWith('#') && !trim.startsWith(';')) {
                    if (section.isEmpty() || !trim.contains('=')) fail()
                    val key = trim.substringBefore('=').trim()
                    if (key.isEmpty() || !keys.add(key)) fail()
                    try { Conf.unquote(trim.substringAfter('=').trim()) } catch (_: ProfileError) { fail() }
                }
                lines += line
            }
            if (inDisabled) throw ProfileError(R.string.profile_error_unterminated_disabled_rule)
            flush()
            return ConfigDocument(blocks)
        }
    }
}
