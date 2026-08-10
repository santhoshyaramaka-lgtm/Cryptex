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

    // Pending password â€” held between password dialog and SAF picker callback
    private String pendingExportPassword = null;

    // SAF launcher: full export â€” user picks location, filename pre-set to cryptex_backup.cxb
    private final ActivityResultLauncher<Intent> exportFilePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && pendingExportPassword != null) {
                        // Take persistable permission HERE on UI thread â€” most reliable point
                        takePersistablePermission(uri);
                        performExportToUri(uri, pendingExportPassword, false);
                        pendingExportPassword = null;
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

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.cardChangePin).setOnClickListener(v -> showCurrentPinDialog());
        findViewById(R.id.cardUpdateBackup).setOnClickListener(v -> launchBackup());
        findViewById(R.id.cardImport).setOnClickListener(v ->
                importFilePicker.launch(new String[]{"application/octet-stream", "*/*"}));
        findViewById(R.id.cardSecurityQ).setOnClickListener(v -> showPinThenSecurityQ());
        findViewById(R.id.cardManageCategories).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, ManageCategoriesActivity.class)));

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

        updateSecurityQValue();
        updateBackupCard();

        // Backup & Data â€” expand/collapse toggle
        LinearLayout sectionBackupHeader = findViewById(R.id.sectionBackupHeader);
        LinearLayout groupBackup = findViewById(R.id.groupBackup);
        android.widget.ImageView ivBackupArrow = findViewById(R.id.ivBackupArrow);
        final boolean[] backupExpanded = {false};
        sectionBackupHeader.setOnClickListener(v -> {
            backupExpanded[0] = !backupExpanded[0];
            groupBackup.setVisibility(backupExpanded[0] ? View.VISIBLE : View.GONE);
            ivBackupArrow.setImageResource(
                    backupExpanded[0] ? R.drawable.ic_arrow_up : R.drawable.ic_arrow_down);
        });


        // Set version label dynamically from build
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((android.widget.TextView) findViewById(R.id.tvAppVersion))
                    .setText("Version " + vn);
        } catch (Exception ignored) {}
    }

    // v12: Auto-lock gap fix â€” save/check timestamp in SettingsActivity
    @Override
    protected void onPause() {
        super.onPause();
        // timestamp saved by BaseActivity.onPause()
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkAndHandleAutoLock()) return;
        updateBackupCard();
    }

    // â”€â”€ UPDATE BACKUP CARD â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void updateBackupCard() {
        TextView tvLast = findViewById(R.id.tvLastBackup);
        long lastTime = storage.getLastExportTime();
        if (lastTime > 0 && storage.hasBackupPassword()) {
            String formatted = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                    .format(new Date(lastTime));
            tvLast.setText(getString(R.string.last_backup, formatted));
        } else {
            tvLast.setText("");
        }
    }

    // â”€â”€ EXPORT â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            // Open SAF picker â€” fixed filename cryptex_backup.cxb
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "cryptex_backup.cxb");
            exportFilePicker.launch(intent);
        });
    }

    private void launchBackup() {
        List<Entry> entries = storage.loadEntries();
        if (entries.isEmpty()) {
            Toast.makeText(this, "No entries to back up.", Toast.LENGTH_SHORT).show();
            return;
        }
        String savedUri = storage.getBackupUri();
        if (savedUri == null || !storage.hasBackupPassword()) {
            // First time â€” ask for password then pick save location
            showExportPasswordDialog();
            return;
        }
        // Existing backup known — offer two options
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        int menuPad = dp(20);
        String[] options = {"Update existing file", "Save to new location"};
        AlertDialog[] dlgRef = {null};
        for (int i = 0; i < options.length; i++) {
            TextView item = new TextView(this);
            item.setText(options[i]);
            item.setTextColor(getResources().getColor(R.color.text_primary));
            item.setTextSize(16f);
            item.setPadding(menuPad, dp(14), menuPad, dp(14));
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            item.setBackgroundResource(ripple.resourceId);
            item.setClickable(true);
            item.setFocusable(true);
            final int idx = i;
            item.setOnClickListener(v -> {
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                if (idx == 0) {
                    Uri uri = Uri.parse(savedUri);
                    String pass = storage.getBackupPassword();
                    performExportToUri(uri, pass, true);
                } else {
                    showExportPasswordDialog();
                }
            });
            menu.addView(item);
            if (i < options.length - 1) {
                View div = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.setMarginStart(menuPad);
                div.setLayoutParams(lp);
                div.setBackgroundColor(getResources().getColor(R.color.divider));
                menu.addView(div);
            }
        }
        AlertDialog backupDlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Backup")
                .setView(menu)
                .setNegativeButton("Cancel", null)
                .create();
        dlgRef[0] = backupDlg;
        backupDlg.show();
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

    // â”€â”€ IMPORT â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
                    // â”€â”€ v24 ZIP format â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    BackupCrypto.ZipContent zipContent = BackupCrypto.decryptZip(fileBytes, password);
                    imported = storage.importFromJson(zipContent.json);
                    if (imported == null) throw new Exception("Corrupted backup data.");
                } else {
                    // â”€â”€ Legacy blob format (pre-v24) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    String json = BackupCrypto.decrypt(fileBytes, password);
                    imported = storage.importFromJson(json);
                    // importFromJson() auto-migrates old attachmentName/Data fields
                    // (Base64 â†’ AttachmentStore files) via StorageHelper.entryFromJson()
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
    private void showPinThenSecurityQ() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);
        EditText etPin = makePinInput();
        etPin.setHint("Current PIN");
        layout.addView(etPin);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Confirm PIN")
                .setView(layout)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        showKeyboardFor(dialog, etPin);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!storage.checkPin(etPin.getText().toString().trim())) {
                etPin.setError("Incorrect PIN");
                etPin.requestFocus();
                return;
            }
            dialog.dismiss();
            showSecurityQDialog();
        });
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
        etQuestion.setHint("Type your questionâ€¦");
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
                .setPositiveButton(R.string.save, null) // null â€” handled below to prevent auto-dismiss
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

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Takes persistable read+write permission for a SAF URI.
     * Must be called on the UI thread immediately after the picker returns the URI â€”
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

    // â”€â”€ Change PIN Flow â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
        // Single dialog with all 3 fields â€” keyboard opens once and stays open
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

    // â”€â”€ v17: Biometric enable confirmation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

}
