# Android app verification

This document lists what has been verified, what has not, and the manual
checklists that remain. Automated tests run in CI (see [CI.md](CI.md)); local
command:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

## Verified

### VPN and service

- On a physical phone (Samsung SM-A025F): Android VPN consent, TUN creation, the
  transition to Connected driven by `Session.Status()`, growing traffic counters,
  loading `https://example.com` through a local test SOCKS5 server, and stop.
- On an API 35 emulator with a temporary host SOCKS5 server (TCP CONNECT and UDP
  ASSOCIATE): consent, TUN creation, `connected` status, growing counters, and
  Chrome loading `https://example.com` and `https://api.ipify.org` through the VPN.
  After returning to the home screen, `dumpsys` still showed the foreground
  service and `VPN CONNECTED`.
- The foreground VPN service is declared with `stopWithTask=false`, is not
  stopped when the Activity closes and returns `START_STICKY`; the active profile
  ID is stored for Android-permitted recovery. A force stop by the system or the
  user does not guarantee recovery. Every start other than the explicit user
  start is a recovery: the profile is read once and classified off the main
  thread, and the session starts that same read. A profile that may need SSH
  interactive auth (SSH source, `Type = ssh`, any `ssh://` value, also after a
  leading BOM or Unicode space, an unknown protocol, or an unreadable profile) is
  skipped instead: no session starts, the recovery ID is cleared, a "Tap to
  reconnect" notification opens the app, and the state shows
  `RECOVERY_NEEDS_USER`. Non-SSH profiles recover as before.
  `RecoverySkipTest` (instrumentation) covers the SSH skip.
- On Android 13 (API 33) and later the recovery notification is shown only if the
  user granted the notification permission; without it the post is dropped
  silently and only the in-app state carries `RECOVERY_NEEDS_USER`. See
  "Notification permission" below for when the app asks.

### Notification permission

Decision #1 (2026-11-07), implemented in `NotificationPrompt.kt` and wired in
`MainActivity`:

- API 33+: the app asks for `POST_NOTIFICATIONS` once, right after the first
  successful connect, meaning the first change from a non-connected state to
  `connected` that the app observes while the Activity is resumed. It never
  asks while the Android VPN consent dialog is up, while a consent result is
  pending, or before the tunnel is connected. If notifications are already on at
  that first connect, the decision is only recorded and nothing is shown.
- Opening the app while the VPN is already connected (connect then Home, or a
  background recovery reconnect) does not prompt: the state replayed at each
  resume is not a transition. The flag stays unset in that case, so the next
  observed connect still asks once.
- The "already asked" flag (`notification_prompt` SharedPreferences,
  `post_notifications_asked`) is committed synchronously before the request is
  launched, so rotation, Activity recreation or process death while the system
  dialog is open never produce a second prompt. The app never re-prompts
  automatically; there is no in-app reset (clearing app data or reinstalling
  resets it).
