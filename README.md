# SSB Login Helper

English | [日本語](README.ja.md)

SSB Login Helper is an Android app that assists with login flows in SSBPro (Soliton SecureBrowser Pro). It encrypts and stores a login ID and password on the device, then performs one accessibility-driven automation run only after the user explicitly taps a launch button.

## Scope

This app is tailored to one specific organization's SSBPro connection environment. It is not a general-purpose SSBPro automation tool, and the current code works as-is only with that environment.

The code contains environment-specific checks for the post-authentication screen, shared bookmarks, destination login fields and buttons, and successful-login screens. Supporting another organization or connection environment requires inspecting the actual screens and workflow, then changing the state transitions and detection rules in the source.

## Features

- Mobile: signs in to SSBPro, then signs in to the mobile site.
- PC: signs in to SSBPro, opens the PC site from shared bookmarks, and signs in.
- Mail: signs in to SSBPro, opens the `事務処理用PCメール` shared bookmark, and signs in.
- Selecting PC or Mail skips the mobile-site login.
- On slower connections, PC login waits for the page to stop changing and revalidates the entered credentials before tapping the button.
- If reconnection produces multiple certificate warnings with identical text, each warning window is handled once.
- Credentials are encrypted with an AES-256/GCM key held by Android Keystore.
- Each automation run must be started explicitly by the user and stops after success, failure, or timeout.

## Requirements

- Android 12 (API 31) or later
- SSBPro package: `jp.co.soliton.securebrowserpro`
- Tested devices: Pixel 7 Pro and Lenovo IdeaPad Duet Gen 9 (ChromeOS)
- SSBPro installed and its connection configured
- Android Studio and the ability to inspect the source and build an APK

Prebuilt APKs are not distributed.

## Build

For an APK you intend to keep using, create a signed release APK in Android Studio.

1. For an update, increment `versionCode` in `app/build.gradle.kts` and update the user-facing `versionName`.
2. Select **Build > Generate Signed App Bundle or APK**.
3. Select **APK**, continue, and choose the `app` module.
4. Select your keystore, key alias, and passwords. For a first build, use **Create new** to create a keystore and signing key.
5. Select the output location and `release` build type. Unless you have a specific compatibility requirement, keep Android Studio's default APK signature versions.
6. Select **Create**.

Keep the signing key and keystore passwords secure. Every update must use the same key; an APK signed with a different key cannot replace the installed app.

The current version values are:

```kotlin
// app/build.gradle.kts
defaultConfig {
    versionCode = 2
    versionName = "1.1"
}
```

For temporary testing, select the `debug` build variant and use **Build > Generate Bundle(s) / APK(s) > Generate APK(s)**. The debug APK is signed automatically with the Android SDK debug key and is normally written to `app/build/outputs/apk/debug/app-debug.apk`.

Command-line checks use the included Gradle wrapper:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

On macOS or Linux, use `./gradlew` instead of `.\gradlew.bat`.

## Install on an Android Phone

1. Copy the signed APK to the phone.
2. Open it from a file manager.
3. If prompted, allow that file manager to install unknown apps.
4. Follow the installation prompts.

An update signed with the same key can be installed in the same way. For development, you can also use USB debugging and ADB:

```text
adb install path/to/app.apk
```

## Install on a Chromebook

Google's documented method for enabling ADB debugging from the Linux development environment applies to Chromebooks released in 2020 or later. The setting may be unavailable on some models or managed devices.

1. Enable the Linux development environment in ChromeOS settings.
2. Open **Develop Android apps** in the Linux settings and enable ADB debugging. A restart and confirmation of the warning are required.
3. Copy the signed APK to **Linux files**.
4. Install ADB in the Linux terminal.

```text
sudo apt install adb
```

5. Connect from the Linux environment to Android and approve the debugging prompt.

```text
adb connect arc
```

6. Install the APK.

```text
adb install ~/app-release.apk
```

Replace the filename or path as needed. To update an installation signed with the same key, use `adb install -r ~/app-release.apk`.

Reference: [Android Developers — Prepare the development environment](https://developer.android.com/develop/devices/chromeos/learn/development-environment)

### Window size on Chromebook

If the Android app window has a fixed size, select the resizable option from the app's title bar. This is a ChromeOS display setting.

## Initial Setup and Use

1. Open SSB Login Helper.
2. Enter and save the login ID and password.
3. Tap the button that opens Accessibility settings.
4. Find and enable **SSB Login Helper**, review Android's warning, and approve it.
5. Return to the app and tap **スマホ版を開く**, **PC版を開く**, or **メールを開く**.

The accessibility service observes SSBPro's UI, fills the stored credentials into recognized fields, and activates the expected controls. It does not run continuously on a schedule: a pending request is created by a button tap and consumed once by the service.

To change or remove stored credentials, use the corresponding controls in the app. Disable the service from Android Accessibility settings when it is no longer needed.

## Security

- Credentials are encrypted using an AES-256/GCM key stored in Android Keystore.
- Credentials and pending automation requests are excluded from cloud backup and device-to-device transfer.
- On regular Android devices, screenshots and previews in the recent-apps screen are blocked while the credential screen is visible.
- On ChromeOS, screen-capture blocking is not enabled because it can interfere with the virtual keyboard.
- Signing keys, APKs, and device-specific configuration files are excluded from Git.
- An accessibility service can inspect and interact with on-screen content. Enable this service only after reviewing the source and Android's permission warning.

## Limitations and Disclaimer

This is an unofficial, environment-specific tool and is not affiliated with or endorsed by Soliton Systems K.K. It has no UI for configuring another organization's destinations.

Changes to SSBPro or the destination sites—including text, view IDs, or screen layouts—may break automation and require source changes. Review your organization's rules before using the app, and use it at your own risk.

## Tests

Run the local unit tests with:

```powershell
.\gradlew.bat test
```

GitHub Actions runs the same Gradle test task for pushes and pull requests. Instrumented tests require an Android device or emulator and are not part of this workflow.

## Related articles

- [SSBProのログイン操作をワンタップにするAndroidアプリ「SSBLoginHelper」を作った (Qiita, Japanese)](https://qiita.com/shiva9ro/items/95baf4d98abb0e5cdb5d)

## License

[MIT License](LICENSE)
