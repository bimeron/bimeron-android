# Bimeron for Android

An Android app that manages `.conf` profiles and runs a system VPN through the embedded `bimeron-core`. Open the project in Android Studio or run `./gradlew :app:assembleDebug`; the APK is written to `app/build/outputs/apk/debug/`.

Release builds (R8, signing from the environment, versioning, 16 KiB checks and the publishing procedure) are described in [docs/RELEASE.md](docs/RELEASE.md). CI is described in [docs/CI.md](docs/CI.md).

## Project structure

- UI: Kotlin, Jetpack Compose, Material 3. Phones show three tabs in a compact bottom bar; wide windows use a navigation rail and, when wide enough, two columns. Profiles are on the home screen.
- Profiles: separate private files `files/profiles/<UUID>.conf`. The selected profile and theme are stored in DataStore; whether the profile list is expanded is kept in memory for the current launch only.
- VPN: a foreground `VpnService` obtains Android VPN consent, creates the TUN interface and a new core Session for every connection. State and counters come from the core's `Status()`. With `Mode=proxy` or `KillSwitch=true` the connect button is unavailable. The foreground service keeps running after the app screen is closed or the task is removed from Recents; force-stopping the app through Android ends the VPN.
- Brand resources, the splash screen and the loading animation come from the Bimeron Android kit v2.1. No artificial delay is added while profiles load.
- Languages: English (`values/`, the default) and Russian (`values-ru/`). There is no in-app language picker; on Android 13+ the language is chosen per app in system Settings → Apps → Bimeron → Language. The list there is the hand-written `res/xml/locales_config.xml` (`android:localeConfig`). It names regional tags (`en-US`, `ru-RU`) so each language is picked in one step, without a region sub-picker; any region of a listed language still resolves to the same resources. The APK packages only the `en` and `ru` resources, library translations included (`appLocaleFilters` in `app/build.gradle.kts`, applied as `defaultConfig.resourceConfigurations`). To add a language, add `values-<lang>/`, a `<locale>` in `locales_config.xml` and the language in `appLocaleFilters`; `LocaleConfigTest` pins the list and fails when the two language sets diverge. Older Android versions follow the system language.
- Backup is disabled because `.conf` files contain keys and passwords in plain text.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for model coordination and GeoData storage.

## Core

The build and exact revision are documented in [docs/CORE-AAR.md](docs/CORE-AAR.md). `app/libs/bimeron-core.aar` is a real build for four ABIs. Publishing the app requires VPN verification on a device or emulator against a working test server: a successful APK build does not prove that routing works or that there is no VPN loop.

## Import

When a profile uses `File = ...`, the app asks for the separate key document through the system file picker and stores it as an inline `Key = ...`. If the file is unavailable, the profile is not saved. The source documents are never modified.

## Logs and diagnostics

The verification checklist and results are in [docs/QA.md](docs/QA.md). The log contains VPN events, profile actions and safe error messages and is kept in process memory. Settings select the verbosity (errors and warnings, normal, or detailed with network changes), the size (500, 1000 or 5000 entries) and whether previous entries are kept on a new connection, as in the desktop client. Before an entry is written, access keys and URIs, passwords, tokens, UUIDs, server addresses, IP addresses and file paths are removed. The Share (system sheet via FileProvider) and Save (SAF file picker) actions export a text report: app and core versions, core capabilities, Android version, device, network state without addresses or SSID, connection state and the log in chronological order, at most 1 MiB. Unlike the desktop client, which deliberately shows free-form diagnostic text unredacted, Android always redacts secrets in both the log and the report.

## Profile editor

The profile editor provides General, Network, Routing and Source config sections with a shared in-memory draft, masked access keys, ordered rules, source highlighting, formatting and undo. Saving validates against the embedded Android core; disabled rules and unrepresented transport options are preserved. See [editor behavior](docs/PROFILE-EDITOR.md), the [Android contract](docs/CONTRACT.md) and the [pinned core artifact](docs/CORE-AAR.md).
