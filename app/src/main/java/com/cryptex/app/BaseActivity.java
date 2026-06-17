package com.cryptex.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import java.io.File;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BaseActivity — shared screen-lock + auto-backup behaviour for all activities.
 *
 * Screen-off strategy:
 *   When the device screen turns off (ACTION_SCREEN_OFF), a dedicated
 *   "forced_lock" boolean is set to true. This is separate from the
 *   bg_time timestamp so that PinActivity's onPause() cannot accidentally
 *   overwrite it with a fresh timestamp.
 *
 * onPause strategy:
 *   Non-PIN activities save the current time as bg_time (for timeout-based
 *   auto-lock). PinActivity skips this entirely — it must never update
 *   the timestamp, because doing so would reset the forced-lock state.
 *
 * onResume strategy (per app-activity):
 *   Check forced_lock OR (timeout > 0 && elapsed > timeout).
 *   If either is true, clear both flags and launch PinActivity.
 *
 * Auto-backup strategy:
 *   onUserLeaveHint fires on whichever activity is in the foreground when
 *   the user presses Home or Recents — could be MainActivity, TypeListActivity,
 *   or DetailActivity. By placing triggerAutoBackup() here in BaseActivity,
 *   it is guaranteed to fire from any screen, not just MainActivity.
 *   onStop() acts as a safety net for screen-off / app kill scenarios.
 *   PinActivity is excluded — a lock screen should never trigger backup.
 */
public abstract class BaseActivity extends AppCompatActivity {

    private StorageHelper baseStorage;

