package com.cryptex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public class SettingsActivity extends BaseActivity {

    private StorageHelper storage;

    // True when this activity was opened only to host a PDF export from TypeListActivity
    // In this mode the full Settings UI is not inflated — finish() after share sheet
    private boolean isPdfOnlyMode = false;

    // Pending password — held between password dialog and SAF picker callback
    private String pendingExportPassword = null;

    // SAF launcher: full export — user picks location, filename pre-set to cryptex_backup.cxb
    private final ActivityResultLauncher<Intent> exportFilePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && pendingExportPassword != null) {
                        // Take persistable permission HERE on UI thread — most reliable point
                        takePersistablePermission(uri);
                        performExportToUri(uri, pendingExportPassword, false);
                        pendingExportPassword = null;
                    }
                }
            });

    // SAF launcher: update backup — reuse stored password, no dialog
    private final ActivityResultLauncher<Intent> updateFilePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    String storedPass = storage.getBackupPassword();
                    if (uri != null && storedPass != null) {
                        // Take persistable permission HERE on UI thread — most reliable point
                        takePersistablePermission(uri);
                        performExportToUri(uri, storedPass, true);
                    }
                }
            });

    private final ActivityResultLauncher<String[]> importFilePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                // Only accept .cxb backup files
                String fileName = "";
                android.database.Cursor cursor = getContentResolver().query(
                        uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) fileName = cursor.getString(idx);
                    cursor.close();
                }
                if (!fileName.toLowerCase().endsWith(".cxb")) {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Unsupported File")
                            .setMessage(getString(R.string.import_cxb_only))
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                showImportPasswordDialog(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);

        storage = StorageHelper.getInstance(this);

        // If launched from TypeListActivity with specific entry IDs → skip to PDF password dialog
        ArrayList<String> pdfEntryIds = getIntent().getStringArrayListExtra("pdf_entry_ids");
        if (pdfEntryIds != null && !pdfEntryIds.isEmpty()) {
            isPdfOnlyMode = true;
            List<Entry> selected = new java.util.ArrayList<>();
            for (Entry e : storage.loadEntries()) {
                if (pdfEntryIds.contains(e.getId())) selected.add(e);
            }
            if (!selected.isEmpty()) {
                new android.os.Handler().postDelayed(() -> showPdfPasswordDialog(selected, true), 200);
            } else {
                finish();
            }
            return; // skip wiring all the Settings UI — this activity is just a PDF host
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.cardChangePin).setOnClickListener(v -> showCurrentPinDialog());
        findViewById(R.id.cardExport).setOnClickListener(v -> showExportPasswordDialog());
        findViewById(R.id.cardExportPdf).setOnClickListener(v -> showPdfExportWarning());
        findViewById(R.id.cardUpdateBackup).setOnClickListener(v -> launchUpdateBackup());
        findViewById(R.id.cardImport).setOnClickListener(v ->
                importFilePicker.launch(new String[]{"application/octet-stream", "*/*"}));
        findViewById(R.id.cardAutoLock).setOnClickListener(v -> showAutoLockDialog());
        findViewById(R.id.cardSecurityQ).setOnClickListener(v -> showSecurityQDialog());
        findViewById(R.id.cardManageCategories).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, ManageCategoriesActivity.class)));

        // Auto-backup toggle
        Switch switchAutoBackup = findViewById(R.id.switchAutoBackup);
        switchAutoBackup.setChecked(storage.isAutoBackupEnabled());
        switchAutoBackup.setOnCheckedChangeListener((btn, isChecked) ->
                storage.setAutoBackupEnabled(isChecked));

        // v17: Biometric toggle
        Switch switchBiometric = findViewById(R.id.switchBiometric);
        switchBiometric.setChecked(storage.isBiometricEnabled());
        switchBiometric.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                // Check hardware availability before enabling
                BiometricManager bm = BiometricManager.from(this);
                int status = bm.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK);
                if (status != BiometricManager.BIOMETRIC_SUCCESS) {
                    switchBiometric.setChecked(false);
                    Toast.makeText(this,
                            getString(R.string.biometric_not_available),
                            Toast.LENGTH_LONG).show();
                } else {
                    // Verify identity with current PIN before enabling
                    showBiometricEnableConfirm(switchBiometric);
                }
            } else {
                storage.setBiometricEnabled(false);
                Toast.makeText(this,
                        getString(R.string.biometric_disabled_msg),
                        Toast.LENGTH_SHORT).show();
            }
        });

        updateAutoLockValue();
        updateSecurityQValue();
        updateBackupCard();

        // Set version label dynamically from build
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((android.widget.TextView) findViewById(R.id.tvAppVersion))
                    .setText("Version " + vn);
        } catch (Exception ignored) {}
    }

    // v12: Auto-lock gap fix — save/check timestamp in SettingsActivity
    @Override
    protected void onPause() {
        super.onPause();
        // timestamp saved by BaseActivity.onPause()
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isPdfOnlyMode) return; // PDF-only mode — no auto-lock check, no card refresh
        if (checkAndHandleAutoLock()) return;
        updateBackupCard();
    }

    // ── UPDATE BACKUP CARD ───────────────────────────────────────────────────

    private void updateBackupCard() {
        View card = findViewById(R.id.cardUpdateBackup);
        TextView tvLast = findViewById(R.id.tvLastBackup);
        View cardAutoBackup = findViewById(R.id.cardAutoBackup);
        long lastTime = storage.getLastExportTime();
        if (lastTime > 0 && storage.hasBackupPassword()) {
            card.setVisibility(View.VISIBLE);
            String formatted = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                    .format(new Date(lastTime));
            tvLast.setText(getString(R.string.last_backup, formatted));
            // Show auto-backup toggle only after at least one export
            cardAutoBackup.setVisibility(View.VISIBLE);
        } else {
            card.setVisibility(View.GONE);
            cardAutoBackup.setVisibility(View.GONE);
        }
    }

    // ── EXPORT ───────────────────────────────────────────────────────────────

    private void showExportPasswordDialog() {
        List<Entry> entries = storage.loadEntries();
        if (entries.isEmpty()) {
            Toast.makeText(this, "No entries to export.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);

        TextView warning = new TextView(this);
        warning.setText(getString(R.string.export_warning));
        warning.setTextSize(13f);
        warning.setPadding(0, 0, 0, dp(12));
        layout.addView(warning);

        EditText etPassword = makePasswordInput(getString(R.string.backup_password_hint));
        layout.addView(etPassword);

        EditText etConfirm = makePasswordInput(getString(R.string.backup_password_confirm_hint));
        layout.addView(etConfirm);

        AlertDialog exportDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.backup_password_title))
                .setView(layout)
                .setPositiveButton("Export", null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create();
        exportDialog.show();
        exportDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pass    = etPassword.getText().toString();
            String confirm = etConfirm.getText().toString();
            if (pass.isEmpty()) {
                etPassword.setError(getString(R.string.backup_password_empty));
                return;
            }
            if (!pass.equals(confirm)) {
                etConfirm.setError(getString(R.string.backup_password_mismatch));
                return;
            }
            exportDialog.dismiss();
            // Store password for later re-use by Update Backup
            pendingExportPassword = pass;
            // Open SAF picker — fixed filename cryptex_backup.cxb
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "cryptex_backup.cxb");
            exportFilePicker.launch(intent);
        });
    }

    private void launchUpdateBackup() {
        if (!storage.hasBackupPassword()) {
            Toast.makeText(this, getString(R.string.update_backup_no_export), Toast.LENGTH_SHORT).show();
            return;
        }
        // ACTION_OPEN_DOCUMENT (not ACTION_CREATE_DOCUMENT) is used here intentionally.
        // URIs from ACTION_OPEN_DOCUMENT are always fully persistable across app restarts —
        // Android guarantees this. URIs from ACTION_CREATE_DOCUMENT may lose their
        // persistable write permission after app restart on some OEM devices (Samsung,
        // Xiaomi, etc.). By asking the user to pick the existing backup file here,
        // we store an ACTION_OPEN_DOCUMENT URI which auto-backup can reliably write to.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        updateFilePicker.launch(intent);
    }

    private void performExportToUri(Uri uri, String password, boolean isUpdate) {
        List<Entry> entries = storage.loadEntries();

        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.backup_encrypting))
                .setView(makeProgressBar())
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                String json = storage.exportToJson(entries);
                if (json == null) throw new Exception("JSON serialisation failed.");

                byte[] encrypted = BackupCrypto.encryptZip(json, password);

                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new Exception("Cannot open output stream.");
                    os.write(encrypted);
                }

                // Save password + timestamp + URI for auto-backup
                storage.setBackupPassword(password);
                storage.setLastExportTime(System.currentTimeMillis());
                storage.setBackupUri(uri.toString());

                runOnUiThread(() -> {
                    progress.dismiss();
                    String msg = isUpdate
                            ? getString(R.string.backup_updated)
                            : getString(R.string.export_success);
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    updateBackupCard();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, getString(R.string.export_fail) + "\n" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ── IMPORT ───────────────────────────────────────────────────────────────

    private void showImportPasswordDialog(Uri uri) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);

        TextView msg = new TextView(this);
        msg.setText(getString(R.string.import_password_msg));
        msg.setTextSize(13f);
        msg.setPadding(0, 0, 0, dp(12));
        layout.addView(msg);

        EditText etPassword = makePasswordInput(getString(R.string.backup_password_hint));
        layout.addView(etPassword);

        AlertDialog importDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.backup_password_title))
                .setView(layout)
                .setPositiveButton("Import", null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create();
        importDialog.show();
        importDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pass = etPassword.getText().toString();
            if (pass.isEmpty()) {
                etPassword.setError(getString(R.string.backup_password_empty));
                return;
            }
            importDialog.dismiss();
            performImport(uri, pass);
        });
    }

    private void performImport(Uri uri, String password) {
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.backup_decrypting))
                .setView(makeProgressBar())
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                byte[] fileBytes = readAllBytes(uri);

                List<Entry> imported;

                if (BackupCrypto.isZipBackup(fileBytes)) {
                    // ── v24 ZIP format ──────────────────────────────────────────
                    BackupCrypto.ZipContent zipContent = BackupCrypto.decryptZip(fileBytes, password);
                    imported = storage.importFromJson(zipContent.json);
                    if (imported == null) throw new Exception("Corrupted backup data.");
                } else {
                    // ── Legacy blob format (pre-v24) ────────────────────────────
                    String json = BackupCrypto.decrypt(fileBytes, password);
                    imported = storage.importFromJson(json);
                    // importFromJson() auto-migrates old attachmentName/Data fields
                    // (Base64 → AttachmentStore files) via StorageHelper.entryFromJson()
                    if (imported == null) throw new Exception("Corrupted backup data.");
                }

                // Merge: skip duplicate IDs
                List<Entry> existing = storage.loadEntries();
                for (Entry e : imported) {
                    boolean found = false;
                    for (Entry ex : existing) {
                        if (ex.getId().equals(e.getId())) { found = true; break; }
                    }
                    if (!found) {
                        if (e.getUpdatedAt() == 0) e.setUpdatedAt(System.currentTimeMillis());
                        existing.add(e);
                    }
                }
                storage.saveEntries(existing);
                storage.setBackupPending(false);

                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, getString(R.string.import_success), Toast.LENGTH_SHORT).show();
                    updateBackupCard();
                });

            } catch (BackupCrypto.WrongPasswordException e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Import Failed")
                            .setMessage(getString(R.string.import_wrong_password))
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (BackupCrypto.InvalidFileException e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Import Failed")
                            .setMessage(getString(R.string.import_invalid_file))
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, getString(R.string.import_fail), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ── V7: Auto-lock ───────────────────────────────────────────────────────
    private void updateAutoLockValue() {
        TextView tv = findViewById(R.id.tvAutoLockValue);
        int sec = storage.getAutoLockTimeout();
        String txt;
        if (sec <= 0) txt = getString(R.string.auto_lock_off);
        else if (sec < 60) txt = getString(R.string.auto_lock_seconds, sec);
        else txt = getString(R.string.auto_lock_minutes, sec / 60);
        tv.setText(txt);
    }
    private void showAutoLockDialog() {
        final String[] options = {
                getString(R.string.auto_lock_off),
                getString(R.string.auto_lock_10s),
                getString(R.string.auto_lock_30s),
                getString(R.string.auto_lock_1m),
                getString(R.string.auto_lock_5m)
        };
        final int[] values = {0, 10, 30, 60, 300};
        int current = storage.getAutoLockTimeout();
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (current == values[i]) checked = i;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_lock)
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    storage.setAutoLockTimeout(values[which]);
                    updateAutoLockValue();
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
    // ── V7: Security Q&A ─────────────────────────────────────────────────────
    private void updateSecurityQValue() {
        TextView tv = findViewById(R.id.tvSecurityQValue);
        int idx = storage.getSecurityQuestionIndex();
        if (idx == ForgotPinActivity.CUSTOM_QUESTION_INDEX) {
            String custom = storage.getCustomSecurityQuestionText();
            tv.setText(custom.isEmpty() ? ForgotPinActivity.QUESTIONS[idx] : custom);
        } else if (idx >= 0 && idx < ForgotPinActivity.QUESTIONS.length) {
            tv.setText(ForgotPinActivity.QUESTIONS[idx]);
        } else {
            tv.setText(R.string.security_question_not_set);
        }
    }
    private void showSecurityQDialog() {
        final String[] questions = ForgotPinActivity.QUESTIONS;
        int current = storage.getSecurityQuestionIndex();
        final int[] selected = {current >= 0 ? current : 0};
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);
        final TextView tvQ = new TextView(this);
        tvQ.setText("Pick a security question:");
        tvQ.setPadding(0, 0, 0, dp(8));
        layout.addView(tvQ);
        final String[] q = {questions[selected[0]]};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.security_question)
                .setSingleChoiceItems(questions, selected[0], (d, which) -> {
                    selected[0] = which;
                })
                .setView(layout)
                .setPositiveButton(R.string.next, (d, w) -> {
                    if (selected[0] == ForgotPinActivity.CUSTOM_QUESTION_INDEX) {
                        showCustomSecurityQInput();
                    } else {
                        showSecurityAInput(selected[0], null);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
    private void showCustomSecurityQInput() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);
        TextView tvQLabel = new TextView(this);
        tvQLabel.setText("Your question:");
        tvQLabel.setPadding(0, 0, 0, dp(4));
        layout.addView(tvQLabel);
        EditText etQuestion = new EditText(this);
        etQuestion.setHint("Type your question…");
        etQuestion.setText(storage.getCustomSecurityQuestionText());
        layout.addView(etQuestion);
        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.security_question)
                .setView(layout)
                .setPositiveButton(R.string.next, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String q = etQuestion.getText().toString().trim();
            if (q.isEmpty()) {
                etQuestion.setError("Question cannot be empty.");
                return;
            }
            dlg.dismiss();
            showSecurityAInput(ForgotPinActivity.CUSTOM_QUESTION_INDEX, q);
        });
    }
    private void showSecurityAInput(int qIndex, String customQuestionText) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);
        TextView tv = new TextView(this);
        tv.setText(R.string.security_answer_prompt);
        layout.addView(tv);
        EditText et = new EditText(this);
        et.setHint(R.string.security_answer_hint);
        layout.addView(et);

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.security_question)
                .setView(layout)
                .setPositiveButton(R.string.save, null) // null — handled below to prevent auto-dismiss
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.show();
        // Override positive button so dialog only closes when answer is valid
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ans = et.getText().toString().trim();
            if (ans.isEmpty()) {
                et.setError(getString(R.string.security_answer_empty));
                return; // dialog stays open
            }
            if (qIndex == ForgotPinActivity.CUSTOM_QUESTION_INDEX && customQuestionText != null) {
                storage.setCustomSecurityQuestion(customQuestionText, ans);
            } else {
                storage.setSecurityQuestion(qIndex, ans);
            }
            updateSecurityQValue();
            dlg.dismiss();
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Takes persistable read+write permission for a SAF URI.
     * Must be called on the UI thread immediately after the picker returns the URI —
     * this is the only reliable moment Android guarantees the permission can be taken.
     * Calling it inside a background thread or later is unreliable and can silently fail.
     */
    private void takePersistablePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    private byte[] readAllBytes(Uri uri) throws Exception {
        try (InputStream raw = getContentResolver().openInputStream(uri);
             java.io.BufferedInputStream is = new java.io.BufferedInputStream(raw, 8192);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) buffer.write(chunk, 0, n);
            return buffer.toByteArray();
        }
    }

    private EditText makePasswordInput(String hint) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint(hint);
        et.setPadding(0, dp(8), 0, dp(16));
        return et;
    }

    private ProgressBar makeProgressBar() {
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        int p = dp(24);
        pb.setPadding(p, p, p, p);
        return pb;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ── Change PIN Flow ───────────────────────────────────────────────────────

    private EditText makePinInput() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        et.setHint("Enter 4-digit PIN");
        et.setPadding(40, 24, 40, 24);
        return et;
    }

    /** Forces the soft keyboard open as soon as the dialog window is attached. */
    private void showKeyboardFor(AlertDialog dialog, EditText et) {
        dialog.setOnShowListener(d -> {
            // Set window to always-visible so the system doesn't suppress the keyboard
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            et.requestFocus();
            // postDelayed gives the window time to gain full focus before forcing keyboard
            et.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et,
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED);
            }, 100);
        });
    }

    private void showCurrentPinDialog() {
        // Single dialog with all 3 fields — keyboard opens once and stays open
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);

        EditText etCurrent = makePinInput();
        etCurrent.setHint("Current PIN");
        layout.addView(etCurrent);

        EditText etNew = makePinInput();
        etNew.setHint("New PIN");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        etNew.setLayoutParams(lp);
        layout.addView(etNew);

        EditText etConfirm = makePinInput();
        etConfirm.setHint("Confirm new PIN");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(12);
        etConfirm.setLayoutParams(lp2);
        layout.addView(etConfirm);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Change PIN")
                .setView(layout)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        showKeyboardFor(dialog, etCurrent);
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String current = etCurrent.getText().toString().trim();
            String newPin  = etNew.getText().toString().trim();
            String confirm = etConfirm.getText().toString().trim();

            if (!storage.checkPin(current)) {
                etCurrent.setError("Incorrect PIN");
                etCurrent.requestFocus();
                return;
            }
            if (newPin.length() != 4) {
                etNew.setError("Must be 4 digits");
                etNew.requestFocus();
                return;
            }
            if (!newPin.equals(confirm)) {
                etConfirm.setError("PINs do not match");
                etConfirm.requestFocus();
                return;
            }
            storage.savePin(newPin);
            dialog.dismiss();
            Toast.makeText(this, "PIN changed successfully!", Toast.LENGTH_SHORT).show();
        });
    }

    // ── v17: Biometric enable confirmation ────────────────────────────────────

    private void showBiometricEnableConfirm(Switch switchBiometric) {
        EditText etPin = makePinInput();
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.biometric_unlock))
                .setMessage(getString(R.string.biometric_verify_pin))
                .setView(etPin)
                .setPositiveButton("Enable", (d, w) -> {
                    String entered = etPin.getText().toString().trim();
                    if (storage.checkPin(entered)) {
                        storage.setBiometricEnabled(true);
                        Toast.makeText(this,
                                getString(R.string.biometric_enabled_msg),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        switchBiometric.setChecked(false);
                        Toast.makeText(this, "Incorrect PIN. Biometric not enabled.",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), (d, w) ->
                        switchBiometric.setChecked(false))
                .setOnCancelListener(d -> switchBiometric.setChecked(false))
                .create();
        showKeyboardFor(dialog, etPin);
        dialog.show();
    }

    // ── v17: PDF Export ───────────────────────────────────────────────────────

    private void showPdfExportWarning() {
        List<Entry> allEntries = storage.loadEntries();
        // Exclude archived entries from PDF export
        // Also strip attachment data — PDF never uses it, no point holding it in RAM
        List<Entry> activeEntries = new java.util.ArrayList<>();
        for (Entry e : allEntries) {
            if (!e.isArchived()) {
                activeEntries.add(e);
            }
        }
        if (activeEntries.isEmpty()) {
            Toast.makeText(this, getString(R.string.pdf_no_entries), Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.export_pdf))
                .setMessage(getString(R.string.export_pdf_warning))
                .setPositiveButton("Continue →", (d, w) -> showPdfCategoryPicker(activeEntries))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showPdfCategoryPicker(List<Entry> activeEntries) {
        // Only show types that have at least one active entry (built-in, excludes checklist)
        final String[] allTypes = EntryType.ALL_TYPES;

        // Build list of types that actually have entries (excludes checklist — not rendered in PDF)
        final java.util.List<String> availableTypes = new java.util.ArrayList<>();
        for (String t : allTypes) {
            if (EntryType.CHECKLIST.equals(t)) continue; // checklist items not exported in PDF
            for (Entry e : activeEntries) {
                if (t.equals(e.getType())) { availableTypes.add(t); break; }
            }
        }

        // Edge case: all active entries are checklist type (not exported in PDF)
        if (availableTypes.isEmpty()) {
            Toast.makeText(this, getString(R.string.pdf_no_entries), Toast.LENGTH_SHORT).show();
            return;
        }

        // Build display labels with entry counts
        final CharSequence[] labels = new CharSequence[availableTypes.size()];
        for (int i = 0; i < availableTypes.size(); i++) {
            String t = availableTypes.get(i);
            int count = 0;
            for (Entry e : activeEntries) if (t.equals(e.getType())) count++;
            labels[i] = EntryType.getDisplayName(t) + "  (" + count + ")";
        }

        // All checked by default
        final boolean[] checked = new boolean[availableTypes.size()];
        java.util.Arrays.fill(checked, true);

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Select Categories")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Next →", null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create();

        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            // Build filtered entry list based on selected categories
            List<Entry> filtered = new java.util.ArrayList<>();
            for (int i = 0; i < availableTypes.size(); i++) {
                if (checked[i]) {
                    String t = availableTypes.get(i);
                    for (Entry e : activeEntries) if (t.equals(e.getType())) filtered.add(e);
                }
            }
            if (filtered.isEmpty()) {
                Toast.makeText(this, "Please select at least one category.", Toast.LENGTH_SHORT).show();
                return;
            }
            dlg.dismiss();
            showPdfPasswordDialog(filtered, true);
        });
    }

    private void showPdfPasswordDialog(List<Entry> entries, boolean textOnly) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);

        EditText etPass = makePasswordInput(getString(R.string.pdf_password_hint));
        EditText etConfirm = makePasswordInput(getString(R.string.pdf_password_confirm_hint));
        layout.addView(etPass);
        layout.addView(etConfirm);

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.pdf_password_title))
                .setView(layout)
                .setPositiveButton(getString(R.string.generate_pdf), null)
                .setNegativeButton(getString(R.string.cancel), (d, w) -> { if (isPdfOnlyMode) finish(); })
                .setOnCancelListener(d -> { if (isPdfOnlyMode) finish(); })
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pass    = etPass.getText().toString();
            String confirm = etConfirm.getText().toString();
            if (pass.isEmpty()) {
                etPass.setError(getString(R.string.pdf_password_empty));
                return;
            }
            if (!pass.equals(confirm)) {
                etConfirm.setError(getString(R.string.pdf_password_mismatch));
                return;
            }
            dlg.dismiss();
            generatePdf(entries, pass, textOnly);
        });
    }

    private void generatePdf(List<Entry> entries, String password, boolean textOnly) {
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.pdf_generating))
                .setView(makeProgressBar())
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                // Initialise PdfBox Android resources (safe to call multiple times)
                PDFBoxResourceLoader.init(getApplicationContext());

                PDDocument doc = new PDDocument();
                final float PAGE_W = PDRectangle.A4.getWidth();
                final float PAGE_H = PDRectangle.A4.getHeight();
                final float MARGIN  = 50f;
                final float COL_W   = PAGE_W - MARGIN * 2;
                final float LINE_H  = 14f;

                // Fonts
                PDType1Font fontBold    = PDType1Font.HELVETICA_BOLD;
                PDType1Font fontRegular = PDType1Font.HELVETICA;

                // Helper: current page state
                final float[] yRef = {PAGE_H - MARGIN};
                final PDPage[] pageRef = {null};
                final PDPageContentStream[] csRef = {null};

                // Open first page
                pageRef[0] = new PDPage(PDRectangle.A4);
                doc.addPage(pageRef[0]);
                csRef[0] = new PDPageContentStream(doc, pageRef[0]);

                // Utility lambdas replaced by a small helper class (Java 8 lambdas can't throw checked)
                // We use a simple approach: write line, check y, add page if needed

                // Title header
                csRef[0].setFont(fontBold, 18);
                csRef[0].beginText();
                csRef[0].newLineAtOffset(MARGIN, yRef[0]);
                csRef[0].showText("Cryptex Export");
                csRef[0].endText();
                yRef[0] -= 20f;

                String dateStr = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                        .format(new Date());
                csRef[0].setFont(fontRegular, 10);
                csRef[0].beginText();
                csRef[0].newLineAtOffset(MARGIN, yRef[0]);
                csRef[0].showText("Generated: " + dateStr);
                csRef[0].endText();
                yRef[0] -= 8f;

                // Divider line
                csRef[0].moveTo(MARGIN, yRef[0]);
                csRef[0].lineTo(MARGIN + COL_W, yRef[0]);
                csRef[0].stroke();
                yRef[0] -= 18f;

                // Group entries by type — preserve picker order, include custom categories
                java.util.List<String> typeOrder = new java.util.ArrayList<>();
                for (Entry e : entries) {
                    if (!typeOrder.contains(e.getType())) typeOrder.add(e.getType());
                }

                for (String type : typeOrder) {
                    List<Entry> group = new java.util.ArrayList<>();
                    for (Entry e : entries) if (type.equals(e.getType())) group.add(e);
                    if (group.isEmpty()) continue;

                    String[] labels = EntryType.getFieldLabels(type);
                    String typeTitle = EntryType.getDisplayName(type)
                            + "  (" + group.size() + ")";

                    // Check page space for section header
                    if (yRef[0] < MARGIN + 40f) {
                        csRef[0].close();
                        pageRef[0] = new PDPage(PDRectangle.A4);
                        doc.addPage(pageRef[0]);
                        csRef[0] = new PDPageContentStream(doc, pageRef[0]);
                        yRef[0] = PAGE_H - MARGIN;
                    }

                    // Section header
                    csRef[0].setFont(fontBold, 13);
                    csRef[0].beginText();
                    csRef[0].newLineAtOffset(MARGIN, yRef[0]);
                    csRef[0].showText(typeTitle);
                    csRef[0].endText();
                    yRef[0] -= 6f;

                    csRef[0].moveTo(MARGIN, yRef[0]);
                    csRef[0].lineTo(MARGIN + COL_W, yRef[0]);
                    csRef[0].stroke();
                    yRef[0] -= 14f;

                    for (Entry e : group) {
                        // Entry title (field1)
                        if (yRef[0] < MARGIN + 30f) {
                            csRef[0].close();
                            pageRef[0] = new PDPage(PDRectangle.A4);
                            doc.addPage(pageRef[0]);
                            csRef[0] = new PDPageContentStream(doc, pageRef[0]);
                            yRef[0] = PAGE_H - MARGIN;
                        }
                        String entryTitle = e.getField1().isEmpty() ? "(no title)" : e.getField1();
                        csRef[0].setFont(fontBold, 11);
                        csRef[0].beginText();
                        csRef[0].newLineAtOffset(MARGIN, yRef[0]);
                        csRef[0].showText(entryTitle);
                        csRef[0].endText();
                        yRef[0] -= LINE_H;

                        // Fields 2–7
                        String[] fieldValues = {
                                e.getField2(), e.getField3(), e.getField4(),
                                e.getField5(), e.getField6(), e.getField7()
                        };
                        for (int fi = 0; fi < fieldValues.length; fi++) {
                            String val = fieldValues[fi];
                            String lbl = (fi + 1 < labels.length) ? labels[fi + 1] : "";
                            if (val == null || val.isEmpty() || lbl.isEmpty()) continue;

                            if (yRef[0] < MARGIN + 20f) {
                                csRef[0].close();
                                pageRef[0] = new PDPage(PDRectangle.A4);
                                doc.addPage(pageRef[0]);
                                csRef[0] = new PDPageContentStream(doc, pageRef[0]);
                                yRef[0] = PAGE_H - MARGIN;
                            }

                            // Label
                            csRef[0].setFont(fontBold, 9);
                            csRef[0].beginText();
                            csRef[0].newLineAtOffset(MARGIN + 10f, yRef[0]);
                            csRef[0].showText(lbl + ":");
                            csRef[0].endText();

                            // Value — sanitise to printable ASCII (PDF Type1 safe)
                            String safeVal = sanitiseForPdf(val);
                            csRef[0].setFont(fontRegular, 9);
                            csRef[0].beginText();
                            csRef[0].newLineAtOffset(MARGIN + 100f, yRef[0]);
                            csRef[0].showText(safeVal);
                            csRef[0].endText();
                            yRef[0] -= LINE_H;
                        }
                        yRef[0] -= 6f; // gap between entries
                    }
                    yRef[0] -= 10f; // gap between sections
                }

                csRef[0].close();

                // Apply password protection (AES-128)
                // First arg = owner password, second arg = user password (required to OPEN)
                // Both set to user's chosen password so the PDF requires it to open.
                AccessPermission ap = new AccessPermission();
                ap.setCanPrint(true);
                ap.setCanModify(false);
                ap.setCanExtractContent(false);
                StandardProtectionPolicy policy =
                        new StandardProtectionPolicy(password, password, ap);
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);

                // Write to bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                doc.save(baos);
                doc.close();
                byte[] pdfBytes = baos.toByteArray();
                final byte[] finalPdfBytes = pdfBytes;

                runOnUiThread(() -> {
                    progress.dismiss();
                    showPdfActionDialog(finalPdfBytes);
                });

            } catch (Exception ex) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this,
                            getString(R.string.pdf_fail) + "\n" + ex.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Replace characters outside printable ASCII range with '?' for PDF Type1 safety. */
    private String sanitiseForPdf(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            sb.append((c >= 32 && c < 127) ? c : '?');
        }
        return sb.toString();
    }

    private void showPdfActionDialog(byte[] pdfBytes) {
        // Go straight to share sheet — covers share, save to Drive/Files, print, email etc.
        sharePdf(pdfBytes);
    }

    private void sharePdf(byte[] pdfBytes) {
        try {
            // Write to cache file, then share via FileProvider
            java.io.File cacheDir = new java.io.File(getCacheDir(), "pdf_export");
            cacheDir.mkdirs();
            java.io.File pdfFile = new java.io.File(cacheDir, getString(R.string.pdf_filename));
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(pdfFile)) {
                fos.write(pdfBytes);
            }
            Uri pdfUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", pdfFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/pdf");
            shareIntent.putExtra(Intent.EXTRA_STREAM, pdfUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.pdf_share)));
            if (isPdfOnlyMode) finish();
        } catch (Exception ex) {
            Toast.makeText(this,
                    getString(R.string.pdf_fail) + "\n" + ex.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

}
