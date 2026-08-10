# Cryptex — Agent Instructions

Offline encrypted personal vault for Android (Java). No internet, no cloud, no analytics.

## Build & Deploy

```powershell
.\gradlew.bat --stop          # always stop daemon first
.\gradlew.bat assembleRelease # or: clean assembleRelease if incremental fails
```

- APK auto-copies to `Cryptex_Key/Cryptex-v{yyyyMMdd}-release.apk`
- **Always** also copy to `G:\My Drive\Cryptex\` after every build
- Version is **date-based** — auto-set at build time. Never bump manually.

> ⚠️ `minifyEnabled = false` — MUST stay false. R8 crashes on Windows paths with spaces.

## Workflow Rules (from `Instructions` file)

1. **Never write code without user approval** — always explain what you understood, state the plan, wait for "yes"
2. **Always confirm understanding** before acting
3. **Strict scope** — changes must not impact any other use case. One bug/feature = one isolated change.
4. **Audit before moving on** — after each task, verify the change is correct before proceeding
5. **After every build** — copy APK to `G:\My Drive\Cryptex\`

## Project Structure

See [PROJECT_STATUS.md](PROJECT_STATUS.md) for full directory layout.

Key files:
- `app/src/main/java/com/cryptex/app/DetailActivity.java` — add/edit/view entry (most feature work happens here)
- `app/src/main/java/com/cryptex/app/EntryType.java` — field labels, secret flags, entry type definitions
- `app/src/main/java/com/cryptex/app/StorageHelper.java` — encrypted prefs read/write
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — adaptive icon foreground (1024×1024 viewport)
- `app/src/main/res/drawable/ic_launcher_background.xml` — adaptive icon background (108×108 viewport)

## Architecture

- **Activity flow:** `OnboardingActivity` → `PinActivity` → `MainActivity` → `TypeListActivity` → `DetailActivity`
- **Storage:** `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore)
- **Backup:** ZIP `.cxb` — `entries.json` + per-attachment `.enc` blobs, AES-256-GCM PBKDF2 200k iterations
- **Entry types:** website, card, bank, personal, pin, note, checklist + custom (`custom_{uuid}`)
- **Fields:** each entry has field1–field7; index is 0-based in Java (field1 = index 0)

## Key Conventions

### Entry Fields (CARD type)
| Index | Field | Input type |
|---|---|---|
| 0 | Card Name | Text, ALL CAPS |
| 1 | Cardholder Name | Text |
| 2 | Card Number | `TYPE_CLASS_PHONE` (allows auto-space), max 23 chars, auto-formats `XXXX XXXX XXXX XXXX` |
| 3 | Expiry | `TYPE_CLASS_PHONE` (allows auto-slash), hint `MM/YY`, max 5 chars, auto-formats `MM/YY` |
| 4 | CVV | `TYPE_CLASS_NUMBER`, max 3 digits |
| 5 | PIN | `TYPE_CLASS_NUMBER` |
| 6 | Notes | Multiline |

### Numeric Keyboard Fields
Use `TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD` for hidden numeric fields.
Use `TYPE_CLASS_PHONE` when auto-inserted characters (spaces, slashes) are needed.
**Critical:** `setRevealOpen(int i)` in `DetailActivity` resets inputType for secret fields — always update it when adding new numeric fields.

### Icon (Adaptive)
- Background: `ic_launcher_background.xml` — viewport 108×108
- Foreground: `ic_launcher_foreground.xml` — viewport **1024×1024** (not 108)
- Android vectors do **not** support `<text>` elements — use `<path>` for any lettering
- Font glyph paths can be extracted with `fonttools` (`extract_glyph.py` in project root)

### Changelog
Add a `Changelog/v{yyyyMMdd}.md` for every release. See [Changelog/](Changelog/) for examples.

## Common Pitfalls

- `setRevealOpen()` is called for all secret fields on entering edit mode — it resets `inputType`. Any numeric field fix must also update this method.
- `TYPE_CLASS_NUMBER` blocks programmatic insertion of non-digit chars (spaces, `/`). Use `TYPE_CLASS_PHONE` when auto-formatting requires non-digit characters.
- Incremental build cache can cause `dex` file lock errors — run `.\gradlew.bat --stop` first.
- `minifyEnabled` must remain `false` — do not change it.
