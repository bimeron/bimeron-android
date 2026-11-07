package org.bimeron.app

/** A unit of the 3-way profile merge. */
sealed interface MergeField {
    /** One `key = value` of a non-rule section, compared by its unquoted value; absent = null. */
    data class Key(val section: String, val key: String) : MergeField
    /** A whole section that one side removed while the other side edited it. */
    data class Section(val section: String) : MergeField
    /** The ordered `[Rule]` list, compared as a single unit. */
    data object Rules : MergeField
    /** Fallback when any side does not parse: the whole text. */
    data object WholeText : MergeField
}

enum class MergeChoice { MINE, THEIRS }

/** Why a merge fell back to one whole-text conflict. */
enum class WholeTextReason { UNPARSABLE, COMMENTS }

/**
 * One displayable line of a side of a conflict. [key] is null for single values and rules. Secret lines
 * must be shown through [display], which masks them unless the user revealed that conflict.
 */
data class MergeLine(val key: String?, val value: String, val secret: Boolean, val disabled: Boolean = false) {
    /** Never prints the value, which may be a secret. */
    override fun toString() = "MergeLine(key=$key, secret=$secret, disabled=$disabled)"
    /** True for an SSH file imported in this draft and not yet saved (`/bimeron-import/<uuid>`). */
    val imported get() = value.startsWith(ProfileMerge.IMPORT_PREFIX)
    fun display(revealed: Boolean): String = if (secret && !revealed) ProfileMerge.MASK else value
}

/** A field both sides changed to different values. A null side means the field is absent (removed) there. */
data class MergeConflict(val field: MergeField, val mine: List<MergeLine>?, val theirs: List<MergeLine>?)

/**
 * Pure 3-way merge of profile texts at [ConfigDocument] field level; no Android dependencies.
 *
 * - base: the text the draft was opened (or last rebased) at; mine: the draft; theirs: the latest stored text.
 * - A field is a `(section, key)` of a non-rule section. Values are compared unquoted, so quoting,
 *   spacing and key order are not changes.
 * - Equal on both sides (including the same edit on both sides) → no conflict. Changed on one side only
 *   → that side wins automatically. Changed on both sides to different values → [MergeConflict].
 *   Deleting a key counts as a change, so delete vs change is a conflict.
 * - A section removed on one side and edited on the other is a single [MergeField.Section] conflict;
 *   removed on one side and untouched on the other → removed.
 * - Rules are one ordered unit ([MergeField.Rules]): rule blocks are compared verbatim (comments and
 *   disabled state included, trailing whitespace ignored). If only mine changed them, mine's blocks are
 *   carried over verbatim. If both changed them, the decision is made on meaning (enabled state and
 *   values): equal meaning, or a cosmetic-only change on one side, merges; otherwise it is a conflict.
 * - If any side does not parse, the result is a single [MergeField.WholeText] conflict (mine vs theirs),
 *   unless mine and theirs are identical ([WholeTextReason.UNPARSABLE]).
 * - The merged text is built on theirs, so its comments and layout are kept. Comments travel only with
 *   blocks taken verbatim from mine: rule blocks taken from mine and sections only mine added. If mine
 *   changed comments that even the merge most favourable to mine (every conflict resolved as mine) would
 *   not keep, the result escalates to one whole-text conflict ([WholeTextReason.COMMENTS]); comment lines
 *   are compared as a multiset, so moving a comment is not a change. Theirs' comment edits never escalate:
 *   they are kept, except that a rule list whose only change in theirs is comments conflicts as a whole
 *   ([MergeField.Rules]) with mine's changed rules instead of being replaced silently.
 */
object ProfileMerge {
    const val MASK = "••••••••"
    const val IMPORT_PREFIX = "/bimeron-import/"

    private val publicKeys = mapOf(
        "Profile" to setOf("Name", "Version"),
        "AccessKey" to setOf("Type", "Server", "Port", "Method", "Plugin"),
        "Interface" to setOf("Mode", "Name", "MTU", "DNS", "IPv6", "KillSwitch"),
        "Routing" to setOf("Default"),
    )
    private val publicRuleKeys = setOf("Domain", "CIDR", "Action")

    /**
     * Raw profiles may carry credentials in any key, including future schemas (see [ProtocolForms.protected]),
     * so everything is secret except an explicit list of fields the editor shows in clear.
     */
    fun isSecret(section: String, key: String): Boolean = key !in publicKeys[section].orEmpty()

