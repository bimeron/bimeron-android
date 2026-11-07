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

## ssconf snapshot store

- `ProfileStore` persists one fetched ssconf body per profile in `filesDir/ssconf/<uuid>.snap` (directory `0700`, file `0600`), next to `profiles/` under the same trusted root. Format v1: magic `BSN1`, version `1`, profile UUID, `sourceLen` (1..8192) and UTF-8 source, `bodyLen` (1..65536) and body, `savedAt` (informational only), SHA-256 trailer; all integers big-endian. `SSConfSnapshots` owns the codec and `sourceOf`, the exact Core source identity (Go `TrimSpace` of `SSConf`, else `Key`, with a case-sensitive `ssconf://` prefix).
- `readForSession` returns the decoded snapshot only when it is bound to the profile's current source. A corrupt, foreign or stale file is deleted and reported as absent; a symlink is ignored, never followed.
- `commitSnapshot(ticket, body)` re-checks under the store lock that the profile exists (`DELETED`), still has the ticket's source (`SOURCE_CHANGED`; source A→B→A is accepted), that no newer ticket has committed (`SUPERSEDED`) and that `ticket.stillValid()` holds (`STALE_TICKET`). It then writes `<uuid>.snap.tmp-*`, syncs, atomically renames and syncs the directory. An I/O failure returns `NotPersisted` and leaves the previous file; the caller keeps the body in memory.
- `discardSnapshot(ticket, digest)` deletes the file only when it decodes to the ticket's source and body digest. It never consumes the ticket, so a following `commitSnapshot` with the same ticket can replace the body.
- Saving a profile keeps its snapshot only if the new source equals the snapshot's source; deleting a profile deletes its snapshot. The startup sweep deletes `.snap` files without a profile and leftover tmp files, and deletes nothing when `ssconf/` contains anything unexpected or a symlink.

## ssconf connect-time fetch

The fetch model follows Outline dynamic access keys: a user-initiated connect fetches the config, automatic reconnects reuse the last fetched one, and a running session is never changed mid-flight. There is no manual refresh action.

- **User connect** (the explicit `START` intent, including a forced restart after saving): exactly one bounded, bind-only `fetchSSConf` (no retry), before the native cleanup gate, capability check, session and TUN. The body is validated by Core (`newSSConfSnapshot`) and committed with `commitSnapshot(ticket, body)`; the session uses that snapshot. `NotPersisted` continues with the in-memory snapshot and journals `SNAPSHOT_NOT_PERSISTED`; a `Conflict` fails with `PROFILE_CHANGED`.
- **Fetch failure on a user connect** (`SSCONF_FETCH_FAILED`, `SSCONF_NETWORK_UNAVAILABLE`, or a body Core rejects, `SSCONF_SNAPSHOT_INVALID`): the stored snapshot is used if Core accepts it, and the journal gets a fixed warning with the fetch's code only ("ssconf update failed (CODE); connecting with the saved configuration"). A stored snapshot Core rejects is discarded. With no usable stored snapshot the attempt fails with the fetch's code. A rejected fetched body is never committed. `CORE_INCOMPATIBLE` (native load failure) and cancellation never fall back.
- **Recovery start** (any start other than the user `START`, e.g. a sticky restart; always-on is not started, see QA) and Core-internal reconnects use only the stored snapshot, with no network access. A recovery start without a usable stored snapshot ends like a skipped SSH recovery: `RECOVERY_NEEDS_USER`, the "Tap to reconnect" notification, a fixed journal entry and the recovery id cleared (a missing snapshot is skipped by `planStart`; a stored body Core rejects is caught in the snapshot step). The next user connect fetches. A start is a recovery exactly when the session got `planStart`'s classified read (`isRecoveryStart`).
- Source and body never appear in logs, the journal, exception messages or `VpnState`.
- Decision logic: `resolveSessionSnapshot` (JVM: `SSConfSessionSnapshotTest`); bind-only proof on the emulator: `SSConfConnectFetchTest`. See [SSCONF-NETWORK.md](SSCONF-NETWORK.md).

## SSH host keys

Core ADR 0035 (accept-new, opt-in) and `core: docs/android-ssh-auth.md`, "Host-key offers".

- Every SSH-capable profile opts in: `BimeronVpnService.runSession` reads the profile's stored lines on IO,
  after the single session-input read and before any native session, with the attempt token checked on both
  sides, and calls `Session.acceptNewHostKeys(bytes)` once, before `start`. SSH-capable is the recovery
  classification `mayNeedInteractiveAuth` (SSH, `Type = ssh`, any `ssh://` value, unknown or unparsable);
  positively non-SSH profiles do not opt in. The call is made even with no stored keys, so an unknown host is
  offered. An imported `KnownHostsFile` stays trusted alongside; Core trusts the union.