    /** Guard so camera-temp cleanup runs only once per process lifetime. */
    private static final AtomicBoolean sCameraCleanupDone = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // API 35 forces edge-to-edge by default (content drawn behind system bars).
        // Setting decorFitsSystemWindows=true restores the traditional behaviour
        // where the system bars reserve space so layouts are never obscured.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        cleanStaleCameraTemps();
    }

    /**
     * Deletes any leftover capture_*.jpg temp files from the camera dir.
     * These are created in startCameraCapture() and deleted in handleCameraCapture(),
     * but if the app was killed mid-capture the file is never cleaned up.
     * Runs once per process lifetime on a background thread.
     */
    private void cleanStaleCameraTemps() {
        if (!sCameraCleanupDone.compareAndSet(false, true)) return;
        final File cameraDir = new File(getCacheDir(), "camera");
        new Thread(() -> {
            try {
                File[] stale = cameraDir.listFiles(
                        f -> f.isFile() && f.getName().startsWith("capture_") && f.getName().endsWith(".jpg"));
                if (stale != null) {
                    for (File f : stale) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                }
            } catch (Exception ignored) { }
        }, "camera-cleanup").start();
    }

    // ── Shared auto-lock check ────────────────────────────────────────────────
    /**
     * Returns true if the app should lock immediately.
     * Checks both forced-lock (screen-off) and timeout-based auto-lock.
     * Clears both flags and launches PinActivity if locking is required.
     * Activities call this at the top of their onResume(); if it returns
     * true they must return immediately without doing any further work.
     */
    protected boolean checkAndHandleAutoLock() {
        if (this instanceof PinActivity || this instanceof OnboardingActivity) return false;
        if (baseStorage == null) baseStorage = StorageHelper.getInstance(this);
        boolean forcedLock = baseStorage.isForcedLock();
        int  timeout = baseStorage.getAutoLockTimeout();
        long last    = baseStorage.getBackgroundTimestamp();
        boolean timeoutExpired = timeout > 0 && last > 0
                && System.currentTimeMillis() - last > timeout * 1000L;
        if (forcedLock || timeoutExpired) {
            baseStorage.setForcedLock(false);
            baseStorage.setBackgroundTimestamp(0);
            Intent intent = new Intent(this, PinActivity.class);
            // Do NOT clear the task — keep the back stack so that after a
            // successful PIN the user resumes exactly where they left off.
            intent.putExtra("resume_on_success", true);
            startActivity(intent);
            return true;
        }
        return false;
    }

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // Always set forced_lock — unconditional, regardless of timeout setting.
                // This cannot be overwritten by onPause() since PinActivity skips saving.
                if (baseStorage != null) {
                    baseStorage.setForcedLock(true);
                }
            }
        }
    };

    private boolean encryptionWarningShown = false; // show at most once per session

    @Override
    protected void onResume() {
        super.onResume();
        if (baseStorage == null) baseStorage = StorageHelper.getInstance(this);

        // Warn user if encrypted storage failed to initialise
        if (baseStorage.isEncryptionFailed() && !encryptionWarningShown
                && !(this instanceof PinActivity) && !(this instanceof OnboardingActivity)) {
            encryptionWarningShown = true;
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("⚠️ Security Warning")
                    .setMessage("Encrypted storage could not be initialised on this device. "
                            + "Your data is currently stored without encryption.\n\n"
                            + "This may be caused by a corrupted Android Keystore. "
                            + "Try restarting your device.")
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show();
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenOffReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // Re-take persistable URI permission on every resume.
        // SAF persistable permissions can be silently dropped by Android after
        // app restarts, device reboots, or storage provider updates.
        // Re-taking on resume ensures the auto-backup write never gets a
        // SecurityException due to a stale/expired permission.
        String uriString = baseStorage.getBackupUri();
        if (uriString != null) {
            try {
                Uri uri = Uri.parse(uriString);
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
                // Permission may no longer be available if the file was deleted
                // or the storage provider changed. Auto-backup will silently skip.
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // PinActivity must NEVER update the background timestamp.
        // Any other activity saves the current time for timeout-based auto-lock.
        if (!(this instanceof PinActivity) && baseStorage != null) {
            baseStorage.setBackgroundTimestamp(System.currentTimeMillis());
        }
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered — safe to ignore
        }
    }

    /**
     * Fires when the user presses Home or Recents on THIS activity (whichever
     * is currently in the foreground). This is the primary auto-backup trigger.
     * Not called when navigating between activities within the app.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        triggerAutoBackup();
    }

    /**
     * Safety net: fires when this activity is no longer visible.
     * Catches scenarios like screen-off or swipe-kill from Recents where
     * onUserLeaveHint may not have fired. The backup_pending flag prevents
     * double work if onUserLeaveHint already ran first.
     */
    @Override
    protected void onStop() {
        super.onStop();
        triggerAutoBackup();
    }

    /**
     * Performs auto-backup if all conditions are met. Safe to call multiple
     * times — the backup_pending flag is cleared atomically before the
     * background thread starts, so duplicate calls do nothing.
     *
     * Excluded from PinActivity: a lock screen must never trigger a backup.
     */
    private void triggerAutoBackup() {
        if (this instanceof PinActivity || this instanceof OnboardingActivity) return;
        if (baseStorage == null) baseStorage = StorageHelper.getInstance(this);

        if (!baseStorage.isAutoBackupEnabled()) return;
        if (!baseStorage.isBackupPending())     return;
        if (!baseStorage.hasBackupPassword() || !baseStorage.hasBackupUri()) return;

        final String password  = baseStorage.getBackupPassword();
        final String uriString = baseStorage.getBackupUri();
        if (password == null || uriString == null) return;

        final List<Entry> entries = baseStorage.loadEntries();

        // Clear pending flag BEFORE launching thread — prevents a second
        // call (e.g. onStop fires after onUserLeaveHint) from doing double work.
        baseStorage.setBackupPending(false);

        new Thread(() -> {
            try {
                String json = baseStorage.exportToJson(entries);
                if (json == null) return;

                // v24: collect attachment files for ZIP backup
                AttachmentStore attachmentStore = new AttachmentStore(BaseActivity.this);
                java.util.List<BackupCrypto.AttachmentItem> attachmentItems =
                        new java.util.ArrayList<>();
                for (Entry entry : entries) {
                    for (Attachment att : entry.getAttachments()) {
                        try {
                            byte[] data = attachmentStore.read(att.getId());
                            attachmentItems.add(
                                    new BackupCrypto.AttachmentItem(att.getId(), data));
                        } catch (Exception ignored) {
                            // Skip unreadable attachment — rest of backup still runs
                        }
                    }
                }

                byte[] encrypted = BackupCrypto.encryptZip(json, attachmentItems, password,
                        baseStorage.exportCustomCategoriesJson());

                Uri uri = Uri.parse(uriString);
                try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
                    if (os == null) return;
                    os.write(encrypted);
                }

                // Success — record timestamp
                baseStorage.setLastExportTime(System.currentTimeMillis());

            } catch (Exception e) {
                // Restore pending so it retries on the next app exit
                baseStorage.setBackupPending(true);
            }
        }).start();
    }
}