    class Result internal constructor(
        val conflicts: List<MergeConflict>,
        /** Fields changed only in mine; carried onto theirs automatically. */
        val mineChanges: Int,
        /** Fields changed only in theirs; kept automatically. */
        val theirsChanges: Int,
        /** Set when the result is one whole-text conflict instead of a field merge. */
        val wholeTextReason: WholeTextReason?,
        private val theirs: String,
        private val mine: String,
        private val parsed: Boolean,
        private val plan: List<(ConfigDocument) -> ConfigDocument>,
        private val resolutions: Map<MergeField, (ConfigDocument) -> ConfigDocument>,
    ) {
        val autoMerged get() = mineChanges + theirsChanges
        fun resolved(choices: Map<MergeField, MergeChoice>) = conflicts.all { it.field in choices }

        /** The merged text. Every conflict must have a choice. */
        fun resolve(choices: Map<MergeField, MergeChoice>): String {
            require(resolved(choices)) { "unresolved conflicts" }
            if (!parsed) return if (conflicts.isEmpty() || choices[MergeField.WholeText] == MergeChoice.THEIRS) theirs else mine
            var document = ConfigDocument.parse(theirs)
            plan.forEach { document = it(document) }
            conflicts.forEach { conflict ->
                if (choices[conflict.field] == MergeChoice.MINE) document = resolutions.getValue(conflict.field)(document)
            }
            return document.render()
        }
    }

    fun merge(base: String, mine: String, theirs: String): Result {
        val documents = runCatching { Triple(ConfigDocument.parse(base), ConfigDocument.parse(mine), ConfigDocument.parse(theirs)) }.getOrNull()
        if (documents == null) return wholeText(mine, theirs, WholeTextReason.UNPARSABLE)
        val (b, m, t) = documents
        val plan = mutableListOf<(ConfigDocument) -> ConfigDocument>()
        val resolutions = mutableMapOf<MergeField, (ConfigDocument) -> ConfigDocument>()
        val conflicts = mutableListOf<MergeConflict>()
        var mineChanges = 0
        var theirsChanges = 0

        val kb = keyed(b); val km = keyed(m); val kt = keyed(t)
        for (section in (kb.keys + km.keys + kt.keys)) {
            val vb = kb[section]; val vm = km[section]; val vt = kt[section]
            when {
                vb != null && vm == null && vt == null -> Unit
                vb != null && vm == null && vt == vb -> { plan += { it.removeSection(section) }; mineChanges++ }
                vb != null && vt == null && vm == vb -> theirsChanges++
                vb == null && vt == null && vm != null -> { plan += { it.withSectionFrom(m, section) }; mineChanges += maxOf(vm.size, 1) }
                vb != null && (vm == null || vt == null) -> {
                    val field = MergeField.Section(section)
                    conflicts += MergeConflict(field, vm?.let { sectionLines(section, it) }, vt?.let { sectionLines(section, it) })
                    resolutions[field] = { document -> replaceSection(document, section, vt, vm) }
                }
                else -> {
                    val inBase = vb.orEmpty(); val inMine = vm.orEmpty(); val inTheirs = vt.orEmpty()
                    for (key in (inBase.keys + inMine.keys + inTheirs.keys)) {
                        val x = inBase[key]; val y = inMine[key]; val z = inTheirs[key]
                        when {
                            y == z -> Unit
                            y == x -> theirsChanges++
                            z == x -> { plan += { setKey(it, section, key, y) }; mineChanges++ }
                            else -> {
                                val field = MergeField.Key(section, key)
                                val secret = isSecret(section, key)
                                conflicts += MergeConflict(field, y?.let { listOf(MergeLine(null, it, secret)) }, z?.let { listOf(MergeLine(null, it, secret)) })
                                resolutions[field] = { document -> setKey(document, section, key, y) }
                            }
                        }
                    }
                }
            }
        }

        val rb = rawRules(b); val rm = rawRules(m); val rt = rawRules(t)
        when {
            rm == rt -> Unit
            rm == rb -> theirsChanges++
            rt == rb -> { plan += { it.withRules(m.rules) }; mineChanges++ }
            // Both changed the blocks: decide on meaning, so a reformat on one side does not conflict.
            semanticRules(m) == semanticRules(t) -> Unit
            // Theirs only reformatted: mine's rules win, unless theirs edited rule comments that would be lost.
            semanticRules(t) == semanticRules(b) && ruleComments(t) == ruleComments(b) -> { plan += { it.withRules(m.rules) }; mineChanges++ }
            semanticRules(m) == semanticRules(b) -> theirsChanges++
            else -> {
                conflicts += MergeConflict(MergeField.Rules, ruleLines(m), ruleLines(t))
                resolutions[MergeField.Rules] = { it.withRules(m.rules) }
            }
        }
        val result = Result(conflicts, mineChanges, theirsChanges, null, theirs, mine, true, plan, resolutions)
        val favourable = ConfigDocument.parse(result.resolve(conflicts.associate { it.field to MergeChoice.MINE }))
        return if (dropsMineComments(b, m, t, favourable)) wholeText(mine, theirs, WholeTextReason.COMMENTS) else result
    }

