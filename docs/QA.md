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
  `RECOVERY_NEEDS_USER`. An ssconf profile without a usable saved configuration is
  skipped the same way, because a recovery never fetches. Non-SSH profiles recover
  as before.
  `RecoverySkipTest` (instrumentation) covers the SSH skip.
- Always-on VPN: Android starts the always-on app's service with the
  `android.net.VpnService` action on boot/user unlock, after a package update
  (`VpnManagerService.onPackageReplaced`) and when always-on is turned on in
  Settings. That start is a recovery for the interrupted session's ID, or else
  for the selected profile (the first profile when none was chosen), so it also
  connects after an explicit Disconnect; to stay off, turn always-on off. It is
  classified like any other recovery: an SSH profile, or one Android cannot run
  (`Mode` other than `vpn`, `KillSwitch = true`), shows "Tap to reconnect". It
  keeps a live session, replaces a failed one, and without a profile only
  records a journal warning. `AlwaysOnTargetTest` and `AlwaysOnStartTest` cover it. On the
  SM-A025F (Android 12) with always-on set and lockdown off, the VPN came up by
  itself after `adb install -r` and after turning always-on off and on. Android
  does not restart it after `am force-stop`; it returns on the next trigger
  above or on Connect. Network changes are not always-on triggers. On this
  phone, `am crash` while connected did not schedule a sticky restart either
  (the ServiceRecord stayed without a restart time until the app was opened).
- On Android 13 (API 33) and later the recovery notification is shown only if the
  user granted the notification permission; without it the post is dropped
  silently and only the in-app state carries `RECOVERY_NEEDS_USER`. See
  "Notification permission" below for when the app asks.
- Session drop notice (security audit M-1): when a session that had reached
  Connected ends without the user stopping it (Core gives up, including while
  reconnecting; packet I/O or network errors), or an unattended recovery start
  (sticky restart) fails, the app posts "VPN disconnected" on the "VPN alerts"
  channel (id `vpn_recovery`, shared with "Tap to reconnect").
  The text is fixed (no profile, server or error text) and a tap opens the app. A
  user stop (also a revoke from system settings or another VPN taking over), a new
  connect or profile switch, an SSH sign-in cancel or host-key rejection, and the
  recovery skip post nothing; a user connect that fails before Connected posts
  nothing either, because the app shows the error. A failure caused by the
  teardown of a stop that is already requested counts as the user's stop. The
  notice keeps the default private lock-screen visibility. It is removed on the
  next user connect and when a session reaches Connected. With notifications off
  nothing is posted. Automated coverage: `DropNoticeDecisionTest` (JVM decision
  table and gate wiring), `DropNotificationTest` (Robolectric, the real
  notification), `DropNoticeServiceTest` (Robolectric, the service command loop
  with only the native session replaced) and `DropNotificationInstrumentedTest`.
  Not yet verified on a device; see "Session drop notice (device QA)" below. Not
  covered: a system destroy of the service mid-session posts nothing (follow-up).
- Switching profiles or applying a saved change while connected stops the old
  session (its TUN closes) before the new one establishes its TUN (audit L-2).
  In between, traffic uses the physical network unless system "Block connections
  without VPN" (lockdown) is on. For an ssconf profile the gap includes the
  connect-time fetch.

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
  "VPN alerts") does not show the row.
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
  requires confirmation. Confirming a switch to a profile Android cannot start
  (proxy mode or core `KillSwitch = true`) disconnects the VPN and starts
  nothing, and the dialog says so (audit L-3; `AppModelOperationsTest`). A denied
  Android VPN consent is shown as an error.
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
resolution. ssconf is excluded because it requires remote resolution. Parser
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

## ssconf connect-time fetch (device QA)

On a real phone (Wi-Fi ADB) with a real ssconf endpoint:

1. Connect an ssconf profile with the server online: the connect fetches the
   config once (server access log), the VPN connects, and no fallback entry
   appears in the journal.
2. Change the config on the server, disconnect and connect again: the new config
   is used (a fresh fetch on every user connect).
3. Take the ssconf server offline (or block it) and connect: the VPN connects with
   the saved configuration and the journal shows "ssconf update failed
   (SSCONF_FETCH_FAILED); connecting with the saved configuration" (or
   `SSCONF_NETWORK_UNAVAILABLE`). The journal and logcat contain no link or body.
