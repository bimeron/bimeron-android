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
- **Recovery start** (any start other than the user `START`, e.g. a sticky restart; always-on is not started, see QA) and Core-internal reconnects use only the stored snapshot, with no network access. A recovery start without a usable stored snapshot fails with `RECOVERY_NEEDS_USER` (the next user connect fetches). Recovery classification is unchanged: SSH-capable profiles are still skipped before this step.
- Source and body never appear in logs, the journal, exception messages or `VpnState`.
- Decision logic: `resolveSessionSnapshot` (JVM: `SSConfSessionSnapshotTest`); bind-only proof on the emulator: `SSConfConnectFetchTest`. See [SSCONF-NETWORK.md](SSCONF-NETWORK.md).
