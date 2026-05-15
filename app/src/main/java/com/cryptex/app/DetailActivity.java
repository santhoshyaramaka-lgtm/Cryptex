package com.cryptex.app;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Detail screen — two modes:
 *
 *   VIEW mode  (existing entry, default on open)
 *     • Fields are non-editable TextViews styled like input boxes
 *     • Secret fields are masked with ● characters + toggle eye button
 *     • Top bar shows: ✏ Edit | Share | Delete
 *
 *   EDIT mode  (new entry, or after tapping ✏ on existing)
 *     • Fields are editable EditTexts
 *     • Top bar shows: Share | Delete (for existing); nothing for new
 *     • Back with changes → popup: Save | Keep Editing | Discard → LIST
 *     • Back with no changes → LIST silently
 *
 * Extras:
 *   "entry_id"   — ID of existing entry → opens in VIEW mode
 *   "entry_type" — type constant for new entry → opens in EDIT mode
 */
public class DetailActivity extends BaseActivity {

    // ── State ─────────────────────────────────────────────────────────────────
    private StorageHelper storage;
    private List<Entry>   entries;
    private Entry         existingEntry = null;
    private String        entryType;
    private boolean       isEditMode;   // true = edit/new, false = view

    // Per-field views (index 0 = field1 … 6 = field7)
    private final EditText[]     editViews   = new EditText[7];
    private final TextView[]     viewTexts   = new TextView[7];
    private final boolean[]      secretFlags = new boolean[7];
    // Track reveal state per secret field so we can reset on mode switch
    private final boolean[]      revealed    = new boolean[7];
    // Reference to each eye-toggle button so we can reset its icon on mode switch
    private final ImageButton[]  eyeButtons  = new ImageButton[7];

    // Top-bar buttons
    private ImageButton btnEdit, btnShare, btnDelete;

    // v9: Unsaved-changes action bar
    private LinearLayout saveActionBar;

    // v9: Attachment state
    private static final long MAX_ATTACHMENT_BYTES  = 5 * 1024 * 1024; // 5 MB
    private static final long WARN_ATTACHMENT_BYTES = 3 * 1024 * 1024; // 3 MB warn threshold
    private String pendingAttachmentName = null; // null = no change pending
    private String pendingAttachmentData = null; // null = no change pending

    // v12: Clipboard auto-clear
    private static final long CLIPBOARD_CLEAR_DELAY_MS = 30_000; // 30 seconds
    private final android.os.Handler clipboardHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable clipboardClearRunnable = null;

    // v9: Attachment row views — cached once in setupAttachmentRow()
    private TextView    tvAttachmentName;
    private ImageButton btnAttachOpen;
    private ImageButton btnAttachRemove;
    private TextView    tvAttachmentWarning;

    // v12: Created date label shown in VIEW mode
    private TextView    tvCreatedAt;

