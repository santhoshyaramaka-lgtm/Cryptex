# Cryptex — Project Status & Handover Document
**Last Updated:** May 25, 2026
**App Name:** Cryptex
**Current Stable Version:** v23.0 (versionCode 23) — open
**APK Location:** `Cryptex_Key/Cryptex-v21.0-release.apk` (stable)

---

## 1. What Is This App?

**Cryptex** is a local, offline, encrypted personal vault / password manager for Android.
- All data is stored on-device using `EncryptedSharedPreferences` (AES-256-GCM).
- No internet permission. No cloud sync. No analytics.
- Supports 7 entry types (including Checklist), backup/restore with password encryption, PIN lock, auto-lock, biometric unlock, archive, and security question recovery.

---

## 2. Project Structure

```
Cryptex/
├── app/
│   ├── build.gradle                  ← version, signing, dependencies
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cryptex/app/
│       │   ├── BaseActivity.java
│       │   ├── Entry.java
│       │   ├── EntryType.java
│       │   ├── EntryAdapter.java
│       │   ├── StorageHelper.java
│       │   ├── BackupCrypto.java
│       │   ├── ChecklistItem.java          ← v19: checklist item model
│       │   ├── PinActivity.java
│       │   ├── ForgotPinActivity.java
│       │   ├── MainActivity.java
│       │   ├── TypeListActivity.java
│       │   ├── DetailActivity.java
│       │   ├── FilterBottomSheet.java
│       │   ├── TypePickerBottomSheet.java
│       │   └── SettingsActivity.java
│       └── res/
│           ├── layout/               ← all XML layouts
│           ├── drawable/             ← icons, backgrounds
│           └── values/
│               ├── strings.xml
│               ├── colors.xml
│               └── themes.xml
├── gradle/
├── Cryptex_Key/
│   ├── cryptex_release.jks           ← release keystore
│   └── Cryptex-v17.0-release.apk    ← last stable APK
├── .vscode/
│   └── settings.json
├── local.properties                  ← SDK path (tracked)
└── build_latest.txt
```

---

## 3. Build Configuration

| Setting | Value |
|---|---|
| `compileSdk` | 34 |
| `minSdk` | 23 |
| `targetSdk` | 34 |
| `versionCode` | 19 |
| `versionName` | "19.0" |
| `minifyEnabled` | **false** (must stay false — see note) |
| Keystore | `Cryptex_Key/cryptex_release.jks` |
| Key alias | `cryptex_key` |

> ⚠️ **IMPORTANT — Why minifyEnabled = false:**
> R8 minifier crashes on Windows when the project path contains spaces
> (`C:\Android Application\...`). R8 splits the path at the space and throws
> `NoSuchFileException: base.jar`. Keep `minifyEnabled false` unless the
> project is moved to a path without spaces.

### Safe Build Command (always use this sequence):
```powershell
.\gradlew.bat --stop
.\gradlew.bat assembleRelease
```
> Only add `Remove-Item "app\build" -Recurse -Force` between stop and build if the previous build failed/was interrupted.

### Auto-copy
After `assembleRelease`, the APK is automatically copied to `Cryptex_Key/Cryptex-v{versionName}-release.apk`.

---

## 4. Dependencies

```gradle
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.security:security-crypto:1.1.0-alpha06'
implementation 'androidx.recyclerview:recyclerview:1.3.2'
implementation 'androidx.activity:activity:1.8.2'
```

---

## 5. Data Model

### Entry
Each entry has:
- `id` — UUID string (unique)
- `type` — one of 7 type constants (see below)
- `field1` – `field7` — generic string slots; meaning depends on type
- `field7` is **always Notes** (multiline) for every non-checklist type
- `checklistItems` — list of `ChecklistItem` objects (only used by `checklist` type)

### ChecklistItem (`ChecklistItem.java`)
| Field | Type | Description |
|---|---|---|
| `id` | String (UUID) | Unique ID per item |
| `text` | String | Item label |
| `checked` | Boolean | Whether item is ticked |

### Entry Types (`EntryType.java`)

| Constant | Display Name | Emoji | field1 | field2 | field3 | field4 | field5 | field6 | field7 |
|---|---|---|---|---|---|---|---|---|---|
| `website` | Website / App | 🌐 | Title | URL | Username | Password* | — | — | Notes |
| `card` | Card | 💳 | Card Name | Cardholder | Card No* | Expiry | CVV* | PIN* | Notes |
| `bank` | Bank Details | 🏦 | Bank Name | Acct Holder | Acct No* | IFSC | Branch | Customer ID* | Notes |
| `personal` | Personal Info | 👤 | Title | Full Name | ID Number* | Date of Birth | — | — | Notes |
| `pin` | PIN / Code | 🔐 | Title | PIN/Code* | — | — | — | — | Notes |
| `note` | Note | 📝 | Title | — | — | — | — | — | Notes |
| `checklist` | Checklist | ☑️ | Title (field1) | — | — | — | — | — | — |

`*` = secret field (masked by default, eye-toggle to reveal)
Checklist items are stored in the separate `checklistItems` list, not in field slots.

