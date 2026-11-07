# Embedded Android core

Source: https://github.com/bimeron/bimeron-core
Revision: core master `8fad07ce3d02c00d265897c894f716175b369da4` (the merge of core
PR #104).

Changes relative to the previously pinned revision `61fa8a0` (core PR #100), by core
pull request:

- #102: macOS stop cancellation during network setup. macOS-only code, not compiled
  for Android.
- #103: native IKEv2 integration. Android still rejects IKEv2; the capabilities
  JSON `ikev2` object gains `auth_*` fields, and the app reads only `supported`.
- #106: structured configuration and connection errors with a shared catalog. Adds
  `Androidcore.errorCatalog()`. Every `Status.LastError` is a catalog code, and an
  unclassified failure is `connection_failed`.
- #104: opt-in SSH accept-new host keys (ADR 0035) and the Android profile-files-only
  guard (ADR 0036).

SHA-256 of `app/libs/bimeron-core.aar`:

```text
3b30cbc58c6d73af48c4e80daddb791a39825cf185c4bb6a11b3052454da2aa9
```

Build requirements: Go 1.26.9, Android Studio OpenJDK 21.0.3 (runtime/VM build `21.0.3+-79915917-b509.11`), Android SDK 34, NDK 27.1.12297006, and gomobile `v0.0.0-20261011034528-197770444867`.

## Rebuilding

**The recorded hash is reproducible only from one absolute source path.** The helper
already passes `-trimpath`, but the absolute source path (and the NDK path) still end
up in the native binaries, so the archive must be extracted to exactly
`/Users/wrk/dev/solarcadence/bimeron/bimeron-artifacts/core-aar-master-8fad07c/source`
with NDK 27.1.12297006. Any other location builds a working AAR with a different
hash. Removing the paths is a separate follow-up.

Archive the pinned revision into that empty directory (do not overlay an existing
checkout), then run the rebuild helper from this repository. It builds the AAR twice
and fails unless both builds are byte-identical:

```sh
REV=8fad07ce3d02c00d265897c894f716175b369da4
CORE_SOURCE=/path/to/bimeron-core            # any clone containing $REV
BASE=/Users/wrk/dev/solarcadence/bimeron/bimeron-artifacts/core-aar-master-8fad07c
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

The helper sets `GOTOOLCHAIN=local`, `GOMAXPROCS=2`, `GOFLAGS='-trimpath -p=2'`
and `CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'`,
with no version override. The core builds all four ABIs with `-androidapi=23`.
As stated above, absolute source and NDK paths remain in the native binaries
despite `-trimpath`; cross-host reproducibility is not established.

## Contents and API

The artifact includes armeabi-v7a, arm64-v8a, x86 and x86_64. The application uses `Androidcore.newSession(byte[], SocketProtector)`, `Session.setNetwork`, `start`, `status`, packet read/write, and `stop`. `newSession` validates without setting a network or starting transport resolution. Android requires `Mode=vpn` and rejects core `KillSwitch=true`.

The accompanying `app/libs/LICENSE` and `app/libs/THIRD_PARTY_NOTICES.txt` are
copied from this revision. Update the revision, artifact, notices and the CI
checksum in `scripts/verify_core_aar.py` together.

Payload SHA-256 values:

```text
cf01e16e0aaae15c7036d46aff0f360f623ab0543e4c4e47389eb2cf78614395  classes.jar
487bea8fb7c5c56cd8a31cf69b4b1803bf56f89b07c3dfcedd943f4470d0bcaa  jni/armeabi-v7a/libgojni.so
b40be2d41bb4fe9d646183964237d828b914ef812122505f798c4b7fa588d534  jni/arm64-v8a/libgojni.so
7bfc0b71d6cf28e3965656d4b54c049f5670a0015053e19f286848d52df4f041  jni/x86/libgojni.so
ba4e9293173fc68ef61c1e112e8008357ad26ea541f7221d9f79a3e37f0130b2  jni/x86_64/libgojni.so
```

All four ABIs pass `scripts/native_alignment.py`: PT_LOAD 16 KiB
alignment/congruence and the GNU_RELRO trailing writable-page check (each RELRO
segment ends on a 16 KiB boundary). The debug APK's four `lib/*/libgojni.so`
entries equal the AAR payloads and pass `native_alignment.py` and build-tools 35
`zipalign -c -P 16 4`.

Compared with the previous artifact (core `61fa8a0`, AAR SHA-256
`d95e9aa7631d31985e76534bf94aa364e5554257cdc582250ca703ae99372535`), the public
API (`javap -public -s`) is purely additive: every previous public declaration and
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
