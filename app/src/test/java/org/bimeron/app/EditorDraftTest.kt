package org.bimeron.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class EditorDraftTest {
    private fun draft(): EditorDraft {
        val text = Conf.fromKey("Example", "socks5://192.0.2.1:1080")
        return EditorDraft(Conf.parse(text).copy(id = "test"))
    }
    @Test fun pendingProtocolEditsAreDirtyAndBlockUndoUntilResolved() {
        val draft = draft()
        val original = draft.source
        draft.pendingProtocolEdits = true
        assertTrue(draft.dirty)
        assertEquals(original, draft.source)
        draft.pendingProtocolEdits = false
        assertFalse(draft.dirty)

        draft.set("Profile", "Name", "Applied")
        val applied = draft.source
        draft.pendingProtocolEdits = true
        draft.undo()
        assertEquals(applied, draft.source)
        assertTrue(draft.canUndo)
        draft.pendingProtocolEdits = false
        assertTrue(draft.dirty)
        draft.undo()
        assertEquals(original, draft.source)
        assertFalse(draft.dirty)
    }
    @Test fun editGenerationAdvancesOnTextChangesPendingTogglesAndUndoOnly() {
        val draft = draft()
        assertNull(draft.baseRevision)
        var last = draft.editGeneration
        fun advanced(): Boolean = (draft.editGeneration > last).also { last = draft.editGeneration }
        draft.set("Profile", "Name", "Edited")
        assertTrue(advanced())
        draft.update(draft.source.copy(selection = TextRange(1, 3)))
        assertFalse(advanced())
        draft.tab = 2
        assertFalse(advanced())
        draft.pendingProtocolEdits = true
        assertTrue(advanced())
        draft.pendingProtocolEdits = true
        assertFalse(advanced())
        draft.undo()
        assertFalse("undo is blocked while protocol edits are pending", advanced())
        draft.pendingProtocolEdits = false
        assertTrue(advanced())
        draft.undo()
        assertTrue(advanced())
        draft.undo()
        assertFalse("empty history", advanced())
        val pinned = EditorDraft(draft.profile, ProfileRevision(1, 2))
        assertEquals(ProfileRevision(1, 2), pinned.baseRevision)
    }
    @Test fun invalidRawKeepsLastDocumentAndCanBeUndone() {
        val draft = draft()
        val previous = draft.source
        draft.update(TextFieldValue("[Profile", TextRange(8)))
        assertTrue(draft.rawInvalid)
        assertEquals("[Profile", draft.source.text)
        assertEquals("Example", draft.document.value("Profile", "Name"))
        draft.undo()
        assertEquals(previous, draft.source)
        assertFalse(draft.rawInvalid)
        assertFalse(draft.dirty)
    }
    @Test fun tabsAndTextSelectionDoNotSaveOrDiscardDraft() {
        val draft = draft()
        draft.set("Interface", "MTU", "1400")
        draft.tab = 3
        draft.update(draft.source.copy(selection = TextRange(2, 8)))
        draft.tab = 1
        assertEquals("1400", draft.document.value("Interface", "MTU"))
        assertTrue(draft.dirty)
        assertTrue(draft.configurationChanged())
        assertTrue(draft.profile.text.contains("MTU = 1420"))
        draft.undo()
        assertFalse(draft.dirty)
        draft.set("Profile", "Name", "New")
        assertFalse(draft.configurationChanged())
    }
    @Test fun rawFormReorderToggleAndUndoRestoreExactText() {
        val draft = draft()
        val original = draft.source
        val raw = TextFieldValue(original.text + "\n[Rule]\n# preserved\nFuture = \"a#;=b\"\n  Domain = a.example\n; tail\nAction = direct\n[Rule]\nDomain = b.example\nAction = proxy", TextRange(3))
        draft.update(raw)
        draft.edit(draft.document.editRule(0, "CIDR", "192.0.2.0/24"))
        draft.edit(draft.document.moveRule(0, 1))
        draft.edit(draft.document.enableRule(1, false))
        repeat(3) { draft.undo() }
        assertEquals(raw, draft.source)
        assertEquals(raw.text, draft.document.render())
        assertFalse(draft.rawInvalid)
        draft.undo()
        assertEquals(original, draft.source)
    }
}