### Storage
- All entries stored as JSON in `EncryptedSharedPreferences` under key `entries`.
- PIN stored under key `pin`.
- Falls back to plain `SharedPreferences` if encryption init fails (silent).

---

## 6. Features Completed (v1.0 → v9.0)

### Core (Early versions)
- [x] PIN setup and verification (4-digit)
- [x] 6 entry types with type-specific field labels
- [x] Add / Edit / Delete entries
- [x] View mode with secret field masking + eye toggle
- [x] Copy field value to clipboard
- [x] Share entry as plain text (with warning dialog)
- [x] Home screen tile grid (2-column, per-type entry count)
- [x] Global search across all fields
- [x] Per-type list with search + sort (A→Z, Z→A, Default)
- [x] Multi-select delete in TypeListActivity
- [x] Legacy entry migration (old `title/username/password` format → new `field1–field7`)

### v7.0
- [x] **PIN attempt limit** — 3 wrong attempts triggers lockout
- [x] **PIN lockout screen** — keypad disabled, locked overlay shown
- [x] **Forgot PIN flow** — security question → answer → set new PIN → clears all lock state
- [x] **Security Question setup** — 3 custom questions, stored answer (case-insensitive, trimmed)
- [x] **Auto-lock** — configurable timeout (Off / 10s / 30s / 1min / 5min); checks on `onResume` of `MainActivity`, `TypeListActivity`, `PinActivity`
- [x] **Encrypted backup export** — AES-256-GCM, PBKDF2 200k iterations, `.msb` file format, SAF `ACTION_CREATE_DOCUMENT` with filename `cryptex_backup.msb`
- [x] **Update Backup** — re-export using stored password to a new file via `ACTION_CREATE_DOCUMENT`; card only shows after first export
- [x] **Import backup** — password dialog → decrypt → merge (skip duplicate IDs)
- [x] **Change PIN** — current PIN → new PIN → confirm flow in Settings
- [x] **Dead code cleanup** — removed `CategoriesActivity.java`, `FilterBottomSheet.java`, `TypePickerBottomSheet.java`

### v8.0
- [x] **Card subtitle fix** — Card list now shows Cardholder Name (`field2`) instead of masked card number
- [x] **Default sort = A→Z** — Every category list opens A→Z by default; sort button toggles A→Z ↔ Z→A (2 modes only)
- [x] **Last modified timestamp** — `updatedAt` (long) added to `Entry`; set on every save in `DetailActivity`; shown bottom-right of every entry card (`HH:mm` for today / `d MMM` for this year / `d MMM yy` for older)
- [x] **Timestamp for old & imported entries** — Entries with no `updatedAt` (pre-v8 or v7 backup imports) auto-stamped with current time on first load or import; covers normal, legacy-format, and imported entries
- [x] **Favourites / Pin to top** — `isFavourite` (boolean) + `pinnedAt` (long) added to `Entry`; `★/☆` star icon on every card; tap to toggle instantly; starred entries float to top sorted by **most recently starred first** (`pinnedAt` descending); regular entries remain A→Z or Z→A
- [x] **Re-star always goes to top** — `pinnedAt` is set to `now` on every star-ON tap and cleared to `0` on star-OFF; unstarring then re-starring an entry always brings it back to position 1 in the pinned group
- [x] **Single combined sort comparator** — One atomic `Collections.sort()` replaces two separate sort calls; guarantees consistent pinned ordering across all 6 categories
- [x] **Version bump** — `versionCode 8`, `versionName "8.0"`, `app_version` string updated

### v10.0 ✅ STABLE — Closed April 12, 2026
- [x] **App icon redesign** — Galaxy spiral vector icon (purple→magenta→pink gradient spiral, gold 8-point star, amber planet, cosmic dots) replacing the default launcher icon; pure white adaptive background
- [x] **Save action bar fix** — "Keep Editing" and "Discard" buttons in the bottom popup were appearing greyed out (used `text_secondary` = `#555555`); changed both icon tint and label color to `text_primary` (`#000000`) so all three buttons look equally active
- [x] **Attachment support** — Attach one file (any type) per entry; max 5 MB
  - `attachmentName` + `attachmentData` (Base64, NO_WRAP) fields added to `Entry.java`
  - `StorageHelper` serializes/deserializes both fields (default `""`)
  - Fully encrypted inside `.msb` backup — imported/exported automatically
  - `📎` indicator on entry card (`item_entry.xml` + `EntryAdapter.java`) when `hasAttachment()` is true
  - Attachment row in `DetailActivity` — single line: label | filename+size | open/attach btn | remove btn
  - VIEW mode: filename + size shown; Open button fires `ACTION_VIEW` via `FileProvider`; Remove hidden
  - EDIT mode: Attach button (file picker); Remove button (✕, confirms then saves immediately)
  - File picked in one stream pass; size validated against 5 MB limit before encoding
  - Size warning shown for files ≥ 3 MB
  - `FileProvider` configured in `AndroidManifest.xml` + `res/xml/file_paths.xml`
  - New drawables: `ic_attachment.xml`, `ic_save.xml`, `ic_close.xml`
