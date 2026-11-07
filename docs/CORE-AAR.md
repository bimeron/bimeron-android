# Embedded Android core

Source: https://github.com/bimeron/bimeron-core
Revision: core master `774dec88f02b7a7b3d41998d829f0fe2ce0a52fd` (the merge of core
PR #109).

Changes relative to the previously pinned revision `8fad07c` (core PR #104), by core
pull request:

- #107: build only. `scripts/build-android.sh` passes
  `-ffile-prefix-map=$ANDROID_NDK_HOME=/android-ndk` to cgo, so the NDK install path
  is no longer embedded in the native debug info. No Go source or API change.
- #108: test only. Adds `androidcore/validation_leak_test.go`, which is not linked
  into the AAR; builds of `aefaff4` (#107) and `d9fd7f4` (#108) through the same
  canonical path are byte-identical (AAR SHA-256
  `e14a164977e5ef94566ab9671d6d08cb21282cf5a24271ad987f165a6cc90607`).
- #109: caps ssconf HTTP response headers at 64 KiB (Go's default is 10 MiB) in
  `core/ssconf.go`, `core/bootstrap.go` and `accesskey.NewResolver`. No Android API
  change (`classes.jar` is byte-identical) and no new error code: an oversized header
  block fails like any other access-key fetch error, as the existing retryable
  `access_key_resolve_failed`.

SHA-256 of `app/libs/bimeron-core.aar`:

```text
93eb73a254dab98751623b40860a18549827984fa4615f3d7116b9751962615f
```

Build requirements: Go 1.26.9, Android Studio OpenJDK 21.0.3 (runtime/VM build `21.0.3+-79915917-b509.11`), Android SDK 34, NDK 27.1.12297006, and gomobile `v0.0.0-20261011034528-197770444867`.

## Rebuilding

The archive can be extracted into any directory, including one inside a tagged git
checkout, under these conditions: the build runs through the rebuild helper (which
disables version detection and pins or strips the variables listed below), with the
toolchain versions above and the NDK at a path of your choice.

gomobile writes absolute `replace`
directives for the core module and its three `third_party` forks into its generated
`go.mod`, and Go records them in the `libgojni.so` build info; `-trimpath` does not
remove them. The rebuild helper therefore always builds through the fixed symlink
`/var/tmp/bimeron-core-src` (pointing at the extracted source), with both the working
directory and `PWD` set to it; without `PWD`, `sh` resolves the symlink and the
physical path is recorded again.

**`/var/tmp/bimeron-core-src` is part of the embedded build layout.** It appears in
every `libgojni.so` (the four `replace` paths), so the recorded hash is reproducible
only through exactly this path. It must not change; a different path builds a working
AAR with a different hash.

The helper creates the symlink and removes it in a `finally` block, also on SIGINT,
SIGTERM and SIGHUP, but only if it is still the symlink it created (same device and
inode, not just the same target). If `/var/tmp/bimeron-core-src` already exists in any
form (a real directory, or a symlink left by a crashed or still-running rebuild), the
helper stops without touching it. Check that no rebuild is running, then remove a
stale symlink with `rm /var/tmp/bimeron-core-src`.

Archive the pinned revision into an empty directory (do not overlay an existing
checkout), then run the rebuild helper from this repository. It builds the AAR twice
and fails unless both builds are byte-identical:

```sh
REV=774dec88f02b7a7b3d41998d829f0fe2ce0a52fd
CORE_SOURCE=/path/to/bimeron-core            # any clone containing $REV
BASE=/path/to/empty/work/dir                 # any location
git -C "$CORE_SOURCE" cat-file -e "$REV^{commit}"
mkdir "$BASE" "$BASE/source"                 # both must not exist yet
git -C "$CORE_SOURCE" archive "$REV" | tar -x -C "$BASE/source"
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.1.12297006"
export PATH="/path/to/go1.26.9/bin:$HOME/go/bin:$JAVA_HOME/bin:$PATH"
python3 scripts/rebuild_core_aar.py "$BASE/source" "$BASE/build"
shasum -a 256 "$BASE"/build-{1,2}/bimeron-core.aar
```

If no local clone is available:

```sh
WORK=$(mktemp -d)
git clone --no-checkout https://github.com/bimeron/bimeron-core.git "$WORK/repo"
git -C "$WORK/repo" fetch origin "$REV"
CORE_SOURCE="$WORK/repo"
```

Build environment. The helper starts from the caller's environment and:

- removes every `BIMERON_*`, `CGO_*` and `GIT_*` variable, plus `GOFLAGS`,
  `GOEXPERIMENT`, `GOOS`, `GOARCH`, `GOAMD64`, `GO386`, `GOARM`, `GOARM64`,
  `GOFIPS140`, `GOENV`, `GOWORK`, `CC`, `CXX` and `OLDPWD`;
- pins `GOTOOLCHAIN=local`, `GOMAXPROCS=2`, `GOFLAGS='-trimpath -p=2'`,
  `CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'`,
  `GOENV=off` (ignore `go env -w` settings) and `GOWORK=off`;
- disables version detection with `GIT_DIR=/nonexistent/bimeron-no-git`. The core's
  `build-android.sh` runs `git describe --tags` when `BIMERON_VERSION` is unset, and
  git discovers repositories from the physical path, not the symlink, so an archive
  extracted inside a tagged checkout would otherwise stamp `-X bimeron/core.Version`
  and change the hash. With `BIMERON_VERSION` stripped and `git describe` failing, no
  version is stamped. As a side effect, Go cannot fetch modules directly over git
  during the build; use the default module proxy or a warm module cache;
- passes `ANDROID_NDK_HOME` on as its real path without a trailing slash (and fails
  if it is not a directory), so the core's `-ffile-prefix-map=$ANDROID_NDK_HOME=/android-ndk`
  always matches the paths the compiler sees. A symlinked or slash-terminated NDK path
  therefore builds the same AAR.

Other variables (`PATH`, `HOME`, `JAVA_HOME`, `ANDROID_HOME`, `GOPATH`, `GOCACHE`,
`GOPROXY`, `TMPDIR` and so on) pass through unchanged. The core builds all four ABIs
with `-androidapi=23`. `scripts/test_rebuild_core_aar.py` checks the symlink
handling, signal cleanup, the working-directory/`PWD` wiring, the environment
filtering, the NDK path normalisation and, in a scratch git repository tagged
`v9.9.9`, that the version detection finds no tag, all without a real build.

Location independence was checked on one macOS host with the hardened helper. Archives
of `774dec8` were extracted to `bimeron-artifacts/aar-canonical/r774/plain/deeper/src-p/source`
(clean environment) and to `bimeron-artifacts/aar-canonical/r774/tagged-checkout/core/source`,
inside a scratch git checkout tagged `v9.9.9` where `git describe` from the source finds
`v9.9.9`. The second run's caller environment was polluted with `BIMERON_VERSION=9.9.9`,
`CGO_CFLAGS=-O0`, `CGO_LDFLAGS=-s`, `GOFLAGS=-mod=mod`, `GOAMD64=v3`, `GO386=softfloat`,
`GOEXPERIMENT` and a slash-terminated `ANDROID_NDK_HOME`. All four helper builds (two
per directory, each directory with its own empty Go build cache) produced the hash
above, with identical `classes.jar` and `libgojni.so` payloads. Earlier, three
directories of `aefaff4`/`d9fd7f4` had likewise produced one identical hash among
themselves. `strings` on each
`libgojni.so` finds no `/Users/` path and no NDK install path; the only source path
is `/var/tmp/bimeron-core-src` (the core module and `third_party/apernet-quic-go`,
`third_party/hysteria-core` and `third_party/quic-go`), and NDK headers appear as
`/android-ndk/toolchains/llvm/prebuilt/darwin-x86_64/...`. That NDK prebuilt
directory name is host-specific, so cross-host reproducibility (for example a Linux
build) is still not established.

## Contents and API

The artifact includes armeabi-v7a, arm64-v8a, x86 and x86_64. The application uses `Androidcore.newSession(byte[], SocketProtector)`, `Session.setNetwork`, `start`, `status`, packet read/write, and `stop`. `newSession` validates without setting a network or starting transport resolution. Android requires `Mode=vpn` and rejects core `KillSwitch=true`.

The accompanying `app/libs/LICENSE` and `app/libs/THIRD_PARTY_NOTICES.txt` are
copied from this revision. Update the revision, artifact, notices and the CI
checksum in `scripts/verify_core_aar.py` together.

Payload SHA-256 values:

```text
cf01e16e0aaae15c7036d46aff0f360f623ab0543e4c4e47389eb2cf78614395  classes.jar
aa650703d97f85ca762a99081481cd305ede7015397b084ce2ce36bf155359e4  jni/armeabi-v7a/libgojni.so
56a63b244429d7d8de56e03f4d5d78f30888ca04fc880608166d6c74ec0055c4  jni/arm64-v8a/libgojni.so
3253ef51ce69152ff4fb629a2fe246a43c7a257d3cf45e3db2fe9faee56c9095  jni/x86/libgojni.so
a8190816b13e1c624334ff44111165a1ac9001a0391a06fb095a4d67c850512e  jni/x86_64/libgojni.so
```

All four ABIs pass `scripts/native_alignment.py`: PT_LOAD 16 KiB
alignment/congruence and the GNU_RELRO trailing writable-page check (each RELRO
segment ends on a 16 KiB boundary). The debug APK's four `lib/*/libgojni.so`
entries equal the AAR payloads and pass `native_alignment.py` and build-tools 35
`zipalign -c -P 16 4`.

Compared with the previous artifact (core `8fad07c`, AAR SHA-256
`3b30cbc58c6d73af48c4e80daddb791a39825cf185c4bb6a11b3052454da2aa9`), `classes.jar`
is byte-identical, so the public API (`javap -public -s`) is unchanged. The four
`libgojni.so` files differ because of the #109 code change and because the embedded
source and NDK paths are now shorter.

The API introduced at `8fad07c`, compared with core `61fa8a0` (AAR SHA-256
`d95e9aa7631d31985e76534bf94aa364e5554257cdc582250ca703ae99372535`), is purely
additive: every previous public declaration and
descriptor is unchanged, public types stay at 13, and there are two additions:

```text
Androidcore.errorCatalog(): String
  ()Ljava/lang/String;
Session.acceptNewHostKeys(byte[]): void throws Exception
  ([B)V
```

`errorCatalog` returns Core's code catalog (#106); the app does not call it in
production, and an instrumentation test checks that every SSH code in it is mapped.
`acceptNewHostKeys` opts a session in to SSH accept-new host keys (core ADR 0035,
`docs/android-ssh-auth.md`). Independently of the opt-in, every androidcore session
now uses only the SSH files its profile names (core ADR 0036): no default identity
scan, no `~/.ssh/known_hosts` fallback, no `~` paths and no SSH agent; an SSH profile
with neither an imported known_hosts nor the opt-in fails with
`ssh_known_hosts_missing`. The application calls `acceptNewHostKeys` once, before
`start`, for every SSH-capable profile; see the SSH host keys section of
[CONTRACT.md](CONTRACT.md). The earlier ssconf snapshot, auth and ssconf bridge
bindings remain present with unchanged descriptors:
`Androidcore.newSSConfSnapshot`, `Androidcore.newSessionWithSSConfSnapshot`,
`Session.pendingAuth/replyAuth/cancelAuth`, `SSConfSocketProtector` extending
`SocketProtector` with `protect/bind`, `Androidcore.newSSConfRequest`, and
`SSConfRequest.resolve/cancel` plus `SSConfResult.content/metadata`.
`app/libs/LICENSE` and `app/libs/THIRD_PARTY_NOTICES.txt` are byte-identical to
the files produced by this build and did not change.

## Open verification

Installed-device qualification of this AAR (API 26, a normal-page API 35 image
and a 16 KiB-page device) is still pending.
