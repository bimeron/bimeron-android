package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class AppWindowPollTest {
    private data class Node(val pkg: String, val secret: Boolean = false)
    private class Fixture(var windows: List<Node>) {
        var time = 0L
        val budgets = mutableListOf<Long>()
        var onIdle: () -> Unit = {}
        val poll = AppWindowPoll(
            roots = { appWindowRoots(windows) { it.pkg } },
            now = { time },
            idle = { budgets += it; time += it; onIdle() },
            find = { node: Node, predicate: (Node) -> Boolean -> node.takeIf(predicate) },
        )
    }

    @Test fun missingAndForeignOnlyWindowsCannotProveAbsence() {
        for (windows in listOf(emptyList(), listOf(Node("foreign")), listOf(Node("org.bimeron.app.test")))) {
            val f = Fixture(windows)
            assertFalse(f.poll.waitAbsent(250) { it.secret })
            assertEquals(250L, f.time)
            assertEquals(listOf(100L, 100L, 50L), f.budgets)
        }
    }

    @Test fun appRootProvesTrueAbsenceButSecretInAnyAppWindowPreventsIt() {
        val f = Fixture(listOf(Node("org.bimeron.app")))
        assertTrue(f.poll.waitAbsent(250) { it.secret })
        assertEquals(0L, f.time)
        f.windows += Node("org.bimeron.app", secret = true)
        assertFalse(f.poll.waitAbsent(250) { it.secret })
    }

    @Test fun foreignActiveWindowDoesNotHideAppMatch() {
        val target = Node("org.bimeron.app", secret = true)
        val f = Fixture(listOf(Node("foreign", secret = true), Node("org.bimeron.app"), target))
        assertSame(target, f.poll.waitNode(250) { it.secret })
    }

    @Test fun appAppearingDuringBoundedIdleCanProveAbsence() {
        val f = Fixture(emptyList())
        f.onIdle = { f.windows = listOf(Node("org.bimeron.app")) }
        assertTrue(f.poll.waitAbsent(250) { it.secret })
        assertEquals(100L, f.time)
    }

    @Test fun missingNodeUsesSameFiniteDeadline() {
        val f = Fixture(listOf(Node("org.bimeron.app")))
        assertNull(f.poll.waitNode(250) { it.secret })
        assertEquals(250L, f.time)
        assertEquals(listOf(100L, 100L, 50L), f.budgets)
    }
}
