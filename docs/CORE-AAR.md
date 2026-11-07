# Embedded Android core

Source: https://github.com/bimeron/bimeron-core
Revision: `61fa8a089a1b1b47df4b996d164b50cd93d0e423` (core PR #100, ssconf
snapshot API, on top of the #97 auth and #98 ssconf bridge changes).

SHA-256 of `app/libs/bimeron-core.aar`:

```text
d95e9aa7631d31985e76534bf94aa364e5554257cdc582250ca703ae99372535
```

Build requirements: Go 1.26.9, Android Studio OpenJDK 21.0.3 (runtime/VM build `21.0.3+-79915917-b509.11`), Android SDK 34, NDK 27.1.12297006, and gomobile `v0.0.0-20261011034528-197770444867`.

## Rebuilding

Archive the pinned revision into a fresh, empty directory (do not overlay an
existing checkout), then run the rebuild helper from this repository. It builds
the AAR twice and fails unless both builds are byte-identical:

```sh
REV=61fa8a089a1b1b47df4b996d164b50cd93d0e423
CORE_SOURCE=/path/to/bimeron-core            # any clone containing $REV
WORK=$(mktemp -d)
git -C "$CORE_SOURCE" cat-file -e "$REV^{commit}"
mkdir "$WORK/source"
git -C "$CORE_SOURCE" archive "$REV" | tar -x -C "$WORK/source"
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.1.12297006"
export PATH="/path/to/go1.26.9/bin:$HOME/go/bin:$JAVA_HOME/bin:$PATH"
python3 scripts/rebuild_core_aar.py "$WORK/source" "$WORK/build"
shasum -a 256 "$WORK"/build-{1,2}/bimeron-core.aar
```

If no local clone is available:

```sh
git clone --no-checkout https://github.com/bimeron/bimeron-core.git "$WORK/repo"
git -C "$WORK/repo" fetch origin "$REV"
CORE_SOURCE="$WORK/repo"
```

The helper sets `GOTOOLCHAIN=local`, `GOMAXPROCS=2`, `GOFLAGS='-trimpath -p=2'`
and `CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'`,
with no version override. The core builds all four ABIs with `-androidapi=23`.
Absolute source and NDK paths remain in the native binaries, so the recorded
hash is reproducible only with the same build layout; cross-host
reproducibility is not established.

## Contents and API

The artifact includes armeabi-v7a, arm64-v8a, x86 and x86_64. The application uses `Androidcore.newSession(byte[], SocketProtector)`, `Session.setNetwork`, `start`, `status`, packet read/write, and `stop`. `newSession` validates without setting a network or starting transport resolution. Android requires `Mode=vpn` and rejects core `KillSwitch=true`.

The accompanying `app/libs/LICENSE` and `app/libs/THIRD_PARTY_NOTICES.txt` are
copied from this revision. Update the revision, artifact, notices and the CI
checksum in `scripts/verify_core_aar.py` together.

Payload SHA-256 values:

```text
e906e529092a8cff3d71bb5b0c5c19e8cd78e50935981c479ae4ef790f324a8a  classes.jar
e2860cf61715e2b44aa19530ee2858112616a2c433e781e797df865c14cee078  jni/armeabi-v7a/libgojni.so
7fa514f3618a2c2d879841916716dc27099f73e750bcfbb7146cc07c1fe3eb0c  jni/arm64-v8a/libgojni.so
61c4514b780c8a79fa452790f4bddaadb732b356f8d983804e6733649539832f  jni/x86/libgojni.so
3684347ca16190456fe832b18d4ec7014a667cab9c4f719f78d92bd1962549cd  jni/x86_64/libgojni.so
```

All four ABIs pass `scripts/native_alignment.py`: PT_LOAD 16 KiB
alignment/congruence and the GNU_RELRO trailing writable-page check (each RELRO
segment ends on a 16 KiB boundary). The debug APK's four `lib/*/libgojni.so`
entries equal the AAR payloads and pass `native_alignment.py` and build-tools 35
`zipalign -c -P 16 4`.

Compared with the previous artifact (core `bd57093`), the public API
(`javap -public -s`) is purely additive: every previous public declaration and
descriptor is unchanged, and public types grow from 12 to 13. Additions:

```text
Androidcore.newSSConfSnapshot(String, byte[]): SSConfSnapshot throws Exception
  (Ljava/lang/String;[B)Lorg/bimeron/core/androidcore/SSConfSnapshot;
Androidcore.newSessionWithSSConfSnapshot(byte[], SSConfSnapshot, byte[], byte[], SocketProtector): Session throws Exception
  ([BLorg/bimeron/core/androidcore/SSConfSnapshot;[B[BLorg/bimeron/core/androidcore/SocketProtector;)Lorg/bimeron/core/androidcore/Session;
Session(byte[], SSConfSnapshot, byte[], byte[], SocketProtector) constructor
final class SSConfSnapshot implements go.Seq$Proxy, constructor (String, byte[])
```

The ssconf snapshot entry points are packaged but not yet called by the
application. The earlier auth and ssconf bindings remain present with unchanged
descriptors: `Session.pendingAuth/replyAuth/cancelAuth`, `SSConfSocketProtector`
extending `SocketProtector` with `protect/bind`, `Androidcore.newSSConfRequest`,
and `SSConfRequest.resolve/cancel` plus `SSConfResult.content/metadata`.

## Open verification

Installed-device qualification of this AAR (API 26, a normal-page API 35 image
and a 16 KiB-page device) is still pending.
