# Releasing the Android app

This document describes the Bimeron Android release build: R8, signing,
versioning, upgrades over an installed version, 16 KiB checks and the publishing
procedure. Related documents: [core AAR](CORE-AAR.md), [CI](CI.md), [QA](QA.md).

## Build configuration

`app/build.gradle.kts`, build type `release`:

- `isMinifyEnabled = true`, `isShrinkResources = true`, rules
  `proguard-android-optimize.txt` + [`app/proguard-rules.pro`](../app/proguard-rules.pro).
- `packaging.jniLibs.useLegacyPackaging = false`: `libgojni.so` is stored in the
  APK uncompressed and 16 KiB-aligned (AGP 8.7.3), and the system loads it
  directly from the APK.
- Signing comes from Gradle properties or environment variables (see below).
  Without them the release build is signed with the local debug key and prints
  `WARNING: release build is signed with the DEBUG key`.
- `applicationId = org.bimeron.app` is the same for debug and release; there are
  no suffixes.

### R8 rules for gomobile

`bimeron-core.aar` is a gomobile binding. `libgojni.so` looks up `go.*` and
`org.bimeron.core.*` classes and methods by JNI name and calls back into Java
objects (for example `SocketProtector.protect`). R8 cannot see these accesses, so:

- `-keep class go.** { *; }` and `-keep class org.bimeron.core.** { *; }`: the
  same rules as the AAR's own `proguard.txt`, duplicated so that a rebuilt AAR
  without consumer rules cannot break the release;
- `-keepclassmembers class * implements org.bimeron.core.** { *; }`: app
  implementations of core interfaces keep their method names;
- `native` methods are not renamed;
- `SourceFile,LineNumberTable` are kept; `mapping.txt` is archived privately for
  every release (never published).

The app uses no reflection (`Class.forName`, `simpleName` and so on), and stored
data is addressed by string keys and paths rather than class names, so
obfuscation does not affect it.

## Signing

| Name | Value |
| --- | --- |
| `BIMERON_KEYSTORE_FILE` | **absolute** path to the keystore (PKCS12/JKS) outside the repository; `~` is not expanded (in `gradle.properties` or in quotes); a relative path is resolved from `app/` |
| `BIMERON_KEYSTORE_PASSWORD` | keystore password |
| `BIMERON_KEY_ALIAS` | key alias |
| `BIMERON_KEY_PASSWORD` | key password |
| `BIMERON_REQUIRE_RELEASE_SIGNING` | `true`/`1` (case-insensitive): configuration fails if no key is set (required for releases); `false`/`0` or empty: the debug fallback is allowed; any other value is an error |

Each name is read first as a Gradle property (`-P…`, `~/.gradle/gradle.properties`,
`ORG_GRADLE_PROJECT_…`), then as an environment variable. If only some of the
four values are set, or the file does not exist, configuration fails.

Rules:

- keystores, passwords and `keystore.properties` are never committed
  (`.gitignore` excludes `*.jks`, `*.keystore`, `*.p12`, `keystore.properties`);
- do not pass passwords with `-P` on the command line on shared machines (they
  are visible in `ps`); use environment variables or `~/.gradle/gradle.properties`
  with mode `600`;
- never publish a debug-signed APK: it cannot update an installation signed with
  the release key.

Creating the key (once; keep it offline with a backup; losing it makes in-place
updates impossible):

```sh
keytool -genkeypair -keystore "$HOME/secure/bimeron-release.jks" -storetype PKCS12 \
  -alias bimeron -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Bimeron, O=Bimeron"
```

## Versioning

`versionName` lives in `gradle.properties` (`bimeron.versionName=MAJOR.MINOR.PATCH`,
`MAJOR` ≤ 999, `MINOR`/`PATCH` ≤ 99). `versionCode` is computed as:

```text
versionCode = MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + BUILD
```

