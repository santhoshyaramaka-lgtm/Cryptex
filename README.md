# Cryptex
Personal Secure Vault
# Cryptex

A local, offline, encrypted personal vault for Android.

## Features
- AES-256-GCM encryption via EncryptedSharedPreferences
- 6 entry types (passwords, cards, notes, etc.)
- PIN lock with auto-lock and security question recovery
- Encrypted backup & restore
- No internet, no cloud, no analytics — fully on-device

## Current Version
v17.0 (stable) — v18.0 in development

## Build
Open in Android Studio and build via Gradle.
Release signing config is in `app/build.gradle`.
Keystore: `ms_Key/ms_release.jks`

## Tech
- Java (Android)
- EncryptedSharedPreferences
- Min SDK: (your min SDK)
- Target SDK: (your target SDK)