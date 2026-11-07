# Android contract

The embedded core artifact and its source revision are documented in [CORE-AAR.md](CORE-AAR.md). This document describes how the app uses that artifact.

- Every successful save passes local document validation and `ProfileStore.validateCore`: `Androidcore.newSession` followed by `stop`, without `setNetwork` or `start`. Invalid local documents can fail before reaching the adapter. Each disabled rule is also temporarily enabled for core validation. The current save path rejects proxy mode and `KillSwitch=true`.
- Existing unsupported profiles that pass local `ConfigDocument` validation can be loaded and corrected in Source config. This does not promise loading arbitrary invalid profiles. Failed validation leaves the previous private file intact; valid saves sync a temporary file before rename.
- Profile Name is optional in the core format. Import supplies a filename fallback in the private copy; the original document is unchanged. Android requires a valid display name.
- The document editor preserves unrepresented fields, comments and sections. Preservation does not guarantee acceptance: core schema validation rejects unknown fields. See [PROFILE-EDITOR.md](PROFILE-EDITOR.md) for source/form synchronization and disabled-rule envelopes.
- Android uses the generated Java AAR API and `VpnService`; desktop RPC instructions are not Android integration instructions.
- Starting a session is not proof of connection. `BimeronVpnService` polls status and reports connected only after the core reports `connected`.
- CIDR rules require an explicit prefix, including `/32` for a single IPv4 address or `/128` for a single IPv6 address. Android validation and the audited routing compiler require prefixes.
- Core kill switch and Android per-app routing are not promised by this integration. OS always-on/lockdown settings are separate from profile fields.
- Proxy profiles cannot be saved without adapter validation.
- Process (per-app) routing is not a profile feature: the rule model exposes CIDR and domain rules only. GeoIP/GeoSite datasets are stored privately by `GeoDataStore` (see [ARCHITECTURE.md](ARCHITECTURE.md)) but are not yet passed to the core session.
- IKEv2 is not a supported Android connection path; the Android adapter rejects it.

## SSConf snapshot store

- `ProfileStore` persists one fetched SSConf body per profile in `filesDir/ssconf/<uuid>.snap` (directory `0700`, file `0600`), next to `profiles/` under the same trusted root. Format v1: magic `BSN1`, version `1`, profile UUID, `sourceLen` (1..8192) and UTF-8 source, `bodyLen` (1..65536) and body, `savedAt` (informational only), SHA-256 trailer; all integers big-endian. `SSConfSnapshots` owns the codec and `sourceOf`, the exact Core source identity (Go `TrimSpace` of `SSConf`, else `Key`, with a case-sensitive `ssconf://` prefix).
- `readForSession` returns the decoded snapshot only when it is bound to the profile's current source. A corrupt, foreign or stale file is deleted and reported as absent; a symlink is ignored, never followed.
- `commitSnapshot(ticket, body)` re-checks under the store lock that the profile exists (`DELETED`), still has the ticket's source (`SOURCE_CHANGED`; source A→B→A is accepted), that no newer ticket has committed (`SUPERSEDED`) and that `ticket.stillValid()` holds (`STALE_TICKET`). It then writes `<uuid>.snap.tmp-*`, syncs, atomically renames and syncs the directory. An I/O failure returns `NotPersisted` and leaves the previous file; the caller keeps the body in memory.
- `discardSnapshot(ticket, digest)` deletes the file only when it decodes to the ticket's source and body digest. It never consumes the ticket, so a following `commitSnapshot` with the same ticket can replace the body.
- Saving a profile keeps its snapshot only if the new source equals the snapshot's source; deleting a profile deletes its snapshot. The startup sweep deletes `.snap` files without a profile and leftover tmp files, and deletes nothing when `ssconf/` contains anything unexpected or a symlink.

## SSH host keys

Core ADR 0035 (accept-new, opt-in) and `core: docs/android-ssh-auth.md`, "Host-key offers".