`BUILD` (0–99, variable/property `BIMERON_VERSION_BUILD`, default 0) is only for
republishing the same `versionName` (for example a re-release with corrected
signing or metadata). Examples: `0.1.0` → `10000`, `1.2.3` → `1020300`, `1.2.3`
with `BUILD=1` → `1020301`. The code grows strictly with the version and stays
below the Android/Play limit (2,100,000,000). The former `versionCode = 1` is
lower than any new value, so upgrading from it is allowed. Never lower the
version or reuse a published `versionCode`.

## Upgrading an installed version

An update (`adb install -r`, app store) keeps data only if the `applicationId`
and signing certificate match and `versionCode` is not lower than the installed one.

- `applicationId` has not changed (`org.bimeron.app`) and must not change.
- Signing: all releases are signed with the same release key. Debug APKs from CI
  and local builds are signed with different debug keys; a release cannot be
  installed over a debug build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and requires
  an uninstall, which deletes all profiles. Backup is disabled
  (`allowBackup=false`, `data_extraction_rules` excludes everything), so before
  moving from a debug build, export profiles manually (`.conf` export in the
  profile editor) and import them again.
- For a future key change, use APK Signature Scheme v3 rotation
  (`apksigner rotate`) rather than a new key; v3 signing is enabled.

App data and its compatibility (from `ProfileStore`, `AppModel`,
`BimeronVpnService`, `GeoDataStore`):

| Data | Location | Format |
| --- | --- | --- |
| Profiles | `files/profiles/<UUID>.conf` | `.conf` text, `[Profile] Version = 1` |
| Theme, selected profile | DataStore `settings` (`theme`, `selected_profile`) | strings |
| Active profile for VPN recovery | SharedPreferences `vpn_recovery` (`active_id`) | string |
| GeoIP/GeoSite | `files/geodata/` | user-imported files |

There is no schema migration and none is needed for this release: the format and
file names are unchanged. `ProfileStore.load()` skips files that fail to parse and
shows a warning, but does not delete them; stricter parsing in a new version hides
rather than destroys a profile, and a downgrade brings it back. Saving is atomic
(temporary file, `fsync`, `rename`). If `Version` or the file layout changes in
the future, add the migration to `ProfileStore` before the first read and cover
it with an instrumentation test.

## 16 KiB pages

- AAR: `scripts/verify_core_aar.py` (run in CI) checks the SHA-256 and, through
  `scripts/native_alignment.py`, PT_LOAD alignment ≥ 16 KiB and RELRO on all ABIs
  (see [CORE-AAR.md](CORE-AAR.md)).
- APK: `python3 scripts/native_alignment.py APK` checks the ELF headers of all
  `.so` files and 16 KiB alignment of uncompressed ZIP entries; the full check is
  `zipalign -c -P 16 -v 4 APK` (build-tools ≥ 35.0.0).
- Runtime: for each release, repeat installation and connection on a 16 KiB API 35
  image.

## Release procedure

