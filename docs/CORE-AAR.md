# Embedded Android core

Source: https://github.com/bimeron/bimeron-core
Revision: `dbbae2a761f26b997dc39a8d3b34fdeada08a37a` (core branch
`aar/tofu-on-61fa8a0`). This build is `61fa8a089a1b1b47df4b996d164b50cd93d0e423`
(core PR #100, SSConf snapshot API) plus only the SSH accept-new host-key commits
of core PR #104, cherry-picked unchanged as `8f98756` (feat) and `dbbae2a` (docs,
ADR 0035). `git diff 61fa8a0 dbbae2a` equals the PR #104 diff
(`git diff 03494c7 e90b4a0`); nothing else from core master is included.

SHA-256 of `app/libs/bimeron-core.aar`:

```text
7de886e5119dd867b98ec596ae7f19d347dff0138807662e91d91a9d89bdcc81
```

Build requirements: Go 1.26.9, Android Studio OpenJDK 21.0.3 (runtime/VM build `21.0.3+-79915917-b509.11`), Android SDK 34, NDK 27.1.12297006, and gomobile `v0.0.0-20261011034528-197770444867`.

## Rebuilding

Archive the pinned revision into a fresh, empty directory (do not overlay an
existing checkout), then run the rebuild helper from this repository. It builds
the AAR twice and fails unless both builds are byte-identical:

```sh
REV=dbbae2a761f26b997dc39a8d3b34fdeada08a37a
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
edefb1691346db0e4a6818457a27eff61551424372a4aa84cf07242dc313c5f7  classes.jar
bd1170bd9e99736ba4b3b1eb5bcfce9de50ed439ad59021c70a4722b29f2f407  jni/armeabi-v7a/libgojni.so
676bb8f1a3bedf472476f3ed72c5dcc241db058bf22c61a28a48380cb694678f  jni/arm64-v8a/libgojni.so
0aa1d9676cfc1d199f9e26de07c856671eabda82b229393a6434954d55cd0934  jni/x86/libgojni.so
f288e1bbe25f7a30cfbd642926a66d962ad488c60ad535b78891699a53135de5  jni/x86_64/libgojni.so
```

All four ABIs pass `scripts/native_alignment.py`: PT_LOAD 16 KiB
alignment/congruence and the GNU_RELRO trailing writable-page check (each RELRO
segment ends on a 16 KiB boundary). The debug APK's four `lib/*/libgojni.so`
entries equal the AAR payloads and pass `native_alignment.py` and build-tools 35
`zipalign -c -P 16 4`.

Compared with the previous artifact (core `61fa8a0`, AAR SHA-256
`d95e9aa7631d31985e76534bf94aa364e5554257cdc582250ca703ae99372535`), the public
API (`javap -public -s`) is purely additive: every previous public declaration and
descriptor is unchanged, public types stay at 13, and there is one addition:

```text
Session.acceptNewHostKeys(byte[]): void throws Exception
  ([B)V
```

`acceptNewHostKeys` opts a session in to SSH accept-new host keys (core ADR 0035,
`docs/android-ssh-auth.md`). The application calls it once, before `start`, for
every SSH-capable profile; see the SSH host keys section of
[CONTRACT.md](CONTRACT.md). The earlier SSConf snapshot, auth and SSConf bridge
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