    // v9: File picker launcher
    private ActivityResultLauncher<String[]> attachmentPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_detail);

        storage = new StorageHelper(this);
        entries = storage.loadEntries();

        // ── Resolve entry / type ──────────────────────────────────────────────
        String entryId = getIntent().getStringExtra("entry_id");
        if (entryId != null) {
            for (Entry e : entries) {
                if (e.getId().equals(entryId)) { existingEntry = e; break; }
            }
        }

        if (existingEntry != null) {
            entryType  = existingEntry.getType();
            isEditMode = false;   // existing → start in VIEW mode
        } else {
            entryType  = getIntent().getStringExtra("entry_type");
            if (entryType == null) entryType = EntryType.WEBSITE;
            isEditMode = true;    // new entry → start in EDIT mode
        }

        // ── Bind top-bar views ────────────────────────────────────────────────
        ((TextView)  findViewById(R.id.tvTypeEmoji)).setText(EntryType.getEmoji(entryType));
        btnEdit   = findViewById(R.id.btnEdit);
        btnShare  = findViewById(R.id.btnShare);
        btnDelete = findViewById(R.id.btnDelete);

        // v9: Unsaved-changes action bar
        saveActionBar = findViewById(R.id.saveActionBar);
        findViewById(R.id.btnBarSave).setOnClickListener(v -> {
            saveActionBar.setVisibility(View.GONE);
            saveEntry();
        });
        findViewById(R.id.btnBarEdit).setOnClickListener(v ->
                saveActionBar.setVisibility(View.GONE));
        findViewById(R.id.btnBarDiscard).setOnClickListener(v -> {
            saveActionBar.setVisibility(View.GONE);
            pendingAttachmentName = null;
            pendingAttachmentData = null;
            finish();
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());

        btnEdit.setOnClickListener(v -> switchToEditMode());

        btnDelete.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Entry")
                    .setMessage("Delete \"" + existingEntry.getDisplayTitle() + "\"? This cannot be undone.")
                    .setPositiveButton("Delete", (d, w) -> {
                        entries.remove(existingEntry);
                        storage.saveEntries(entries);
                        storage.setBackupPending(true);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnShare.setOnClickListener(v -> showShareDialog());

        // v9: Register file picker launcher
        attachmentPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) handleAttachmentPicked(uri);
                });

        // ── Build field rows ──────────────────────────────────────────────────
        LinearLayout container = findViewById(R.id.fieldsContainer);
        String[]  labels = EntryType.getFieldLabels(entryType);
        boolean[] secret = EntryType.getSecretFlags(entryType);

        for (int i = 0; i < 7; i++) {
            secretFlags[i] = secret[i];
            if (labels[i].isEmpty()) continue;
            buildFieldRow(container, i, labels[i], secret[i]);
        }

        // ── Populate values ───────────────────────────────────────────────────
        if (existingEntry != null) {
            for (int i = 0; i < 7; i++) {
                String val = existingEntry.getFieldByIndex(i + 1);
                if (editViews[i]  != null) editViews[i].setText(val);
                if (viewTexts[i]  != null) setViewText(i, val);
            }
        }

        // ── Apply initial mode UI ─────────────────────────────────────────────
        // v12: Build "Added:" label at bottom of fields container
        tvCreatedAt = new TextView(this);
        tvCreatedAt.setTextSize(12f);
        tvCreatedAt.setTextColor(getResources().getColor(R.color.text_secondary));
        LinearLayout.LayoutParams caParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        caParams.topMargin = dpToPx(12);
        tvCreatedAt.setLayoutParams(caParams);
        if (existingEntry != null && existingEntry.getCreatedAt() > 0) {
            tvCreatedAt.setText("Added: " + formatDate(existingEntry.getCreatedAt()));
        }
        tvCreatedAt.setVisibility(View.GONE); // shown only in VIEW mode
        container.addView(tvCreatedAt);

        applyModeUI();

        // v9: Setup attachment row
        setupAttachmentRow();
    }

    // ── Mode switching ────────────────────────────────────────────────────────

    private void switchToEditMode() {
        isEditMode = true;
        saveActionBar.setVisibility(View.GONE); // ensure bar is hidden when entering edit
        // Freshly populate edit fields from the live entry values
        for (int i = 0; i < 7; i++) {
            if (editViews[i] != null && existingEntry != null) {
                editViews[i].setText(existingEntry.getFieldByIndex(i + 1));
            }
        }
        applyModeUI();
        updateAttachmentRow(); // v9: refresh attachment row icons for edit mode
    }

    private void applyModeUI() {
        TextView tvScreenTitle = findViewById(R.id.tvScreenTitle);

        if (isEditMode) {
            // ── EDIT mode ─────────────────────────────────────────────────────
            tvScreenTitle.setText(existingEntry != null
                    ? "Edit " + EntryType.getDisplayName(entryType)
                    : "New "  + EntryType.getDisplayName(entryType));

            btnEdit.setVisibility(View.GONE);

            if (existingEntry != null) {
                btnShare.setVisibility(View.VISIBLE);
                btnDelete.setVisibility(View.VISIBLE);
            } else {
                btnShare.setVisibility(View.GONE);
                btnDelete.setVisibility(View.GONE);
            }

            // Show edit fields, hide view texts; open eye for secret fields
            for (int i = 0; i < 7; i++) {
                if (editViews[i]  != null) editViews[i].setVisibility(View.VISIBLE);
                if (viewTexts[i]  != null) viewTexts[i].setVisibility(View.GONE);
                if (secretFlags[i]) setRevealOpen(i);   // eye open + text visible
            }
            // v12: hide "Added:" label in EDIT mode
            if (tvCreatedAt != null) tvCreatedAt.setVisibility(View.GONE);

        } else {
            // ── VIEW mode ─────────────────────────────────────────────────────
            tvScreenTitle.setText(existingEntry.getDisplayTitle());

            btnEdit.setVisibility(View.VISIBLE);
            btnShare.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.VISIBLE);

            // Show view texts, hide edit fields; close eye for secret fields
            for (int i = 0; i < 7; i++) {
                if (editViews[i]  != null) editViews[i].setVisibility(View.GONE);
                if (viewTexts[i]  != null) viewTexts[i].setVisibility(View.VISIBLE);
                if (secretFlags[i]) resetReveal(i);     // eye closed + text masked
            }
            // v12: show "Added:" label in VIEW mode
            if (tvCreatedAt != null) {
                if (existingEntry != null && existingEntry.getCreatedAt() > 0) {
                    tvCreatedAt.setText("Added: " + formatDate(existingEntry.getCreatedAt()));
                    tvCreatedAt.setVisibility(View.VISIBLE);
                } else {
                    tvCreatedAt.setVisibility(View.GONE);
                }
            }
        }
    }

    // ── Back press ────────────────────────────────────────────────────────────

    // v12: Cancel clipboard clear timer when activity is destroyed
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (clipboardClearRunnable != null) clipboardHandler.removeCallbacks(clipboardClearRunnable);
    }

    // v12: Auto-lock gap fix — save/check timestamp in DetailActivity
    @Override
    protected void onPause() {
        super.onPause();
        // timestamp saved by BaseActivity.onPause()
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkAndHandleAutoLock()) return;
    }

    @Override
    public void onBackPressed() {
        // If the action bar is already visible, second Back dismisses it (stay in edit)
        if (saveActionBar.getVisibility() == View.VISIBLE) {
            saveActionBar.setVisibility(View.GONE);
            return;
        }

        if (isEditMode) {
            if (hasUnsavedChanges()) {
                // Show the inline action bar instead of a dialog
                saveActionBar.setVisibility(View.VISIBLE);
            } else {
                // Nothing changed — go straight to list
                finish();
            }
        } else {
            // VIEW mode — go to list
            super.onBackPressed();
        }
    }

    /** Returns true if any edit field differs from the stored entry value. */
    private boolean hasUnsavedChanges() {
        // RC2 fix: a pending attachment change is also an unsaved change
        if (pendingAttachmentName != null) return true;

        if (existingEntry == null) {
            // New entry: any non-empty field = unsaved change
            for (int i = 0; i < 7; i++) {
                if (editViews[i] != null && !editViews[i].getText().toString().trim().isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        // Existing entry: compare each field
        for (int i = 0; i < 7; i++) {
            if (editViews[i] == null) continue;
            String current  = editViews[i].getText().toString().trim();
            String original = existingEntry.getFieldByIndex(i + 1).trim();
            if (!current.equals(original)) return true;
        }
        return false;
    }

    /** Resets reveal state for a single field index. */
    private void resetReveal(int i) {
        if (!secretFlags[i]) return;
        revealed[i] = false;
        // Reset eye button icon back to closed
        if (eyeButtons[i] != null) {
            eyeButtons[i].setImageResource(R.drawable.ic_eye_off);
        }
        // Re-mask the edit field
        if (editViews[i] != null) {
            editViews[i].setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            int len = editViews[i].getText().length();
            if (len > 0) editViews[i].setSelection(len);
        }
        // Re-mask the view text
        if (viewTexts[i] != null && existingEntry != null) {
            String raw = existingEntry.getFieldByIndex(i + 1);
            setViewText(i, raw);
        }
    }

    /** Sets the eye and edit field to OPEN/visible for edit mode entry. */
    private void setRevealOpen(int i) {
        if (!secretFlags[i]) return;
        revealed[i] = true;
        if (eyeButtons[i] != null) {
            eyeButtons[i].setImageResource(R.drawable.ic_eye);
        }
        if (editViews[i] != null) {
            editViews[i].setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            int len = editViews[i].getText().length();
            if (len > 0) editViews[i].setSelection(len);
        }
    }

    // ── Field row builder ─────────────────────────────────────────────────────

    /**
     * Builds one label + (view TextView) + (edit EditText) + action buttons row.
     * Both the view text and edit text are created; visibility is toggled by applyModeUI().
     */
    private void buildFieldRow(LinearLayout container, int index, String label, boolean isSecret) {
        boolean isNotes = (index == 6);

        // ── Label ─────────────────────────────────────────────────────────────
        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(getResources().getColor(R.color.text_secondary));
        tvLabel.setTextSize(12f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dpToPx(10);
        tvLabel.setLayoutParams(labelParams);
        container.addView(tvLabel);

        // ── Row wrapper (holds both view text and edit text + buttons) ─────────
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin    = dpToPx(4);
        rowParams.bottomMargin = dpToPx(2);
        row.setLayoutParams(rowParams);

        // ── VIEW text (non-editable, styled same as input box) ────────────────
        TextView tvView = new TextView(this);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvView.setLayoutParams(tvParams);
        tvView.setBackground(getResources().getDrawable(R.drawable.input_bg, getTheme()));
        tvView.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        tvView.setTextSize(15f);
        tvView.setTextColor(getResources().getColor(R.color.input_text));
        if (isNotes) {
            tvView.setMinLines(4);
            tvView.setGravity(Gravity.TOP | Gravity.START);
        } else {
            tvView.setSingleLine(true);
        }
        viewTexts[index] = tvView;
        row.addView(tvView);

        // ── EDIT text ─────────────────────────────────────────────────────────
        EditText etEdit = new EditText(this);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etEdit.setLayoutParams(etParams);
        etEdit.setBackground(getResources().getDrawable(R.drawable.input_bg, getTheme()));
        etEdit.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        etEdit.setTextSize(15f);
        etEdit.setTextColor(getResources().getColor(R.color.input_text));
        etEdit.setHintTextColor(getResources().getColor(R.color.hint_color));
        etEdit.setHint(label);
        if (isNotes) {
            etEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            etEdit.setMinLines(4);
            etEdit.setMaxLines(10);
            etEdit.setGravity(Gravity.TOP | Gravity.START);
        } else if (isSecret) {
            // Edit mode starts VISIBLE (open eye) — user should see what they type
            etEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            etEdit.setSingleLine(true);
        } else {
            etEdit.setInputType(InputType.TYPE_CLASS_TEXT);
            etEdit.setSingleLine(true);
        }
        etEdit.setVisibility(View.GONE);   // hidden until EDIT mode
        editViews[index] = etEdit;
        row.addView(etEdit);

        // ── Secret toggle button (eye) ────────────────────────────────────────
        if (isSecret && !isNotes) {
            // Always start closed/masked — applyModeUI() sets the correct state
            ImageButton btnToggle = makeIconButton(R.drawable.ic_eye_off);
            revealed[index] = false;
            eyeButtons[index] = btnToggle;
            btnToggle.setOnClickListener(v -> {
                revealed[index] = !revealed[index];
                // Swap icon: open eye when revealed, closed eye when masked
                btnToggle.setImageResource(
                        revealed[index] ? R.drawable.ic_eye : R.drawable.ic_eye_off);
                if (isEditMode) {
                    etEdit.setInputType(revealed[index]
                            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    etEdit.setSelection(etEdit.getText().length());
                } else {
                    String raw = existingEntry != null
                            ? existingEntry.getFieldByIndex(index + 1) : "";
                    tvView.setText(revealed[index] ? raw : maskText(raw));
                    tvView.setTextColor(getResources().getColor(
                            raw.isEmpty() ? R.color.hint_color : R.color.input_text));
                }
            });
            row.addView(btnToggle);
        }

        // ── Long-press to copy (VIEW mode only) ──────────────────────────────
        tvView.setLongClickable(true);
        tvView.setOnLongClickListener(v -> {
            String text = existingEntry != null
                    ? existingEntry.getFieldByIndex(index + 1) : "";
            if (text.isEmpty()) {
                Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show();
            } else {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(this, "Copied! Clears in 30s", Toast.LENGTH_SHORT).show();
                // v12: cancel any existing clear timer, start a fresh 30s countdown
                if (clipboardClearRunnable != null) clipboardHandler.removeCallbacks(clipboardClearRunnable);
                clipboardClearRunnable = () -> {
                    ClipboardManager cbClear = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cbClear != null) cbClear.setPrimaryClip(ClipData.newPlainText("", ""));
                };
                clipboardHandler.postDelayed(clipboardClearRunnable, CLIPBOARD_CLEAR_DELAY_MS);
            }
            return true;
        });

        container.addView(row);
    }

    // ── Helpers: view text population ─────────────────────────────────────────

    /** Sets the view-mode TextView, masking if the field is secret. */
    private void setViewText(int index, String value) {
        if (viewTexts[index] == null) return;
        if (value.isEmpty()) {
            viewTexts[index].setText("—");
            viewTexts[index].setTextColor(getResources().getColor(R.color.hint_color));
        } else {
            viewTexts[index].setText(secretFlags[index] ? maskText(value) : value);
            viewTexts[index].setTextColor(getResources().getColor(R.color.input_text));
        }
    }

    /** Returns ●●●●● masking string of the same length as the input. */
    private String maskText(String text) {
        if (text.isEmpty()) return "";
        char[] mask = new char[text.length()];
        java.util.Arrays.fill(mask, '●');
        return new String(mask);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private void saveEntry() {
        if (editViews[0] != null && editViews[0].getText().toString().trim().isEmpty()) {
            editViews[0].setError("Required");
            editViews[0].requestFocus();
            return;
        }

        if (existingEntry != null) {
            existingEntry.setType(entryType);
            for (int i = 0; i < 7; i++) {
                if (editViews[i] != null) {
                    existingEntry.setFieldByIndex(i + 1, editViews[i].getText().toString().trim());
                }
            }
            existingEntry.setUpdatedAt(System.currentTimeMillis()); // v8: track last modified
        } else {
            String[] vals = new String[7];
            for (int i = 0; i < 7; i++) {
                vals[i] = (editViews[i] != null) ? editViews[i].getText().toString().trim() : "";
            }
            Entry newEntry = new Entry(UUID.randomUUID().toString(), entryType,
                    vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6]);
            long now = System.currentTimeMillis();
            newEntry.setUpdatedAt(now); // v8: track creation time
            newEntry.setCreatedAt(now); // v12: set creation date once
            entries.add(newEntry);
            existingEntry = newEntry;   // assign so attachment block below can reference it
        }

        // v9: persist pending attachment changes
        if (pendingAttachmentName != null) {
            existingEntry.setAttachmentName(pendingAttachmentName);
            existingEntry.setAttachmentData(pendingAttachmentData != null ? pendingAttachmentData : "");
        }

        storage.saveEntries(entries);
        storage.setBackupPending(true);

        // Go straight to list — no VIEW mode stop
        pendingAttachmentName = null;
        pendingAttachmentData = null;
        finish();
    }

    /**
     * Shows the share confirmation dialog.
     * - No attachment → simple Yes/No confirm.
     * - Has attachment → same dialog + a pre-checked "Include attachment" checkbox
     *   showing the filename so the user can opt out before sharing.
     */
    private void showShareDialog() {
        String attName = pendingAttachmentName != null ? pendingAttachmentName
                : (existingEntry != null ? existingEntry.getAttachmentName() : "");
        boolean hasAttachment = !attName.isEmpty();

        if (!hasAttachment) {
            // Simple confirm — no attachment involved
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Share Entry")
                    .setMessage("This will share your data as plain text.\n\nAre you sure?")
                    .setPositiveButton("Share", (d, w) -> shareEntry(false))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        // Build a small custom view: message text + checkbox + filename
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int ph = dpToPx(24);
        int pv = dpToPx(16);
        layout.setPadding(ph, pv, ph, 0);

        TextView msg = new TextView(this);
        msg.setText("This will share your data as plain text.\n\nAre you sure?");
        msg.setTextSize(15f);
        msg.setTextColor(getResources().getColor(R.color.text_primary));
        layout.addView(msg);

        // Spacer
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(16)));
        layout.addView(spacer);

        // Checkbox row
        CheckBox chk = new CheckBox(this);
        chk.setText("Include attachment");
        chk.setChecked(true);   // default ON — user expects file to come along
        chk.setTextSize(15f);
        chk.setTextColor(getResources().getColor(R.color.text_primary));
        layout.addView(chk);

        // Filename label below checkbox (slightly indented, secondary colour)
        TextView tvFile = new TextView(this);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fileParams.leftMargin = dpToPx(32);
        tvFile.setLayoutParams(fileParams);
        tvFile.setText(attName);
        tvFile.setTextSize(12f);
        tvFile.setTextColor(getResources().getColor(R.color.text_secondary));
        layout.addView(tvFile);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Share Entry")
                .setView(layout)
                .setPositiveButton("Share", (d, w) -> shareEntry(chk.isChecked()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareEntry(boolean withAttachment) {
        String[] labels = EntryType.getFieldLabels(entryType);
        StringBuilder sb = new StringBuilder();
        sb.append(EntryType.getEmoji(entryType))
          .append("  ").append(EntryType.getDisplayName(entryType))
          .append("\n─────────────────────────\n");

        for (int i = 0; i < 7; i++) {
            if (labels[i].isEmpty()) continue;
            String val;
            if (isEditMode && editViews[i] != null) {
                val = editViews[i].getText().toString().trim();
            } else {
                val = existingEntry != null ? existingEntry.getFieldByIndex(i + 1) : "";
            }
            if (!val.isEmpty()) {
                sb.append(labels[i]).append(":  ").append(val).append("\n");
            }
        }
        sb.append("─────────────────────────\nShared from Cryptex");
        String shareText = sb.toString();

        // ── Text-only share (no attachment or checkbox unchecked) ─────────────
        if (!withAttachment) {
            startActivity(Intent.createChooser(
                    buildTextOnlyShareIntent(shareText), "Share via"));
            return;
        }

        // Resolve attachment — pending (unsaved pick) takes priority over saved
        String attName = pendingAttachmentName != null ? pendingAttachmentName
                : (existingEntry != null ? existingEntry.getAttachmentName() : "");
        String attData = pendingAttachmentData != null ? pendingAttachmentData
                : (existingEntry != null ? existingEntry.getAttachmentData() : "");

        boolean shouldAttach = !attName.isEmpty() && !attData.isEmpty();

        if (!shouldAttach) {
            // withAttachment=true but no actual file data — text only
            startActivity(Intent.createChooser(
                    buildTextOnlyShareIntent(shareText), "Share via"));
            return;
        }

        // ── Text + Attachment — ACTION_SEND_MULTIPLE with TWO files ───────────
        //
        // WHY this approach:
        //   ACTION_SEND with EXTRA_TEXT + EXTRA_STREAM is unreliable —
        //   receiving apps treat MIME as the signal: if MIME is a file type
        //   they ignore EXTRA_TEXT; if MIME is text/plain they ignore EXTRA_STREAM.
        //   There is no single intent that reliably delivers both on all apps.
        //
        //   The ONLY approach that works everywhere is ACTION_SEND_MULTIPLE
        //   with two FileProvider URIs:
        //     URI 1 → entry_info.txt  (the formatted text written as a file)
        //     URI 2 → the actual attachment file
        //
        //   Every app (Gmail, WhatsApp, Telegram, Drive) handles two-file share:
        //     Gmail     → subject line + two email attachments
        //     WhatsApp  → two files sent in sequence
        //     Telegram  → two files sent together
        //     Drive     → two files uploaded
        // ─────────────────────────────────────────────────────────────────────
        try {
            File cacheDir = new File(getCacheDir(), "attachments");
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();

            // ── File 1: write the entry text as a .txt file ───────────────────
            String txtFileName = (existingEntry != null
                    ? existingEntry.getDisplayTitle()
                            .replaceAll("[^a-zA-Z0-9_\\-]", "_")
                    : "entry") + ".txt";
            File txtFile = new File(cacheDir, txtFileName);
            try (FileOutputStream fos = new FileOutputStream(txtFile)) {
                fos.write(shareText.getBytes("UTF-8"));
            }
            Uri txtUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", txtFile);

            // ── File 2: decode and write the attachment ───────────────────────
            byte[] attBytes = Base64.decode(attData, Base64.NO_WRAP);
            File attFile = new File(cacheDir, attName);
            try (FileOutputStream fos = new FileOutputStream(attFile)) {
                fos.write(attBytes);
            }
            Uri attUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", attFile);

            // ── Build ACTION_SEND_MULTIPLE with both URIs ─────────────────────
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            uris.add(txtUri);   // text info first
            uris.add(attUri);   // actual attachment second

            Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            shareIntent.setType("*/*");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                    existingEntry != null ? existingEntry.getDisplayTitle() : "");
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);

            // Grant read permission for both URIs via ClipData
            ClipData clip = ClipData.newRawUri("", txtUri);
            clip.addItem(new ClipData.Item(attUri));
            shareIntent.setClipData(clip);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(shareIntent, "Share via");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);

        } catch (Exception e) {
            // Any failure — fall back to text-only gracefully
            Toast.makeText(this,
                    "Could not attach file — sharing text only.",
                    Toast.LENGTH_SHORT).show();
            startActivity(Intent.createChooser(
                    buildTextOnlyShareIntent(shareText), "Share via"));
        }
    }

    /** Builds a plain-text only share intent. */
    private Intent buildTextOnlyShareIntent(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT,
                existingEntry != null ? existingEntry.getDisplayTitle() : "");
        i.putExtra(Intent.EXTRA_TEXT, text);
        return i;
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private ImageButton makeIconButton(int drawableRes) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(drawableRes);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44));
        p.leftMargin = dpToPx(4);
        btn.setLayoutParams(p);
        int[] attrs = new int[]{android.R.attr.selectableItemBackgroundBorderless};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        btn.setBackground(ta.getDrawable(0));
        ta.recycle();
        return btn;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ── v9: Attachment ────────────────────────────────────────────────────────

    /** Initial setup of attachment row — caches views and binds click handlers. */
    private void setupAttachmentRow() {
        // Cache views once — avoids repeated findViewById on every updateAttachmentRow() call
        tvAttachmentName    = findViewById(R.id.tvAttachmentName);
        btnAttachOpen       = findViewById(R.id.btnAttachOpen);
        btnAttachRemove     = findViewById(R.id.btnAttachRemove);
        tvAttachmentWarning = findViewById(R.id.tvAttachmentWarning);

        btnAttachOpen.setOnClickListener(v -> {
            if (isEditMode) {
                // Launch file picker
                attachmentPicker.launch(new String[]{"*/*"});
            } else {
                // Open existing attachment
                openAttachment();
            }
        });

        btnAttachRemove.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.remove_btn))
                        .setMessage(getString(R.string.confirm_remove_attachment))
                        .setPositiveButton(getString(R.string.remove_btn), (d, w) -> {
                            // Clear on the entry object immediately and persist —
                            // no "pending" here because remove should not require
                            // a separate Save tap; it saves instantly like unfavourite.
                            if (existingEntry != null) {
                                existingEntry.setAttachmentName("");
                                existingEntry.setAttachmentData("");
                                storage.saveEntries(entries);
                                storage.setBackupPending(true);
                            }
                            // Also clear any in-flight pending so the row reflects reality
                            pendingAttachmentName = null;
                            pendingAttachmentData = null;
                            updateAttachmentRow();
                            Toast.makeText(this, R.string.attachment_removed, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
        );

        updateAttachmentRow();
    }

    /** Refreshes the attachment row UI based on current state. */
    private void updateAttachmentRow() {
        // Resolve effective name/data (pending overrides saved)
        String name = pendingAttachmentName != null ? pendingAttachmentName
                : (existingEntry != null ? existingEntry.getAttachmentName() : "");
        String data = pendingAttachmentData != null ? pendingAttachmentData
                : (existingEntry != null ? existingEntry.getAttachmentData() : "");

        boolean hasFile = !name.isEmpty();

        if (hasFile) {
            // Show filename + approx size
            long bytes = (data.length() * 3L) / 4; // approx decode size
            String sizeStr = formatBytes(bytes);
            if (bytes >= WARN_ATTACHMENT_BYTES) {
                tvAttachmentWarning.setVisibility(View.VISIBLE);
            } else {
                tvAttachmentWarning.setVisibility(View.GONE);
            }
            tvAttachmentName.setText(name + "  " + sizeStr);
            tvAttachmentName.setTextColor(getResources().getColor(R.color.text_primary));
        } else {
            tvAttachmentWarning.setVisibility(View.GONE);
            if (isEditMode) {
                tvAttachmentName.setText(getString(R.string.no_file_attached));
            } else {
                tvAttachmentName.setText(getString(R.string.no_attachment));
            }
            tvAttachmentName.setTextColor(getResources().getColor(R.color.hint_color));
        }

        // Open/Attach button: always visible in EDIT; in VIEW only when file exists
        btnAttachOpen.setVisibility((isEditMode || hasFile) ? View.VISIBLE : View.GONE);

        // Remove button: only in EDIT mode when a file exists
        btnAttachRemove.setVisibility((isEditMode && hasFile) ? View.VISIBLE : View.GONE);
    }

    /** Called when user picks a file from SAF picker. */
    private void handleAttachmentPicked(Uri uri) {
        try {
            // Read all bytes in one pass (avoids opening the stream twice)
            byte[] bytes;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new Exception("Cannot read file");
                bytes = is.readAllBytes();
            }

            // Reject if over size limit
            if (bytes.length > MAX_ATTACHMENT_BYTES) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("File Too Large")
                        .setMessage(getString(R.string.file_too_large)
                                + "  (" + formatBytes(bytes.length) + ")")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }

            // Get original filename
            String fileName = "attachment";
            android.database.Cursor cursor = getContentResolver().query(
                    uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) fileName = cursor.getString(idx);
                cursor.close();
            }

            // Encode to Base64
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);

            pendingAttachmentName = fileName;
            pendingAttachmentData = base64;
            updateAttachmentRow();

        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.attachment_open_error), Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the attachment — first shows which apps support it, then launches chooser. */
    private void openAttachment() {
        String name = pendingAttachmentName != null ? pendingAttachmentName
                : (existingEntry != null ? existingEntry.getAttachmentName() : "");
        String data = pendingAttachmentData != null ? pendingAttachmentData
                : (existingEntry != null ? existingEntry.getAttachmentData() : "");

        if (name.isEmpty() || data.isEmpty()) return;

        try {
            // Decode and write to cache
            byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
            File cacheDir = new File(getCacheDir(), "attachments");
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
            File outFile = new File(cacheDir, name);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(bytes);
            }

            // FileProvider URI
            Uri fileUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", outFile);

            // Resolve MIME type
            String ext = MimeTypeMap.getFileExtensionFromUrl(
                    Uri.fromFile(outFile).toString());
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    ext != null ? ext.toLowerCase() : "");
            if (mime == null || mime.isEmpty()) mime = "*/*";

            // Query which apps can handle this MIME
            Intent queryIntent = new Intent(Intent.ACTION_VIEW);
            queryIntent.setDataAndType(fileUri, mime);
            queryIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            PackageManager pm = getPackageManager();
            List<ResolveInfo> matched = pm.queryIntentActivities(queryIntent,
                    PackageManager.MATCH_DEFAULT_ONLY);

            // Also query with */* to catch file managers
            Intent queryFallback = new Intent(Intent.ACTION_VIEW);
            queryFallback.setDataAndType(fileUri, "*/*");
            queryFallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<ResolveInfo> fallbackMatched = pm.queryIntentActivities(queryFallback,
                    PackageManager.MATCH_DEFAULT_ONLY);

            // Merge both lists — deduplicate by package name
            List<String> appNames = new ArrayList<>();
            List<String> seenPackages = new ArrayList<>();
            for (ResolveInfo ri : matched) {
                String pkg = ri.activityInfo.packageName;
                if (!seenPackages.contains(pkg)) {
                    seenPackages.add(pkg);
                    appNames.add("• " + ri.loadLabel(pm).toString());
                }
            }
            for (ResolveInfo ri : fallbackMatched) {
                String pkg = ri.activityInfo.packageName;
                if (!seenPackages.contains(pkg)) {
                    seenPackages.add(pkg);
                    appNames.add("• " + ri.loadLabel(pm).toString());
                }
            }

            // Build the final chooser intent
            final String finalMime = mime;
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, finalMime);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            viewIntent.setClipData(ClipData.newRawUri("attachment", fileUri));

            Intent chooserIntent = Intent.createChooser(viewIntent, "Open with");
            chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (appNames.isEmpty()) {
                // No apps found — tell user what type of app they need
                String suggestion = getSuggestedAppHint(ext != null ? ext.toLowerCase() : "");
                new MaterialAlertDialogBuilder(this)
                        .setTitle("No App Found")
                        .setMessage("No app on your device can open this file type (."
                                + (ext != null ? ext : "unknown") + ").\n\n"
                                + suggestion)
                        .setPositiveButton("OK", null)
                        .show();
            } else {
                // Directly launch the chooser — no intermediate dialog
                try {
                    startActivity(chooserIntent);
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
                }
            }

        } catch (Exception e) {
            Toast.makeText(this, R.string.attachment_open_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Returns a hint suggesting what kind of app to install for the given extension.
     * Called only when no app is found on the device.
     */
    private String getSuggestedAppHint(String ext) {
        switch (ext) {
            case "pdf":
                return "Try installing a PDF viewer such as:\n• Adobe Acrobat Reader\n• Google Drive\n• WPS Office";
            case "doc": case "docx":
                return "Try installing a Word processor such as:\n• Microsoft Word\n• WPS Office\n• Google Docs";
            case "xls": case "xlsx":
                return "Try installing a Spreadsheet app such as:\n• Microsoft Excel\n• WPS Office\n• Google Sheets";
            case "ppt": case "pptx":
                return "Try installing a Presentation app such as:\n• Microsoft PowerPoint\n• WPS Office\n• Google Slides";
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp":
                return "Try installing a photo viewer such as:\n• Google Photos\n• Gallery";
            case "mp4": case "mkv": case "avi": case "mov": case "3gp":
                return "Try installing a video player such as:\n• VLC\n• MX Player\n• Google Photos";
            case "mp3": case "aac": case "wav": case "flac": case "ogg":
                return "Try installing a music player such as:\n• VLC\n• Google Play Music\n• Spotify";
            case "zip": case "rar": case "7z":
                return "Try installing a file archive app such as:\n• ZArchiver\n• RAR\n• Files by Google";
            case "txt": case "csv": case "log":
                return "Try installing a text editor such as:\n• Simple Text Editor\n• Files by Google\n• Notepad";
            default:
                return "Try installing Files by Google or a file manager app that can open this format.";
        }
    }

    /** Formats bytes to human-readable string. */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** v12: Formats a timestamp millis to a readable date string. */
    private String formatDate(long millis) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(millis);
        java.text.SimpleDateFormat fmt = (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR))
                ? new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                : new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(millis));
    }
}
