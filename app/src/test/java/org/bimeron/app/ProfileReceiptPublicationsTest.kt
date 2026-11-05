package org.bimeron.app

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ProfileReceiptPublicationsTest {
    @Test fun duplicateAndOutOfOrderReceiptsCannotRollbackOrResurrect() {
        val publications = ProfileReceiptPublications()
        val id = UUID.randomUUID()
        val one = ProfileRevision(5, 1); val two = ProfileRevision(5, 2); val three = ProfileRevision(5, 3)
        fun saved(revision: ProfileRevision) = CommitReceipt(UUID.randomUUID(), id, CommitReceipt.Kind.SAVED, revision, Sha256.of(byteArrayOf()))
        val newest = saved(two)
        assertTrue(publications.accept(newest, one))
        assertFalse(publications.accept(newest, null))
        assertFalse(publications.accept(saved(one), null))
        val deleted = CommitReceipt(UUID.randomUUID(), id, CommitReceipt.Kind.DELETED, null, null)
        assertTrue(publications.accept(deleted, two, two))
        assertFalse(publications.accept(deleted, null, two))
        assertFalse(publications.accept(saved(two), null))
        assertTrue(publications.accept(saved(three), null))
        assertFalse(publications.accept(deleted.copy(requestId = UUID.randomUUID()), three, two))
    }
}
