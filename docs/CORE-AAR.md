# Embedded Android core

Source: https://github.com/bimeron/bimeron-core
Revision: commit `a93594339eada230c0c63016788697bbcdbb82bb`, reachable through the
annotated tag `android-aar/tofu-a935943` (preferred; branches may be cleaned up) and
the branch `aar/tofu-on-61fa8a0`. This build is `61fa8a089a1b1b47df4b996d164b50cd93d0e423`
(core PR #100, ssconf snapshot API) plus only the four SSH host-key commits of
core PR #104, cherry-picked unchanged:

```text
8f98756 feat(ssh): add opt-in accept-new host-key offers
dbbae2a docs(ssh): document accept-new host-key offers and ADR 0035
06e259d fix(ssh): harden accept-new verification; Android uses only profile files
a935943 docs(ssh): document host-key review fixes and ADR 0036
```

`git diff 61fa8a0 a935943` equals the PR #104 diff (`git diff 03494c7 2a87ffa`);
nothing else from core master is included. Core PR #104 is still under review; if
it changes, this AAR is rebuilt and the pins below change with it.

SHA-256 of `app/libs/bimeron-core.aar`:

```text
f8fa974b5c95240f911269586dd8803b2344f6e2fa96da85a002c7950f186454
```

Build requirements: Go 1.26.9, Android Studio OpenJDK 21.0.3 (runtime/VM build `21.0.3+-79915917-b509.11`), Android SDK 34, NDK 27.1.12297006, and gomobile `v0.0.0-20261011034528-197770444867`.

## Rebuilding

Archive the pinned revision into a fresh, empty directory (do not overlay an
existing checkout), then run the rebuild helper from this repository. It builds
the AAR twice and fails unless both builds are byte-identical:

```sh
REV=a93594339eada230c0c63016788697bbcdbb82bb
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
git -C "$WORK/repo" fetch origin refs/tags/android-aar/tofu-a935943
git -C "$WORK/repo" cat-file -e "$REV^{commit}"
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
f4246a884dc17e27234bc4679b04b903c8b6d1a0e9ffc3c54cf925efde297ed1  classes.jar
f95cab7a1aaecb14f793ee19a7cd8b18edfdc9bf1bf8e37f0fa75bbbfb25cc17  jni/armeabi-v7a/libgojni.so
130280a575a373e44f541f6a3342977760d6cf8c1b416573a59d70bbbef4bc98  jni/arm64-v8a/libgojni.so
55bc0f79a034d03805b0ec9ac429cc593ac7544f1f199c0c706afe219d3fe776  jni/x86/libgojni.so
1ed11aa9d1083eb494ca247d3dacd26ed0f9cf05f784fbb2cd70364e991f1ca5  jni/x86_64/libgojni.so
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
`docs/android-ssh-auth.md`). Independently of the opt-in, every androidcore session
now uses only the SSH files its profile names (core ADR 0036): no default identity
scan, no `~/.ssh/known_hosts` fallback, no `~` paths and no SSH agent; an SSH profile
with neither an imported known_hosts nor the opt-in fails with
`ssh_known_hosts_missing`. The application calls it once, before `start`, for
every SSH-capable profile; see the SSH host keys section of
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
