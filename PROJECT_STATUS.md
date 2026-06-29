# Cryptex — Project Status & Handover Document

**Last Updated:** June 23, 2026 (build 2)
**Current Version:** v20260623 (date-based, auto-set at build time) — ✅ STABLE
**APK:** `Cryptex_Key/Cryptex-v20260623-release.apk` | `G:\My Drive\Cryptex\Cryptex-v20260623-release.apk`
**Branch:** `Cryptex-30`

---

## 1. What Is This App?

**Cryptex** is a local, offline, encrypted personal vault and password manager for Android.

- All data stored on-device using `EncryptedSharedPreferences` (AES-256-GCM)
- No internet permission. No cloud sync. No analytics.
- 8 entry types: Website, Card, Bank, Personal, PIN, Others, Note, Checklist
- Features: PIN lock, auto-lock, biometric unlock, archive, security question recovery, encrypted backup, PDF export, Manage Categories (show/hide tiles)

---

## 2. Project Structure

```
Cryptex/
├── app/
│   ├── build.gradle                  ← version (auto date), signing, dependencies
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cryptex/app/
│       │   ├── BaseActivity.java               ← base class; auto-lock, backup
│       │   ├── PinActivity.java                ← LAUNCHER; PIN login + setup
│       │   ├── MainActivity.java               ← home screen tile grid
│       │   ├── TypeListActivity.java           ← per-type entry list
│       │   ├── DetailActivity.java             ← add / edit / view entry
│       │   ├── SettingsActivity.java           ← all settings
│       │   ├── ManageCategoriesActivity.java   ← show/hide home screen tiles
│       │   ├── ForgotPinActivity.java          ← security question → reset PIN
│       │   ├── Entry.java                      ← entry data model
│       │   ├── EntryType.java                  ← 8 built-in types + field labels
│       │   ├── EntryAdapter.java               ← RecyclerView adapter
│       │   ├── StorageHelper.java              ← encrypted prefs read/write
│       │   ├── BackupCrypto.java               ← ZIP .cxb encrypt/decrypt
│       │   ├── ChecklistItem.java              ← checklist item model
│       │   ├── CustomField.java                ← per-record field def (Others type)
│       │   └── FieldManagerDialog.java         ← "Manage Fields" UI (Others type)
│       └── res/
│           ├── layout/               ← XML layouts
│           ├── drawable/             ← icons, backgrounds
│           └── values/
│               ├── strings.xml
│               ├── colors.xml
│               └── themes.xml
├── Cryptex_Key/
│   ├── cryptex_release.jks           ← release keystore
│   └── Cryptex-v20260623-release.apk ← latest stable APK
├── local.properties                  ← SDK path (tracked)
└── build_latest.txt
```

---

## 3. Build Configuration

| Setting | Value |
|---|---|
| `compileSdk` | 35 |
| `minSdk` | 23 |
| `targetSdk` | 35 |
| `versionCode` | `new Date().format('yyyyMMdd').toInteger()` — auto at build time |
| `versionName` | `new Date().format('yyyyMMdd')` — auto at build time |
| `minifyEnabled` | **false** — must never change (see warning below) |
| Keystore | `Cryptex_Key/cryptex_release.jks` |
| Key alias | `cryptex_key` |

> ⚠️ **Why minifyEnabled = false:** R8 crashes on Windows when the project path contains spaces (`C:\Android Application\...`) — throws `NoSuchFileException: base.jar`. Keep `minifyEnabled false` permanently unless the project is moved to a space-free path.

### Build Commands

```powershell
.\gradlew.bat --stop
.\gradlew.bat assembleRelease
```

