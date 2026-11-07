package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class ProfileCopyNameTest {
    private val english: (String, Int) -> String = { base, n -> if (n == 1) "$base (copy)" else "$base (copy $n)" }
    // The Russian format from values-ru ("copy" in Russian), escaped to keep Cyrillic in resources only.
    private val russian: (String, Int) -> String = { base, n -> if (n == 1) "$base (\u043a\u043e\u043f\u0438\u044f)" else "$base (\u043a\u043e\u043f\u0438\u044f $n)" }

    @Test fun firstFreeLabelWins() {
        assertEquals("Office (copy)", copyProfileName("Office", setOf("Office"), english))
        assertEquals("Office (copy 2)", copyProfileName("Office", setOf("Office", "Office (copy)"), english))
        assertEquals("Office (copy 4)", copyProfileName("Office", setOf("Office (copy)", "Office (copy 2)", "Office (copy 3)"), english))
        assertEquals("\u041e\u0444\u0438\u0441 (\u043a\u043e\u043f\u0438\u044f 2)", copyProfileName(" \u041e\u0444\u0438\u0441 ", setOf("\u041e\u0444\u0438\u0441 (\u043a\u043e\u043f\u0438\u044f)"), russian))
        // A copy of a copy gets its own suffix.
        assertEquals("Office (copy) (copy)", copyProfileName("Office (copy)", setOf("Office (copy)"), english))
    }

    @Test fun longNamesAreTruncatedToTheNameLimit() {
        val long = "x".repeat(Conf.NAME_LIMIT)
        val first = copyProfileName(long, emptySet(), english)
        assertEquals(Conf.NAME_LIMIT, first.length)
        assertTrue(first.endsWith(" (copy)"))
        assertEquals(first, Conf.validName(first))
        val numbered = copyProfileName(long, setOf(first), english)
        assertEquals(Conf.NAME_LIMIT, numbered.length)
        assertTrue(numbered.endsWith(" (copy 2)"))
        // Trailing spaces left by the cut are dropped, and a surrogate pair is never split.
        val spaced = "a".repeat(Conf.NAME_LIMIT - 8) + " " + "b".repeat(20)
        assertEquals("a".repeat(Conf.NAME_LIMIT - 8) + " (copy)", copyProfileName(spaced, emptySet(), english))
        val emoji = "a".repeat(Conf.NAME_LIMIT - 8) + "\uD83D\uDE00" + "b".repeat(10)
        val cut = copyProfileName(emoji, emptySet(), english)
        assertEquals("a".repeat(Conf.NAME_LIMIT - 8) + " (copy)", cut)
        assertTrue(cut.length <= Conf.NAME_LIMIT)
        assertEquals(cut, Conf.validName(cut))
    }
}
