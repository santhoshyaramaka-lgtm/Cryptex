# Cryptex — Project Status & Handover Document
**Last Updated:** May 15, 2026
**App Name:** Cryptex
**Current Stable Version:** v17.0 (versionCode 17) — closed ✅
**Next Version:** v18.0 (versionCode 18) — open
**APK Location:** `Cryptex_Key/Cryptex-v17.0-release.apk` (stable)

---

## 1. What Is This App?

**Cryptex** is a local, offline, encrypted personal vault / password manager for Android.
- All data is stored on-device using `EncryptedSharedPreferences` (AES-256-GCM).
- No internet permission. No cloud sync. No analytics.
- Supports 6 entry types, backup/restore with password encryption, PIN lock, auto-lock, and security question recovery.

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
| `versionCode` | 17 |
| `versionName` | "17.0" |
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
- `type` — one of 6 type constants (see below)
- `field1` – `field7` — generic string slots; meaning depends on type
- `field7` is **always Notes** (multiline) for every type

### Entry Types (`EntryType.java`)

| Constant | Display Name | Emoji | field1 | field2 | field3 | field4 | field5 | field6 | field7 |
|---|---|---|---|---|---|---|---|---|---|
| `website` | Website / App | 🌐 | Title | URL | Username | Password* | — | — | Notes |
| `card` | Card | 💳 | Card Name | Cardholder | Card No* | Expiry | CVV* | PIN* | Notes |
| `bank` | Bank Details | 🏦 | Bank Name | Acct Holder | Acct No* | IFSC | Branch | Customer ID* | Notes |
| `personal` | Personal Info | 👤 | Title | Full Name | ID Number* | Date of Birth | — | — | Notes |
| `pin` | PIN / Code | 🔐 | Title | PIN/Code* | — | — | — | — | Notes |
| `note` | Note | 📝 | Title | — | — | — | — | — | Notes |

`*` = secret field (masked by default, eye-toggle to reveal)

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

### Entry JSON fields (v9.0)
Each entry in the `entries` JSON array includes:

| JSON key | Type | Default | Purpose |
|---|---|---|---|
| `id` | String | — | UUID |
| `type` | String | `website` | One of 6 type constants |
| `field1`–`field7` | String | `""` | Entry data fields |
| `updatedAt` | Long | `0` | Last modified millis; auto-stamped to `now` if `0` on load |
| `favourite` | Boolean | `false` | Whether entry is pinned to top |
| `pinnedAt` | Long | `0` | When star was last tapped ON (millis); `0` when unstarred |
| `attachmentName` | String | `""` | Original filename; empty = no attachment |
| `attachmentData` | String | `""` | Base64-encoded file bytes (NO_WRAP); empty = no attachment |

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

## 12i. V18.0 — Candidate Features

> V17.0 is closed and stable. Items below are candidates for v18.0:

- [ ] **Password strength indicator** — Weak / Fair / Strong bar on Website / Card password fields
- [ ] **Search highlight** — highlight matched text in search results
- [ ] **Custom security question** — let user type their own question instead of picking from 3
- [ ] **Dark mode toggle** — manual override (currently follows system DayNight)
- [ ] **Multiple attachments per entry** — currently limited to one file per entry

---

## 12e. V15.0 — Upcoming Features

> V14.0 is closed and stable. Items below are planned for v15.0:

### Candidate features (to be prioritised)
- [ ] **Biometric unlock** — fingerprint / face as alternative to PIN
- [ ] **Password strength indicator** — on Website / Card password fields
- [ ] **Dark mode toggle** — manual override (currently follows system DayNight)
- [ ] **Export to plain text / CSV** — optional, with strong warning dialog
- [ ] **Custom security questions** — let user type their own question
- [ ] **Multiple backup slots** — named backups, not just one file
- [ ] **Search highlight** — highlight matched text in search results
- [ ] **Multiple attachments per entry** — v9 supports one; expand to a list

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
