# Profile editor

The editor follows Android design 1.9: General, Network, Routing, and Source config. It uses the core revision pinned in [CORE-AAR.md](CORE-AAR.md).

## Draft and synchronization

`EditorDraft` lives in the activity ViewModel, so configuration changes retain the text, selected section, selection and undo history. It is deliberately not written to saved-instance state or disk because it contains credentials. Process death discards unsaved edits. Back asks whether to discard a dirty draft; switching sections never saves it.

`ConfigDocument` retains each section's original lines. Form edits replace only the selected option. Unrepresented transport fields and sections, including ShadowTLS, remain in the document. Explicit parameter sources are edited in Source config; the General "Key or link" field is read-only, supports reveal/hide and copies the complete value to a sensitive clipboard entry. Dedicated key editing is deferred, except for ssconf: an ssconf link is shown once, in that same field, which is editable and writes back to the key it is stored in (`SSConf` or `Key`). It stays masked until revealed and can be copied; below it the editor shows that only the link format is checked, and an invalid link blocks saving. Whether the field edits the link is decided by the profile as opened (its stored source is an ssconf form), not by the current text, so tab switches and rotation keep it: an edit that removes the `ssconf://` prefix of a `Key` stays editable, is reported invalid, and Done refuses it on every tab except Source config (where the whole document is edited, e.g. to change the protocol deliberately). Raw-only ssconf documents (several sources, `Type`, control characters) are edited in Source config. The type is shown as "ssconf"; the stored key names are unchanged. Invalid raw syntax keeps the last parsed document but prevents form edits and saving until corrected or undone.

Formatting normalizes assignment spacing without sorting sections or rules. Undo retains up to 32 text revisions in memory. The native Compose text field retains selection and IME composition; syntax coloring uses an identity offset mapping.

## Disabled rules

The core has no `Enabled` option. Disabled rules are retained in this exact comment envelope:

```ini
# bimeron:disabled-rule
#| [Rule]
#| Domain = example.com
#| Action = direct
# bimeron:end-rule
```

The editor preserves their position among enabled rules. It rejects malformed envelopes and validates disabled rules before saving, including through the deployed core with each disabled rule temporarily enabled. Ordinary comments are never interpreted as disabled rules. Domain/CIDR changes replace the old destination field; reordering preserves first-match semantics.

## Validation and persistence

Validation checks local structure, byte limit, interface parameters and rule fields, then creates and stops a core Session without calling SetNetwork or Start. ssconf resolution is deferred until connection: every user connect fetches the link once, and there is no manual refresh (see [CONTRACT.md](CONTRACT.md)). Core error text is not displayed directly because it can contain credentials; safe line numbers are retained when available.

Android requires `Mode=vpn` and `KillSwitch=false`. Previously imported proxy or core-kill-switch profiles can still be loaded, but must be corrected in Source config before saving. System always-on/lockdown settings are app-wide and are never serialized into a profile. While the VPN service is running on API 29+, the editor shows the actual service-reported OS flags; otherwise it directs the user to system settings without claiming a status.

Only a validated document replaces the private profile file: a temporary file is written and synced before atomic rename. A failed validation leaves the prior file untouched. Saving does not interrupt an active session; effective transport/routing changes offer the existing reconnect action, while renaming and formatting alone do not. Cancelling that reconnect leaves the current session running and the saved configuration applies on the next connection.

## Concurrent changes

Opening the editor goes through `AppModel.openEditor`, which records the store revision of the shown profile as the draft's `baseRevision` (none if the shown profile no longer equals the stored one). Done and Delete commit with `compareAndSave`/`compareAndDelete` against that base, so any change made elsewhere since opening, including A→B→A with identical bytes, is a conflict. An update or delete without a base, or for a draft whose text no longer matches the request, is a conflict too; only creating a profile commits without a base. On conflict the editor stays open with the draft and its imported SSH files, says the edits were not saved and offers a rebase (below). The profile list is reloaded without touching the draft, including after changes made through another window, so closing and reopening the profile pins its latest revision. A profile deleted elsewhere stays in the editor until the user leaves it. A delete that would certainly conflict fails before an active VPN is stopped.

