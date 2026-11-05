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

## Verification

Unit tests cover line preservation, disabled-rule round trips, source/destination replacement, rule priority changes, escaping, malformed input, draft undo and configuration-change detection. Device tests cover offline validation with the deployed AAR, rollback on invalid save, draft retention across Activity recreation and discard confirmation.