- APK auto-copies to `Cryptex_Key/Cryptex-v{date}-release.apk`
- Then manually copy to `G:\My Drive\Cryptex\`
- Only add `Remove-Item "app\build" -Recurse -Force` between the two commands if the previous build failed or was interrupted

---

## 4. Dependencies

```gradle
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.security:security-crypto:1.1.0-alpha06'
implementation 'androidx.recyclerview:recyclerview:1.3.2'
implementation 'androidx.activity:activity:1.8.2'
implementation 'com.tom-roush:pdfbox-android:2.0.27.0'
implementation 'androidx.biometric:biometric:1.1.0'
```

---

## 5. Data Model

### Entry Fields

Each entry has:
- `id` — UUID string
- `type` — one of 8 built-in type constants
- `field1` – `field7` — generic string slots; meaning depends on type
- `field7` is always Notes (multiline) for every non-checklist type
- `checklistItems` — list of `ChecklistItem` objects (checklist type only)
- `recordFields` — list of `CustomField` (Others type only — per-record field definitions)
- `recordIncludeNotes` — boolean (Others type only)

### Entry Types

| Constant | Display | Emoji | field1 | field2 | field3 | field4 | field5 | field6 | field7 |
|---|---|---|---|---|---|---|---|---|---|
| `website` | Website | 🌐 | Title | URL | Username | Password* | — | — | Notes |
| `card` | Card | 💳 | Card Name | Cardholder | Card No* | Expiry | CVV* | PIN* | Notes |
| `bank` | Bank Details | 🏦 | Bank Name | Acct Holder | Acct No* | IFSC | Branch | Customer ID* | Notes |
| `personal` | Personal Info | 👤 | Title | Full Name | ID Number* | Date of Birth | — | — | Notes |
| `pin` | PIN / Code | 🔐 | Title | PIN/Code* | — | — | — | — | Notes |
| `others` | Others | 📦 | Title | — | — | — | — | — | per-record fields |
| `note` | Note | 📝 | Title | — | — | — | — | — | Notes |
| `checklist` | Checklist | ☑️ | Title | — | — | — | — | — | — |

`*` = secret field (masked by default, eye-toggle to reveal)

**Others type specifics:**
- `isPerRecordFields(type)` returns `true` for `others` only
- Field labels and secret flags are defined per-record via `FieldManagerDialog`
- `CustomField.java` and `FieldManagerDialog.java` are kept for this purpose

### Entry JSON Keys

| Key | Type | Default | Purpose |
|---|---|---|---|
| `id` | String | — | UUID |
| `type` | String | `website` | One of 8 type constants |
| `field1`–`field7` | String | `""` | Entry data |
| `updatedAt` | Long | `0` | Last modified millis |
| `createdAt` | Long | `0` | Creation millis |
| `favourite` | Boolean | `false` | Pinned to top |
| `pinnedAt` | Long | `0` | When star was last tapped ON |
| `archived` | Boolean | `false` | Hidden from normal lists |
| `checklistItems` | JSON Array | `[]` | `{id, text, checked}` objects |
| `recordFields` | JSON Array | `[]` | `CustomField` objects (Others type) |
| `recordIncludeNotes` | Boolean | `true` | Whether Notes field shown (Others type) |

### Storage

- All entries: JSON in `EncryptedSharedPreferences` under key `entries`
- PIN: key `pin`
- Falls back to plain `SharedPreferences` if encryption init fails (silent, warning shown)

---

## 6. Activity Flow

```
PinActivity  (LAUNCHER)
    |
    +-- First run       --> PIN setup (set --> confirm --> MainActivity)
    +-- Normal login    --> PIN verify (correct --> MainActivity)
    +-- 3 wrong tries   --> lockout screen (Forgot PIN? --> ForgotPinActivity)
    +-- Locked state    --> locked overlay (Forgot PIN? --> ForgotPinActivity)

ForgotPinActivity
    +-- Step 1: Security Q&A  (wrong --> retry | correct --> Step 2)
    +-- Step 2: Set new PIN   --> confirm --> clears lock --> MainActivity (CLEAR_TASK)

MainActivity
    +-- Tile grid (8 type tiles with entry counts, hidden types excluded)
    +-- Global search (across all types)
    +-- onResume: auto-lock check --> PinActivity if expired
    +-- Settings --> SettingsActivity
    +-- First open: one-time warning dialog (security Q + backup setup)

TypeListActivity  (receives entry_type Intent extra)
    +-- Entry list for one type, search, sort (A-Z / Z-A / Date)
    +-- Long press --> multi-select --> bulk delete
    +-- FAB --> add new entry (DetailActivity in add mode)
    +-- onResume: auto-lock check

DetailActivity  (receives entry_id or entry_type)
    +-- VIEW mode  -- read-only, masked secrets, copy (long-press), share, delete, edit
    +-- EDIT mode  -- editable fields
                      Back (no changes)   --> list silently
                      Back (has changes)  --> saveActionBar: Save | Keep Editing | Discard

