# ssconf network scope

`SSConfFetch.kt` (`fetchSSConf(context, source)`) is the only production consumer
of `withSSConfNetwork` and `SSConfNetworkLease`; no other app code may use them.
It performs one bounded request with no retry (5 s bind, 5 s capture, 15 s Core
resolve), must be called off Main, and maps failures only to the fixed codes
`SSCONF_NETWORK_UNAVAILABLE` (acquisition failure, or request creation or resolve
failing after lease invalidation) and `SSCONF_FETCH_FAILED` (any other request/resolve
error). Native load Errors (`LinkageError`) are not mapped and propagate out of
`fetchSSConf`; `resolveSessionSnapshot` maps them to `CORE_INCOMPATIBLE` (below).
Cancellation of the caller wins over any failure, and the invalidation
registration is closed on every path. Source, body and lease diagnostics never enter
exception messages, `VpnState` or the journal. An internal overload takes the lease
scope and request factory as seams (JVM tests: `SSConfFetchMappingTest`).

The VPN service uses it on every user-initiated connect of an ssconf profile (Outline
dynamic access key model, see [CONTRACT.md](CONTRACT.md)): one bounded fetch, Core
validation, then `ProfileStore.commitSnapshot`, and the session uses the fresh snapshot.
If the fetch fails (`SSCONF_FETCH_FAILED`, `SSCONF_NETWORK_UNAVAILABLE`) or Core rejects
the fetched body (`SSCONF_SNAPSHOT_INVALID`, never committed), the persisted snapshot is
used if Core accepts it, with a fixed journal warning that carries only the code; a
persisted snapshot that Core rejects is discarded, and without a usable one the attempt
fails with the fetch's code. Recovery starts and Core-internal reconnects never fetch:
they use the persisted snapshot, and a recovery start without one fails with
`RECOVERY_NEEDS_USER`. There is no manual refresh. A commit conflict fails the attempt
with `PROFILE_CHANGED`; an I/O failure to persist continues with the validated in-memory
snapshot and records `SNAPSHOT_NOT_PERSISTED` in the journal at WARNING. All of this
happens before the native cleanup gate, capability check and TUN; the session is then
created with `Androidcore.newSessionWithSSConfSnapshot`. The decision logic is
`resolveSessionSnapshot` (JVM tests: `SSConfSessionSnapshotTest`); the creation order is
`createCoreSession` (JVM tests: `CoreSessionOrderTest`). Because this step precedes the
capability check, a native load failure from Core validation or the fetch
(`UnsatisfiedLinkError`, `NoSuchMethodError`, or `NoClassDefFoundError` caused by a
failed native load) fails the attempt with `CORE_INCOMPATIBLE`, like `CoreCapabilities`.
A `LinkageError` from the store (discard or commit) propagates. A commit conflict seen
by an attempt that is already stale is cancellation, not `PROFILE_CHANGED`.
Instrumentation: `SSConfFetchTest` (real fetch failure paths, observer count back at 0,
state and token unchanged), `SnapshotStartTest` (the service's snapshot step with a
real store and native validation; a recovery start never fetches, a user connect
fetches once and replaces the snapshot) and `SSConfConnectFetchTest` (bind-only proof:
a user connect fetches once from a local endpoint inside the test through a socket the
lease protects and binds to the physical, non-VPN network, while no VPN exists, and
commits it; the lease is released before native work, session creation and the point
where the TUN would be established; a recovery start acquires no lease; a failed real
fetch falls back to a stored snapshot or fails with its code; a rejected body is
`SSCONF_SNAPSHOT_INVALID`). Core's own request accepts only HTTPS with a system-trusted
certificate, so that test swaps only the native request (`fetchSSConf`'s `newRequest`
seam) and keeps the production lease scope. It runs up to unstarted session creation;
no TUN is established and no Session is started.

`withSSConfNetwork(context, block)` requires existing VPN consent and binds the
private same-UID service without starting a VPN session. Acquisition waits at
most five seconds for binding. The non-suspending block runs on IO. Create the
native request there, register `lease.onInvalidated { request.cancel() }`, retain
that registration through blocking resolve, and close it in finally.

The immutable physical network and up to four IPv4 DNS servers belong to this
scope. FD protection and binding are separate fail-closed operations; binding
closes only a duplicate descriptor. Environmental invalidation invokes each
registration once, outside the lifetime lock. Cancellation signals invalidation
while the worker runs; binding and observer cleanup wait for the worker to exit.
Normal scope release is silent and disables the lease. Acquisition/platform
failures expose only `SSConf network unavailable`.

`generation` identifies a lease, not a durable network revision. Successful return
only establishes validity at the final in-scope check. The host owns its independent
pending-event/network revision authority and source/profile/draft/attempt guards
through atomic publication. Candidates must retain neither service nor lease.
No host gate, schema, native bridge, or persistence behavior is implemented here.

Instrumentation runs only on a CI emulator or a dedicated, isolated test device
with an idle VPN service and a physical network with IPv4 DNS. No test starts a
Session, establishes a TUN, opens consent UI, or loads a profile. Synthetic
onRevoke is a lifecycle signal, not proof of actual permission revocation.