- [x] **No-Save-button UX** — Save button removed from `DetailActivity`
  - Back in EDIT mode with changes → inline `saveActionBar` slides up at the bottom
  - Bar has 3 icon+label columns: 💾 Save | ✏ Keep Editing | ✕ Discard
  - Second Back press dismisses the bar (returns to editing)
  - Back with no changes → goes to LIST silently (no bar shown)
  - Save and Discard both go straight to LIST — no intermediate VIEW mode stop
- [x] **Code cleanup & optimisations**
  - Dead `resetAllReveals()` method removed
  - Attachment row views cached as fields (eliminates 4× `findViewById` per refresh)
  - File read consolidated to one stream pass
  - Stale Javadoc and comments updated

---

## 7. Activity & Flow Map

```
PinActivity  (LAUNCHER)
    │
    ├─ First run → PIN setup (set → confirm → MainActivity)
    ├─ Normal → PIN verify (correct → MainActivity)
    ├─ 3 fails → lockout screen (Forgot PIN? → ForgotPinActivity)
    └─ Locked → locked overlay (Forgot PIN? → ForgotPinActivity)

ForgotPinActivity
    ├─ Step 1: Security Q&A (wrong → retry, correct → Step 2)
    └─ Step 2: Set new PIN → confirm → clears lock → MainActivity (CLEAR_TASK)

MainActivity
    ├─ Tile grid (6 type tiles with entry counts)
    ├─ Global search (RecyclerView across all types)
    ├─ onResume: auto-lock check → PinActivity if expired
    └─ Settings button → SettingsActivity

TypeListActivity  (receives entry_type extra)
    ├─ Entry list for one type, search, sort
    ├─ Long press → multi-select → bulk delete
    ├─ FAB → add new entry (DetailActivity, entry_type mode)
    └─ onResume: auto-lock check → PinActivity if expired

DetailActivity  (receives entry_id OR entry_type extra)
    ├─ VIEW mode  → read-only, masked secrets, copy, share, delete, ✏ edit button
    │               attachment row: filename+size, Open button, no Remove
    └─ EDIT mode  → editable fields, attachment row with Attach+Remove
                    Back (no changes) → LIST silently
                    Back (has changes) → saveActionBar: Save | Keep Editing | Discard → LIST

SettingsActivity
    ├─ Import Backup  (SAF picker → password → decrypt → merge)
    ├─ Export Backup  (password → confirm → SAF picker → encrypt → save)
    ├─ Export as PDF  (password → confirm → generate → share sheet)
    ├─ Update Backup  (reuses stored password → SAF picker → overwrite)
    ├─ Auto-Backup    (toggle — shown only after first export)
    ├─ Auto-lock      (picker: Off/10s/30s/1min/5min)
    ├─ Biometric Unlock (toggle — PIN-verified enable, hardware check)
    ├─ Change PIN
    └─ Security Question (pick question → enter answer → save)
```

---

## 8. Backup File Format (`.msb`)

```
Offset  Size    Content
0       4       Magic header: 'M' 'S' 'B' 'K'
4       1       Version byte (currently = 1)
5       16      PBKDF2 salt (random per export)
21      12      AES-GCM IV / nonce (random per export)
33      N       AES-256-GCM ciphertext + 16-byte auth tag
```

- Key derivation: `PBKDF2WithHmacSHA256`, 200,000 iterations, 256-bit key
- Encryption: `AES/GCM/NoPadding`, 128-bit auth tag
- Wrong password → `WrongPasswordException` (GCM auth tag mismatch)
- Bad magic/version → `InvalidFileException`

---

## 9. Security Questions (hardcoded)

Defined in `ForgotPinActivity.QUESTIONS[]`:
1. "What is your planet?"
2. "How big is the universe?"
3. "Don't forget smiling"

Answer stored as `trim().toLowerCase()` in `EncryptedSharedPreferences`.

---

## 10. Known Build Failure Causes & Fixes

| Failure | Cause | Fix |
|---|---|---|
| `Premature end of file` on XML in `packaged_res/` | Corrupt cached build file | `Remove-Item "app\build" -Recurse -Force` then rebuild |
| `mergeDexRelease: classes.dex used by another process` | Previous Gradle process still running | `.\gradlew.bat --stop` then rebuild |
| `resource string/xyz not found` in AAPT | Missing string in `strings.xml` | Add missing string to `strings.xml` |
| `cannot find symbol` in Java compile | Dead Activity files with broken references | Delete the dead `.java` file if not in Manifest |
| R8 `NoSuchFileException: base.jar` | `minifyEnabled=true` + spaces in path | Keep `minifyEnabled false` |
| App crashes after PIN (`ClassCastException`) | Java field type doesn't match XML view type | Match Java field type exactly to XML type |

---

## 11. StorageHelper Keys Reference