- SSH files (decision 8). What is enforced, and where:
  - **Service (every start, before any native session):** `requireSshFilesInsideApp` (`SshFileGuard.kt`)
    collects every SSH file reference Core would open: `[AccessKey]` `IdentityFile`/`KnownHostsFile` and the
    `identity`/`known_hosts` query parameters of any `ssh://` value (keys case-insensitive, every value,
    percent-decoded). Each must be the right kind of file under this profile's imports,
    `<filesRoot>/ssh-resources/profiles/<profile uuid>/<generation uuid>/{identity|known_hosts}`, with
    `filesRoot` resolved as `ProfileStore` resolves it. Anything else (`/sdcard/...`, `/storage/...`, `~`,
    relative paths, another profile's or the wrong kind of file, a malformed query) fails the attempt with
    `SSH_FILE_OUTSIDE_APP`; nothing is dialed.
  - **Core (every Android session, core ADR 0036):** no default identity scan, no `~/.ssh/known_hosts`
    fallback, `~` paths refused, no SSH agent; without an imported known_hosts and without the opt-in the attempt
    fails with `ssh_known_hosts_missing` before dialing.
  - **Manifest:** no storage permission is requested (checked on the processed merged manifest), and the app
    reads SSH files only through the Storage Access Framework import.
  - **Save (every save path, through `compareAndSave`):** `ProfileStore.validate` applies the same check
    (`SshFileGuard.insideApp(text, id, filesRoot)`, which the service's check delegates to) after resolving the
    draft's import placeholders, before the store lock, and refuses with the fixed `ssh_files_not_imported`
    message. The SSH form marks such typed paths at the field. Profiles stored earlier with outside paths
    still load and open; they fail to save until the files are imported, and fail to connect as above.
- Trusted keys fail closed. If the store cannot be read (I/O error, symlink, oversized, or any line that is not
  a valid offer line) the attempt fails with `TRUSTED_KEYS_UNREADABLE` before any native session; it never opts
  in with no keys, which would offer a changed key as a new server. If Core rejects the bytes the attempt fails
  with the same code and `start` is never called. The profile editor shows "Trusted server keys could not be
  read" with Forget enabled, which recovers.
- `pendingAuth` kind `host_key` is parsed strictly by `SshAuthenticationController.parse`: 48-hex ids;
  `host` from Core's literal alphabet as `host:port` (IPv6 bracketed); a known key type; `SHA256:` plus 43
  unpadded base64 characters equal to the digest of the key in the line; and a `knownHostsLine` of exactly
  three single-space printable ASCII fields, at most 12 KiB, whose host is the OpenSSH normalization of the
  lowercased dialed `host` (bare for port 22, `[host]:port` otherwise, at most 300 characters) and whose type and key blob match
  `keyType`. Anything else is rejected and never shown. Existing kinds are unchanged.
- The dialog ("New server") shows `host:port`, the key type and the full fingerprint in monospace. The dialog
  sets `SecureFlagPolicy.SecureOn` on its own window (FLAG_SECURE on the dialog only; the activity window is
  not made secure). Trust sends exactly `["accept"]` through `VpnAuthEndpoint.reply`; Cancel and dismiss call
  `cancelAuth`. Rejections use the existing failed states.
- Persistence: a successful `ReplyAuth` is final in Core even if a timeout, Stop or network change follows,
  so the line is persisted if and only if `ReplyAuth` returned success. After Core accepts `["accept"]`, `AuthAttempt` (service side) passes the `knownHostsLine` of the
  offer it published under the same ids to `ProfileStore.rememberTrustedHostKey`, never UI input. Under the
  store lock the profile must still exist; the line is deduplicated and the file capped at 256 KiB (Core's
  limit). A failure or a full file leaves the session running (Core remembers the key for its reconnects) and
  journals a fixed WARN.
- Storage: `filesDir/trusted-hosts/<uuid>.known_hosts`, directory `0700`, file `0600`, one plain line per key,
  replaced atomically (tmp, fsync, `ATOMIC_MOVE`, directory fsync). Reading never drops lines: any invalid line
  makes the file unreadable (above). It is a separate top-level tree, outside `profiles/`, `ssconf/` and
  `ssh-resources/`. Every access holds the store lock and takes no other lock;
  `rememberTrustedHostKey` must never be called with the `VpnLive.gate` monitor held (lock order guard →
  draft → resources → gate) and fails closed if it is. `compareAndDelete` removes the file best-effort under the
  store lock. The fail-closed startup sweep also inventories `trusted-hosts/`: it deletes `<uuid>.known_hosts`
  files without a `profiles/<uuid>.conf` and leftover `<uuid>.known_hosts.tmp-*` files, and any other entry,
  directory or symlink makes it delete nothing at all. A new profile, including "Save as new profile", starts
  with no trusted keys; a deleted profile counts 0 and Forget does nothing.
- The profile editor's SSH section shows "Trusted server keys: N" with Forget (after a confirmation), which
  deletes the file immediately; it is not part of Save. The count is read off Main when shown, on resume and on
  every VPN state change.
- `Status.LastError` mapping, ahead of the auth-history heuristic: `ssh_host_key_mismatch` →
  `HOST_KEY_MISMATCH`, `ssh_host_key_rejected` → `AUTH_CANCELLED`, `ssh_host_key_unanswered` →
  `AUTH_UNANSWERED`, `ssh_host_key_unknown` → `SSH_CONNECTION_FAILED`, `ssh_host_key_ca_required` →
  `HOST_KEY_CA_REQUIRED` (the imported known_hosts uses `@cert-authority`; forgetting app-owned keys does not
  help, so it has its own message), `ssh_known_hosts_missing` → `SSH_KNOWN_HOSTS_MISSING`. The app's own
  fixed codes `TRUSTED_KEYS_UNREADABLE` and `SSH_FILE_OUTSIDE_APP` are raised before Core runs. A changed key
  is never offered and never accepted.
- Recovery starts (sticky restart and always-on `SERVICE_INTERFACE`) never show UI. They classify with
  `mustSkipRecovery`, which includes the same `mayNeedInteractiveAuth` that decides the opt-in, so every profile
  that would opt in (and could therefore receive a host-key offer) is skipped before `runSession`. A recovery that
  proceeds is positively non-SSH: it does not opt in and does not read the trusted-keys store, and the file guard
  and fail-closed read still run on its input (`HostKeyRecoveryTest`).

## Session drop notice

Without system lockdown, Android sends traffic over the physical network as soon as the TUN closes, so a session that ends on its own must not end silently (security audit M-1).

- When `Command.Finished` arrives for the current attempt, `noteSessionFinished` / `shouldNotifyDrop` (`VpnRuntime.kt`) decide whether to post "VPN disconnected" (notification id 3, on the recovery channel `vpn_recovery`, shown as "VPN alerts"; opens `MainActivity`). The text is fixed: no profile name, id, server, Core or error text. Lock-screen visibility stays the default (private), like the recovery notification, and it alerts once.
- It is posted when the attempt was not ended by the user and either reached `connected` (a later failure in any state notifies, including Core giving up while `reconnecting`) or was a recovery start (sticky restart, always-on), which runs unattended. A recovery start that fails before its session runs (`CLEANUP_BLOCKED`, `RECOVERY_STATE_FAILED` in the start branch) is announced the same way.
- It is never posted for `Command.Stop` (including `onRevoke`), a newer `Start` (superseded token), `onDestroy` (all three advance the attempt gate), a failure whose `Command.Finished` was queued ahead of an in-app STOP or `onRevoke` that is already sent but not yet consumed (the stop's own system teardown can fail the session first, while its token is still current), `AUTH_CANCELLED` (SSH sign-in cancel or host-key rejection) or `RECOVERY_NEEDS_USER` (which has its own "Tap to reconnect" notification). A user connect that fails before `connected` is not announced: the user is in the app and sees the error.
- The user-intent check uses the code the attempt ended with, before a teardown code (`CLEANUP_BLOCKED`, `RECOVERY_STATE_FAILED`) replaces it. No new `VpnErrorCode` is added.
- It is cancelled on the next user `START` (`noteStartCommand`, like the recovery notification) and when a session reaches `connected`. With notifications off (`POST_NOTIFICATIONS` not granted, or turned off) nothing is posted.
- Known gaps (follow-ups): if the system destroys the service mid-session without a stop, nothing is posted (`onDestroy` cancels the command loop); and if Android delivers `onRevoke` only after the session's `Command.Finished` was handled, that revoke still shows the notice.
- Service wiring is covered by `DropNoticeServiceTest` (Robolectric, through `BimeronVpnService.sessionDriverForTest`, which replaces only the native part of a session).
- Switch and apply stop the old session before the new TUN exists, so protection briefly lapses without lockdown (audit L-2). This is a known limitation; see QA.