    private fun wholeText(mine: String, theirs: String, reason: WholeTextReason): Result {
        val conflicts = if (mine == theirs) emptyList() else
            listOf(MergeConflict(MergeField.WholeText, listOf(MergeLine(null, mine, true)), listOf(MergeLine(null, theirs, true))))
        return Result(conflicts, 0, 0, reason.takeIf { conflicts.isNotEmpty() }, theirs, mine, false, emptyList(), emptyMap())
    }

    /**
     * True if [result] does not reflect mine's comment edits: for every comment line whose count mine
     * changed, the result must hold theirs' count plus mine's change (or mine's count when theirs made the
     * same change).
     */
    private fun dropsMineComments(b: ConfigDocument, m: ConfigDocument, t: ConfigDocument, result: ConfigDocument): Boolean {
        fun counts(document: ConfigDocument) = document.comments().groupingBy { it }.eachCount()
        val cb = counts(b); val cm = counts(m); val ct = counts(t); val cr = counts(result)
        return (cb.keys + cm.keys).any { line ->
            val base = cb[line] ?: 0; val ours = cm[line] ?: 0; val other = ct[line] ?: 0
            if (ours == base) return@any false
            val expected = if (ours == other) ours else maxOf(0, other + ours - base)
            (cr[line] ?: 0) != expected
        }
    }

    /** Non-rule sections with their unquoted values; the preamble (no section) has no keys. */
    private fun keyed(document: ConfigDocument): Map<String, Map<String, String>> =
        document.sections.filter { it != "Rule" }.associateWith { document.values(it) }

    private fun setKey(document: ConfigDocument, section: String, key: String, value: String?) =
        if (value == null) document.remove(section, key) else document.set(section, key, value)

    /** Makes [section] equal [target] (removed if null), starting from [current]. */
    private fun replaceSection(document: ConfigDocument, section: String, current: Map<String, String>?, target: Map<String, String>?): ConfigDocument {
        if (target == null) return document.removeSection(section)
        var result = document.addSection(section)
        current.orEmpty().keys.filter { it !in target }.forEach { result = result.remove(section, it) }
        target.forEach { (key, value) -> if (current?.get(key) != value) result = result.set(section, key, value) }
        return result
    }

    private fun sectionLines(section: String, values: Map<String, String>) =
        values.map { (key, value) -> MergeLine(key, value, isSecret(section, key)) }

    private data class RawRule(val disabled: Boolean, val lines: List<String>) {
        override fun toString() = "RawRule(disabled=$disabled, lines=${lines.size})"
    }
    private fun ruleComments(document: ConfigDocument) =
        document.rules.flatMap { rule -> rule.lines.map { it.trim() }.filter { it.startsWith('#') || it.startsWith(';') } }.sorted()
    private fun rawRules(document: ConfigDocument) = document.rules.map { rule ->
        RawRule(rule.disabled, rule.lines.map { it.trimEnd() }.dropLastWhile { it.isEmpty() })
    }
    private fun semanticRules(document: ConfigDocument) = document.rules.map { it.disabled to it.values() }
    private fun ruleLines(document: ConfigDocument) = document.rules.map { rule ->
        val values = rule.values()
        MergeLine(null, values.entries.joinToString(", ") { "${it.key} = ${it.value}" },
            secret = values.keys.any { it !in publicRuleKeys }, disabled = rule.disabled)
    }
}