4. With the server offline and no saved configuration (a new profile), connect:
   it fails with "Could not fetch ssconf." (or "No network is available for
   ssconf.").
5. Recovery: with the VPN connected, kill the process (`am kill` after moving the
   app to the background, or let the system restart the sticky service): the
   restarted session uses the saved configuration with no request in the server
   log. Network switches during a session (Core-internal reconnects) make no
   request either.

## Session drop notice (device QA)

On a real phone (Wi-Fi ADB), with notifications allowed for Bimeron:

1. Connect, move the app to the background, then stop the server (or block it
   on the router). When Core gives up, "VPN disconnected · Your traffic is no
   longer protected by Bimeron. Tap to open the app." appears. It shows no profile
   name or server. Tap it: Bimeron opens on the connection page with the error.
2. Repeat with airplane mode on mid-session and wait until Core gives up
   (Reconnecting, then the failure): the same notice appears.
3. Tap Connect again: the notice disappears at once (user start).
4. Connect, then stop from the app or the VPN notification: no notice.
5. Connect, then disconnect from system VPN settings: no notice.
6. Connect a profile whose server is offline (fails before Connected): no notice;
   the error is shown in the app.
7. SSH profile: cancel the sign-in prompt: no notice.
8. Turn Bimeron's notifications off in system settings and repeat step 1: nothing
   is posted and nothing crashes; the in-app state shows the failure.

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

## SSH host-key TOFU device checklist

Not yet run on a device. Use an SSH server you control, a profile with no
imported known_hosts unless a step says otherwise, and compare fingerprints with
`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the server. JVM coverage:
`SshHostKeyOfferTest`, `SshHostKeyDialogTest`, `TrustedHostKeysTest`,
`TrustedHostKeysSweepTest`, `TrustedHostKeysSectionTest`, `HostKeyOptInTest`,
`NoExternalStorageTest`, `SshFileGuardTest`; JNI: `HostKeyOptInNativeTest`.

1. First connect: the "New server" dialog shows `host:port`, the key type and
   the full `SHA256:` fingerprint in monospace, matching the server. A screenshot
   is blocked (FLAG_SECURE). Trust: the VPN connects, and the profile editor
   shows "Trusted server keys: 1".
2. Disconnect and connect again: no dialog, connects. Switch Wi-Fi/mobile while
   connected (Core reconnect): no dialog.
3. Forget, then connect and press Cancel: the attempt ends with
   `AUTH_CANCELLED`, nothing is stored ("Trusted server keys: 0").
4. Trust again, then change the server's host key (regenerate it): the next
   connect fails with `HOST_KEY_MISMATCH` and its message, with no dialog. After
   Forget in the profile editor, the next connect offers the new key.
5. Profile with an imported known_hosts that lists the server: no dialog,
   connects. With an imported known_hosts that lists a different key for the
   server: `HOST_KEY_MISMATCH`, no dialog.
6. Leave the dialog unanswered for 60 seconds: it closes and the attempt ends
   with `AUTH_UNANSWERED`; nothing is stored.
7. Rotate the device and lock/unlock while the dialog is shown: the dialog
   stays (or reappears) for the same offer, and Trust still connects. Recreating
   the activity never trusts by itself.
8. Delete the profile: `files/trusted-hosts/<uuid>.known_hosts` is gone
   (`adb shell run-as org.bimeron.app ls files/trusted-hosts`). "Save as new
   profile" starts with no trusted keys.
9. The journal shows no host, fingerprint or key text; at most the fixed
   "Trusted server key was not saved" warning.
10. Damage the store (`adb shell run-as org.bimeron.app sh -c 'echo x >> files/trusted-hosts/<uuid>.known_hosts'`):
    the next connect fails with `TRUSTED_KEYS_UNREADABLE` and no dialog; the editor shows "Trusted server keys
    could not be read"; Forget, then connect offers the server again.
11. In Source config set `IdentityFile = /sdcard/.ssh/id_ed25519` (or an `ssh://...?known_hosts=/sdcard/...`
    key): connecting fails with `SSH_FILE_OUTSIDE_APP` and nothing is dialed.