SettingsActivity
    +-- Import Backup     (SAF picker --> password --> decrypt --> merge)
    +-- Export Backup     (password --> confirm --> SAF picker --> encrypt --> save)
    +-- Export as PDF     (category picker --> password --> generate --> share sheet)
    +-- Update Backup     (reuses stored password --> SAF picker --> overwrite)
    +-- Auto-Backup       (toggle; shown only after first export)
    +-- Auto-lock         (Off / 10s / 30s / 1min / 5min)
    +-- Biometric Unlock  (toggle; PIN-verified enable, hardware check)
    +-- Manage Categories --> ManageCategoriesActivity
    +-- Change PIN        (single dialog: current + new + confirm, inline validation)
    +-- Security Question (pick question --> enter answer --> save)

ManageCategoriesActivity
    +-- List of all 8 built-in types with Switch toggle per row
        OFF --> hidden from home grid and excluded from search (data untouched)
        ON  --> tile reappears, all entries intact
```

---

## 7. Backup Format (.cxb — ZIP)

The `.cxb` file is a standard ZIP archive containing two entries:

| Entry | Content |
|---|---|
| `salt` | 16-byte PBKDF2 salt (raw bytes) |
| `entries.enc` | AES-256-GCM encrypted JSON of all entries |

- **Key derivation:** `PBKDF2WithHmacSHA256`, 200,000 iterations, 256-bit key
- **Encryption:** `AES/GCM/NoPadding`, random 12-byte IV prepended to ciphertext, 128-bit auth tag
- **Wrong password:** GCM auth tag mismatch → error dialog shown
- **No attachments, no custom categories** — stripped in v20260623

---

## 8. Security Questions

Defined in `ForgotPinActivity.QUESTIONS[]`:

| Index | Question |
|---|---|
| 0 | "What makes you happy?" |
| 1 | "What is your favorite food?" |
| 2 | "Write my own question…" (custom — text stored in `security_q_custom`) |

- `CUSTOM_QUESTION_INDEX = 2`
- Answer stored as `trim().toLowerCase()` in `EncryptedSharedPreferences`
- Custom question text stored as-is (not lowercased) under key `security_q_custom`

---

## 9. StorageHelper Keys Reference

### EncryptedSharedPreferences

| Key | Type | Purpose |
|---|---|---|
| `entries` | String (JSON) | All entries |
| `pin` | String | 4-digit PIN |
| `pin_attempts` | Int | Failed PIN attempt count |
| `pin_locked` | Boolean | Whether PIN is locked out |
| `security_q` | Int | Security question index (0–2) |
| `security_a` | String | Security answer (trimmed, lowercase) |
| `security_q_custom` | String | Custom question text (index 2 only) |
| `autolock` | Int | Auto-lock timeout in seconds (0 = off) |
| `bg_time` | Long | Background timestamp for auto-lock |
| `forced_lock` | Boolean | Set on screen-off; triggers lock regardless of timeout |
| `backup_pass` | String | Last used backup password |
| `backup_uri` | String | Last used backup file URI |
| `last_export_time` | Long | Timestamp of last successful export |
| `biometric_enabled` | Boolean | Whether biometric unlock is active |
| `hidden_tile_types` | String (JSON array) | Type IDs hidden from home screen |

### Plain SharedPreferences (not encrypted)

| File | Key | Purpose |
|---|---|---|
| `cryptex_tips` | `backup_tip_shown` | True after the one-time first-open warning dialog is shown |

---

## 10. Known Build Failures & Fixes

| Failure | Cause | Fix |
|---|---|---|
| `Premature end of file` on XML | Corrupt cached build file | `Remove-Item "app\build" -Recurse -Force` then rebuild |
| `mergeDexRelease: classes.dex used by another process` | Previous Gradle still running | `.\gradlew.bat --stop` then rebuild |
| `resource string/xyz not found` | Missing string in `strings.xml` | Add missing string |
| `cannot find symbol` in Java | Dead file with broken references | Delete the unused `.java` file |
| R8 `NoSuchFileException: base.jar` | `minifyEnabled=true` + spaces in path | Keep `minifyEnabled false` |
| App crashes after PIN (`ClassCastException`) | Java field type mismatch with XML view type | Match Java field type exactly to XML |

---

## 11. Current Java Files (16 total)

| File | Purpose |
|---|---|
| `BaseActivity.java` | Base class; auto-lock check, screen-off receiver, auto-backup |
| `PinActivity.java` | LAUNCHER; PIN entry, setup, lockout overlay, biometric trigger |
| `MainActivity.java` | Home tile grid, global search, first-open warning dialog |
| `TypeListActivity.java` | Per-type entry list, sort, multi-select delete, bulk PDF |
| `DetailActivity.java` | Add / edit / view entry; all type-specific UI; save action bar; Note body text-selectable in view mode |
| `SettingsActivity.java` | All settings cards; Change PIN (single dialog); keyboard helper |
| `ManageCategoriesActivity.java` | Show/hide home screen tiles via switch toggles |
| `ForgotPinActivity.java` | Security Q&A step → new PIN step → clears lock |
| `Entry.java` | Entry model with all fields, serialization helpers |
| `EntryType.java` | 8 built-in type constants, display names, emojis, field labels |
| `EntryAdapter.java` | RecyclerView adapter; star toggle, search highlight, timestamps |
| `StorageHelper.java` | All encrypted prefs read/write; backup URI; hidden types |
| `BackupCrypto.java` | ZIP .cxb encrypt (PBKDF2 + AES-GCM) and decrypt |
| `ChecklistItem.java` | Checklist item model (id, text, checked) |
| `CustomField.java` | Per-record field definition used by the Others type |
| `FieldManagerDialog.java` | "Manage Fields" dialog for adding/editing per-record fields (Others) |

---

## 12. Version History

| Version | Date | Summary |
|---|---|---|
| v4.0–v6.0 | — | PIN, 6 entry types, add/edit/delete, legacy migration, encrypted backup (.msb) |
| v7.0 | — | PIN attempt lockout, Forgot PIN, Security Q&A, auto-lock, Update Backup, Change PIN |
| v8.0 | — | Card subtitle fix, A→Z default sort, last-modified timestamps, favourites/pin-to-top |
| v9.0–v10.0 | Apr 12, 2026 | Single attachment per entry (Base64, 5MB), no-Save-button UX (inline action bar), icon redesign |
| v11.0–v16.0 | Apr 13, 2026 | Vault Door icon, long-press copy, `createdAt`, screenshot prevention, clipboard auto-clear, auto-lock gap fix, `BaseActivity`, screen-lock bypass fix, double-PIN fix, auto-backup on close, dynamic PIN title |
| v17.0 | Apr 13, 2026 | Password-protected PDF export (PdfBox AES-128), biometric unlock (optional fingerprint) |
| v18.0 | May 2026 | App renamed MS → Cryptex, git repo initialised, new keystore, resume-on-auto-lock, search highlight |
| v19.0 | May 19, 2026 | Archive/unarchive entries, Checklist entry type (ChecklistItem model, inline add/edit/check/delete, progress indicator) |
| v20.0 | May 22, 2026 | Camera attachment, PDF category picker, auto-compress photos, sort dialog (Date/Name), various bug fixes, switch toggle colours |
| v21.0–v22.0 | May–Jun 2026 | Attachment viewer stale-cache fix, background-thread save refactor, checklist in-place toggle, RecyclerView animator disabled |
| v23.0–v24.0 | Jun 2026 | "Website" label, multi-attachment support (AttachmentStore, EncryptedFile), ZIP .cxb backup v2, Chrome C icon, PIN screen icon |
| v25.0 | Jun 8, 2026 | API 35 target, 4-step onboarding (Agreement → How It Works → Security Q → Reminder), custom security question, backup tip dialog, rotating PIN taglines, bulk PDF from list, Note type clean UI, attachment UI simplification |
| v26.0 | Jun 9, 2026 | Custom categories (create/edit/delete, custom fields, backup/PDF), slot-aware file picker, export choice dialog (text/files/both) |
| v27.0 | Jun 10, 2026 | Attachment groups (named folders, collapsible), selective share (per-file checkboxes), attachment count raised to 50 |
| v28.0 | Jun 12, 2026 | Custom category dialog overhaul (single field, reorder/mask/remove popup), attachment filename search, grid/list home toggle, duplicate title check |
| v29.0 | Jun 16, 2026 | "Others" built-in type (📦, per-record fields), overflow menu in edit mode, Save → VIEW mode, Manage Categories (show/hide 8 tiles), attachment group bug fixes |
| **v20260623** | **Jun 23, 2026** | **Custom categories removed. Attachments removed. Onboarding removed. Date-based versioning. Security questions updated. Change PIN → single dialog. Keyboard auto-open fix. Dynamic version in Settings. Duplicate title check removed. Note body text-selectable in view mode.** |

---

## 13. v20260623 — What Changed (Latest)

### Removed: Custom Categories
- `CustomCategory.java` deleted
- `EntryType.init()`, `isCustom()`, `findCustom()`, `getAllTypes()` removed
- `ALL_TYPES[]` = 8 built-in types only; `isPerRecordFields()` returns `true` for `others` only
- All 6 custom category dialog methods removed from `MainActivity`
- `loadCustomCategories()`, `saveCustomCategories()`, `exportCustomCategoriesJson()`, `mergeCustomCategories()`, `importCustomCategoriesFromJson()` removed from `StorageHelper`
- `BackupCrypto.encryptZip()` no longer takes `customCategoriesJson` arg — no `custom_categories.enc` in backup ZIP
- **Kept:** `CustomField.java` and `FieldManagerDialog.java` — still used by the built-in Others type

### Removed: Attachments
- `Attachment.java`, `AttachmentStore.java`, `item_attachment_row.xml`, `ic_attachment.xml` deleted
- Multi-attachment UI stripped from `DetailActivity`
- `BaseActivity` auto-backup no longer passes attachment blobs
- Backup ZIP simplified: contains only `salt` + `entries.enc`

### Removed: Onboarding
- `OnboardingActivity.java` and `activity_onboarding.xml` deleted
- All `onboarding_*` strings (~20) removed from `strings.xml`
- `PinActivity` is now the **LAUNCHER** (`exported="true"`)
- All 3 `instanceof OnboardingActivity` guards removed from `BaseActivity`

### Added: First-Open Warning Dialog (replaces onboarding)
- `MainActivity.showBackupTipIfNeeded()` — one-time dialog on first open
- Covers both: security question setup and backup setup
- **Skipped if:** `hasBackupPassword() && hasBackupUri() && getSecurityQuestionIndex() != -1`
- Buttons: **"Go to Settings"** | **"Later"**
- Flag: `backup_tip_shown` in `cryptex_tips` SharedPreferences

### Changed: Versioning — Auto Date-Based
- `app/build.gradle`:
  ```groovy
  versionCode new Date().format('yyyyMMdd').toInteger()
  versionName new Date().format('yyyyMMdd')
  ```
- APK: `Cryptex-v20260623-release.apk`
- No manual version bumps ever needed

### Changed: Security Questions
| # | New question |
|---|---|
| 0 | "What makes you happy?" |
| 1 | "What is your favorite food?" |
| 2 | "Write my own question…" |

### Changed: Change PIN — Single Dialog
**Before:** 3 separate sequential dialogs (keyboard open/close between each)

**After:** One `AlertDialog` with all 3 fields:
1. Current PIN
2. New PIN
3. Confirm New PIN

Inline `setError()` validation on Save tap. Keyboard opens once and stays open.

### Changed: Keyboard Auto-Open Fix
New `showKeyboardFor(AlertDialog, EditText)` helper in `SettingsActivity`:
- `SOFT_INPUT_STATE_ALWAYS_VISIBLE | SOFT_INPUT_ADJUST_RESIZE` on the dialog window
- `InputMethodManager.showSoftInput(et, SHOW_FORCED)` — not ignorable
- `postDelayed(100ms)` — ensures window has focus before keyboard request
- Applied to: Change PIN dialog, Biometric enable dialog

### Changed: Settings Version Display
- Reads at runtime: `PackageManager.getPackageInfo(getPackageName(), 0).versionName`
- No hardcoded `@string/app_version` string — always shows actual build date

### Changed: ManageCategoriesActivity Cleaned
- Uses `EntryType.ALL_TYPES` directly (8 built-ins)
- Removed `EntryType.init()` call — not needed
- No custom type code — shows only 8 built-in toggles

### Removed: Duplicate Title Check
Entries within the same category can now share the same title. For example, two Website entries both named "Gmail" are allowed. The block that prevented saving with a duplicate title has been removed entirely.

### Changed: Note Body — Text Selectable in View Mode
In Note view mode, the body text now supports native Android text selection. Long-pressing shows drag handles — you can highlight any word, line, or paragraph and copy just that part. Previously, long-pressing the Note body always copied the entire text. All other entry types keep the original whole-field long-press copy behaviour.
