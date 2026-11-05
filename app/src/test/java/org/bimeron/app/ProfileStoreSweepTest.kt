package org.bimeron.app

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileStoreSweepTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun completeInventoryKeepsBothPathsAndDeletesOnlyOrphans() {
        val resources = SshProfileResources(File(temp.root.canonicalFile, "ssh-resources"))
        val id = UUID.randomUUID()
        val identity = resources.prepare(id, resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream()))
        val hosts = resources.prepare(id, resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream()))
        val orphan = resources.prepare(UUID.randomUUID(), resources.stage(SshProfileResources.Kind.IDENTITY, "old".byteInputStream()))
        val staging = resources.stage(SshProfileResources.Kind.IDENTITY, "staged".byteInputStream())
        resources.sweepBeforeDrafts(mapOf(id to setOf(identity.path, hosts.path)))
        assertTrue(File(identity.path).exists()); assertTrue(File(hosts.path).exists())
        assertFalse(File(orphan.path).exists()); assertFalse(staging.file.exists())
    }
    @Test fun incompleteOrUnexpectedOrSymlinkInventoryRetainsAll() {
        val root = File(temp.root.canonicalFile, "ssh-resources")
        val resources = SshProfileResources(root)
        val staged = resources.stage(SshProfileResources.Kind.IDENTITY, "staged".byteInputStream())
        resources.sweepBeforeDrafts(null)
        assertTrue(staged.file.exists())
        val unknown = File(root, "unknown").apply { writeText("retain") }
        resources.sweepBeforeDrafts(emptyMap())
        assertTrue(staged.file.exists()); assertTrue(unknown.exists())
        unknown.delete()
        val outside = temp.newFile().apply { writeText("outside") }
        Files.createSymbolicLink(File(root, "profiles").toPath(), outside.toPath())
        runCatching { resources.sweepBeforeDrafts(emptyMap()) }
        assertTrue(staged.file.exists()); assertEquals("outside", outside.readText())
    }
}