Callback capabilities and link properties establish the initial snapshot with a
five-second cancellable wait. Subsequent callbacks update validity synchronously;
cancellation listeners run outside the network-state lock. Binding cleanup is
attempted even when bindService returns false or throws. The public scoped helper
and lease API are unchanged. Internal capture is now suspending; its narrow
before-snapshot hook permits cancellation after actual callback registration.

Deterministic JVM regression tests in SSConfNetworkSnapshotTest:
- initialDeliveryWaitsForBothFieldsWithoutInvalidation
- propertiesBeforeCapabilitiesAndDnsChangeUseCallbackValues
- replacementAndLossInvalidateButUnselectedChangesDoNot
- selectedNetworkLosingEligibilityInvalidatesImmediately
- captureRacingDnsCallbackNeverPublishesStaleValidLease
- cancellationWhileWaitingDoesNotProduceLease
- concurrentCallbackInvalidatesBeforeNotificationAndReleasesNetworkLock

SSConfLeaseStateTest additionally covers once-only and late notification,
independent registrations, deferred validity, silent close, and descriptor guards.

Instrumentation class: org.bimeron.app.SSConfNetworkTest. Each method is individually
selectable; the full class is intended to run together. All prerequisites assert;
there are no assume/skip branches.
- bindOnlyDestructionPreservesGlobalState: framework unbind/destruction and system
  SERVICE_INTERFACE fallback; non-default synthetic connected state and protection
  plus the attempt token must survive. Globals are restored in finally. The
  destruction helper reads the flag on main after lifecycle cleanup completes.
  No consent requirement.
- simulatedStartedJobStopsOnOffMainRevokeWhileBound: synthetic started/job fields
  only; invokes revoke on Dispatchers.Default and verifies Stop cancels the job
  while a resolver binding retains the service. Checks the volatile field contract
  deterministically; restores state, protection, events and recovery preference.
  Never calls onStartCommand, Session.start or establish; no consent requirement.
- simulatedStartedActiveStateSurvivesResolverBindRelease: prepared fixture; sets
  only in-memory started/job fields, releases an actual resolver lease, checks
  the simulated job and global state survive, restores fields before destruction.
- bindingAcquisitionCancellationUnbindsBeforeCapture: prepared fixture; real bind,
  delayed connection delivery, cancellation, unbind and framework destruction.
- failedBindingAttemptsStillUnbind: prepared fixture; false and SecurityException
  binding attempts each perform guarded unbind and expose the fixed safe error.
- acquisitionCancellationClosesObserverAndBinding: prepared fixture; pauses after
  real observer registration, cancels, verifies observer removal, unbind and
  framework destruction without entering the worker.
- unpreparedAccessorFailsWithoutRunningBlock: denied fixture; verifies safe failure.
- cancellationSignalsBeforeWorkerExitAndWaitsForCleanup: prepared fixture; blocks
  worker, cancels, verifies prompt notification while actual binding, service lease
  registration and observer remain; releases worker and verifies all cleanup.
- preparedSocketsKeepOriginalOwnershipAndRevokeInvalidates: prepared fixture;
  original descriptor ownership, separate protect/bind guards, synthetic revoke.

Estimated class runtime: 30–90 seconds including app-op fixture commands, excluding
installation.

The test-only consent fixture issues these bounded (five-second) UiAutomation
shell commands for the target package only:
- cmd appops get --user current org.bimeron.app ACTIVATE_VPN
- cmd appops set --user current org.bimeron.app ACTIVATE_VPN allow
  (prepared methods), or deny (unprepared method)
- cmd appops set --user current org.bimeron.app ACTIVATE_VPN PRIOR_MODE
  (finally, even on assertion failure)
It re-reads the mode after each set and asserts the actual VpnService.prepare
result. Explicit allow/ignore/deny/default/foreground modes are restored exactly.
UID overrides, unknown modes, unexpected lines, or ambiguous output fail before
mutation. The parser accepts only a single explicit ACTIVATE_VPN entry (with
recognized platform timing history), or exactly "No operations." followed by
"Default mode: MODE". An absent entry without a reported default fails safely;
no default is guessed. SSConfConsentModeTest adds four pure parser tests in the
test APK, separate from the nine SSConfNetworkTest device methods.

Platform limitation: "No operations." plus a reported "Default mode: MODE" is
tracked separately from an explicit "ACTIVATE_VPN: default" entry. For an initially
absent entry, the fixture restores the reported platform default mode in finally
and verifies that effective mode, whether the platform reports absence or an
entry afterward. For an initially explicit entry, it verifies the exact explicit
mode, including "default". Restoration is attempted after set/read/prepare/block
failures too; a restoration failure is suppressed on the original failure, or
fails the test directly if no earlier failure exists.

Effective-default restoration with history retained is acceptable only on isolated
test devices. Consent checks can record access/rejection timestamps, durations,
and an ACTIVATE_VPN entry that was initially absent. Restoring the mode does not
restore that history or guarantee entry absence; this history limit also applies
to initially explicit modes. No package-wide reset or other app-op modification
is used to erase it. No claim is made to restore platform VPN internal preparation
state; the fixture restores the app-op and checks actual VpnService.prepare state
before the test body, without starting a VPN.

Supported validation targets are the API 34 CI emulator and an API 35 test device. API 26 app-op output
that reports absence without the default is unsupported and fails before mutation.
The started flag is volatile because Android may deliver onRevoke off main.
Synthetic lifecycle tests do not demonstrate actual VPN revocation or native cleanup.