| Key constant | Type | Purpose |
|---|---|---|
| `entries` | String (JSON) | All entries |
| `pin` | String | 4-digit PIN |
| `pin_attempts` | Int | Failed PIN attempt count |
| `pin_locked` | Boolean | Whether PIN is locked out |
| `security_q` | Int | Security question index (0–2) |
| `security_a` | String | Security answer (trimmed, lowercase) |
| `autolock` | Int | Auto-lock timeout in seconds (0 = off) |
| `bg_time` | Long | Background timestamp (millis) for auto-lock |
| `forced_lock` | Boolean | Set to `true` on screen-off; cleared after PIN shown; triggers lock regardless of timeout |
| `backup_pass` | String | Last used backup password |
| `last_export_time` | Long | Timestamp of last successful export |
| `biometric_enabled` | Boolean | Whether biometric unlock is active |

### Entry JSON fields (v19.0)
Each entry in the `entries` JSON array includes:

| JSON key | Type | Default | Purpose |
|---|---|---|---|
| `id` | String | — | UUID |
| `type` | String | `website` | One of 7 type constants |
| `field1`–`field7` | String | `""` | Entry data fields |
| `updatedAt` | Long | `0` | Last modified millis; auto-stamped to `now` if `0` on load |
| `createdAt` | Long | `0` | Set once on creation; falls back to `updatedAt` for pre-v12 entries |
| `favourite` | Boolean | `false` | Whether entry is pinned to top |
| `pinnedAt` | Long | `0` | When star was last tapped ON (millis); `0` when unstarred |
| `attachmentName` | String | `""` | Original filename; empty = no attachment |
| `attachmentData` | String | `""` | Base64-encoded file bytes (NO_WRAP); empty = no attachment |
| `archived` | Boolean | `false` | Whether entry is archived (hidden from normal lists) |
| `checklistItems` | JSON Array | `[]` | List of `{id, text, checked}` objects; only used for `checklist` type |

---

## 12. V11.0 — Completed Features ✅ STABLE — Closed April 13, 2026

- [x] **App icon redesign** — Vault Door icon (charcoal background, steel blue radial gradient door, teal accent rings, 8 bolts, keyhole, handle bar)
- [x] **Launcher PNG fallback** — mipmap PNGs generated and placed in all density folders (ldpi → xxxhdpi) for Android 7.1 and below
- [x] **ArrayList import fix** — `import java.util.ArrayList` added to `DetailActivity.java`

---

## 12b. V12.0 — Completed Features ✅ STABLE — Closed April 13, 2026

- [x] **Long-press to copy** — Removed copy icon button in view mode; long-press any field value to copy to clipboard; removed copy button in edit mode entirely
- [x] **Input box contrast** — Improved EditText background and border visibility in both light and dark mode (`input_bg.xml`, `colors.xml`, `values-night/colors.xml`)
- [x] **Creation date** — `createdAt` field added to `Entry`; displayed in detail view; handled in `StorageHelper` serialization with fallback to `updatedAt` for pre-v12 entries
- [x] **Screenshot prevention** — `FLAG_SECURE` added to all activities (blocks screenshots and app switcher preview)
- [x] **Clipboard auto-clear** — Clipboard cleared automatically 30 seconds after a copy in `DetailActivity`
- [x] **Auto-lock gap fix** — All activities (`MainActivity`, `TypeListActivity`, `DetailActivity`, `SettingsActivity`) save/check background timestamp on pause/resume; introduced `BaseActivity`
- [x] **BaseActivity** — Common base class with a screen-off `BroadcastReceiver`; all activities extend it instead of `AppCompatActivity` directly

---

## 12c. V13.0 — Completed Features ✅ STABLE — Closed April 13, 2026

### Screen-lock bypass fix (root cause & solution)

**Root cause (3 bugs working together):**
1. `PinActivity` extends `BaseActivity`. When the device was locked *while the PIN screen was showing*, `BaseActivity.onPause()` ran on `PinActivity` and saved `System.currentTimeMillis()` as `bg_time` — overwriting the forced-lock signal set by the screen-off receiver.
2. The screen-off receiver in `BaseActivity` only set the forced-lock value when `getAutoLockTimeout() > 0`. With auto-lock disabled the device lock did nothing at all.
3. The auto-lock check used `bg_time = 1L` as a sentinel — a fragile trick that `PinActivity.onPause()` destroyed on every device lock.

**Fix applied:**
- Added a dedicated `forced_lock` Boolean key to `StorageHelper` (separate from `bg_time`).
- Screen-off receiver sets `forced_lock = true` **unconditionally** — regardless of timeout setting.
- `BaseActivity.onPause()` saves the timestamp for all activities **except** `PinActivity` (`instanceof` check). `PinActivity` never touches the timestamp.
- All app activities (`MainActivity`, `TypeListActivity`, `DetailActivity`, `SettingsActivity`) check **both** `forced_lock == true` OR `(timeout > 0 && elapsed > timeout)` on `onResume()`.
- After launching `PinActivity`, both `forced_lock` is cleared and `bg_time` is reset to `0`.

- [x] **Screen-lock bypass fix** — PIN always required after device unlock regardless of auto-lock setting

---

## 12d. V14.0 — Completed Features ✅ STABLE — Closed April 13, 2026

### Double PIN screen fix (root cause & solution)

