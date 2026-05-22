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

    private static final String PREFS_NAME  = "ms_secure_prefs";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_PIN     = "pin";

    private final SharedPreferences prefs;
    private boolean encryptionFailed = false; // true if EncryptedSharedPreferences init failed

    public StorageHelper(Context context) {
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
        obj.put("attachmentName",  e.getAttachmentName());
        obj.put("attachmentData",  e.getAttachmentData());
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
        entry.setAttachmentName(obj.optString("attachmentName", ""));
        entry.setAttachmentData(obj.optString("attachmentData", ""));
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

    // ── V7: PIN attempt, lock, security Q&A, auto-lock ───────────────
    private static final String KEY_PIN_ATTEMPTS = "pin_attempts";
    private static final String KEY_PIN_LOCKED   = "pin_locked";
    private static final String KEY_SECURITY_Q   = "security_q"; // int index
    private static final String KEY_SECURITY_A   = "security_a"; // string answer (case-insensitive)
    private static final String KEY_AUTOLOCK     = "autolock";   // int seconds
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
}
