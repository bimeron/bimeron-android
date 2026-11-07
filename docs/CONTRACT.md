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
