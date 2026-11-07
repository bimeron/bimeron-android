package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class ProfileMergeTest {
    private val base = "[Profile]\nVersion = 1\nName = Office\n\n" +
        "[AccessKey]\nType = ss\nServer = 192.0.2.1\nPort = 443\nMethod = aes-128-gcm\nPassword = base-secret\n\n" +
        "[Interface]\nMode = vpn\nName = Bimeron\nMTU = 1420\nDNS = 1.1.1.1\n\n" +
        "[Routing]\nDefault = proxy\n\n" +
        "[Rule]\nDomain = example.org\nAction = direct\n\n" +
        "[Rule]\nCIDR = 203.0.113.0/24\nAction = block\n"

    private fun edit(text: String, change: ConfigDocument.() -> ConfigDocument) = ConfigDocument.parse(text).change().render()
    private fun doc(text: String) = ConfigDocument.parse(text)
    private fun resolveAll(result: ProfileMerge.Result, choice: MergeChoice) =
        result.resolve(result.conflicts.associate { it.field to choice })

    @Test fun disjointEditsMergeWithoutConflicts() {
        val mine = edit(base) { set("Interface", "MTU", "1400").set("AccessKey", "Password", "mine-secret") }
        val theirs = edit(base) { set("Profile", "Name", "Renamed").set("Interface", "DNS", "9.9.9.9") }
        val result = ProfileMerge.merge(base, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(2, result.mineChanges)
        assertEquals(2, result.theirsChanges)
        assertEquals(4, result.autoMerged)
        val merged = doc(result.resolve(emptyMap()))
        assertEquals("1400", merged.value("Interface", "MTU"))
        assertEquals("mine-secret", merged.value("AccessKey", "Password"))
        assertEquals("Renamed", merged.value("Profile", "Name"))
        assertEquals("9.9.9.9", merged.value("Interface", "DNS"))
        assertEquals(2, merged.rules.size)
    }

    @Test fun sameEditOnBothSidesIsNotAConflict() {
        val mine = edit(base) { set("Interface", "MTU", "1400") }
        val theirs = edit(base) { set("Interface", "MTU", "1400").set("Profile", "Name", "Theirs") }
        val result = ProfileMerge.merge(base, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(0, result.mineChanges)
        assertEquals(theirs, result.resolve(emptyMap()))
    }

    @Test fun quotingAndSpacingAreNotChanges() {
        val mine = base.replace("MTU = 1420", "MTU=\"1420\"")
        val theirs = edit(base) { set("Interface", "MTU", "1500") }
        val result = ProfileMerge.merge(base, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("1500", doc(result.resolve(emptyMap())).value("Interface", "MTU"))
    }

    @Test fun bothChangedToDifferentValuesConflictAndEachChoiceApplies() {
        val mine = edit(base) { set("Interface", "MTU", "1400").set("Interface", "DNS", "8.8.8.8") }
        val theirs = edit(base) { set("Interface", "MTU", "1500") }
        val result = ProfileMerge.merge(base, mine, theirs)
        val field = MergeField.Key("Interface", "MTU")
        assertEquals(listOf(field), result.conflicts.map { it.field })
        assertEquals("1400", result.conflicts.single().mine!!.single().value)
        assertEquals("1500", result.conflicts.single().theirs!!.single().value)
        assertFalse(result.conflicts.single().mine!!.single().secret)
        assertEquals(1, result.mineChanges)
        assertFalse(result.resolved(emptyMap()))
        try { result.resolve(emptyMap()); fail("unresolved conflicts must not merge") } catch (_: IllegalArgumentException) {}
        val takeMine = doc(result.resolve(mapOf(field to MergeChoice.MINE)))
        assertEquals("1400", takeMine.value("Interface", "MTU"))
        assertEquals("8.8.8.8", takeMine.value("Interface", "DNS"))
        val takeTheirs = doc(result.resolve(mapOf(field to MergeChoice.THEIRS)))
        assertEquals("1500", takeTheirs.value("Interface", "MTU"))
        assertEquals("8.8.8.8", takeTheirs.value("Interface", "DNS"))
    }

    @Test fun deleteVersusChangeConflictsInBothDirections() {
        val removed = edit(base) { remove("Interface", "DNS") }
        val changed = edit(base) { set("Interface", "DNS", "9.9.9.9") }
        val field = MergeField.Key("Interface", "DNS")
        val mineDeleted = ProfileMerge.merge(base, removed, changed)
        assertEquals(field, mineDeleted.conflicts.single().field)
        assertNull(mineDeleted.conflicts.single().mine)
        assertEquals("", doc(mineDeleted.resolve(mapOf(field to MergeChoice.MINE))).value("Interface", "DNS"))
        assertFalse(mineDeleted.resolve(mapOf(field to MergeChoice.MINE)).contains("DNS"))
        val theirsDeleted = ProfileMerge.merge(base, changed, removed)
        assertNull(theirsDeleted.conflicts.single().theirs)
        assertEquals("9.9.9.9", doc(theirsDeleted.resolve(mapOf(field to MergeChoice.MINE))).value("Interface", "DNS"))
        assertFalse(theirsDeleted.resolve(mapOf(field to MergeChoice.THEIRS)).contains("DNS"))
    }

    @Test fun deleteOnOneSideOnlyIsCarried() {
        val mine = edit(base) { remove("Interface", "DNS") }
        val theirs = edit(base) { set("Profile", "Name", "Theirs") }
        val merged = ProfileMerge.merge(base, mine, theirs).resolve(emptyMap())
        assertFalse(merged.contains("DNS"))
        assertEquals("Theirs", doc(merged).value("Profile", "Name"))
    }

    @Test fun keysAddedOnBothSides() {
        val mine = edit(base) { set("Interface", "IPv6", "disable").set("Interface", "KillSwitch", "true") }
        val theirs = edit(base) { set("Interface", "IPv6", "enable").set("Interface", "KillSwitch", "true") }
        val result = ProfileMerge.merge(base, mine, theirs)
        val conflict = result.conflicts.single()
        assertEquals(MergeField.Key("Interface", "IPv6"), conflict.field)
        assertEquals("disable", doc(resolveAll(result, MergeChoice.MINE)).value("Interface", "IPv6"))
    }

    @Test fun sectionRemovedOnOneSideAndUntouchedOnTheOtherIsRemoved() {
        val withExtra = "$base\n[ShadowTLS]\nServerName = example.org\nPassword = tls\n"
        val mine = edit(withExtra) { removeSection("ShadowTLS") }
        val theirs = edit(withExtra) { set("Profile", "Name", "Theirs") }
        val result = ProfileMerge.merge(withExtra, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        val merged = doc(result.resolve(emptyMap()))
        assertFalse("ShadowTLS" in merged.sections)
        assertEquals("Theirs", merged.value("Profile", "Name"))
        val kept = ProfileMerge.merge(withExtra, theirs, mine).resolve(emptyMap())
        assertFalse("ShadowTLS" in doc(kept).sections)
    }

    @Test fun sectionRemovedVersusEditedIsOneSectionConflict() {
        val withExtra = "$base\n[ShadowTLS]\nServerName = example.org\nPassword = tls\n"
        val mine = edit(withExtra) { removeSection("ShadowTLS") }
        val theirs = edit(withExtra) { set("ShadowTLS", "Password", "rotated") }
        val result = ProfileMerge.merge(withExtra, mine, theirs)
        val field = MergeField.Section("ShadowTLS")
        val conflict = result.conflicts.single()
        assertEquals(field, conflict.field)
        assertNull(conflict.mine)
        assertEquals(listOf("ServerName", "Password"), conflict.theirs!!.map { it.key })
        assertTrue(conflict.theirs!!.all { it.secret })
        assertFalse("ShadowTLS" in doc(result.resolve(mapOf(field to MergeChoice.MINE))).sections)
        assertEquals("rotated", doc(result.resolve(mapOf(field to MergeChoice.THEIRS))).value("ShadowTLS", "Password"))
        // Reverse direction: mine edited the section that theirs removed.
        val reverse = ProfileMerge.merge(withExtra, theirs, mine)
        assertNull(reverse.conflicts.single().theirs)
        val restored = doc(reverse.resolve(mapOf(field to MergeChoice.MINE)))
        assertEquals("rotated", restored.value("ShadowTLS", "Password"))
        assertEquals("example.org", restored.value("ShadowTLS", "ServerName"))
    }

    @Test fun sectionAddedOnlyByMineIsCarried() {
        val mine = "$base\n[ShadowTLS]\nServerName = example.org\nPassword = tls\n"
        val theirs = edit(base) { set("Interface", "MTU", "1500") }
        val result = ProfileMerge.merge(base, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(2, result.mineChanges)
        val merged = doc(result.resolve(emptyMap()))
        assertEquals("tls", merged.value("ShadowTLS", "Password"))
        assertEquals("1500", merged.value("Interface", "MTU"))
    }

    @Test fun rulesChangedOnlyByMineAreCarriedVerbatimWithComments() {
        val mine = edit(base) { moveRule(1, -1).enableRule(0, false) }.replace("[Rule]\nDomain = example.org", "[Rule]\n# keep me\nDomain = example.org")
        val theirs = edit(base) { set("Interface", "MTU", "1500") }
        val result = ProfileMerge.merge(base, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        val merged = doc(result.resolve(emptyMap()))
        assertEquals(doc(mine).rules, merged.rules)
        assertTrue(merged.rules[0].disabled)
        assertTrue(result.resolve(emptyMap()).contains("# keep me"))
        assertEquals("1500", merged.value("Interface", "MTU"))
    }

    @Test fun rulesChangedOnlyByTheirsAreKept() {
        val mine = edit(base) { set("Interface", "MTU", "1400") }
        val theirs = edit(base) { removeRule(0) }
        val merged = doc(ProfileMerge.merge(base, mine, theirs).resolve(emptyMap()))
        assertEquals(1, merged.rules.size)
        assertEquals("1400", merged.value("Interface", "MTU"))
    }

    @Test fun ruleListChangedOnBothSidesIsOneConflict() {
        val mine = edit(base) { editRule(0, "Action", "proxy") }
        val theirs = edit(base) { addRule() }
        val result = ProfileMerge.merge(base, mine, theirs)
        val conflict = result.conflicts.single()
        assertEquals(MergeField.Rules, conflict.field)
        assertEquals(2, conflict.mine!!.size)
        assertEquals(3, conflict.theirs!!.size)
        assertEquals("Domain = example.org, Action = proxy", conflict.mine!![0].value)
        assertFalse(conflict.mine!![0].secret)
        assertEquals(doc(mine).rules, doc(result.resolve(mapOf(MergeField.Rules to MergeChoice.MINE))).rules)
        assertEquals(doc(theirs).rules, doc(result.resolve(mapOf(MergeField.Rules to MergeChoice.THEIRS))).rules)
    }

    @Test fun ruleListsWithTheSameMeaningOrACosmeticChangeMerge() {
        val reformatted = base.replace("Domain = example.org", "Domain=example.org")
        val semantic = edit(base) { editRule(1, "Action", "proxy") }
        val same = ProfileMerge.merge(base, semantic, semantic.replace("Action = proxy", "Action=proxy"))
        assertTrue(same.conflicts.isEmpty())
        val cosmeticTheirs = ProfileMerge.merge(base, semantic, reformatted)
        assertTrue(cosmeticTheirs.conflicts.isEmpty())
        assertEquals("proxy", doc(cosmeticTheirs.resolve(emptyMap())).rules[1].values()["Action"])
        val cosmeticMine = ProfileMerge.merge(base, reformatted, semantic)
        assertTrue(cosmeticMine.conflicts.isEmpty())
        assertEquals("proxy", doc(cosmeticMine.resolve(emptyMap())).rules[1].values()["Action"])
    }

    @Test fun disabledStateIsPartOfTheRuleList() {
        val mine = edit(base) { enableRule(0, false) }
        val theirs = edit(base) { editRule(0, "Action", "block") }
        val conflict = ProfileMerge.merge(base, mine, theirs).conflicts.single()
        assertEquals(MergeField.Rules, conflict.field)
        assertTrue(conflict.mine!![0].disabled)
        assertFalse(conflict.theirs!![0].disabled)
    }

    @Test fun unparsableSideFallsBackToWholeTextConflict() {
        val broken = "$base\nnot a key line"
        val theirs = edit(base) { set("Interface", "MTU", "1500") }
        val result = ProfileMerge.merge(base, broken, theirs)
        val conflict = result.conflicts.single()
        assertEquals(MergeField.WholeText, conflict.field)
        assertTrue(conflict.mine!!.single().secret)
        assertTrue(conflict.theirs!!.single().secret)
        assertEquals(0, result.autoMerged)
        assertEquals(broken, result.resolve(mapOf(MergeField.WholeText to MergeChoice.MINE)))
        assertEquals(theirs, result.resolve(mapOf(MergeField.WholeText to MergeChoice.THEIRS)))
        // An unparsable base or theirs falls back the same way; identical sides do not conflict.
        assertEquals(MergeField.WholeText, ProfileMerge.merge(broken, base, theirs).conflicts.single().field)
        assertEquals(MergeField.WholeText, ProfileMerge.merge(base, theirs, broken).conflicts.single().field)
        val identical = ProfileMerge.merge(base, broken, broken)
        assertTrue(identical.conflicts.isEmpty())
        assertEquals(broken, identical.resolve(emptyMap()))
    }

    @Test fun mergedTextKeepsTheirsLayoutAndFlagsMineCommentEdits() {
        val commented = base.replace("[Interface]", "# theirs comment\n[Interface]")
        val mine = edit(base) { set("Interface", "MTU", "1400") }.replace("[Routing]", "# my note\n[Routing]")
        val result = ProfileMerge.merge(base, mine, commented)
        assertTrue(result.commentsMayBeLost)
        val merged = result.resolve(emptyMap())
        assertTrue(merged.contains("# theirs comment"))
        assertFalse(merged.contains("# my note"))
        assertFalse(ProfileMerge.merge(base, edit(base) { set("Interface", "MTU", "1400") }, commented).commentsMayBeLost)
    }

    @Test fun secretsAreMaskedInThePresentationModelUntilRevealed() {
        val mine = edit(base) { set("AccessKey", "Password", "mine-secret").set("Interface", "MTU", "1400") }
        val theirs = edit(base) { set("AccessKey", "Password", "their-secret").set("Interface", "MTU", "1500") }
        val conflicts = ProfileMerge.merge(base, mine, theirs).conflicts.associateBy { it.field }
        val password = conflicts.getValue(MergeField.Key("AccessKey", "Password"))
        val line = password.mine!!.single()
        assertTrue(line.secret)
        assertEquals(ProfileMerge.MASK, line.display(revealed = false))
        assertEquals(ProfileMerge.MASK, password.theirs!!.single().display(revealed = false))
        assertEquals("mine-secret", line.display(revealed = true))
        val mtu = conflicts.getValue(MergeField.Key("Interface", "MTU")).mine!!.single()
        assertEquals("1400", mtu.display(revealed = false))
        // The mask never reveals the secret's length.
        assertEquals(ProfileMerge.MASK, MergeLine(null, "x", true).display(false))
    }

    @Test fun unknownAndSourceFieldsAreSecretByDefault() {
        assertTrue(ProfileMerge.isSecret("AccessKey", "SS"))
        assertTrue(ProfileMerge.isSecret("AccessKey", "VLESS"))
        assertTrue(ProfileMerge.isSecret("AccessKey", "Password"))
        assertTrue(ProfileMerge.isSecret("AccessKey", "IdentityFile"))
        assertTrue(ProfileMerge.isSecret("FutureTransport", "Token"))
        assertFalse(ProfileMerge.isSecret("AccessKey", "Server"))
        assertFalse(ProfileMerge.isSecret("Profile", "Name"))
        val withSecretRule = base.replace("CIDR = 203.0.113.0/24", "CIDR = 203.0.113.0/24\nToken = t")
        val conflict = ProfileMerge.merge(withSecretRule, edit(withSecretRule) { removeRule(0) }, edit(withSecretRule) { addRule() }).conflicts.single()
        assertTrue(conflict.mine!!.single().secret)
        assertFalse(conflict.theirs!![0].secret)
    }

    @Test fun importedSshPlaceholdersInMineSurviveTheMerge() {
        val ssh = "[Profile]\nVersion = 1\nName = Ssh\n\n[AccessKey]\nType = ssh\nServer = 192.0.2.1\nPort = 22\nUsername = user\n" +
            "IdentityFile = /data/ssh/old/identity\n\n[Interface]\nMode = vpn\nMTU = 1420\n"
        val placeholder = "/bimeron-import/6f1c3c4e-8e1a-4d2b-9a55-0e7a1f3d2c11"
        val hostsPlaceholder = "/bimeron-import/0a8b5c1e-2222-4d2b-9a55-0e7a1f3d2c11"
        val mine = edit(ssh) { set("AccessKey", "IdentityFile", placeholder).set("AccessKey", "KnownHostsFile", hostsPlaceholder) }
        // Theirs re-imported its own key: both changed IdentityFile; KnownHostsFile only changed in mine.
        val theirs = edit(ssh) { set("AccessKey", "IdentityFile", "/data/ssh/new/identity").set("Interface", "MTU", "1400") }
        val result = ProfileMerge.merge(ssh, mine, theirs)
        val field = MergeField.Key("AccessKey", "IdentityFile")
        val conflict = result.conflicts.single()
        assertEquals(field, conflict.field)
        assertTrue(conflict.mine!!.single().imported)
        assertFalse(conflict.theirs!!.single().imported)
        assertTrue(conflict.mine!!.single().secret)
        val merged = doc(result.resolve(mapOf(field to MergeChoice.MINE)))
        assertEquals(placeholder, merged.value("AccessKey", "IdentityFile"))
        assertEquals(hostsPlaceholder, merged.value("AccessKey", "KnownHostsFile"))
        assertEquals("1400", merged.value("Interface", "MTU"))
        assertEquals(setOf(placeholder, hostsPlaceholder), SshDraftResources.paths(merged.render()))
        val theirsChoice = doc(result.resolve(mapOf(field to MergeChoice.THEIRS)))
        assertEquals("/data/ssh/new/identity", theirsChoice.value("AccessKey", "IdentityFile"))
        assertEquals(hostsPlaceholder, theirsChoice.value("AccessKey", "KnownHostsFile"))
    }

    @Test fun mergedTextStaysValidForTheStore() {
        val mine = edit(base) { set("Interface", "MTU", "1400").addRule() }
        val theirs = edit(base) { set("Profile", "Name", "Theirs").remove("Interface", "DNS") }
        val merged = ProfileMerge.merge(base, mine, theirs).resolve(emptyMap())
        val profile = doc(merged).validate()
        assertEquals("Theirs", profile.name)
        assertEquals(3, doc(merged).rules.size)
    }

    @Test fun mineEqualToTheirsHasNothingToMerge() {
        val same = edit(base) { set("Interface", "MTU", "1400") }
        val result = ProfileMerge.merge(base, same, same)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(0, result.autoMerged)
        assertEquals(same, result.resolve(emptyMap()))
    }

    @Test fun theirsCosmeticRuleCommentsReplacedByMineAreFlagged() {
        val mine = edit(base) { editRule(1, "Action", "proxy") }
        val theirs = base.replace("[Rule]\nDomain = example.org", "[Rule]\n# their note\nDomain = example.org")
        val result = ProfileMerge.merge(base, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        assertFalse(result.resolve(emptyMap()).contains("# their note"))
        assertTrue(result.commentsMayBeLost)
        // Theirs' rule change kept, or no comment involved: nothing is flagged.
        assertFalse(ProfileMerge.merge(base, edit(base) { set("Interface", "MTU", "1400") }, theirs).commentsMayBeLost)
        assertFalse(ProfileMerge.merge(base, mine, base.replace("Domain = example.org", "Domain=example.org")).commentsMayBeLost)
    }

    @Test fun mergeLinesNeverPrintTheirValues() {
        val line = MergeLine("Password", "do-not-print", secret = true)
        assertFalse(line.toString().contains("do-not-print"))
        assertFalse(MergeConflict(MergeField.Key("AccessKey", "Password"), listOf(line), null).toString().contains("do-not-print"))
    }
}
