# Development

DuoDash is a native Android app using Java Views. Its application ID is `dev.nova.deck`.

## Build

Requires JDK 17 or newer and Android SDK Platform 35. Set the SDK path in your local `local.properties` or `ANDROID_HOME`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Keep the same signing key and increase `versionCode` when shipping updates. Signing keys, pairing files and build output must stay outside Git.

To update a test device without clearing its settings:

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

The native test APK can be compiled with `./gradlew assembleDebugAndroidTest`. Compilation does not run device tests. Instrumentation can temporarily interrupt Accessibility, so confirm DuoDash reconnects afterward.

## Structure

| Path | Purpose |
| --- | --- |
| `app/src/main/java/dev/nova/deck` | Dashboard, setup, profiles and services |
| `app/src/main/java/dev/nova/deck/bridge` | System-control helper |
| `app/src/test` | JVM tests |
| `app/src/androidTest` | Device checks |
| `scripts` | Paired-device USB recovery and tests |
| `app/src/main/assets` | Dependency notices and license texts |
| `third_party` | Vendored SPAKE2 source and license |

## System controls

The app pairs with Android's Wireless debugging service and starts a shell helper. Pairing keys and its session token stay in private app storage. The helper checks the caller UID and token and accepts a fixed set of commands. It communicates locally, so a running helper does not require Wi-Fi. Reboot ends the helper process; saved pairing can be reused to restore it.

Helper health is checked separately from status collection. A failed telemetry request must not reset the saved layout or be treated as lost pairing.

## Displays and input

The dashboard layout is shared across displays. Output-specific settings are documented in [Display profiles](output-profiles.md).

Settings render inside the existing overlay. Touch entry preserves the underlying app's keyboard target. Controller entry requests window focus. Touch only disables controller navigation while retaining shortcut capture and the show/hide shortcut.

Retroid hat-axis D-pad events bypass the accessibility key filter. A temporary focus lease requests an already-visible task on the dashboard's display. If the firmware ignores task focus, a tap outside the screen bounds is used as a focus signal, following Retroid's own implementation. No tap inside another app or persistent vendor setting is used. Returning focus to an external app remains unreliable and must not be described as fixed.

## Captures and inspection

Handheld-only overlays are excluded from Android screen captures and mirroring. External overlays must remain included: Android 13's exclusion flag also hides them from physical external outputs. DuoDash's screenshot feature draws its own overlay into the saved image when Include DuoDash is enabled.

Debug builds expose a bounded view tree for their own UI:

```sh
adb -s SERIAL shell dumpsys activity service dev.nova.deck/.OverlayService ui
```

Input text is redacted. Do not use global window-view dumps on the Nova; they have caused WindowManager hangs during testing. A normal screen capture alone cannot establish whether an excluded overlay is present.
