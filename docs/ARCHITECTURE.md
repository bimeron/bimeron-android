# Architecture notes

## AppModel operation coordination

`AppModel` delegates profile workflows to the internal `AppModelOperations`. The
Android wrapper supplies `viewModelScope` (Main dispatcher), `ProfileStore`,
preference persistence, the runtime `StateFlow` and service/log callbacks. The
coordinator serializes refresh, selection, save, delete and switch-confirmation
persistence through a `Mutex`. Refresh/save/delete busy counters include queued
jobs and are released on completion, including cancellation before execution.
Selection and confirmation are not represented by those busy/loading counters.

Selection is independent of the active VPN profile:

- While disconnected, requesting the currently selected profile still queues a
  selection, overriding older queued or in-flight selection writes.
- While connected, tapping the selected profile does not offer a switch.
- Editing active profile A while B is selected keeps B selected and can offer to
  apply A. A pending apply records the active profile identity and
  `connectedAtElapsed`, and re-checks the current runtime synchronously before
  applying. Traffic counters do not identify a session.
- Switch confirmation checks the intent generation and runtime session around
  selection persistence; an invalidated confirmation restores the previous
  selection before releasing the lock.
- A deleted profile ID cannot be saved again. Initial selection loading retries
  after a failed refresh. Editor completion callbacks require the captured draft
  object identity.

Limits: the model lock does not cover independent service or UI actions, and
runtime transitions can happen outside it. Production confinement relies on the
Main dispatcher. Cancellation propagates without success/error callbacks, but a
disk mutation may complete before cancellation is delivered; save and delete do
not promise rollback.

`AppModelOperationsTest` covers this ordering deterministically with
`CompletableDeferred` barriers and no sleeps.

## GeoData storage

`GeoDataStore` is a blocking JVM component built on Java NIO (API 26+). It takes
the app's private `filesDir` and a mandatory trusted `GeoDataValidator`; fixed
per-kind filenames live under `filesDir/geodata`. Call it on a worker thread.

- All instances serialize operations, including validation and publication,
  through a process-wide monitor. Validators must not mutate files or re-enter
  the store. Other processes and external writers are unsupported.
- The caller owns the `InputStream` on success and failure.
- Streaming enforces a configurable positive limit capped at 64 MiB without
  trusting `available()` or a declared size; empty input fails.
- A same-directory temporary file is flushed with `fd.sync()`, closed, fully
  validated and catalogued, then published with a single `ATOMIC_MOVE`. There is
  no non-atomic fallback. Any failure before publication keeps the previous bytes;
  the temporary file is always deleted.
- Catalog reads revalidate persisted files; returned lists are immutable copies.
- Durability is limited: the directory is not fsynced, so power loss may lose a
  recently acknowledged rename, and process death can leave temporary files.

The bundled core exposes no GeoData API, so `GeoDataCoreAdapter` always throws
`UnsupportedOperationException`. A future integration must replace it with the
core's v2ray-protobuf decoder and fully validate malformed entries, duplicates and
resource limits. `GeoDataStoreTest` uses a synthetic validator to exercise store
transactions only; it does not establish protobuf compatibility.