### Rebase after a conflict

When a save from the editor conflicts and the draft is still shown unchanged, `AppModelOperations` reads the latest stored profile with its revision in one read under the store lock (`ProfileStore.readLatest`, the same classification as the compare-and-save precondition). It then merges the draft onto it with `ProfileMerge` and shows the conflict panel. Nothing is saved automatically.

- **Changed elsewhere.** The panel shows how many fields were merged automatically and lists each conflicting field with its two values, "Your version" and "Saved version". Each pair is a radio group. Secret values are masked until the user taps Show for that field; an imported SSH file reads as "Imported file (not saved yet)". Unresolved cards are highlighted and announce "Not chosen". Apply is enabled only when every conflict has a choice. Apply first runs the local profile validation a save runs; if the merged text fails it, the panel says so and nothing changes (the user picks other versions, reloads or saves as new). Applying replaces the draft text with the merged text and moves the draft's base (`EditorDraft.profile` and `baseRevision`) to the latest stored profile and revision, so the next Done compares against it. If the profile changed again in the meantime, that save conflicts and rebases again from the new base. Applying clears undo history and resets form-local state such as reveals.
- **Reload latest.** After a discard confirmation, the draft is replaced in place by the latest stored profile at its revision (read again at that moment). Edits and undo history are discarded; imports stay owned by the draft until it is disposed.
- **Save as new profile.** Creates a new profile with a new id from the draft text (with expected revision `null`) and closes the editor. The copy is renamed with a localized suffix: "Name (copy)" (localized), or "Name (copy 2)", 3 and so on if that name is already taken by a loaded profile. The base name is shortened, never inside a surrogate pair, so the result stays within the 128-character name limit. The SSH files the text references are copied into a fresh `SshDraftResources` for the new id (`copyTo`): both the draft's imports (staged or already published) and files of the old profile's saved generations. The commit hands them over to the new profile; the copy is disposed either way. The editor's own draft keeps its imports untouched until it is disposed (contract §9), so a failed save-as-new loses nothing.
- **Deleted or unreadable elsewhere.** Save as new profile is the only action (Reload latest would offer the same thing).
- When the merge falls back to one whole-text conflict, the panel says why (comment edits that cannot be merged, or a version that cannot be merged setting by setting); the choices are mine, theirs, or save as new.
- Cancel closes the panel and keeps the draft and its old base; the next Done conflicts and merges again.

While the panel is open, the editor's message line does not repeat the conflict message.

**Delete conflicts.** A delete from the editor whose base is stale gets no rebase panel. If the profile changed, a dialog asks "Profile was changed — Delete anyway / Cancel". Delete anyway re-reads the latest revision (`readLatest`) and compare-and-deletes against it, stopping the VPN first as every delete does when the profile is connected or a connection attempt is for it (`deleteStopsVpn`, which also decides when both delete confirmations warn that the connection will be stopped). If the profile changes again in between, the dialog appears again. A profile already deleted elsewhere counts as deleted: the editor closes. An unreadable profile keeps the conflict message.

The conflict is held by the draft (`EditorDraft.conflict`), so closing or replacing the editor drops it. A rebase, reload or save-as-new result that arrives after the draft was replaced or edited is dropped, like other editor results.

A draft's imported SSH key is published under the profile on its first save attempt, so a conflicting save leaves a published but unsaved generation. `SshProfileResources.prepare(..., draft = true)` marks it as draft-owned: another writer's save (`afterSaved`) or delete (`afterDeleted`) of the same profile keeps it until the draft saves it or aborts it on dispose. A rebased retry can therefore still use the import. Saving a profile marks every owned path it references as saved, even when another referenced path (for example a typed `KnownHostsFile`) is not owned by the app, so deleting the profile later removes its keys.

