# Hotspot automation

NavOnWeb can start or stop projection in response to a Wi-Fi hotspot state change on Android 16/API 36.

## Platform support

The implementation uses Android's public `TetheringManager.registerTetheringEventCallback` API and observes only whether Wi-Fi tethering is active. It does not read the hotspot SSID, password, or connected-client identity, and it does not turn tethering on or off.

Android 8 through Android 15 do not provide an equivalent public API to ordinary applications. Hotspot automation is therefore unavailable on those versions.

## Behavior

Automation modes are mutually exclusive:

- None
- Bluetooth
- Hotspot

When Hotspot is selected, the first callback establishes the current baseline and does not start or stop projection. Only a later transition has an effect:

- Hotspot off to on: request projection start.
- Hotspot on to off: request projection stop, including a session started manually.

Duplicate events and callbacks left over from a previous mode are ignored. Changing the automation mode does not synthesize a hotspot-off event.

## Background monitor

Hotspot mode uses a visible foreground service while the mode is enabled. The persistent notification indicates that monitoring is active. After an ordinary process restart, Android may recreate the monitor; after reboot or package replacement, NavOnWeb requests restoration only if Hotspot mode was already selected.

Force-stopping the application disables its services and broadcast receivers until the user opens it again. Android or device-vendor background policies may also delay or reject restoration.

The projection session itself uses a connected-device foreground service. Foreground service status improves continuity but does not guarantee that Android will keep either service running indefinitely.

## Permissions and privacy

Hotspot observation uses network-state access and the foreground-service declarations required by the Android version. NavOnWeb does not require privileged tethering permissions, hidden APIs, root access, or polling.
