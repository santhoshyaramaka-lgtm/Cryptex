# Cryptex
**Personal Secure Vault — Android**

A local, offline, encrypted personal vault / password manager for Android.

## Features
- AES-256-GCM encryption via `EncryptedSharedPreferences` — everything on-device
- 8 entry types: Website/App, Card, Bank Details, Personal Info, PIN/Code, Others, Note, Checklist
- PIN lock with auto-lock timeouts and biometric (fingerprint) unlock
- 3-wrong-attempts lockout + security question recovery (Forgot PIN)
- Screenshot prevention (`FLAG_SECURE`) across all screens
- Encrypted backup & restore (`.cxb` — AES-256-GCM, PBKDF2 200k iterations)
- Password-protected PDF export with category selection
- Favourites / pin-to-top with per-entry star toggle
- Archive entries — hide without deleting; unarchive any time
- Checklists — dedicated checklist entry type with inline add/edit/check/delete and progress indicator
- Manage Categories — show/hide any category tile from home screen (data never deleted)
- Search highlight — matched text highlighted amber in global and per-type search
- Clipboard auto-clear (30 seconds after copy)
- Auto-backup on app close (when data has changed)
- No internet permission. No cloud sync. No analytics.

## Current Version
**Date-based versioning** (`yyyyMMdd`) — auto-set at build time. No manual bumps needed.

## Build
```powershell
.\gradlew.bat --stop
.\gradlew.bat assembleRelease
```
- APK auto-copied to `Cryptex_Key/Cryptex-v{date}-release.apk`
- Also copy to `G:\My Drive\Cryptex\` after every build
- Keystore: `Cryptex_Key/cryptex_release.jks` | Alias: `cryptex_key`

> ⚠️ Keep `minifyEnabled false` — R8 crashes when the project path contains spaces.

## Tech
- Java (Android)
- `EncryptedSharedPreferences` (AES-256-GCM)
- `androidx.security:security-crypto:1.1.0-alpha06`
- `com.tom-roush:pdfbox-android:2.0.27.0`
- `androidx.biometric:biometric:1.1.0`
- Min SDK: 23 | Target SDK: 35