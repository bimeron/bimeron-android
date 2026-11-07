# Continuous integration

Workflow: [`.github/workflows/android.yml`](../.github/workflows/android.yml).
It runs on every push and pull request on Ubuntu 24.04 with Temurin Java 17,
Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21 and compileSdk 34. The job has read-only
contents permission and a 45-minute timeout.

## Steps

1. Checkout with full history (`fetch-depth: 0`).
2. Repository policy: `scripts/test_check_repo_policy.py`, then
   `scripts/check_repo_policy.py` (see below).
3. Pinned core AAR: `scripts/verify_core_aar.py` checks the SHA-256 recorded in
   [CORE-AAR.md](CORE-AAR.md) and 16 KiB native alignment of all four ABIs.
4. Offline script self-tests: `test_verify_core_aar.py`,
   `test_native_alignment.py`, `test_connected_test_runner.py`,
   `test_rebuild_core_aar.py` (canonical source path, signal cleanup, build
   environment filtering and disabled version detection of the core AAR rebuild
   helper, without a real build).
5. Build, unit tests and lint:

   ```sh
   ./gradlew --no-daemon --stacktrace --continue :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
   ```

   Failures remain fatal; `--continue` lets independent tasks finish. Unit and
   lint reports are uploaded with `always()`. The debug APK is uploaded before
   the emulator tests, so an emulator failure does not lose a built APK.
6. Connected tests on an API 34 `google_apis` x86_64 emulator with KVM, driven
   by `scripts/connected_test_runner.py`.

All Python checks are dependency-free and can be run locally from the
repository root:

```sh
python3 scripts/check_repo_policy.py
python3 scripts/test_check_repo_policy.py
python3 scripts/verify_core_aar.py
python3 scripts/test_verify_core_aar.py
python3 scripts/test_native_alignment.py
python3 scripts/test_connected_test_runner.py
python3 scripts/test_rebuild_core_aar.py
```

Pinned inputs improve repeatability but do not prove bit-for-bit APK or core
reproducibility: action tags, JDK patch versions, runner images and dependency
resolution are not fully locked.

## Repository policy

`scripts/check_repo_policy.py` inspects every file listed by
`git ls-files --cached --others --exclude-standard` and fails on:

- Cyrillic characters in any UTF-8 text file outside the allow-list
  `CYRILLIC_ALLOWED`, which contains only the Android Russian localization
  resources `app/src/main/res/values-ru/` and `app/src/main/res/values-ru-*/`.
  Tests must read Russian strings from those resources instead of using literals.
  Binary files (NUL bytes or invalid UTF-8) are not scanned.
- `*.html`, `*.htm`, `*.zip` files and anything under `design/`.
- Files larger than 1 MiB, except `app/libs/bimeron-core.aar`.

It also checks that every new commit's author and committer email is either
`4gknw59gyj@privaterelay.appleid.com` or `noreply@github.com`:

- Pull requests: commits in `head --not base`.
- Pushes: commits in `after --not before`. When `before` is missing, all zeros
  (initial push, new branch or tag) or not in the history (force push), it checks
  every commit not reachable from another remote branch or tag, which is the whole
  history on the first push to a new repository.
- Locally, pass `--range origin/main..HEAD`; without a range or a GitHub event
  the commit check is skipped. `--no-commits` runs only the file checks.

A shallow clone fails the commit check rather than passing silently.

## Connected test runner

`scripts/connected_test_runner.py` runs these phases in order and stops at the
first nonzero exit:

1. `adb shell settings put global hide_error_dialogs 1`, then a read-back check.
   Foreign system ANR dialogs (for example "Pixel Launcher isn't responding")
   can otherwise cover the app for a whole run on a cold emulator. This does not
   weaken assertions: tests still require an observed `org.bimeron.app` window,
   and an ANR or crash of the app under test still terminates instrumentation.
2. `./gradlew :app:installDebug`.
3. Set the app locale to Russian (`cmd locale set-app-locales ... --locales ru`).
4. `adb shell am wait-for-broadcast-idle` to drain the broadcast backlog. API 34
   has no device-side deadline, so the host applies a 120-second timeout; typical
   drains take about 20 seconds but can exceed 30 seconds right after boot.
5. `./gradlew :app:connectedDebugAndroidTest`.

The error-dialog phases have a 30-second host timeout. Timed-out phases record
exit 124 and launch failures 127. Bounded phases send stdout and stderr to
`/dev/null` because platform output can contain identities.

`ci-diagnostics/metadata.json` records only: API level, checked-out SHA,
validated PR head SHA, run ID and attempt, the numeric exit of each phase (null
when not run) and `broadcast_idle_seconds`. On failure it also records a
bounded `pidof org.bimeron.app` probe (5-second command deadline, 129-byte read
limit, strict numeric filter); raw probe output is never retained. Probe or
metadata failures never replace the original exit code.

The uploaded artifact contains this metadata plus `TEST-*.xml` and the HTML/CSS/JS
connected-test reports, never the whole results directory, device logs or
screenshots. Reports keep throwable text and assertion messages as emitted, so
they may include synthetic fixture values. Instrumentation tests must therefore
use synthetic data only (documentation IP ranges, `example.invalid`, test
credentials); re-check this boundary before adding real credentials or datasets.
