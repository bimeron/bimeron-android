package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SshKeyImportTest {
    @Test fun ownershipAndDuplicateResults() {
        val c = SshKeyImportController(); val draft = Any()
        assertNull(c.begin(SshProfileResources.Kind.IDENTITY))
        c.update(draft, true)
        val ticket = c.begin(SshProfileResources.Kind.IDENTITY)!!
        assertNull(c.begin(SshProfileResources.Kind.KNOWN_HOSTS))
        assertTrue(c.finish(ticket)); assertFalse(c.finish(ticket))
    }
    @Test fun lateSyntheticStagingRemainsUntransferredAndCanBeDiscarded() {
        val root = Files.createTempDirectory("ssh-import-test").toFile().canonicalFile
        try {
            val resources = SshProfileResources(File(root, "resources"))
            val c = SshKeyImportController(); c.update(Any(), true)
            val ticket = c.begin(SshProfileResources.Kind.IDENTITY)!!
            val token = resources.stage(ticket.kind, "synthetic fixture".byteInputStream())
            c.update(Any(), true)
            var callbacks = 0
            if (c.finish(ticket)) callbacks++ else resources.discard(token)
            assertEquals(0, callbacks)
            assertTrue(File(root, "resources/staging").listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun disableDraftChangeAndDisposalInvalidateInFlightResult() {
        for (invalidate in listOf<(SshKeyImportController, Any) -> Unit>(
            { c, d -> c.update(d, false); c.update(d, true) },
            { c, _ -> c.update(Any(), true) },
            { c, _ -> c.dispose() },
        )) {
            val c = SshKeyImportController(); val draft = Any(); c.update(draft, true)
            val ticket = c.begin(SshProfileResources.Kind.KNOWN_HOSTS)!!
            invalidate(c, draft); assertFalse(c.finish(ticket))
        }
    }
}
