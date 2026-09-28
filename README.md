# Battery Nag

A tiny Android utility that warns you when the battery falls below 30%.

## Behavior
- Starts warning at 30%.
- Beeps and posts a high-priority notification.
- Warning interval becomes shorter as battery percentage decreases.
- Stops when the phone is charging.
- In-app snooze buttons: 1–5 hours.
- Handles battery state changes and reboot/Android background behavior without continuous polling.

## Build remotely
1. Create a GitHub repository.
2. Upload this project.
3. GitHub Actions will build the APK from `.github/workflows/build-apk.yml`.
4. Open the repository's Actions tab.
5. Download the `BatteryNag-debug-apk` artifact from the completed run.

This first build uses the Android debug signing key so it can be installed directly for personal use. For a private sideloaded utility, this is sufficient for testing. A release keystore can be added later if desired.