**Root cause:**
After a correct PIN was entered, `goToMain()` in `PinActivity` launched `MainActivity` but did **not** clear `forced_lock` or `bg_time` first. `MainActivity.onResume()` then ran the lock check with a stale `bg_time` (saved by `MainActivity.onPause()` at the moment the screen turned off). If the auto-lock timeout had elapsed during the time the phone was locked, `timeoutExpired = true` → a **second PIN screen** was immediately shown right after a correct entry.

**Fix applied:**
- `PinActivity.goToMain()` now calls `storage.setForcedLock(false)` and `storage.setBackgroundTimestamp(0)` **before** launching `MainActivity`.
- This guarantees `MainActivity.onResume()` always sees a clean state after a successful PIN entry — no stale `bg_time`, no stale `forced_lock`.

- [x] **Double PIN screen fix** — entering correct PIN always goes straight to the app, never shows PIN screen twice

---

## 12f. V15.0 — Completed Features ✅ STABLE — Closed April 13, 2026

- [x] **Auto-backup on app close** — `BaseActivity.onStop()` / `onUserLeaveHint()` triggers backup when user leaves the app
- [x] **`backup_pending` flag** — set on every save, delete, bulk delete, favourite toggle; cleared after successful backup
- [x] **"Update Backup" uses `ACTION_OPEN_DOCUMENT`** — reliable URI permissions across all OEM devices (Samsung, Xiaomi, etc.)

---

## 12g. V16.0 — Completed Features ✅ STABLE — Closed April 13, 2026

- [x] **Dynamic PIN screen title** — shows "Set New PIN" on first install, "Confirm PIN" on confirmation, hidden on normal login
- [x] **App version string** — updated to "Version 16.0" in `strings.xml`

---

## 12h. V17.0 — Completed Features ✅ STABLE — Closed April 13, 2026

### Export as PDF (Password Protected)
- [x] **PDF export** — Settings → "Export as PDF" card
- [x] **Warning dialog** — shown before password entry; explains plain-text risk
- [x] **Password protection** — AES-128 via `PdfBox-Android 2.0.27.0`; both owner + user password set so PDF requires password to open
- [x] **PDF layout** — grouped by entry type, section headers, all fields printed, auto page-break
- [x] **ASCII sanitisation** — non-printable/emoji characters replaced with `?` for PDF Type1 font safety
- [x] **Direct share sheet** — after generation, Android share sheet opens immediately (share, save to Drive/Files, print, email — all in one)
- [x] **New dependency** — `com.tom-roush:pdfbox-android:2.0.27.0`

### Biometric Unlock (Optional)
- [x] **Biometric toggle in Settings** — "Biometric Unlock" card with ON/OFF switch
- [x] **PIN verification to enable** — user must enter correct PIN before biometric is activated
- [x] **Hardware check** — toggle disabled with message if no fingerprint enrolled
- [x] **Clean biometric prompt** — `BIOMETRIC_WEAK` only, zero-width space negative button → only fingerprint icon + app name shown, no Cancel/Enter Password
- [x] **Auto-trigger on login** — biometric prompt shown automatically 300ms after PIN screen opens (login mode only)
- [x] **"Use Fingerprint" button** — shown on PIN screen below keypad when biometric is enabled
- [x] **PIN always fallback** — dismissing biometric returns to PIN keypad seamlessly
- [x] **`biometric_enabled` key** — stored in `EncryptedSharedPreferences`
- [x] **New dependency** — `androidx.biometric:biometric:1.1.0`

### Bug Fixes
- [x] **Settings scrollable** — wrapped cards in `ScrollView`; top bar stays fixed; all settings accessible on small screens
- [x] **Fingerprint drawable** — `ic_fingerprint.xml` vector added
- [x] **`file_paths.xml`** — `pdf_export/` cache path added for FileProvider PDF share

---

## 12i. V18.0 — Completed Features ✅ STABLE — Closed May 18, 2026

### Resume Where You Left Off
- [x] **Back stack preserved on auto-lock** — `checkAndHandleAutoLock()` no longer uses `FLAG_ACTIVITY_CLEAR_TASK`; PinActivity is pushed on top of the existing back stack
- [x] **`resume_on_success` extra** — passed to PinActivity when lock triggers mid-session; `goToMain()` calls `finish()` instead of launching `MainActivity`, so the user resumes the exact screen they were on
- [x] **Cold start unaffected** — first install / PIN setup still navigates to `MainActivity` (no `resume_on_success` flag)
- [x] **Forgot PIN reset unaffected** — still uses `CLEAR_TASK` → `MainActivity` as before
- [x] Works across all screens: `MainActivity`, `TypeListActivity`, `DetailActivity`, `SettingsActivity`

### Search Highlight
- [x] **Highlight matched text** — every case-insensitive match of the search query is highlighted in entry title and subtitle on all list screens
- [x] **`setSearchQuery()` API on `EntryAdapter`** — callers pass query before `notifyDataSetChanged()`
- [x] **`highlight()` helper** — uses `SpannableString` + `BackgroundColorSpan` + `ForegroundColorSpan`
- [x] **Light mode** — amber background `#FFE082` + forced black text `#000000`
- [x] **Dark mode** — same amber background + forced black text (no dissolving)
- [x] **Highlight clears** when search box is emptied
- [x] Applied in `MainActivity` (global search) and `TypeListActivity` (per-type search)
- [x] `search_highlight` + `search_highlight_text` colors added to both `colors.xml` and `values-night/colors.xml`

