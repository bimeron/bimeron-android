# Profile editor

The editor follows Android design 1.9: General, Network, Routing, and Source config. It uses the core revision pinned in [CORE-AAR.md](CORE-AAR.md).

## Draft and synchronization

`EditorDraft` lives in the activity ViewModel, so configuration changes retain the text, selected section, selection and undo history. It is deliberately not written to saved-instance state or disk because it contains credentials. Process death discards unsaved edits. Back asks whether to discard a dirty draft; switching sections never saves it.

`ConfigDocument` retains each section's original lines. Form edits replace only the selected option. Unrepresented transport fields and sections, including ShadowTLS, remain in the document. Explicit parameter sources are edited in Source config; the General key field is read-only, supports reveal/hide and copies the complete value to a sensitive clipboard entry. Dedicated key editing is deferred. Invalid raw syntax keeps the last parsed document but prevents form edits and saving until corrected or undone.

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

Validation checks local structure, byte limit, interface parameters and rule fields, then creates and stops a core Session without calling SetNetwork or Start. SSConf resolution is deferred until connection. Core error text is not displayed directly because it can contain credentials; safe line numbers are retained when available.

Android requires `Mode=vpn` and `KillSwitch=false`. Previously imported proxy or core-kill-switch profiles can still be loaded, but must be corrected in Source config before saving. System always-on/lockdown settings are app-wide and are never serialized into a profile. While the VPN service is running on API 29+, the editor shows the actual service-reported OS flags; otherwise it directs the user to system settings without claiming a status.

Only a validated document replaces the private profile file: a temporary file is written and synced before atomic rename. A failed validation leaves the prior file untouched. Saving does not interrupt an active session; effective transport/routing changes offer the existing reconnect action, while renaming and formatting alone do not. Cancelling that reconnect leaves the current session running and the saved configuration applies on the next connection.

## Concurrent changes

Opening the editor goes through `AppModel.openEditor`, which records the store revision of the shown profile as the draft's `baseRevision` (none if the shown profile no longer equals the stored one). Done and Delete commit with `compareAndSave`/`compareAndDelete` against that base, so any change made elsewhere since opening, including A→B→A with identical bytes, is a conflict. An update or delete without a base, or for a draft whose text no longer matches the request, is a conflict too; only creating a profile commits without a base. On conflict the editor stays open with the draft and its imported SSH files and says the edits were not saved. The profile list is reloaded without touching the draft, including after changes made through another window, so closing and reopening the profile pins its latest revision. A profile deleted elsewhere stays in the editor until the user leaves it. A delete that would certainly conflict fails before an active VPN is stopped.

`EditorDraft.editGeneration` advances on every text change, pending form edit toggle and undo. A validation or save result for a replaced or further-edited draft neither closes the editor nor replaces its message; a queued save for such a draft is dropped. A successful save closes the editor; the base is never advanced in place. Every commit takes a new store revision, so a draft left open after its own save conflicts on the next save until it is reopened.

### Three-way merge rules

`ProfileMerge` (pure Kotlin, no Android dependencies) merges three texts: the base (the text the draft was opened or last rebased at), mine (the draft) and theirs (the latest stored text). It works on `ConfigDocument` fields:

- A field is a `(section, key)` of a non-rule section. Values are compared unquoted, so quoting, spacing and key order are not changes. A missing key is a value too, so deleting a key on one side and changing it on the other is a conflict.
- A field changed on one side only takes that side's value automatically. A field changed on both sides to the same value is not a conflict. A field changed on both sides to different values is a conflict, and the user picks mine or theirs for it.
- A section removed on one side and untouched on the other is removed. A section removed on one side and edited on the other is a single section conflict: mine or theirs applies to the whole section.
- The `[Rule]` list is one field, because rule order is priority and disabled rules are blocks. It is compared verbatim (comments and disabled state included, trailing whitespace ignored). If only mine changed it, mine's rule blocks are carried over verbatim. If both sides changed it, a change with the same meaning (enabled state and values), or a cosmetic-only change on one side, merges; anything else is one rule-list conflict.
- If any of the three texts does not parse, the merge falls back to one whole-text conflict between mine and theirs (no conflict if they are identical).
- The merged text is built on theirs, so theirs' comments and layout are kept. Comment edits in mine are carried only inside rule blocks taken from mine; the merge reports when mine changed comments.
- Secrets: every value is treated as secret, except a fixed list the editor shows in clear (profile name and version, server, port, method, plugin, type, interface settings, default route and rule `Domain`/`CIDR`/`Action`). Whole-text values are always secret. The presentation model shows a fixed-length mask for secrets until the user reveals that conflict.
- Imported SSH files (`/bimeron-import/<uuid>` placeholders) are ordinary values of `IdentityFile`/`KnownHostsFile`, so a placeholder in mine survives whenever mine's value is chosen or carried.

## Verification

Unit tests cover line preservation, disabled-rule round trips, source/destination replacement, rule priority changes, escaping, malformed input, draft undo and configuration-change detection. Device tests cover offline validation with the deployed AAR, rollback on invalid save, draft retention across Activity recreation and discard confirmation.
