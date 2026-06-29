# Cryptex — v20260623 Changelog

**Date:** June 23, 2026
**Branch:** Cryptex-30
**APK:** `Cryptex-v20260623-release.apk`

---

## Changes in This Version

### 1. Custom Categories — Removed

The custom category feature (which allowed users to create their own entry types with a name, emoji, and custom fields) has been fully removed. The app now has exactly 8 fixed built-in types: Website, Card, Bank, Personal, PIN, Others, Note, and Checklist.

The built-in **Others** type is kept — it already supports per-record custom fields and covers the same use case as custom categories without the overhead of managing user-defined types.

---

### 2. Attachments — Removed

File attachment support has been removed. Entries can no longer have files attached to them. The backup format is also simplified — it now contains only the entries data, with no attachment blobs.

---

### 3. Onboarding — Removed

The 4-step onboarding screen shown on first install has been removed. The app now opens directly on the PIN screen on every launch. A simple one-time warning dialog appears the first time the home screen opens, reminding the user to set up their security question and export a backup before storing sensitive data.

---

### 4. Versioning — Now Auto Date-Based

The version number is no longer set manually. It is automatically computed from the build date in `YYYYMMDD` format. Building on any given day produces a version like `20260623`. No human intervention needed — just run the build.

---

### 5. Security Questions — Updated

The two preset security questions have been replaced with simpler, more personal ones:
- "What makes you happy?"
- "What is your favorite food?"

The third option ("Write my own question…") remains unchanged.

---

### 6. Change PIN — Now a Single Dialog

Previously, changing the PIN required going through three separate dialogs one after another — current PIN, then new PIN, then confirm PIN. This felt disjointed and the keyboard kept closing and reopening between steps.

It is now a single dialog with all three fields together. The user fills them all in at once and taps Save. Errors are shown inline if anything is wrong.

---

### 7. Keyboard Auto-Open Fix

In the Change PIN and Biometric enable dialogs, the keyboard was not reliably opening when the dialog appeared. It sometimes required a manual tap on the input field.

This is fixed. The keyboard now opens automatically as soon as any of these dialogs appear, without requiring a tap.

---

### 8. Version Number in Settings — Now Dynamic

The version shown in the Settings screen was previously a hardcoded string that had to be updated manually with every release. It now reads the actual installed version at runtime, so it always stays accurate without any extra steps.

---

### 9. Duplicate Title Check — Removed

Previously, saving an entry was blocked if another entry in the same category had the same title. This was too restrictive — for example, you might have two Gmail accounts under Website, or two SBI accounts under Bank.

The check has been removed. You can now create as many entries with the same title as you need within any category.

---

### 10. Note Body — Text Selectable in View Mode

When viewing a Note, long-pressing the body text previously copied the entire note to clipboard immediately with no way to select just part of it.

Now the Note body supports native Android text selection — long-press shows drag handles so you can highlight any word, line, or section and copy only what you need. All other entry types (Website, Card, Bank, etc.) keep the original whole-field long-press copy behaviour unchanged.
