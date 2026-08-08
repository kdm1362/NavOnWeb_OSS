# Hotspot-triggered projection automation

## Supported public API

NavOnWeb targets and compiles against Android 16 / API 36. API 36 exposes the public
[`TetheringManager.registerTetheringEventCallback`](https://developer.android.com/reference/android/net/TetheringManager#registerTetheringEventCallback(java.util.concurrent.Executor,%20android.net.TetheringManager.TetheringEventCallback))
API to ordinary applications holding `ACCESS_NETWORK_STATE`. The callback immediately supplies the
current tethering state and later changes. NavOnWeb filters
[`TetheringInterface.type`](https://developer.android.com/reference/android/net/TetheringInterface#getType())
for `TETHERING_WIFI`; it does not inspect hotspot SSID, credentials, or connected-client identity.

There is no equivalent public, unprivileged observation API before API 36. NavOnWeb therefore marks
hotspot automation unsupported on API 26 through 35. It deliberately does not use hidden Soft AP
broadcasts, reflection, `TETHER_PRIVILEGED`, `NETWORK_SETTINGS`, root, or polling. The app also does
not start or stop phone tethering itself; the user or system remains in control of the hotspot.

## Event semantics

Automation has one mutually exclusive mode:

- `NONE`
- `BLUETOOTH`
- `HOTSPOT`

Selecting one mode disables the other logically. Each selection has a monotonically changing
generation. A callback registered for an older mode/generation cannot affect the current service.

The first tethering callback after registration is a baseline only. It never starts or stops the
projection service, even when the hotspot is already on or off. Duplicate states are ignored. Only
a later real transition has a side effect:

- Wi-Fi tethering OFF to ON: request projection service start.
- Wi-Fi tethering ON to OFF: request projection service stop, including a manually started session.

Unregistering or changing mode does not manufacture an OFF transition. This prevents lifecycle and
settings changes from being mistaken for the user turning off the hotspot.

## Process and foreground-service limits

`TetheringEventCallback` is an in-process callback. It does not launch a terminated application.
When the user explicitly selects HOTSPOT mode, NavOnWeb therefore starts a persistent
`HotspotAutomationMonitorService`. It registers the callback through `HotspotAutomationSource`,
runs as a `specialUse` foreground service, and shows an ongoing low-priority notification for as
long as the mode remains selected. `START_STICKY` lets Android recreate it after ordinary process
reclamation, but the callback itself is not a cold-start mechanism.

An explicit `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` receiver requests restoration only when HOTSPOT
mode was already selected. Restoration starts the monitor, not projection, and the immediate
tethering callback remains baseline-only. OEM background policy can still delay or reject the
restoration request. Force-stopping NavOnWeb disables its services and broadcasts until the user
opens it again; merely turning on the hotspot cannot override that boundary.

Android 14 and later require the monitor service to declare `foregroundServiceType="specialUse"`,
the `FOREGROUND_SERVICE_SPECIAL_USE` permission, and an
`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation in the manifest. Android's
[`special-use service documentation`](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
states that this free-form use case is reviewed during Play submission. For Play distribution,
the App content foreground-service declaration must describe the user benefit and interruption
impact and include a demonstration video; declaration is not a guarantee of approval. See the
official
[`Play Console foreground-service requirements`](https://support.google.com/googleplay/android-developer/answer/13392821).
If the use case is rejected, release must fail closed by removing the persistent monitor and
limiting detection to periods when the app process is already active.

Android 12 and later generally prohibit starting a foreground service while an app is in the
background. The Android runtime catches this condition and reports a deferred start; when
notification permission is available it offers a user-visible action to open NavOnWeb. See
[`ForegroundServiceStartNotAllowedException`](https://developer.android.com/reference/android/app/ForegroundServiceStartNotAllowedException)
and the official
[`foreground-service launch restrictions`](https://developer.android.com/develop/background-work/services/fgs/launch).

The projection service now declares the `connectedDevice` foreground-service type instead of the
time-limited `dataSync` type. Its LAN/USB interaction still requires an accurate Play Console FGS
declaration and the manifest prerequisite (`CHANGE_NETWORK_STATE` is declared). This removes the
Android 15 data-sync six-hour bucket but does not guarantee indefinite survival: users, Android,
and OEM power policy can still stop either service.

## Verification contract

Unit tests use a fake tethering backend and cover:

- initial baseline suppression;
- duplicate suppression and ON/OFF transitions;
- no synthetic OFF on unregister;
- callback generation invalidation after restart or mode change;
- API unsupported and registration-permission failure paths;
- dispatch through `AutomationServiceController` with the captured `HOTSPOT` generation.

Physical API 36 testing must still enable HOTSPOT mode in NavOnWeb, verify the persistent
notification, and toggle Wi-Fi tethering from system Settings. Confirm one start/stop action per
real transition, then separately exercise ordinary process reclaim, force-stop, reboot, and package
replacement to verify the documented lifecycle boundaries.