The saved key files the draft's base text references are pinned while the draft is open (`SshDraftResources.pinBase`, at creation and on every rebase or reload; a process-wide, memory-only registry, `SshGenerationPins`). `afterSaved` and `afterDeleted` keep pinned generations and remove them when the last pin is released on dispose, together with an emptied profile directory. Save-as-new can therefore copy the key of a profile deleted elsewhere, or whose key another editor replaced. Lock order: draft, resources, pin registry; the registry never calls out. The startup sweep runs before any draft exists, so pins never need persisting.

`EditorDraft.editGeneration` advances on every text change, pending form edit toggle, undo, rebase and reload. A validation or save result for a replaced or further-edited draft neither closes the editor nor replaces its message; a queued save for such a draft is dropped. A successful save closes the editor; a save never advances the base in place (only an applied rebase or a reload does). Every commit takes a new store revision, so a draft left open after its own save conflicts on its next save and is then rebased.

### Three-way merge rules

`ProfileMerge` (pure Kotlin, no Android dependencies) merges three texts: the base (the text the draft was opened or last rebased at), mine (the draft) and theirs (the latest stored text). It works on `ConfigDocument` fields:

- A field is a `(section, key)` of a non-rule section. Values are compared unquoted, so quoting, spacing and key order are not changes. A missing key is a value too, so deleting a key on one side and changing it on the other is a conflict.
- A field changed on one side only takes that side's value automatically. A field changed on both sides to the same value is not a conflict. A field changed on both sides to different values is a conflict, and the user picks mine or theirs for it.
- A section removed on one side and untouched on the other is removed. A section removed on one side and edited on the other is a single section conflict: mine or theirs applies to the whole section.
- The `[Rule]` list is one field, because rule order is priority and disabled rules are blocks. It is compared verbatim (comments and disabled state included, trailing whitespace ignored). If only mine changed it, mine's rule blocks are carried over verbatim. If both sides changed it, a change with the same meaning (enabled state and values), or a cosmetic-only change on one side, merges; anything else is one rule-list conflict.
- If any of the three texts does not parse, the merge falls back to one whole-text conflict between mine and theirs (no conflict if they are identical).
- The merged text is built on theirs, so theirs' comments and layout are kept. Comments travel only with blocks taken verbatim from mine: rule blocks taken from mine and sections only mine added. If mine changed comments that even the merge most favourable to mine (every conflict resolved as mine) would not keep, the merge escalates to one whole-text conflict (mine, theirs, or save as new) instead of dropping them. Comment lines are compared as a multiset per line text, so moving a comment is not an edit.
- Theirs' comment edits never escalate and are kept. One exception is decided explicitly: if theirs' only rule-list change is comments and mine changed the rules, that is a rule-list conflict, so theirs' rule comments are not replaced silently by mine's rules. A reformat without comment changes in theirs still lets mine's rules win.
- Secrets: every value is treated as secret, except a fixed list the editor shows in clear (profile name and version, server, port, method, plugin, type, interface settings, default route and rule `Domain`/`CIDR`/`Action`). Whole-text values are always secret. The presentation model shows a fixed-length mask for secrets until the user reveals that conflict.
- Imported SSH files (`/bimeron-import/<uuid>` placeholders) are ordinary values of `IdentityFile`/`KnownHostsFile`, so a placeholder in mine survives whenever mine's value is chosen or carried.

## Verification

Unit tests cover the three-way merge (`ProfileMergeTest`), rebase, reload and save-as-new through a real store (`EditorConflictRebaseTest`), line preservation, disabled-rule round trips, source/destination replacement, rule priority changes, escaping, malformed input, draft undo and configuration-change detection. Device tests cover offline validation with the deployed AAR, rollback on invalid save, draft retention across Activity recreation, discard confirmation and the conflict panel flow (`ProfileConflictDialogTest`).