- While notifications are off, Settings shows a "Notifications" section with one
  row, "Notifications are off · Open settings". It opens the app's notification
  settings (`Settings.ACTION_APP_NOTIFICATION_SETTINGS` with
  `EXTRA_APP_PACKAGE`). The state is re-read with
  `NotificationManagerCompat.areNotificationsEnabled()` on every resume and after
  the permission result, so the section disappears once notifications are on.
  On API 33+ the row is hidden before the one-time request (the default "off"
  state is not the user's choice yet).
- API < 33: nothing is requested or recorded. For consistency the row is shown
  whenever the user turned the app's notifications off in system settings.
- Only the app-level switch is checked; a single disabled channel (for example
  "VPN recovery") does not show the row.
- Automated coverage: `NotificationPromptTest` (JVM/Robolectric: decision table,
  API < 33, consent flow, flag persistence across instances, request-once with a
  recreated controller, row shown/hidden with the system state, settings intent
  extras, no prompt and no flag for an already-connected replay at resume),
  `A11yMainScreensTest` / `A11yLargeFontTest` (the row is one 48 dp
  button whose click label is "Open settings"; no clipping at font scale 2), and
  `NotificationPromptInstrumentedTest` (API 33+ device: no prompt when opened
  while already connected; the system prompt appears only after an observed
  change to `connected`, once, not again after a second connect or recreation,
  and the Settings row appears; with the flag set no prompt appears).

Manual device checklist (API 33+ unless noted):

1. Fresh install → no prompt at launch or during VPN consent → connect → the
   notification prompt appears once, after the status shows Connected.
2. Deny → Settings shows "Notifications are off"; tap it → the app's
   notification settings open.
3. Rotate and disconnect/reconnect → no new prompt.
   Fresh install variant: tap connect and press Home before it shows Connected (or let
   recovery reconnect in the background), reopen → no prompt; disconnect and
   connect again → the prompt appears once.
4. Force-stop or swipe the app away, reopen and connect → no new prompt.
5. Turn notifications on in system settings, return → the row is gone; turn them
   off again → the row is back, still no prompt.
6. Fresh install, allow at the prompt → no row; the VPN notification is visible.
7. API < 33: no prompt ever; turning notifications off in system settings shows
   the row, turning them on hides it.

### Profiles and home screen

- `ProfileStoreTest` (instrumentation): create, read, update, delete and clearing
  the selected profile.
- Three sections, collapsible profiles on the home screen and separate
  selected/active IDs. Selecting or adding keeps the list expansion until the
  next launch; reselecting does not start the VPN. Switching while connected
  requires confirmation. A denied Android VPN consent is shown as an error.
  Configuration changes to the active profile are saved without disconnecting
  and offer a separate reconnect; renaming alone does not. Duration and traffic
  come from the service state.
- Automated coverage: selection persistence across models, expansion reset on a
  new launch, no collapse on selection, switch cancel/confirm, profile file
  operations and rejection of a stale attempt token.
- Profile rows: one icon, the name, the protocol only, and a separate transparent
  settings button. The selected and pressed backgrounds span the whole row.
  `ProfileHoverTest` sends `ACTION_HOVER_MOVE` mouse events and checks pixels on
  the row body and the settings button: an unselected row changes its whole
  background, a selected row keeps its color.
- Visually checked: empty and populated phone states, the add dialog, expansion,
  returning through tabs, expansion retained across rotation, two columns on a
  1920×1200 window at 240 dpi, system light and dark themes, and font scale 1.5.

### Profile editor

See [PROFILE-EDITOR.md](PROFILE-EDITOR.md). Instrumentation covers offline
dynamic-key validation against the bundled AAR, retained disabled rules,
invalid-save rollback, Activity recreation and discard, and optional reconnect
after saving an active configuration. Visually checked: all four phone sections,
a dark 1920×1200 window at 240 dpi and phone font scale 1.5.

### Protocol validation against the bundled core

Instrumentation tests call `Androidcore` directly with synthetic credentials and
documentation addresses (`192.0.2.0/24`, `example.invalid`):

- Accepted URIs: SS, SOCKS5, VLESS, Trojan, AnyTLS, TUIC, Hysteria2/hy2,
  HTTP/HTTPS, Naive HTTPS/QUIC, MASQUE, VMess, SSH (parser acceptance only).
- Accepted explicit sections: ss/shadowsocks, socks5, http/https, masque, naive,
  trojan, vless, ssh.
- Rejected inputs include invalid VMess base64, invalid TUIC/VLESS UUIDs, Trojan
  `security=none`, unknown MASQUE options, AnyTLS/Naive `insecure=1`, duplicate
  hy2 `sni`, unknown explicit types, incomplete SOCKS5 authentication and invalid
  SS ciphers, plus Android's proxy-mode and core `KillSwitch` restrictions.
- Every rejected create or overwrite leaves the stored files and loaded profiles
  unchanged.

These tests construct and stop a Session without `Start`, `SetNetwork` or DNS
resolution. SSConf is excluded because it requires remote resolution. Parser
acceptance does not prove transport availability, certificate validation,
authentication, routing, UDP, reconnect or a successful connection.

### Accessibility

The visual design at the default font size is unchanged by the accessibility
work. Semantics:

- The power button exposes an action name (Connect, Disconnect, Cancel, Retry)
  and a `stateDescription` with the current status; the caption below it is
  hidden from TalkBack to avoid duplication. The status and the hint/error are
  polite live regions.
- Profile rows are `Role.RadioButton` inside a `selectableGroup`; the
  "Configure profile …" button is a separate 48 dp target. The "Profiles" header
  is a heading described as "Profiles: N" with the counter hidden. "Add" is
  announced as "Add profile". Expand/Collapse is a button with a
  "List collapsed/expanded" state. The read warning is a live region.
- Bottom and side navigation are a tab group (`selectableGroup` + `Role.Tab`).
- The theme options in Settings are one radio group: each row is
  `selectable(Role.RadioButton)`, the circle has no handler of its own (one focus
  stop per option), 48 dp size kept. Log level and log size are separate radio
  groups; "Keep previous entries" is `Role.Switch`.
- Headings: "Bimeron", page titles, section titles and "Session traffic".
- Traffic metrics and log entries are read as one stop ("Received, 2.0 KB";
  "time, message"). The session timer is announced as "Session duration hh:mm:ss".
  B/KB/MB units are localized.
- The loading animation is described as "Loading"; the error on the add-key
  screen is a live region.
- Large fonts: bottom navigation labels may wrap to two lines, and the side
  navigation widens to fit its labels (at least 88 dp). Other layout breakpoints
  (vertical metrics at font scale ≥ 1.3, two columns from 850 dp, side navigation
  from 600 dp) are unchanged.
- Contrast (WCAG 2.1, checked by `A11yContrastTest`): secondary text alpha was
  raised to 0.62 (about 4.8:1) in the light theme. Other text pairs are ≥ 4.5:1 and
  icons ≥ 3:1. Known exception: the active side-navigation icon in the dark theme
  (`#287BFF` on `#293F60`, 2.7:1); the state is also conveyed by the label color
  and item background.

JVM tests (Robolectric, no emulator): `A11yMainScreensTest` (roles, names,
states, headings, live regions, selection groups, targets ≥ 48 dp),
`A11yLargeFontTest` (at `fontScale = 2.0` and 360 dp width no text on home, empty
home, settings or log is clipped or overflows), `A11yContrastTest` (both themes).

### Languages

On Android 13+ (API 35 emulator), Settings → Apps → Bimeron → Language lists
English and Russian under "All languages" and the system locale (English (United
States)) and the current app language under "Suggested". Suggested entries apply
in one tap; a language entry opens the system's region list for that language even
though `locales_config.xml` names `en-US` and `ru-RU`. Selecting switches the app
UI, and `cmd locale get-app-locales` / `set-app-locales` report and apply the same
value. Any region (for example `en-GB` or `ru-KG`, stored earlier) resolves to the
English or Russian resources.
`LocaleConfigTest` (JVM) checks that the merged manifest's `android:localeConfig`
is `@xml/locales_config` and lists exactly `en-US` and `ru-RU`, that its languages
match the APK locale filters (`resourceConfigurations`), and that regional tags
resolve to `values/` and `values-ru/`. `LocaleConfigInstrumentedTest` checks the
list through the platform `LocaleConfig` API on API 33+ devices.

### Diagnostics and log export

Log levels and size, keeping previous entries, clearing, report export through
the system share sheet and SAF, and secret redaction. JVM tests cover redaction of
URIs, keys, passwords, tokens, UUIDs, IPv4/IPv6, host:port, and paths;
idempotence; unchanged ordinary messages; level filtering; size limits; clearing
on connect; and report format with byte-based truncation.

## Not yet verified

- Removing the task from Recents while the VPN is active, and traffic continuity
  afterwards.
- Manual TalkBack navigation and keyboard focus with a physical keyboard.
- SAF import across different document providers; editing and deleting on both
  window sizes; rotation and split screen.
- Live connections for `ssconf`, VLESS, Trojan and other protocols; direct
  routing; full UDP/DNS; IPv6; Wi-Fi/mobile network switching with DNS updates;
  process crashes; actual always-on/lockdown transitions; long-running memory use.
- Log export on a device, share-sheet recipients and document providers.

An AAR build and a successful `Start()` do not prove any of the above.

## VPN test plan

For every supported protocol, verify Session setup, TCP, UDP and DNS; protection
and binding of all physical sockets; absence of a VPN loop; stop and restart.
Verify `ssconf` and direct routing separately. Then verify IPv6, physical network
and DNS changes, process crashes, always-on/lockdown and long-running use with
memory monitoring. Compare state and counters from `Status()` with actual traffic.

## Manual TalkBack checklist

1. Home: focus order is "Bimeron" → status → power button ("Connect, VPN
   disconnected, button") → hint → "Profiles: N" → "Add profile" → profile rows
   ("Home, VLESS, selected, radio button") each with a separate
   "Configure profile …" → "Collapse/Expand" → traffic → export → tabs.
2. Connecting/disconnecting announces the status change without moving focus; a
   connection error is read out.
3. Profile rows are announced as disabled while connecting.
4. Settings: three radio buttons "1 of 3", one stop per option; double tap changes
   the theme.
5. Log: one stop per entry.
6. Font size 200 % and maximum display size, on a phone and a 1920×1200 tablet,
   light and dark themes: no clipping or overlap.
7. External keyboard: Tab moves through profile rows and settings buttons, with a
   visible focus indicator.
