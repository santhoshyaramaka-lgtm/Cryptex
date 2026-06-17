package com.cryptex.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StorageHelper {

    // ── Singleton ─────────────────────────────────────────────────────────────
    // EncryptedSharedPreferences.create() performs AES-256 GCM key derivation
    // via Android Keystore — a slow hardware IPC operation (~100–300 ms).
    // Using a singleton ensures this is done exactly once per app process,
    // not once per activity creation.
    private static volatile StorageHelper instance;

    public static StorageHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (StorageHelper.class) {
                if (instance == null) {
                    instance = new StorageHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private static final String PREFS_NAME  = "ms_secure_prefs";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_PIN     = "pin";

    private final SharedPreferences prefs;
    private final Context context; // kept for AttachmentStore — always applicationContext
    private boolean encryptionFailed = false; // true if EncryptedSharedPreferences init failed
    private boolean attachmentMigrationOccurred = false; // v24: set in entryFromJson when old format is migrated

    private StorageHelper(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences temp;
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            temp = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Encryption unavailable — fall back to plain prefs but flag it
            // so the app can warn the user rather than silently storing plain text
            encryptionFailed = true;
            temp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        prefs = temp;
    }

    /** Returns true if encrypted storage could not be initialised. */
    public boolean isEncryptionFailed() { return encryptionFailed; }

    // ── One-time migrations ───────────────────────────────────────────────────

    private static final String KEY_MIGRATION_TITLE_CAPS = "migration_title_caps_done";

    /**
     * One-time migration: uppercase the title (field1) of every existing entry.
     * Runs once on the first launch after this version is installed, then never again.
     * Call this from the singleton constructor or from MainActivity.onResume()
     * before loadEntries() is used to populate the UI.
     */
    public void migrateTitlesToCaps() {
        if (prefs.getBoolean(KEY_MIGRATION_TITLE_CAPS, false)) return; // already done
        try {
            List<Entry> entries = loadEntries();
            boolean changed = false;
            for (Entry e : entries) {
                String title = e.getField1();
                if (title != null && !title.isEmpty()) {
                    String upper = title.toUpperCase();
                    if (!upper.equals(title)) {
                        e.setFieldByIndex(1, upper);
                        changed = true;
                    }
                }
            }
            if (changed) saveEntries(entries);
        } catch (Exception ignored) {
            // Never crash on migration — worst case titles stay mixed-case
        }
        // Mark done regardless, so we don't retry on every launch even if nothing changed
        prefs.edit().putBoolean(KEY_MIGRATION_TITLE_CAPS, true).apply();
    }

    // ── PIN ───────────────────────────────────────────────────────────────────

    public boolean hasPin() { return prefs.contains(KEY_PIN); }

    public void savePin(String pin) { prefs.edit().putString(KEY_PIN, pin).apply(); }

    public boolean checkPin(String pin) { return pin.equals(prefs.getString(KEY_PIN, "")); }

    // ── SORT MODE (per entry type) ─────────────────────────────────────────────
    // 0 = Date newest first (default), 1 = Date oldest first, 2 = Name A→Z, 3 = Name Z→A

    public int getSortMode(String entryType) {
        return prefs.getInt("sort_mode_" + entryType, 0);
    }

    public void setSortMode(String entryType, int mode) {
        prefs.edit().putInt("sort_mode_" + entryType, mode).apply();
    }

    // ── ENTRIES ───────────────────────────────────────────────────────────────

    public List<Entry> loadEntries() {
        List<Entry> list = new ArrayList<>();
        attachmentMigrationOccurred = false;
        try {
            String json = prefs.getString(KEY_ENTRIES, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(entryFromJson(obj));
            }
        } catch (Exception e) {
            // Return empty list on error
        }
        // v24: persist new format immediately if any entry was migrated from old
        // single-attachment fields — prevents migration running again on next load
        if (attachmentMigrationOccurred) {
            saveEntries(list);
            attachmentMigrationOccurred = false;
        }
        return list;
    }

    public void saveEntries(List<Entry> entries) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) arr.put(entryToJson(e));
            prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply();
        } catch (Exception e) {
            // Silent fail
        }
    }

    /** Saves a pre-serialised JSON string directly — used by background saves to avoid race conditions. */
    public void saveEntriesJson(String json) {
        if (json == null) return;
        prefs.edit().putString(KEY_ENTRIES, json).apply();
    }

    // ── EXPORT / IMPORT ───────────────────────────────────────────────────────

    public String exportToJson(List<Entry> entries) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) arr.put(entryToJson(e));
            return arr.toString(2);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Entry> importFromJson(String json) {
        List<Entry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(entryFromJson(obj));
            }
        } catch (Exception e) {
            return null;
        }
        return list;
    }

    // ── JSON Serialization ────────────────────────────────────────────────────

    private JSONObject entryToJson(Entry e) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("id",       e.getId());
        obj.put("type",     e.getType());
        obj.put("field1",   e.getField1());
        obj.put("field2",   e.getField2());
        obj.put("field3",   e.getField3());
        obj.put("field4",   e.getField4());
        obj.put("field5",   e.getField5());
        obj.put("field6",   e.getField6());
        obj.put("field7",   e.getField7());
        obj.put("updatedAt",       e.getUpdatedAt());
        obj.put("createdAt",       e.getCreatedAt()); // v12
        obj.put("favourite",       e.isFavourite());
        obj.put("pinnedAt",        e.getPinnedAt());
        // v24: attachments list — metadata only, file bytes live in AttachmentStore
        JSONArray attachmentsArr = new JSONArray();
        for (Attachment a : e.getAttachments()) {
            JSONObject aObj = new JSONObject();
            aObj.put("id",       a.getId());
            aObj.put("name",     a.getName());
            aObj.put("mimeType", a.getMimeType());
            aObj.put("size",     a.getSize());
            if (!a.getGroup().isEmpty()) aObj.put("group", a.getGroup()); // v27: omit if empty
            attachmentsArr.put(aObj);
        }
        obj.put("attachments", attachmentsArr);
        obj.put("archived",        e.isArchived()); // v19
        // v20: checklist items
        JSONArray itemsArr = new JSONArray();
        for (ChecklistItem item : e.getChecklistItems()) {
            JSONObject itemObj = new JSONObject();
            itemObj.put("id",      item.getId());
            itemObj.put("text",    item.getText());
            itemObj.put("checked", item.isChecked());
            itemsArr.put(itemObj);
        }
        obj.put("checklistItems", itemsArr);
        // v29: named attachment groups (includes empty groups)
        if (!e.getAttachmentGroups().isEmpty()) {
            JSONArray groupsArr = new JSONArray();
            for (String g : e.getAttachmentGroups()) groupsArr.put(g);
            obj.put("attachmentGroups", groupsArr);
        }
        // v29: per-record field definitions (custom categories only; omit if empty)
        if (!e.getRecordFields().isEmpty()) {
            JSONArray rfArr = new JSONArray();
            for (CustomField f : e.getRecordFields()) {
                JSONObject fo = new JSONObject();
                fo.put("label",  f.getLabel());
                fo.put("secret", f.isSecret());
                rfArr.put(fo);
            }
            obj.put("recordFields", rfArr);
            obj.put("recordIncludeNotes", e.isRecordIncludeNotes());
        }
        return obj;
    }

    private Entry entryFromJson(JSONObject obj) throws Exception {
        String id = obj.optString("id", UUID.randomUUID().toString());

        // ── Detect legacy format (has "title" key instead of "type") ──────────
        if (obj.has("title") && !obj.has("type")) {
            return migrateLegacyEntry(id, obj);
        }

        // ── New format ────────────────────────────────────────────────────────
        Entry entry = new Entry(
                id,
                obj.optString("type",   EntryType.WEBSITE),
                obj.optString("field1", ""),
                obj.optString("field2", ""),
                obj.optString("field3", ""),
                obj.optString("field4", ""),
                obj.optString("field5", ""),
                obj.optString("field6", ""),
                obj.optString("field7", "")
        );
        entry.setUpdatedAt(obj.optLong("updatedAt", 0));
        // v8: if no timestamp (pre-v8 entry), stamp with current time
        if (entry.getUpdatedAt() == 0) entry.setUpdatedAt(System.currentTimeMillis());
        // v12: createdAt — fallback to updatedAt for pre-v12 entries
        long createdAt = obj.optLong("createdAt", 0);
        entry.setCreatedAt(createdAt > 0 ? createdAt : entry.getUpdatedAt());
        entry.setFavourite(obj.optBoolean("favourite", false));
        entry.setPinnedAt(obj.optLong("pinnedAt", 0));
        // v24: attachment deserialization — new list format or migrate old single attachment
        JSONArray attachmentsJson = obj.optJSONArray("attachments");
        if (attachmentsJson != null && attachmentsJson.length() > 0) {
            // New format: read metadata list
            List<Attachment> attachments = new ArrayList<>();
            for (int i = 0; i < attachmentsJson.length(); i++) {
                JSONObject aObj = attachmentsJson.getJSONObject(i);
                attachments.add(new Attachment(
                        aObj.optString("id",       ""),
                        aObj.optString("name",     ""),
                        aObj.optString("mimeType", ""),
                        aObj.optLong("size", 0),
                        aObj.optString("group", "") // v27: empty string for ungrouped (backward compatible)
                ));
            }
            entry.setAttachments(attachments);
        } else {
            // Migration path: old single attachment embedded as Base64
            String oldName = obj.optString("attachmentName", "");
            String oldData = obj.optString("attachmentData", "");
            if (!oldName.isEmpty() && !oldData.isEmpty()) {
                try {
                    byte[] bytes = android.util.Base64.decode(oldData, android.util.Base64.NO_WRAP);
                    AttachmentStore store = new AttachmentStore(context);
                    Attachment migrated = store.save(bytes, oldName, guessMimeType(oldName));
                    List<Attachment> list = new ArrayList<>();
                    list.add(migrated);
                    entry.setAttachments(list);
                    attachmentMigrationOccurred = true; // signal loadEntries() to persist new format
                } catch (Exception ignored) {
                    // Migration failed — entry loads fine without the attachment
                    // (corrupted Base64 or disk full; nothing we can do)
                }
            }
        }
        entry.setArchived(obj.optBoolean("archived", false)); // v19
        // v20: checklist items
        JSONArray itemsArr = obj.optJSONArray("checklistItems");
        if (itemsArr != null) {
            List<ChecklistItem> items = new ArrayList<>();
            for (int i = 0; i < itemsArr.length(); i++) {
                JSONObject itemObj = itemsArr.getJSONObject(i);
                items.add(new ChecklistItem(
                        itemObj.optString("id",   java.util.UUID.randomUUID().toString()),
                        itemObj.optString("text",  ""),
                        itemObj.optBoolean("checked", false)
                ));
            }
            entry.setChecklistItems(items);
        }
        // v29: named attachment groups
        JSONArray groupsArr = obj.optJSONArray("attachmentGroups");
        if (groupsArr != null) {
            List<String> groups = new ArrayList<>();
            for (int i = 0; i < groupsArr.length(); i++) {
                String g = groupsArr.optString(i, "");
                if (!g.isEmpty()) groups.add(g);
            }
            entry.setAttachmentGroups(groups);
        }
        // v29: per-record field definitions (custom categories only)
        JSONArray rfArr = obj.optJSONArray("recordFields");
        if (rfArr != null && rfArr.length() > 0) {
            List<CustomField> recordFields = new ArrayList<>();
            for (int i = 0; i < rfArr.length(); i++) {
                JSONObject fo = rfArr.getJSONObject(i);
                recordFields.add(new CustomField(
                        fo.optString("label", "Field " + (i + 1)),
                        fo.optBoolean("secret", false)
                ));
            }
            entry.setRecordFields(recordFields);
            entry.setRecordIncludeNotes(obj.optBoolean("recordIncludeNotes", true));
        }
        return entry;
    }

    /**
     * Migrates a legacy entry (title/username/password/details/category)
     * to the new format (type/field1–field7).
     *
     * Legacy Website entry:
     *   title    → field1
     *   details  → field2 (used as URL if it looks like a URL, else notes)
     *   username → field3
     *   password → field4
     *   details  → field7 (notes)
     *   category → mapped to type via EntryType.fromLegacyCategory()
     */
    private Entry migrateLegacyEntry(String id, JSONObject obj) throws Exception {
        String title    = obj.optString("title",    "");
        String details  = obj.optString("details",  "");
        String username = obj.optString("username", "");
        String password = obj.optString("password", "");
        String category = obj.optString("category", "General");

        String type = EntryType.fromLegacyCategory(category);

        Entry legacy;
        switch (type) {
            case EntryType.BANK:
                // title → bank name (field1), username → account holder (field2),
                // password → account number (field3), details → notes (field7)
                legacy = new Entry(id, type, title, username, password, "", "", "", details);
                break;

            case EntryType.NOTE:
                // title → field1, details → field7 (notes)
                legacy = new Entry(id, type, title, "", "", "", "", "", details);
                break;

            default: // WEBSITE (and work/social/gaming)
                // title → field1, details → field7, username → field3, password → field4
                legacy = new Entry(id, type, title, "", username, password, "", "", details);
                break;
        }
        // v8: stamp legacy entries with current time (no updatedAt in old format)
        legacy.setUpdatedAt(System.currentTimeMillis());
        return legacy;
    }

    /**
     * Guesses a MIME type from a file extension for use during v9→v24 attachment migration.
     * Returns "application/octet-stream" as a safe default for unknown extensions.
     */
    private static String guessMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".doc"))  return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }

    // ── V7: PIN attempt, lock, security Q&A, auto-lock ───────────────
    private static final String KEY_PIN_ATTEMPTS = "pin_attempts";
    private static final String KEY_PIN_LOCKED   = "pin_locked";
    private static final String KEY_SECURITY_Q      = "security_q"; // int index
    private static final String KEY_SECURITY_A      = "security_a"; // string answer (case-insensitive)
    private static final String KEY_SECURITY_Q_CUSTOM = "security_q_custom"; // custom question text (index 2 only)
    private static final String KEY_AUTOLOCK        = "autolock";   // int seconds
    private static final String KEY_BG_TIMESTAMP = "bg_time";    // long millis

    // ── PIN attempt tracking ─────────────────────────────────────────
    public int getFailedAttempts() {
        return prefs.getInt(KEY_PIN_ATTEMPTS, 0);
    }
    public void setFailedAttempts(int count) {
        prefs.edit().putInt(KEY_PIN_ATTEMPTS, count).apply();
    }
    public void clearFailedAttempts() {
        // Use commit() (synchronous) — this must be written to disk immediately
        // before PinActivity finishes, so the next login always reads 0 attempts.
        // apply() is async and can be read back stale on fast activity transitions.
        prefs.edit().remove(KEY_PIN_ATTEMPTS).commit();
    }
    public boolean isPinLocked() {
        return prefs.getBoolean(KEY_PIN_LOCKED, false);
    }
    public void setPinLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_PIN_LOCKED, locked).apply();
    }
    public void clearPinLocked() {
        prefs.edit().remove(KEY_PIN_LOCKED).apply();
    }

    // ── Security question/answer ────────────────────────────────────
    public void setSecurityQuestion(int index, String answer) {
        prefs.edit()
            .putInt(KEY_SECURITY_Q, index)
            .putString(KEY_SECURITY_A, answer.trim().toLowerCase())
            .apply();
    }
    /** For custom question (index 2): saves the question text alongside index + answer. */
    public void setCustomSecurityQuestion(String questionText, String answer) {
        prefs.edit()
            .putInt(KEY_SECURITY_Q, 2)
            .putString(KEY_SECURITY_Q_CUSTOM, questionText.trim())
            .putString(KEY_SECURITY_A, answer.trim().toLowerCase())
            .apply();
    }
    /** Returns the custom question text, or empty string if not set. */
    public String getCustomSecurityQuestionText() {
        return prefs.getString(KEY_SECURITY_Q_CUSTOM, "");
    }
    public int getSecurityQuestionIndex() {
        return prefs.getInt(KEY_SECURITY_Q, -1);
    }
    public boolean checkSecurityAnswer(String answer) {
        String stored = prefs.getString(KEY_SECURITY_A, "");
        return stored.equals(answer.trim().toLowerCase());
    }

    // ── Auto-lock timeout (seconds) ────────────────────────────────
    public void setAutoLockTimeout(int seconds) {
        prefs.edit().putInt(KEY_AUTOLOCK, seconds).apply();
    }
    public int getAutoLockTimeout() {
        return prefs.getInt(KEY_AUTOLOCK, 0); // 0 = disabled
    }

    // ── Last background timestamp ──────────────────────────────────
    public void setBackgroundTimestamp(long millis) {
        prefs.edit().putLong(KEY_BG_TIMESTAMP, millis).apply();
    }
    public long getBackgroundTimestamp() {
        return prefs.getLong(KEY_BG_TIMESTAMP, 0);
    }

    // ── Forced lock (set on screen-off, cleared after launching PIN) ──────────
    private static final String KEY_FORCED_LOCK = "forced_lock";

    public void setForcedLock(boolean forced) {
        prefs.edit().putBoolean(KEY_FORCED_LOCK, forced).apply();
    }
    public boolean isForcedLock() {
        return prefs.getBoolean(KEY_FORCED_LOCK, false);
    }

    // ── Backup password + last export time ────────────────────────
    private static final String KEY_BACKUP_PASSWORD  = "backup_pass";
    private static final String KEY_LAST_EXPORT_TIME = "last_export_time";
    private static final String KEY_BACKUP_URI        = "backup_uri";
    private static final String KEY_AUTO_BACKUP       = "auto_backup_enabled";
    private static final String KEY_BACKUP_PENDING    = "backup_pending";

    public void setBackupPassword(String password) {
        prefs.edit().putString(KEY_BACKUP_PASSWORD, password).apply();
    }
    public String getBackupPassword() {
        return prefs.getString(KEY_BACKUP_PASSWORD, null);
    }
    public boolean hasBackupPassword() {
        return prefs.contains(KEY_BACKUP_PASSWORD);
    }
    public void setLastExportTime(long millis) {
        prefs.edit().putLong(KEY_LAST_EXPORT_TIME, millis).apply();
    }
    public long getLastExportTime() {
        return prefs.getLong(KEY_LAST_EXPORT_TIME, 0);
    }

    // ── Auto-backup URI (persisted SAF URI from last export) ──────
    public void setBackupUri(String uri) {
        prefs.edit().putString(KEY_BACKUP_URI, uri).apply();
    }
    public String getBackupUri() {
        return prefs.getString(KEY_BACKUP_URI, null);
    }
    public boolean hasBackupUri() {
        return prefs.contains(KEY_BACKUP_URI);
    }

    // ── Auto-backup enabled toggle ─────────────────────────────────
    public void setAutoBackupEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply();
    }
    public boolean isAutoBackupEnabled() {
        return prefs.getBoolean(KEY_AUTO_BACKUP, false);
    }

    // ── Backup pending flag (set when entries change) ──────────────
    public void setBackupPending(boolean pending) {
        // Use commit() (synchronous) not apply() — this flag must be
        // written to disk immediately so BaseActivity.onStop() reads
        // the correct value even if called milliseconds after this.
        prefs.edit().putBoolean(KEY_BACKUP_PENDING, pending).commit();
    }
    public boolean isBackupPending() {
        return prefs.getBoolean(KEY_BACKUP_PENDING, false);
    }

    // ── v17: Biometric unlock enabled ─────────────────────────────
    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";

    public void setBiometricEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply();
    }
    public boolean isBiometricEnabled() {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    // ── v26: Custom categories ────────────────────────────────────────────────
    private static final String KEY_CUSTOM_CATEGORIES = "custom_categories";

    public List<CustomCategory> loadCustomCategories() {
        List<CustomCategory> list = new ArrayList<>();
        try {
            String json = prefs.getString(KEY_CUSTOM_CATEGORIES, "[]");
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                String id    = obj.optString("id",    "");
                String name  = obj.optString("name",  "");
                String emoji = obj.optString("emoji", "📁");
                List<CustomField> fields = new ArrayList<>();
                org.json.JSONArray fa = obj.optJSONArray("fields");
                if (fa != null) {
                    for (int j = 0; j < fa.length(); j++) {
                        org.json.JSONObject fo = fa.getJSONObject(j);
                        fields.add(new CustomField(
                                fo.optString("label",  "Field " + (j + 1)),
                                fo.optBoolean("secret", false)
                        ));
                    }
                }
                if (!id.isEmpty() && !name.isEmpty()) {
                    boolean includeNotes = obj.optBoolean("includeNotes", true);
                    list.add(new CustomCategory(id, name, emoji, fields, includeNotes));
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public void saveCustomCategories(List<CustomCategory> categories) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (CustomCategory c : categories) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("id",    c.getId());
                obj.put("name",  c.getName());
                obj.put("emoji", c.getEmoji());
                org.json.JSONArray fa = new org.json.JSONArray();
                for (CustomField f : c.getFields()) {
                    org.json.JSONObject fo = new org.json.JSONObject();
                    fo.put("label",  f.getLabel());
                    fo.put("secret", f.isSecret());
                    fa.put(fo);
                }
                obj.put("fields", fa);
                obj.put("includeNotes", c.isIncludeNotes());
                arr.put(obj);
            }
            prefs.edit().putString(KEY_CUSTOM_CATEGORIES, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Returns the raw JSON string for custom categories — used in backup export. */
    public String exportCustomCategoriesJson() {
        return prefs.getString(KEY_CUSTOM_CATEGORIES, "[]");
    }

    /** Merges imported custom categories — skips any with duplicate IDs. */
    public void mergeCustomCategories(List<CustomCategory> imported) {
        List<CustomCategory> existing = loadCustomCategories();
        java.util.Set<String> existingIds = new java.util.HashSet<>();
        for (CustomCategory c : existing) existingIds.add(c.getId());
        for (CustomCategory c : imported) {
            if (!existingIds.contains(c.getId())) {
                existing.add(c);
            }
        }
        saveCustomCategories(existing);
    }

    // ── Hidden tile types (Manage Categories) ────────────────────────────────

    private static final String KEY_HIDDEN_TYPES = "hidden_tile_types";

    /** Returns the set of type IDs the user has hidden from the home screen. */
    public java.util.Set<String> getHiddenTypes() {
        String json = prefs.getString(KEY_HIDDEN_TYPES, "[]");
        java.util.Set<String> result = new java.util.HashSet<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) result.add(arr.getString(i));
        } catch (Exception ignored) {}
        return result;
    }

    /** Persists the set of hidden type IDs. */
    public void setHiddenTypes(java.util.Set<String> hidden) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String id : hidden) arr.put(id);
            prefs.edit().putString(KEY_HIDDEN_TYPES, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Parses a JSON string of custom categories — used during backup import. Returns null on error. */
    public List<CustomCategory> importCustomCategoriesFromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            // Parse directly from the JSON string — never touch stored prefs to avoid
            // a crash between a temp-write and a restore overwriting existing categories.
            List<CustomCategory> list = new ArrayList<>();
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                String id    = obj.optString("id",    "");
                String name  = obj.optString("name",  "");
                String emoji = obj.optString("emoji", "📁");
                List<CustomField> fields = new ArrayList<>();
                org.json.JSONArray fa = obj.optJSONArray("fields");
                if (fa != null) {
                    for (int j = 0; j < fa.length(); j++) {
                        org.json.JSONObject fo = fa.getJSONObject(j);
                        fields.add(new CustomField(
                                fo.optString("label",  "Field " + (j + 1)),
                                fo.optBoolean("secret", false)
                        ));
                    }
                }
                if (!id.isEmpty() && !name.isEmpty()) {
                    boolean includeNotes = obj.optBoolean("includeNotes", true);
                    list.add(new CustomCategory(id, name, emoji, fields, includeNotes));
                }
            }
            return list;
        } catch (Exception ignored) {
            return null;
        }
    }
}