### Icon Upgrade (Vault Door V18)
- [x] **All elements scaled inward** — fit inside Android adaptive icon safe zone (61%); no clipping on circle or rounded-square launchers
- [x] **Radial gradient background** — deep `#1C2128` centre → `#0D1117` edge (replaces flat `#212121`)
- [x] **Double glow rings** — outer and inner accent rings each get a wide transparent glow layer for neon effect
- [x] **Gold diagonal bolts** — 4 diagonal bolts changed from dull steel `#546E7A` → gold `#FFD700` with amber stroke `#FFA000`
- [x] **White keyhole with teal glow** — keyhole circle and slot changed from plain teal → white fill + `#00E5FF` stroke
- [x] **Icon centre alignment fix** — all ring paths corrected from wrong Y-centre to `M512,512` so all 3 rings, bolts and keyhole are perfectly centred on device

### Version Bump
- [x] `versionCode 18`, `versionName "18.0"`, `app_version` string updated to `"Version 18.0"`

---

## 12j. V19.0 — Completed Features ✅ STABLE — Closed May 19, 2026

### Archive / Unarchive
- [x] **Archive button** — top-bar `📦` icon in `DetailActivity` (VIEW mode only); tapping shows confirm dialog "Archive this entry?"
- [x] **Unarchive** — same button; when entry is already archived shows amber tint and dialog "Unarchive this entry?"
- [x] **Auto-unstar on archive** — if a favourited entry is archived, `isFavourite` is cleared and `pinnedAt` reset to `0` automatically
- [x] **Archived entries hidden** — `EntryAdapter` and `TypeListActivity` filter out `isArchived = true` entries from all normal list views and tile counts
- [x] **`isArchived` field** — Boolean added to `Entry.java`; serialised as `"archived"` in JSON (default `false`); backward-compatible (missing key → `false`)
- [x] **`archived` key** — stored in encrypted backup (`.msb`) and PDF export; round-trips correctly on import

### Checklist Entry Type
- [x] **New `checklist` type** — added to `EntryType.java` with emoji `☑️`, display name "Checklist", tile on `MainActivity`
- [x] **`ChecklistItem` model** — `id` (UUID), `text` (String), `checked` (Boolean); `create(text)` factory
- [x] **`checklistItems` list on `Entry`** — `List<ChecklistItem>` field; serialised as `"checklistItems"` JSON array; fallback UUID on missing `id` during import
- [x] **`DetailActivity` checklist mode** — when type is `checklist`, standard field rows are hidden and `checklistContainer` is shown instead
- [x] **New checklist title dialog** — on first open of a new checklist, a name dialog appears; entry is persisted immediately on confirm (no separate Save tap needed)
- [x] **Unchecked / checked split** — items rendered in two dynamic `LinearLayout`s (`checklistUncheckedItems` / `checklistCheckedItems`); checked items appear below a divider
- [x] **Item row layout** (`item_checklist_row.xml`) — checkbox, `TextView` (view state), `EditText` (edit state, `GONE` by default), delete `✕` button
- [x] **Inline item editing** — tap any item text to switch it to an `EditText`; focus-loss auto-saves; clearing text deletes the item; Enter key saves and closes keyboard
- [x] **Add item row** — persistent row at bottom of unchecked list:
  - **Idle**: shows `+` icon + "Add item" hint text
  - **Active** (focused): `+` is replaced by a disabled preview checkbox; ✕ cancel button appears; a secondary `+ Add item` row appears below for continuous entry
  - Enter commits the item and keeps keyboard open for rapid multi-item entry
  - ✕ cancel clears text, removes focus, returns to idle state
  - Tapping secondary row re-focuses the add `EditText`
- [x] **Progress indicator** — `"X of Y done"` label above items; hidden when list is empty
- [x] **Empty state** — `"No items yet"` label shown when list has no items
- [x] **Checkbox toggle** — updates model and re-renders instantly; encrypted write done on background thread (no UI lag)
- [x] **Clear completed button** — shown in divider row when checked items exist; confirm dialog → removes all checked items → saves + re-renders
- [x] **Share checklist** — formatted share text: title, unchecked items (☐), checked items (☑), progress line, "Shared from Cryptex" footer
- [x] **Back press safety** — `onBackPressed()` commits any in-progress `etAddItem` text and saves any in-progress inline item edit before `finish()`
- [x] **Re-entrancy guard** — `checklistRendering` boolean prevents `removeAllViews()` focus-loss events from triggering a recursive `renderChecklist()` call
- [x] **Background save race fix** — `saveInBackground()` now serialises to JSON on the main thread and writes the immutable string on the background thread; eliminates shared-mutable-state race on ART
- [x] **`saveEntriesJson(String)` API** — added to `StorageHelper` for direct pre-serialised JSON write

### Version Bump
- [x] `versionCode 19`, `versionName "19.0"`, `app_version` string updated to `"Version 19.0"`

---

## 12k. V20.0 — Completed Features ✅ STABLE — Closed May 22, 2026

