# Cannsheet Mobile

Cannsheet Mobile is a personal Android app for recording cannabis purchases and consumption. It keeps data in a local Room database, queues changes while offline, and synchronizes with the existing Google Sheets backend through Google Apps Script.

This project is the side-by-side mobile build of Cannsheet:

- Application ID: `com.noamv.cannsheet.mobile`
- App label: `Cannsheet Mobile`
- Minimum Android version: Android 7.0 (API 24)
- Target SDK: Android 16 / API 36

The Kotlin namespace remains `com.example` so the working application source does not need to be reorganized.

## Open in Android Studio

1. Install a current Android Studio release with JDK 17 or newer.
2. Install Android SDK Platform 36.1 when prompted.
3. Open this repository as an existing project.
4. Let Gradle sync, then run the `app` configuration on a device or emulator.

No Gemini, Firebase, or `.env` configuration is required.

## Build a debug APK

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Android creates and uses the normal local debug signing key automatically.

## Backend

The app uses the same Google Apps Script deployment as the original Cannsheet installation. The current endpoint is the default in `app/src/main/java/com/example/ui/CannsheetViewModel.kt` and can also be changed from the app's Settings screen for the current session.

`backend_additions.gs` contains the companion Apps Script handlers expected by the Android client. Review its `FORM_ID` and sheet layout before deploying it to a different Google Sheets backend.

## Release signing

Release builds are left unsigned unless all four environment variables are set:

- `KEYSTORE_PATH`
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Keystores and local credentials are intentionally excluded from version control.
