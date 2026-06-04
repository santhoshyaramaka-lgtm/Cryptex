# Cryptex
**Personal Secure Vault — Android**

A local, offline, encrypted personal vault / password manager for Android.

## Features
- AES-256-GCM encryption via `EncryptedSharedPreferences` — everything on-device
- 7 entry types: Website/App, Card, Bank Details, Personal Info, PIN/Code, Note, **Checklist**
- PIN lock with auto-lock timeouts and biometric (fingerprint) unlock
- 3-wrong-attempts lockout + security question recovery (Forgot PIN)
- Screenshot prevention (`FLAG_SECURE`) across all screens
- Encrypted backup & restore (`.cxb` — AES-256-GCM, PBKDF2 200k iterations)
- Password-protected PDF export with category selection (AES-128, direct share sheet)
- File attachments per entry (any type, up to 5 MB, auto-compressed for camera photos)
- Favourites / pin-to-top with per-entry star toggle
- **Archive entries** — hide without deleting; unarchive any time
- **Checklists** — dedicated checklist entry type with inline add/edit/check/delete and progress indicator
- Search highlight — matched text highlighted amber in global and per-type search
- Clipboard auto-clear (30 seconds after copy)
- Auto-backup on app close (when data has changed)
- No internet permission. No cloud sync. No analytics.

## Current Version
**v23.0** (versionCode 23) — stable

## Build
Open in Android Studio or build via Gradle:
```powershell
.\gradlew.bat --stop
.\gradlew.bat assembleRelease
```
Release APK is auto-copied to `Cryptex_Key/Cryptex-v23.0-release.apk`.
Keystore: `Cryptex_Key/cryptex_release.jks` | Alias: `cryptex_key`

> ⚠️ Keep `minifyEnabled false` — R8 crashes when the project path contains spaces.

## Tech
- Java (Android)
- `EncryptedSharedPreferences` (AES-256-GCM)
- `androidx.security:security-crypto:1.1.0-alpha06`
- `com.tom-roush:pdfbox-android:2.0.27.0`
- `androidx.biometric:biometric:1.1.0`
- Min SDK: 23 | Target SDK: 34