Requirements: JDK 17+ (locally the Android Studio JBR), Android SDK 34,
build-tools 35.0.0, a clean tree at a commit from `main`.

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
BT="$ANDROID_HOME/build-tools/35.0.0"
APK=app/build/outputs/apk/release/app-release.apk
```

1. Version: bump `bimeron.versionName` in `gradle.properties` in a separate
   commit through a PR; update the release notes.
2. Core: `python3 scripts/verify_core_aar.py` (SHA-256 matches CORE-AAR.md,
   native libraries are aligned). Check that LICENSE/THIRD_PARTY_NOTICES match
   the core revision.
3. Build and checks:

   ```sh
   export BIMERON_KEYSTORE_FILE="$HOME/secure/bimeron-release.jks"   # absolute path; not '~/…'
   export BIMERON_KEY_ALIAS=bimeron
   read -rs BIMERON_KEYSTORE_PASSWORD && export BIMERON_KEYSTORE_PASSWORD
   read -rs BIMERON_KEY_PASSWORD && export BIMERON_KEY_PASSWORD
   export BIMERON_REQUIRE_RELEASE_SIGNING=true
   ./gradlew --no-daemon clean :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
   ```

4. Signature and alignment:

   ```sh
   "$BT/apksigner" verify --verbose --print-certs "$APK"   # v2 and v3: true, release certificate DN and SHA-256
   "$BT/zipalign" -c -P 16 -v 4 "$APK" | tail -1             # Verification successful
   python3 scripts/native_alignment.py "$APK"                # Native alignment OK
   "$BT/aapt2" dump badging "$APK" | head -1                 # org.bimeron.app, versionCode, versionName
   ```

   Compare the certificate SHA-256 with the value recorded for previous releases.
5. R8 kept the core:

   ```sh
   unzip -l "$APK" 'lib/*/libgojni.so'                       # 4 ABIs
   unzip -p "$APK" classes.dex > "$TMPDIR/classes.dex"       # minSdk 26 + R8: a single classes.dex
   "$BT/dexdump" "$TMPDIR/classes.dex" | grep -E "Class descriptor.*L(go|org/bimeron/core)/"
   ```

   Expect `go/Seq*`, `go/Universe*`, `org/bimeron/core/androidcore/{Androidcore,Session,SocketProtector}`
   with original method names (`newSession`, `start`, `status`, `stop`, `protect` …).
6. On a device (mandatory; the build does not prove this):
   - clean install, profile import, connection and traffic, stop;
   - upgrade over the previous release: `adb install -r` the previous APK, add
     profiles, select a profile and theme, then `adb install -r` the new APK;
     profiles, selection and theme are preserved and the VPN starts;
   - a 16 KiB API 35 image (`getconf PAGE_SIZE` = `16384`) and a device/emulator
     with API 26–29;
   - the scenarios from [QA](QA.md).
7. Checksums and archive:

   ```sh
   V=$(sed -n 's/^bimeron.versionName=//p' gradle.properties)
   mkdir -p dist && cp "$APK" "dist/bimeron-android-$V.apk"
   (cd dist && shasum -a 256 "bimeron-android-$V.apk" > SHA256SUMS)
   # mapping.txt goes only to the private archive, never to dist/ or SHA256SUMS
   mkdir -p "$HOME/secure/bimeron-mapping/$V"
   cp app/build/outputs/mapping/release/mapping.txt "$HOME/secure/bimeron-mapping/$V/mapping.txt"
   shasum -a 256 "$HOME/secure/bimeron-mapping/$V/mapping.txt" > "$HOME/secure/bimeron-mapping/$V/mapping.sha256"
   ```

   Only the APK and `SHA256SUMS` (covering only the APK) are published.
   `mapping.txt` reveals original class names and is only needed to decode stack
   traces: keep it with its private checksum in the internal release archive, do
   not attach it to the GitHub Release and do not publish its hash.
8. Publishing:
   - [ ] tag `vX.Y.Z` on the build commit (`git tag -s vX.Y.Z`), matching `versionName`;
   - [ ] GitHub Release: APK, `SHA256SUMS`, signing certificate SHA-256, notes,
         core revision and SHA-256 from CORE-AAR.md;
   - [ ] Google Play (if used): AAB (`:app:bundleRelease`), Play App Signing,
         VpnService and `specialUse` foreground service declarations, privacy
         policy, Data safety;
   - [ ] record `versionCode`, APK and certificate SHA-256 in the release log;
   - [ ] remove temporary keystore copies and `dist/` from shared machines.

## Proposed CI changes (not yet applied)

1. Add an R8 and 16 KiB check for release to the existing `build` job, without
   secrets (debug-signed, never published):

   ```yaml
         - name: Release build (R8, debug-signed) and 16 KiB check
           run: |
             ./gradlew --no-daemon --stacktrace :app:assembleRelease
             APK=app/build/outputs/apk/release/app-release.apk
             "$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 "$APK"
             python3 scripts/native_alignment.py "$APK"
             unzip -p "$APK" classes.dex > "$RUNNER_TEMP/classes.dex"
             "$ANDROID_HOME/build-tools/35.0.0/dexdump" "$RUNNER_TEMP/classes.dex" \
               | grep -q "Lorg/bimeron/core/androidcore/Session;"
   ```

   (`android-actions/setup-android` must install `build-tools;35.0.0`.)

2. A separate tag-triggered `.github/workflows/release.yml`:

   ```yaml
   name: Android release
   on:
     push:
       tags: ['v*']
   permissions:
     contents: write
   jobs:
     release:
       runs-on: ubuntu-24.04
       environment: release            # secrets only in a protected environment with manual approval
       timeout-minutes: 45
       steps:
         - uses: actions/checkout@v4
         - name: Tag matches versionName
           run: test "v$(sed -n 's/^bimeron.versionName=//p' gradle.properties)" = "$GITHUB_REF_NAME"
         - run: python3 scripts/verify_core_aar.py
         - uses: actions/setup-java@v4
           with: { distribution: temurin, java-version: '17' }
         - uses: android-actions/setup-android@v3
           with: { packages: 'platform-tools build-tools;35.0.0' }
         - uses: gradle/actions/setup-gradle@v4
         - name: Decode keystore
           env: { KEYSTORE_B64: '${{ secrets.BIMERON_KEYSTORE_BASE64 }}' }
           run: |
             echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/release.jks"
             echo "BIMERON_KEYSTORE_FILE=$RUNNER_TEMP/release.jks" >> "$GITHUB_ENV"
         - name: Build signed release
           env:
             BIMERON_KEYSTORE_PASSWORD: ${{ secrets.BIMERON_KEYSTORE_PASSWORD }}
             BIMERON_KEY_ALIAS: ${{ secrets.BIMERON_KEY_ALIAS }}
             BIMERON_KEY_PASSWORD: ${{ secrets.BIMERON_KEY_PASSWORD }}
             BIMERON_REQUIRE_RELEASE_SIGNING: 'true'
           run: ./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
         - name: Verify and checksum
           env: { EXPECTED_CERT_SHA256: '${{ vars.BIMERON_CERT_SHA256 }}' }
           run: |
             BT="$ANDROID_HOME/build-tools/35.0.0"; APK=app/build/outputs/apk/release/app-release.apk
             "$BT/apksigner" verify --print-certs "$APK" | grep -qi "certificate SHA-256 digest: $EXPECTED_CERT_SHA256"
             "$BT/zipalign" -c -P 16 4 "$APK"
             python3 scripts/native_alignment.py "$APK"
             mkdir dist; V="${GITHUB_REF_NAME#v}"
             cp "$APK" "dist/bimeron-android-$V.apk"
             (cd dist && sha256sum bimeron-android-$V.apk > SHA256SUMS)
         - name: Remove keystore
           if: always()
           run: rm -f "$RUNNER_TEMP/release.jks"
         # mapping.txt is deliberately not uploaded: artifacts of a public repository are
         # readable by anyone with read access. If releases are built in CI, add a separate
         # step that encrypts mapping.txt with the team key and stores it privately.
         - name: Draft GitHub release
           env: { GH_TOKEN: '${{ github.token }}' }
           run: gh release create "$GITHUB_REF_NAME" --draft --verify-tag dist/*
   ```

   The draft release is published manually after on-device verification (step 6).

## Size

The release APK contains uncompressed `libgojni.so` for four ABIs (~200 MB). For
app stores an AAB is preferable (Play serves a single ABI); for direct downloads
`splits.abi` could publish one APK per ABI, which would require distinct
`versionCode` values per ABI and an extended versioning scheme. No decision has
been made; it is not enabled in the current build.