### ✨ New Features

#### PDF Export — Category Picker
- [x] **Category selection step** — added between warning dialog and password dialog
- [x] **Only shows types with entries** — empty types not listed
- [x] **Entry count per category** — e.g. `Website / App  (5)`
- [x] **All categories checked by default** — user unchecks what they don't want
- [x] **Empty selection guard** — if all unchecked, shows toast and keeps dialog open
- [x] **Checklist excluded from PDF** — checklist type never appears in picker
- [x] **Archived entries excluded** — only active entries passed to PDF

#### Camera Attachment — Auto Compress
- [x] **Silent auto-compression** — if captured photo exceeds 5 MB, automatically compressed
- [x] **Progressive quality** — tries 80% → 60% → 40% JPEG quality until under 5 MB
- [x] **No toast, no interruption** — photo attaches seamlessly
- [x] **Fallback error** — only shows "File Too Large" if all compression attempts fail (extremely rare)
- [x] **Camera temp file cleanup** — temp `.jpg` file deleted immediately after reading bytes (cache leak fix)

### 🔴 Bugs Fixed

| # | Bug | File |
|---|---|---|
| 1 | PDF category picker showed blank dialog when all active entries were checklist type | `SettingsActivity.java` |
| 2 | Camera temp photos never deleted — silent cache storage leak | `DetailActivity.java` |
| 3 | Star button crash — `getAdapterPosition()` returning `-1` on fast tap during list refresh | `EntryAdapter.java` |
| 4 | Encryption fallback — silent plain text storage with no user warning | `StorageHelper.java` + `BaseActivity.java` |
| 5 | Security answer dialog — closed with empty answer saved (validation bypassed) | `SettingsActivity.java` |
| 6 | Change PIN dialogs — dismissed silently by tapping outside (new PIN lost) | `SettingsActivity.java` |
| 7 | Import — backup card not refreshed after successful import | `SettingsActivity.java` |

### 🟡 Performance Fixes

| # | Fix | File |
|---|---|---|
| 1 | `SimpleDateFormat` replaced with `ThreadLocal<SimpleDateFormat>` for thread safety | `EntryAdapter.java` |
| 2 | PDF export strips attachment Base64 data from RAM before generating — not needed in PDF | `SettingsActivity.java` |

### 🔄 Backup Format Rename
- [x] **`.msb` → `.cxb`** — backup format renamed from `.msb` (MS Backup) to `.cxb` (Cryptex Backup)
- [x] **New exports** — always saved as `cryptex_backup.cxb`
- [x] **Import blocks non-`.cxb` files** — clear error dialog: *"Only .cxb backup files are supported"*
- [x] **Old `.msb` files blocked** — users must re-export from an older version if needed
- [x] Updated: `SettingsActivity.java`, `strings.xml`, `BackupCrypto.java`, docs

### 🎨 UI / UX Fixes
- [x] **Switch toggle colours** — OFF = grey (`#CCCCCC`), ON = blue (`#2196F3`), thumb always white
- [x] **Applied to both switches** — Auto-backup and Biometric Unlock toggles
- [x] **Color state lists** — `color/switch_track.xml` + `color/switch_thumb.xml`
- [x] **Security answer error** — shows inline field error instead of toast; dialog stays open
- [x] **Change PIN** — `setCancelable(false)` on new PIN + confirm dialogs

### Version Bump
- [x] `versionCode 20`, `versionName "20.0"`, `app_version` string updated to `"Version 20.0"`

---

## 12l. V21.0 — Completed Features ✅ STABLE — Closed May 25, 2026

### 🔴 Bug Fixed

#### Attachment Viewer — Stale Cache (Wrong File Shown)
- **Issue:** Opening Entry 1's attachment, then opening Entry 2's attachment always showed Entry 1's file.
- **Root Cause:** Both entries wrote their attachment to the same path `cache/attachments/<filename>`. If two entries had attachments with the same filename (e.g. `photo.jpg`), they shared an identical `FileProvider` URI. External viewer apps (image viewers, PDF readers) cache content by URI — so they always served the first entry's cached file.
- **Fix:** Each entry now writes its attachment to a unique per-entry subdirectory: `cache/attachments/<entryId>/<filename>`. Every entry has a distinct URI — no collision, no stale cache.
- **Files changed:** `DetailActivity.java` (`openAttachment()` and `shareEntry()`)
- **Also fixed in share flow** — the same collision existed when sharing an entry with an attachment.

### Version Bump
- [x] `versionCode 21`, `versionName "21.0"`

---

## 13. Version History