- Every SSH-capable profile opts in: `BimeronVpnService.runSession` reads the profile's stored lines on IO,
  after the single session-input read and before any native session, with the attempt token checked on both
  sides, and calls `Session.acceptNewHostKeys(bytes)` once, before `start`. SSH-capable is the recovery
  classification `mayNeedInteractiveAuth` (SSH, `Type = ssh`, any `ssh://` value, unknown or unparsable);
  positively non-SSH profiles do not opt in. The call is made even with no stored keys, so an unknown host is
  offered. An imported `KnownHostsFile` stays trusted alongside; Core trusts the union. Android sessions use
  only files imported into app storage: no storage permission is declared and no `/sdcard` or `~/.ssh` path is
  read or passed to Core.
- If the store cannot be read the session opts in with no keys and journals a fixed WARN. If Core rejects the
  bytes the session starts without the opt-in (known_hosts-only verification) and journals the same WARN.
  Neither path trusts anything silently.
- `pendingAuth` kind `host_key` is parsed strictly by `SshAuthenticationController.parse`: 48-hex ids;
  `host` from Core's literal alphabet as `host:port` (IPv6 bracketed); a known key type; `SHA256:` plus 43
  unpadded base64 characters equal to the digest of the key in the line; and a `knownHostsLine` of exactly
  three single-space printable ASCII fields, at most 12 KiB, whose host is the OpenSSH normalization of the
  lowercased dialed `host` (bare for port 22, `[host]:port` otherwise, at most 300 characters) and whose type and key blob match
  `keyType`. Anything else is rejected and never shown. Existing kinds are unchanged.
- The dialog ("New server") shows `host:port`, the key type and the full fingerprint in monospace, with
  FLAG_SECURE. Trust sends exactly `["accept"]` through `VpnAuthEndpoint.reply`; Cancel and dismiss call
  `cancelAuth`. Rejections use the existing failed states.
- Persistence: after Core accepts `["accept"]`, `AuthAttempt` (service side) passes the `knownHostsLine` of the
  offer it published under the same ids to `ProfileStore.rememberTrustedHostKey`, never UI input. Under the
  store lock the profile must still exist; the line is deduplicated and the file capped at 256 KiB (Core's
  limit). A failure or a full file leaves the session running (Core remembers the key for its reconnects) and
  journals a fixed WARN.
- Storage: `filesDir/trusted-hosts/<uuid>.known_hosts`, directory `0700`, file `0600`, one plain line per key,
  replaced atomically (tmp, fsync, `ATOMIC_MOVE`, directory fsync). It is a separate top-level tree, outside
  `profiles/`, `ssconf/` and `ssh-resources/`. Every access holds the store lock and takes no other lock;
  `rememberTrustedHostKey` must never be called with the `VpnLive.gate` monitor held (lock order guard →
  draft → resources → gate) and fails closed if it is. Reads return only lines that are valid offer lines, so a
  damaged file cannot make Core reject the opt-in. `compareAndDelete` removes the file best-effort under the
  store lock. The fail-closed startup sweep also inventories `trusted-hosts/`: it deletes `<uuid>.known_hosts`
  files without a `profiles/<uuid>.conf` and leftover `<uuid>.known_hosts.tmp-*` files, and any other entry,
  directory or symlink makes it delete nothing at all. A new profile, including "Save as new profile", starts
  with no trusted keys; a deleted profile counts 0 and Forget does nothing.
- The profile editor's SSH section shows "Trusted server keys: N" with Forget (after a confirmation), which
  deletes the file immediately; it is not part of Save.
- `Status.LastError` mapping, ahead of the auth-history heuristic: `ssh_host_key_mismatch` →
  `HOST_KEY_MISMATCH`, `ssh_host_key_rejected` → `AUTH_CANCELLED`, `ssh_host_key_unanswered` →
  `AUTH_UNANSWERED`, `ssh_host_key_unknown` → `SSH_CONNECTION_FAILED`, `ssh_known_hosts_missing` →
  `SSH_KNOWN_HOSTS_MISSING`. A changed key is never offered and never accepted. Recovery still skips SSH-capable
  profiles.
