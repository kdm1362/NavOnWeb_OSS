# Projection density

NavOnWeb stores an Android Auto UI density for each projection profile. Density changes interface scale; it does not change encoded resolution, WebRTC bitrate, browser authorization, or access level.

## Defaults and range

| Profile | Recommended density |
|---|---:|
| Basic 800×480 | 140 DPI |
| HD 1280×720 | 220 DPI |
| Full HD 1920×1080 | 320 DPI |

The accepted range is 72 through 320 DPI, inclusive. Resetting a profile removes its saved override and restores the current recommendation.

## Application path

1. The Android settings screen validates and saves a whole-number value for the selected profile.
2. Missing, corrupt, or out-of-range values fall back to that profile's recommendation.
3. The effective value is validated again before service discovery.
4. Android Auto receives the density with the active resolution and margins.

The browser supplies only bounded viewport geometry. It cannot provide or override density.

Basic profile portrait mode uses the Android Auto 720×1280 protocol frame. Its configured landscape density is scaled against the 220-DPI portrait baseline to keep the apparent interface size consistent. HD and Full HD use their configured density in either orientation.

## Runtime behavior

Changing the density of the active profile reconnects the Android Auto runtime from service discovery so the new layout can take effect. The HTTP server, pairing state, and browser page remain available. Editing an inactive profile only saves the value for the next time that profile is selected.

Rapid accepted changes are coalesced. Small viewport fluctuations are also filtered by the margin hysteresis described in [PROJECTION_PROFILES.md](PROJECTION_PROFILES.md).