| Version | Date | Key Features Added |
|---|---|---|
| v4.0 | — | Initial release — PIN, 6 entry types, add/edit/delete |
| v5.0 | — | Legacy migration, multi-select delete, sort, global search |
| v6.0 | — | Encrypted backup export/import (AES-256-GCM, `.msb` format) |
| v7.0 | — | PIN attempt lock, Forgot PIN, Security Q&A, Auto-lock, Update Backup, Change PIN, dead code cleanup |
| v8.0 | — | Card subtitle fix, default A→Z sort, last-modified timestamp, old/imported entry timestamping, favourites/pin-to-top with `pinnedAt` ordering, single combined sort comparator |
| v9.0 | Apr 12, 2026 | Attachment support (Base64, 5MB, FileProvider, 📎 card indicator), no-Save-button UX (inline action bar), code cleanup & optimisations |
| v10.0 | Apr 12, 2026 | Galaxy spiral app icon redesign, Save action bar fix (Keep Editing / Discard visibility) |
| v11.0 | Apr 13, 2026 | Vault Door icon (charcoal/steel blue/teal), mipmap PNG fallbacks for all densities, ArrayList import fix |
| v12.0 | Apr 13, 2026 | Long-press to copy (no copy icon), input box contrast, `createdAt` field, screenshot prevention (`FLAG_SECURE`), clipboard auto-clear (30s), auto-lock gap fix, `BaseActivity` with screen-off receiver |
| v13.0 | Apr 13, 2026 | Screen-lock bypass fix: dedicated `forced_lock` flag, `PinActivity` never saves timestamp, all app activities check `forced_lock OR timeout`, screen-off always locks unconditionally |
| v14.0 | Apr 13, 2026 | Double PIN screen fix: `PinActivity.goToMain()` clears `forced_lock` and resets `bg_time=0` before launching MainActivity, so a correct PIN always lands on the app directly |
| v15.0 | Apr 13, 2026 | Auto-backup on app close (`onStop`/`onUserLeaveHint` in `BaseActivity`), `backup_pending` flag on all data-changing actions, "Update Backup" switched to `ACTION_OPEN_DOCUMENT` for reliable URI permissions |
| v16.0 | Apr 13, 2026 | Dynamic PIN screen title ("Set New PIN" / "Confirm PIN" / hidden on login), app version string updated to 16.0 |
| v17.0 | Apr 13, 2026 | Password-protected PDF export (PdfBox-Android AES-128, direct share sheet), Biometric unlock (optional fingerprint, Settings toggle, PIN-verified enable, clean prompt — no buttons), Settings scroll fix, PDF password bug fix |
| v18.0 | Apr 13, 2026 | **App renamed MS → Cryptex**: applicationId `com.cryptex.app`, package `com.cryptex.app`, FileProvider authority, app name, biometric prompt title, PDF export title, backup filename, share footer, PDF filename all updated. Removed stale `com/ms/app/` source folder (root cause of build failure). Settings card order reordered: Import first, Export/PDF/UpdateBackup/AutoBackup, then Security section (Auto-lock, Biometric, Change PIN, Security Question). All old-name comments and strings cleaned up. |
| v18.0 (repo init) | May 15, 2026 | **Git repository initialised**: Stale `com/ms/app/` duplicate source folder deleted. New release keystore created (`Cryptex_Key/cryptex_release.jks`, alias `cryptex_key`). Key folder renamed `ms_Key/` → `Cryptex_Key/`. Old `ms_release.jks` deleted. `build.gradle` updated with new keystore path, alias, and auto-copy target. `local.properties` added and tracked. `.vscode/` tracked. Build verified successful. |
| v18.0 (features) | May 18, 2026 | **Resume where you left off**: back stack preserved on auto-lock — PIN screen pushed on top, `finish()` on success resumes exact previous screen. **Search highlight**: matched text highlighted amber (`#FFE082`) + forced black text in both global and per-type search, light + dark mode safe. **Icon upgrade**: all vault door elements scaled inside Android safe zone, radial gradient background, double glow rings, gold diagonal bolts, white keyhole with teal glow, all ring paths centred correctly at `(512,512)`. `versionCode 18`, `versionName "18.0"`. |
| v19.0 | May 19, 2026 | **Archive / Unarchive**: archive button in detail view; amber tint when archived; auto-unstar on archive; archived entries hidden from all lists and tile counts; `isArchived` field added to `Entry`, serialised in JSON + backup. **Checklist type**: new `checklist` entry type (☑️); `ChecklistItem` model (id/text/checked); `checklistItems` list on `Entry`; dedicated checklist UI in `DetailActivity` (unchecked/checked split, inline add/edit/delete, progress indicator, empty state, clear completed, share format); add-row UX (idle `+` → active checkbox+cancel+secondary row, Enter keeps keyboard, ✕ cancels); re-entrancy guard; background save race fix (`saveEntriesJson` API on `StorageHelper`); back-press safety. `versionCode 19`, `versionName "19.0"`. |
| v20.0 | May 22, 2026 | **Camera attachment** (photo or file), **sort dialog** (Date/Name, direction toggle, per-type persistence), **archive toggle in top bar** (3 states), **checklist add row tap fix**, bug fixes, `versionCode 20`, `versionName "20.0"`. |
| v21.0 | May 25, 2026 | **Attachment viewer bug fix** — opening a second entry's attachment always showed the first entry's file (stale URI cache in external viewer apps). Fixed by writing each entry's cached attachment into a unique per-entry subdirectory (`cache/attachments/<entryId>/filename`) so every entry has a distinct `FileProvider` URI. Same fix applied to the share flow. `versionCode 21`, `versionName "21.0"`. |
