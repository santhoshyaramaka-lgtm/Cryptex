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
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private ImageButton btnEdit, btnShare, btnDelete, btnArchive;

    // v9: Unsaved-changes action bar
    private LinearLayout saveActionBar;

    // v24: Attachment state (replaces single pendingAttachmentName/Data from v9)
    private static final int  MAX_ATTACHMENT_COUNT  = 5;
    private static final long MAX_TOTAL_BYTES       = 100L * 1024 * 1024; // 100 MB total (5 × 20 MB)
    private static final long MAX_SINGLE_BYTES      =  20L * 1024 * 1024; // 20 MB per file
    private AttachmentStore attachmentStore;
    // Files the user has added in this edit session but not yet saved
    private final List<PendingAttachment> pendingAdds = new ArrayList<>();
    // IDs of existing saved attachments the user has removed in this edit session
    private final Set<String> pendingRemovals = new HashSet<>();

    // v12: Clipboard auto-clear
    private static final long CLIPBOARD_CLEAR_DELAY_MS = 30_000; // 30 seconds
    private final android.os.Handler clipboardHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable clipboardClearRunnable = null;

    // v24: Attachment section views — bound in setupAttachmentSection()
    private LinearLayout attachmentListContainer;
    private TextView     btnAddAttachment;
    private boolean      attachmentsExpanded = false; // VIEW mode: 2+ files collapsed by default

    // v12: Created date label shown in VIEW mode
    private TextView    tvCreatedAt;

    // v20: Checklist views
    private boolean      checklistRendering = false; // re-entrancy guard for renderChecklist()
    private String       checklistEditingItemId = null; // ID of item currently being inline-edited
    private LinearLayout checklistContainer;
    private LinearLayout checklistUncheckedItems;
    private LinearLayout checklistCheckedItems;
    private View         checklistDividerRow;  // the whole divider+button row
    private TextView     btnClearCompleted;
    private TextView     tvChecklistProgress;
    private TextView     tvChecklistEmpty;
    private android.widget.EditText    etAddItem;
    private android.widget.ImageButton btnCancelAddItem;
    private CheckBox                   cbAddItemPreview;
    private TextView                   tvAddItemPlus;
    private View                       checklistAddRowSecondary;
    private boolean                    isCommittingAddItem = false; // guard: prevent focus-loss deactivation during commit

    // v9: File picker launcher
    private ActivityResultLauncher<String[]> attachmentPicker;
    // v20: Camera capture launcher
    private ActivityResultLauncher<Uri>      cameraPicker;
    private Uri                              cameraOutputUri = null; // temp file URI for capture

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_detail);

        storage = StorageHelper.getInstance(this);
        entries = storage.loadEntries();
        attachmentStore = new AttachmentStore(this); // v24

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
        btnEdit    = findViewById(R.id.btnEdit);
        btnShare   = findViewById(R.id.btnShare);
        btnDelete  = findViewById(R.id.btnDelete);
        btnArchive = findViewById(R.id.btnArchive);

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
            pendingAdds.clear();     // v24: discard any un-saved picks — files not written yet
            pendingRemovals.clear(); // v24: discard removals — files not deleted yet
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
                        // v24: delete attachment files before removing entry
                        final List<Attachment> attachmentsToDelete =
                                new ArrayList<>(existingEntry.getAttachments());
                        // Serialize on main thread, write on background, finish() AFTER write
                        // so TypeListActivity.onResume() never reads stale data
                        final String json = storage.exportToJson(entries);
                        if (json != null) {
                            new Thread(() -> {
                                attachmentStore.deleteAll(attachmentsToDelete); // v24
                                storage.saveEntriesJson(json);
                                storage.setBackupPending(true);
                                runOnUiThread(() -> finish());
                            }).start();
                        } else {
                            new Thread(() -> {
                                attachmentStore.deleteAll(attachmentsToDelete); // v24
                                runOnUiThread(() -> finish());
                            }).start();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnShare.setOnClickListener(v -> showShareDialog());

        // v19: Archive / Unarchive
        btnArchive.setOnClickListener(v -> {
            boolean currentlyArchived = existingEntry.isArchived();
            String title = currentlyArchived
                    ? getString(R.string.unarchive_confirm_title)
                    : getString(R.string.archive_confirm_title);
            String msg = currentlyArchived
                    ? getString(R.string.unarchive_confirm_msg)
                    : getString(R.string.archive_confirm_msg);
            String action = currentlyArchived
                    ? getString(R.string.unarchive)
                    : getString(R.string.archive);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton(action, (d, w) -> {
                        boolean nowArchived = !currentlyArchived;
                        existingEntry.setArchived(nowArchived);
                        // Auto-unstar when archiving
                        if (nowArchived && existingEntry.isFavourite()) {
                            existingEntry.setFavourite(false);
                            existingEntry.setPinnedAt(0);
                        }
                        // v24: if archiving, delete attachment files (archived entries cannot be opened)
                        final List<Attachment> attachmentsToDelete = nowArchived
                                ? new ArrayList<>(existingEntry.getAttachments())
                                : java.util.Collections.emptyList();
                        if (nowArchived) existingEntry.setAttachments(new ArrayList<>());
                        // Serialize on main thread, write on background, finish() AFTER write
                        // so TypeListActivity.onResume() never reads stale data
                        final String json = storage.exportToJson(entries);
                        if (json != null) {
                            new Thread(() -> {
                                if (!attachmentsToDelete.isEmpty())
                                    attachmentStore.deleteAll(attachmentsToDelete);
                                storage.saveEntriesJson(json);
                                storage.setBackupPending(true);
                                runOnUiThread(() -> finish());
                            }).start();
                        } else {
                            new Thread(() -> {
                                if (!attachmentsToDelete.isEmpty())
                                    attachmentStore.deleteAll(attachmentsToDelete);
                                runOnUiThread(() -> finish());
                            }).start();
                        }
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        });

        // v9: Register file picker launcher
        attachmentPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) handleAttachmentPicked(uri);
                });

        // v20: Register camera capture launcher
        cameraPicker = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraOutputUri != null) handleCameraCapture();
                });

        // ── Build field rows ──────────────────────────────────────────────────
        LinearLayout container = findViewById(R.id.fieldsContainer);

        // v20: bind checklist views
        checklistContainer      = findViewById(R.id.checklistContainer);
        checklistUncheckedItems = findViewById(R.id.checklistUncheckedItems);
        checklistCheckedItems   = findViewById(R.id.checklistCheckedItems);
        checklistDividerRow     = findViewById(R.id.checklistDividerRow);
        btnClearCompleted       = findViewById(R.id.btnClearCompleted);
        tvChecklistProgress     = findViewById(R.id.tvChecklistProgress);
        tvChecklistEmpty        = findViewById(R.id.tvChecklistEmpty);
        etAddItem                = findViewById(R.id.etAddItem);
        btnCancelAddItem         = findViewById(R.id.btnCancelAddItem);
        cbAddItemPreview         = findViewById(R.id.cbAddItemPreview);
        tvAddItemPlus            = findViewById(R.id.tvAddItemPlus);
        checklistAddRowSecondary = findViewById(R.id.checklistAddRowSecondary);

        btnClearCompleted.setOnClickListener(v -> {
            if (existingEntry == null) return;
            long checkedCount = existingEntry.getChecklistItems().stream()
                    .filter(ChecklistItem::isChecked).count();
            if (checkedCount == 0) return;
            String msg = checkedCount == 1
                    ? "Remove 1 completed item?"
                    : "Remove " + checkedCount + " completed items?";
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Clear completed")
                    .setMessage(msg)
                    .setPositiveButton("Remove", (d, w) -> {
                        existingEntry.getChecklistItems().removeIf(ChecklistItem::isChecked);
                        saveChecklistAndRefresh();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        if (EntryType.CHECKLIST.equals(entryType)) {
            // Checklist type — skip fixed field rows, show checklist UI
            container.setVisibility(View.GONE);
            checklistContainer.setVisibility(View.VISIBLE);
            setupChecklistAddRow();
            if (existingEntry == null) {
                // New checklist — prompt for title first
                promptChecklistTitle();
            } else {
                renderChecklist();
            }
        } else if (EntryType.NOTE.equals(entryType)) {
            // Note type — clean paper-like UI, no boxes, no labels
            buildNoteUI(container);
            if (existingEntry != null) {
                if (editViews[0] != null) editViews[0].setText(existingEntry.getField1());
                if (editViews[6] != null) editViews[6].setText(existingEntry.getField7());
                if (viewTexts[0] != null) viewTexts[0].setText(existingEntry.getField1());
                if (viewTexts[6] != null) viewTexts[6].setText(existingEntry.getField7());
            }
        } else {
            // All other types — normal field rows
            String[]  labels = EntryType.getFieldLabels(entryType);
            boolean[] secret = EntryType.getSecretFlags(entryType);
            for (int i = 0; i < 7; i++) {
                secretFlags[i] = secret[i];
                if (labels[i].isEmpty()) continue;
                buildFieldRow(container, i, labels[i], secret[i]);
            }
            // ── Populate values ───────────────────────────────────────────────
            if (existingEntry != null) {
                for (int i = 0; i < 7; i++) {
                    String val = existingEntry.getFieldByIndex(i + 1);
                    if (editViews[i]  != null) editViews[i].setText(val);
                    if (viewTexts[i]  != null) setViewText(i, val);
                }
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

        // v24: Setup attachment section (replaces v9 setupAttachmentRow)
        setupAttachmentSection();
    }

    // ── Mode switching ────────────────────────────────────────────────────────

    // v19: update archive button icon and tint based on current archived state
    private void updateArchiveButton() {
        if (existingEntry == null || btnArchive == null) return;
        btnArchive.setImageResource(R.drawable.ic_archive);
        btnArchive.setColorFilter(existingEntry.isArchived()
                ? 0xFFFFAA00  // amber tint = currently archived
                : 0xFFFFFFFF); // white = not archived
    }

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
        renderAttachmentList(); // v24: refresh attachment list for edit mode
    }

    private void applyModeUI() {
        TextView tvScreenTitle = findViewById(R.id.tvScreenTitle);

        // v20: Checklist has its own permanent UI — no edit/view mode toggle needed
        if (EntryType.CHECKLIST.equals(entryType)) {
            if (existingEntry != null) {
                tvScreenTitle.setText(existingEntry.getDisplayTitle());
                btnEdit.setVisibility(View.GONE);
                btnShare.setVisibility(View.VISIBLE);
                btnDelete.setVisibility(View.VISIBLE);
                btnArchive.setVisibility(View.VISIBLE);
                updateArchiveButton();
            } else {
                // New checklist — show title as "New Checklist", save on first item add
                tvScreenTitle.setText("New Checklist");
                btnEdit.setVisibility(View.GONE);
                btnShare.setVisibility(View.GONE);
                btnDelete.setVisibility(View.GONE);
                btnArchive.setVisibility(View.GONE);
            }
            return;
        }

        // Note type — clean paper UI, no boxes
        if (EntryType.NOTE.equals(entryType)) {
            applyNoteModeUI(tvScreenTitle);
            return;
        }

        if (isEditMode) {
            // ── EDIT mode ─────────────────────────────────────────────────────
            tvScreenTitle.setText(existingEntry != null
                    ? "Edit " + EntryType.getDisplayName(entryType)
                    : "New "  + EntryType.getDisplayName(entryType));

            btnEdit.setVisibility(View.GONE);
            btnArchive.setVisibility(View.GONE); // v19: hidden in edit mode

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
            btnArchive.setVisibility(View.VISIBLE); // v19
            updateArchiveButton();

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
        // v20: checklist — commit any in-flight edits, then go back
        if (EntryType.CHECKLIST.equals(entryType)) {
            // 1. Commit etAddItem staging text (saves + re-renders if non-empty)
            commitAddItem();
            // 2. If a row etText has focus, retrieve its tagged item and save the text,
            //    then null the focus listener so the dying activity doesn't get a re-render.
            View focused = getCurrentFocus();
            if (focused instanceof android.widget.EditText && focused != etAddItem) {
                Object tag = focused.getTag();
                if (tag instanceof ChecklistItem && existingEntry != null) {
                    ChecklistItem ci = (ChecklistItem) tag;
                    String editedText = ((android.widget.EditText) focused).getText().toString().trim();
                    if (editedText.isEmpty()) {
                        existingEntry.getChecklistItems().remove(ci);
                    } else {
                        ci.setText(editedText);
                    }
                    ((android.widget.EditText) focused).setOnFocusChangeListener(null);
                    checklistEditingItemId = null;
                    existingEntry.setUpdatedAt(System.currentTimeMillis());
                    storage.saveEntries(entries);
                    storage.setBackupPending(true);
                }
            }
            finish();
            return;
        }
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
        // v24: pending attachment adds or removals are unsaved changes
        if (!pendingAdds.isEmpty() || !pendingRemovals.isEmpty()) return true;

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

    // ── v20: Checklist ────────────────────────────────────────────────────────

    /** For new checklists — show a title dialog before showing the items UI. */
    private void promptChecklistTitle() {
        android.widget.EditText etTitle = new android.widget.EditText(this);
        etTitle.setHint("Checklist name");
        etTitle.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        // Title always uppercase
        etTitle.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps()});
        int pad = dpToPx(16);
        etTitle.setPadding(pad, pad, pad, pad);

        new MaterialAlertDialogBuilder(this)
                .setTitle("New Checklist")
                .setView(etTitle)
                .setCancelable(false)
                .setPositiveButton("Create", (d, w) -> {
                    String title = etTitle.getText().toString().trim().toUpperCase();
                    if (title.isEmpty()) title = "CHECKLIST";
                    // Create and persist the entry immediately
                    long now = System.currentTimeMillis();
                    Entry newEntry = new Entry(UUID.randomUUID().toString(),
                            EntryType.CHECKLIST,
                            title, "", "", "", "", "", "");
                    newEntry.setUpdatedAt(now);
                    newEntry.setCreatedAt(now);
                    entries.add(newEntry);
                    existingEntry = newEntry;
                    // Save in background — UI updates from in-memory list, not from disk
                    final String newJson = storage.exportToJson(entries);
                    if (newJson != null) {
                        new Thread(() -> {
                            storage.saveEntriesJson(newJson);
                            storage.setBackupPending(true);
                        }).start();
                    }
                    // Update UI
                    ((TextView) findViewById(R.id.tvScreenTitle)).setText(title);
                    btnDelete.setVisibility(View.VISIBLE);
                    btnShare.setVisibility(View.VISIBLE);
                    btnArchive.setVisibility(View.VISIBLE);
                    updateArchiveButton();
                    renderChecklist();
                    // Auto-focus add row on brand-new checklist
                    etAddItem.post(() -> {
                        etAddItem.requestFocus();
                        android.view.inputmethod.InputMethodManager imm2 =
                                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm2 != null) imm2.showSoftInput(etAddItem, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    });
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .show();

        // Auto-show keyboard
        etTitle.post(() -> {
            etTitle.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etTitle, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void setupChecklistAddRow() {
        // Tapping the secondary row — only acts if input has text (same as pressing Enter)
        // Uses ACTION_UP on touch to prevent etAddItem losing focus before we act
        checklistAddRowSecondary.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                String text = etAddItem.getText().toString().trim();
                if (!text.isEmpty()) {
                    commitAddItem(); // commit + keep focus + keep keyboard open
                }
                // empty input → do nothing
            }
            return true; // always consume touch so etAddItem never loses focus
        });

        // Gain focus → activate: replace "+" with checkbox, show ✕ and secondary row
        etAddItem.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                tvAddItemPlus.setVisibility(View.GONE);
                cbAddItemPreview.setVisibility(View.VISIBLE);
                btnCancelAddItem.setVisibility(View.VISIBLE);
                checklistAddRowSecondary.setVisibility(View.VISIBLE);
            } else {
                // Lost focus — commit if text present, then deactivate
                commitAddItem();
                tvAddItemPlus.setVisibility(View.VISIBLE);
                cbAddItemPreview.setVisibility(View.GONE);
                btnCancelAddItem.setVisibility(View.GONE);
                checklistAddRowSecondary.setVisibility(View.GONE);
            }
        });

        // Enter key → commit item, stay focused (secondary row stays visible)
        etAddItem.setOnEditorActionListener((v, actionId, event) -> {
            commitAddItem();
            return true;
        });

        // ✕ button → clear text, remove focus → deactivates the row
        btnCancelAddItem.setOnClickListener(v -> {
            etAddItem.setText("");
            etAddItem.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etAddItem.getWindowToken(), 0);
        });
    }

    /** Commits whatever text is in etAddItem as a new checklist item (if non-empty). */
    private void commitAddItem() {
        String text = etAddItem.getText().toString().trim();
        if (!text.isEmpty() && existingEntry != null) {
            ChecklistItem item = ChecklistItem.create(text);
            existingEntry.getChecklistItems().add(item);
            etAddItem.setText("");

            // Grab focus + keep keyboard open BEFORE rebuild
            // This prevents the focus-loss gap caused by removeAllViews() during re-render
            etAddItem.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etAddItem,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);

            // Rebuild list — focus already held, no visible delay
            saveChecklistAndRefresh();
        }
    }

    /** Renders the full checklist — unchecked items, add row, divider, checked items. */
    private void renderChecklist() {
        if (checklistContainer == null) return;
        if (checklistRendering) return; // re-entrancy guard: focus-loss on removeAllViews can re-trigger
        checklistRendering = true;
        try {
            checklistUncheckedItems.removeAllViews();
            checklistCheckedItems.removeAllViews();

            if (existingEntry == null) {
                tvChecklistEmpty.setVisibility(View.VISIBLE);
                checklistDividerRow.setVisibility(View.GONE);
                updateChecklistProgress();
                return;
            }

            java.util.List<ChecklistItem> items = existingEntry.getChecklistItems();
            int total   = items.size();
            int checked = 0;
            for (ChecklistItem item : items) { if (item.isChecked()) checked++; }

            tvChecklistEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
            checklistDividerRow.setVisibility(checked > 0 ? View.VISIBLE : View.GONE);

            for (ChecklistItem item : items) {
                android.view.View row = buildChecklistItemRow(item);
                if (item.isChecked()) {
                    checklistCheckedItems.addView(row);
                } else {
                    checklistUncheckedItems.addView(row);
                }
                // If this item was tapped for editing, open its editor after the render
                if (item.getId().equals(checklistEditingItemId)) {
                    android.widget.EditText etText = row.findViewById(R.id.etItemText);
                    TextView               tvText  = row.findViewById(R.id.tvItemText);
                    android.widget.ImageButton btnDel = row.findViewById(R.id.btnDeleteItem);
                    tvText.setVisibility(View.GONE);
                    etText.setVisibility(View.VISIBLE);
                    btnDel.setVisibility(View.VISIBLE);
                    etText.setText(item.getText());
                    etText.post(() -> {
                        etText.requestFocus();
                        etText.setSelection(etText.getText().length());
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(etText,
                                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    });
                }
            }
            updateChecklistProgress();
        } finally {
            checklistRendering = false;
        }
    }

    private android.view.View buildChecklistItemRow(ChecklistItem item) {
        android.view.View rowView = getLayoutInflater().inflate(R.layout.item_checklist_row, null);

        android.widget.CheckBox cb       = rowView.findViewById(R.id.cbItem);
        TextView                tvText   = rowView.findViewById(R.id.tvItemText);
        android.widget.EditText etText   = rowView.findViewById(R.id.etItemText);
        android.widget.ImageButton btnDel = rowView.findViewById(R.id.btnDeleteItem);

        // Set checkbox state — set BEFORE attaching listener to avoid triggering it during bind
        cb.setOnCheckedChangeListener(null);
        cb.setChecked(item.isChecked());
        applyChecklistItemStyle(tvText, item);
        etText.setTag(item); // v20: tag so onBackPressed can find the item from the focused view

        // Checkbox toggle — move row in-place, save in background
        cb.setOnCheckedChangeListener((btn, isChecked) -> {
            item.setChecked(isChecked);
            // If an item is being inline-edited, fall back to full rebuild to
            // preserve the editing state; otherwise move the row in-place
            if (checklistEditingItemId != null) {
                renderChecklist();
            } else {
                toggleChecklistItemInPlace(item, rowView, isChecked);
            }
            saveInBackground();
        });

        // Tap text → mark this item as being edited and re-render.
        // post() defers until after any in-progress render (guard) has finished.
        tvText.setOnClickListener(v -> {
            checklistEditingItemId = item.getId();
            etAddItem.post(() -> renderChecklist());
        });

        // Focus lost → auto-save text, or delete item if text was cleared
        etText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                checklistEditingItemId = null; // no longer editing this item
                String newText = etText.getText().toString().trim();
                if (newText.isEmpty()) {
                    // User cleared the text — treat as delete
                    etText.setOnFocusChangeListener(null); // prevent re-entrancy
                    if (existingEntry != null) existingEntry.getChecklistItems().remove(item);
                } else {
                    item.setText(newText);
                }
                saveChecklistAndRefresh();
            }
        });

        // Done/Enter on keyboard → save
        etText.setOnEditorActionListener((v, actionId, event) -> {
            etText.clearFocus();
            return true;
        });

        // ✕ delete — clear focus listener first to avoid double-save on focus loss
        btnDel.setOnClickListener(v -> {
            etText.setOnFocusChangeListener(null);
            if (existingEntry != null) {
                existingEntry.getChecklistItems().remove(item);
                saveChecklistAndRefresh();
            }
        });

        return rowView;
    }

    private void applyChecklistItemStyle(TextView tv, ChecklistItem item) {
        tv.setText(item.getText());
        if (item.isChecked()) {
            tv.setPaintFlags(tv.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            tv.setTextColor(getResources().getColor(R.color.text_secondary));
        } else {
            tv.setPaintFlags(tv.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            tv.setTextColor(getResources().getColor(R.color.text_primary));
        }
    }

    /**
     * Moves a checklist row in-place between the unchecked/checked containers
     * without a full removeAllViews() rebuild. Called on checkbox tap when no
     * item is being inline-edited. Avoids re-inflating all rows and eliminates
     * the visual flicker caused by a full renderChecklist().
     */
    private void toggleChecklistItemInPlace(ChecklistItem item, android.view.View rowView, boolean isChecked) {
        // Update visual style (strikethrough + colour) on the text view in this row
        TextView tvText = rowView.findViewById(R.id.tvItemText);
        applyChecklistItemStyle(tvText, item);

        // Move the row to the correct container
        android.view.ViewGroup parent = (android.view.ViewGroup) rowView.getParent();
        if (parent != null) parent.removeView(rowView);

        if (isChecked) {
            // Checked items append to the bottom of the checked section
            checklistCheckedItems.addView(rowView);
        } else {
            // Unchecked: re-insert at the position matching the model list order
            java.util.List<ChecklistItem> items = existingEntry.getChecklistItems();
            int insertPos = 0;
            for (ChecklistItem ci : items) {
                if (ci.getId().equals(item.getId())) break;
                if (!ci.isChecked()) insertPos++;
            }
            checklistUncheckedItems.addView(rowView, insertPos);
        }

        // Sync divider, progress, empty state
        java.util.List<ChecklistItem> allItems = existingEntry.getChecklistItems();
        int checkedCount = 0;
        for (ChecklistItem ci : allItems) if (ci.isChecked()) checkedCount++;
        checklistDividerRow.setVisibility(checkedCount > 0 ? View.VISIBLE : View.GONE);
        tvChecklistEmpty.setVisibility(allItems.isEmpty() ? View.VISIBLE : View.GONE);
        updateChecklistProgress();
    }

    private void updateChecklistProgress() {
        if (existingEntry == null || tvChecklistProgress == null) return;
        java.util.List<ChecklistItem> items = existingEntry.getChecklistItems();
        int total   = items.size();
        int checked = 0;
        for (ChecklistItem item : items) { if (item.isChecked()) checked++; }
        tvChecklistProgress.setText(total == 0 ? "" : checked + " of " + total + " done");
        tvChecklistProgress.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
    }

    private void saveChecklistAndRefresh() {
        // Use saveInBackground() — same as checkbox toggle — so text edits,
        // inline deletes and Enter-to-save never block the UI thread
        saveInBackground();
        renderChecklist();
    }

    /**
     * Persists entries to encrypted storage on a background thread.
     * Used by checkbox toggle so the UI re-renders instantly without
     * waiting for the AES-256 EncryptedSharedPreferences write.
     * JSON is serialised on the main thread (safe) and the string is
     * written on a background thread (no shared-mutable-state race).
     */
    private void saveInBackground() {
        if (existingEntry == null) return;
        existingEntry.setUpdatedAt(System.currentTimeMillis());
        // Serialize to JSON on the main thread — safe, no concurrent mutation
        final String json = storage.exportToJson(entries);
        if (json == null) return;
        new Thread(() -> {
            storage.saveEntriesJson(json);
            storage.setBackupPending(true);
        }).start();
    }

    // ── Note-type: clean paper UI ─────────────────────────────────────────────

    /**
     * Builds the Note-specific UI — no boxes, no labels, no borders.
     * Title: bold heading. Body: plain flowing text. Fills editViews[0]/[6] and viewTexts[0]/[6].
     */
    private void buildNoteUI(LinearLayout container) {
        int padH = dpToPx(20);
        int padV = dpToPx(16);
        container.setPadding(padH, padV, padH, padV);

        // ── Title ─────────────────────────────────────────────────────────────
        // VIEW text
        TextView tvTitle = new TextView(this);
        tvTitle.setTextSize(22f);
        tvTitle.setTextColor(getResources().getColor(R.color.text_primary));
        tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
        tvTitle.setBackground(null);
        tvTitle.setPadding(0, 0, 0, dpToPx(4));
        LinearLayout.LayoutParams titleVP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvTitle.setLayoutParams(titleVP);
        // Long-press to copy title
        tvTitle.setLongClickable(true);
        tvTitle.setOnLongClickListener(v -> {
            String text = tvTitle.getText().toString();
            if (text.isEmpty()) { Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show(); return true; }
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Title", text));
            Toast.makeText(this, "Copied! Clears in 30s", Toast.LENGTH_SHORT).show();
            if (clipboardClearRunnable != null) clipboardHandler.removeCallbacks(clipboardClearRunnable);
            clipboardClearRunnable = () -> { ClipboardManager c2 = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); if (c2 != null) c2.setPrimaryClip(ClipData.newPlainText("", "")); };
            clipboardHandler.postDelayed(clipboardClearRunnable, CLIPBOARD_CLEAR_DELAY_MS);
            return true;
        });
        viewTexts[0] = tvTitle;
        container.addView(tvTitle);

        // EDIT text
        EditText etTitle = new EditText(this);
        etTitle.setTextSize(22f);
        etTitle.setTextColor(getResources().getColor(R.color.input_text));
        etTitle.setHintTextColor(getResources().getColor(R.color.hint_color));
        etTitle.setHint("Note title...");
        etTitle.setBackground(null);
        etTitle.setPadding(0, 0, 0, dpToPx(4));
        etTitle.setSingleLine(true);
        etTitle.setTypeface(etTitle.getTypeface(), android.graphics.Typeface.BOLD);
        etTitle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etTitle.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps()});
        etTitle.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        etTitle.setVisibility(View.GONE);
        editViews[0] = etTitle;
        container.addView(etTitle);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.text_secondary));
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
        divP.topMargin = dpToPx(8);
        divP.bottomMargin = dpToPx(12);
        divider.setLayoutParams(divP);
        container.addView(divider);

        // ── Body (field7 = Notes) ─────────────────────────────────────────────
        // VIEW text
        TextView tvBody = new TextView(this);
        tvBody.setTextSize(15f);
        tvBody.setTextColor(getResources().getColor(R.color.text_primary));
        tvBody.setBackground(null);
        tvBody.setPadding(0, 0, 0, 0);
        tvBody.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams bodyVP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvBody.setLayoutParams(bodyVP);
        tvBody.setOnLongClickListener(v -> {
            String text = tvBody.getText().toString();
            if (text.isEmpty()) { Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show(); return true; }
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Note", text));
            Toast.makeText(this, "Copied! Clears in 30s", Toast.LENGTH_SHORT).show();
            if (clipboardClearRunnable != null) clipboardHandler.removeCallbacks(clipboardClearRunnable);
            clipboardClearRunnable = () -> { ClipboardManager c2 = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); if (c2 != null) c2.setPrimaryClip(ClipData.newPlainText("", "")); };
            clipboardHandler.postDelayed(clipboardClearRunnable, CLIPBOARD_CLEAR_DELAY_MS);
            return true;
        });
        viewTexts[6] = tvBody;
        container.addView(tvBody);

        // EDIT text
        EditText etBody = new EditText(this);
        etBody.setTextSize(15f);
        etBody.setTextColor(getResources().getColor(R.color.input_text));
        etBody.setHintTextColor(getResources().getColor(R.color.hint_color));
        etBody.setHint("Start writing...");
        etBody.setBackground(null);
        etBody.setPadding(0, 0, 0, 0);
        etBody.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        etBody.setMinLines(12);
        etBody.setGravity(Gravity.TOP | Gravity.START);
        etBody.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams bodyEP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etBody.setLayoutParams(bodyEP);
        etBody.setVisibility(View.GONE);
        editViews[6] = etBody;
        container.addView(etBody);
    }

    /** Applies view/edit mode UI for Note type specifically. */
    private void applyNoteModeUI(TextView tvScreenTitle) {
        if (isEditMode) {
            tvScreenTitle.setText(existingEntry != null ? "Edit Note" : "New Note");
            btnEdit.setVisibility(View.GONE);
            btnArchive.setVisibility(View.GONE);
            if (existingEntry != null) {
                btnShare.setVisibility(View.VISIBLE);
                btnDelete.setVisibility(View.VISIBLE);
            } else {
                btnShare.setVisibility(View.GONE);
                btnDelete.setVisibility(View.GONE);
            }
            if (editViews[0] != null) editViews[0].setVisibility(View.VISIBLE);
            if (editViews[6] != null) editViews[6].setVisibility(View.VISIBLE);
            if (viewTexts[0] != null) viewTexts[0].setVisibility(View.GONE);
            if (viewTexts[6] != null) viewTexts[6].setVisibility(View.GONE);
            if (tvCreatedAt != null) tvCreatedAt.setVisibility(View.GONE);
            // Focus body if title already has content
            if (existingEntry != null && editViews[6] != null) {
                editViews[6].requestFocus();
            } else if (editViews[0] != null) {
                editViews[0].requestFocus();
            }
        } else {
            tvScreenTitle.setText(existingEntry != null ? existingEntry.getDisplayTitle() : "Note");
            btnEdit.setVisibility(View.VISIBLE);
            btnShare.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.VISIBLE);
            btnArchive.setVisibility(View.VISIBLE);
            updateArchiveButton();
            if (editViews[0] != null) editViews[0].setVisibility(View.GONE);
            if (editViews[6] != null) editViews[6].setVisibility(View.GONE);
            if (viewTexts[0] != null) viewTexts[0].setVisibility(View.VISIBLE);
            if (viewTexts[6] != null) viewTexts[6].setVisibility(View.VISIBLE);
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
        // Title field (index 0 = field1) is always stored and displayed in ALL CAPS.
        // AllCaps filter auto-uppercases every keystroke so the user sees it immediately.
        if (index == 0) {
            etEdit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps()});
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
        // v20: checklist saves itself on every action — should never reach here
        if (EntryType.CHECKLIST.equals(entryType)) { finish(); return; }

        if (editViews[0] != null && editViews[0].getText().toString().trim().isEmpty()) {
            editViews[0].setError("Required");
            editViews[0].requestFocus();
            return;
        }

        if (existingEntry != null) {
            existingEntry.setType(entryType);
            for (int i = 0; i < 7; i++) {
                if (editViews[i] != null) {
                    String val = editViews[i].getText().toString().trim();
                    // Title (field1, index 0) is always stored in ALL CAPS — safety net
                    if (i == 0) val = val.toUpperCase();
                    existingEntry.setFieldByIndex(i + 1, val);
                }
            }
            existingEntry.setUpdatedAt(System.currentTimeMillis()); // v8: track last modified
        } else {
            String[] vals = new String[7];
            for (int i = 0; i < 7; i++) {
                String val = (editViews[i] != null) ? editViews[i].getText().toString().trim() : "";
                // Title (field1, index 0) is always stored in ALL CAPS — safety net
                if (i == 0) val = val.toUpperCase();
                vals[i] = val;
            }
            Entry newEntry = new Entry(UUID.randomUUID().toString(), entryType,
                    vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6]);
            long now = System.currentTimeMillis();
            newEntry.setUpdatedAt(now); // v8: track creation time
            newEntry.setCreatedAt(now); // v12: set creation date once
            entries.add(newEntry);
            existingEntry = newEntry;   // assign so attachment block below can reference it
        }

        // v24: persist pending attachment changes
        // Write new files via AttachmentStore, delete removed files
        final List<Attachment> currentAttachments = existingEntry != null
                ? new ArrayList<>(existingEntry.getAttachments()) : new ArrayList<>();
        // Remove flagged removals from the list that will be stored
        currentAttachments.removeIf(a -> pendingRemovals.contains(a.getId()));
        existingEntry.setAttachments(currentAttachments);
        // Capture pending state before clearing for background thread
        final List<PendingAttachment> toWrite = new ArrayList<>(pendingAdds);
        final Set<String> toDelete = new HashSet<>(pendingRemovals);
        pendingAdds.clear();
        pendingRemovals.clear();

        final String json = storage.exportToJson(entries);

        // Fast path: no attachment file I/O needed — save synchronously on the main thread
        // and finish immediately. This matches v23 behaviour and avoids any potential
        // memory-visibility issue with EncryptedSharedPreferences.apply() called from a
        // background thread (main-thread reads may not see background-thread writes instantly).
        if (toWrite.isEmpty() && toDelete.isEmpty()) {
            if (json != null) {
                storage.saveEntriesJson(json);
                storage.setBackupPending(true);
            }
            finish();
            return;
        }

        // Slow path: there are attachment files to write/delete — use a background thread
        // for file I/O, then do the final entry save + finish on the UI thread.
        new Thread(() -> {
            // Write new attachment files
            List<Attachment> written = new ArrayList<>();
            for (PendingAttachment pa : toWrite) {
                try {
                    Attachment saved = attachmentStore.save(pa.bytes, pa.name, pa.mimeType);
                    written.add(saved);
                } catch (Exception e) {
                    // If a file fails to write, skip it silently — entry still saves
                }
            }
            // Delete removed files
            for (String id : toDelete) {
                attachmentStore.delete(id);
            }
            // Add newly written attachments to the entry and re-serialize
            runOnUiThread(() -> {
                if (!written.isEmpty()) {
                    existingEntry.getAttachments().addAll(written);
                }
                final String json2 = storage.exportToJson(entries);
                if (json2 != null) {
                    storage.saveEntriesJson(json2);
                    storage.setBackupPending(true);
                }
                finish();
            });
        }).start();
    }

    /**
     * Shows the share confirmation dialog.
     * v24: For entries with multiple attachments, lists all attachment filenames.
     */
    private void showShareDialog() {
        // Build effective attachment list: saved – removals + pending adds
        List<Attachment> savedAtts = existingEntry != null
                ? existingEntry.getAttachments() : new ArrayList<>();
        boolean hasAttachment = !savedAtts.isEmpty() || !pendingAdds.isEmpty();

        if (!hasAttachment) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Share Entry")
                    .setMessage("This will share your data as plain text.\n\nAre you sure?")
                    .setPositiveButton("Share", (d, w) -> shareEntry(false))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        // Build file list description
        StringBuilder fileList = new StringBuilder();
        for (Attachment a : savedAtts) {
            if (!pendingRemovals.contains(a.getId())) {
                fileList.append("\u2022 ").append(a.getName()).append("\n");
            }
        }
        for (PendingAttachment pa : pendingAdds) {
            fileList.append("\u2022 ").append(pa.name).append(" (unsaved)\n");
        }

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

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(16)));
        layout.addView(spacer);

        CheckBox chk = new CheckBox(this);
        chk.setText("Include attachments");
        chk.setChecked(true);
        chk.setTextSize(15f);
        chk.setTextColor(getResources().getColor(R.color.text_primary));
        layout.addView(chk);

        TextView tvFile = new TextView(this);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fileParams.leftMargin = dpToPx(32);
        tvFile.setLayoutParams(fileParams);
        tvFile.setText(fileList.toString().trim());
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
        // v20: Checklist has its own share format
        if (EntryType.CHECKLIST.equals(entryType) && existingEntry != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("☑️  ").append(existingEntry.getDisplayTitle()).append("\n");
            sb.append("─────────────────────────\n");
            java.util.List<ChecklistItem> items = existingEntry.getChecklistItems();
            for (ChecklistItem item : items) {
                if (!item.isChecked()) sb.append("☐ ").append(item.getText()).append("\n");
            }
            for (ChecklistItem item : items) {
                if (item.isChecked()) sb.append("☑ ").append(item.getText()).append("\n");
            }
            int total = items.size();
            int checked = 0;
            for (ChecklistItem item : items) { if (item.isChecked()) checked++; }
            sb.append("─────────────────────────\n");
            sb.append(checked).append(" of ").append(total).append(" done\n");
            sb.append("Shared from Cryptex");
            startActivity(Intent.createChooser(
                    buildTextOnlyShareIntent(sb.toString()), "Share via"));
            return;
        }

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

        // ── v24: Build effective attachment list (saved - removals + pending adds) ──
        List<Attachment> savedAtts = existingEntry != null
                ? existingEntry.getAttachments() : new ArrayList<>();
        boolean anyFiles = !savedAtts.isEmpty() || !pendingAdds.isEmpty();
        if (!anyFiles) {
            startActivity(Intent.createChooser(
                    buildTextOnlyShareIntent(shareText), "Share via"));
            return;
        }

        // ── Text + Attachment(s) — read files on background thread ────────────
        // Attachments can be up to 100 MB total; reading on the UI thread risks ANR.
        final String finalShareText = shareText;
        final String entryId = (existingEntry != null && existingEntry.getId() != null)
                ? existingEntry.getId() : "pending";
        final String entryTitle = existingEntry != null ? existingEntry.getDisplayTitle() : "";
        final List<Attachment> savedAttsSnapshot = new ArrayList<>(savedAtts);
        final Set<String> removalsSnapshot = new HashSet<>(pendingRemovals);
        final List<PendingAttachment> addsSnapshot = new ArrayList<>(pendingAdds);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        AlertDialog progress = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Preparing share...")
                .setView(pb)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "attachments/" + entryId);
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();

                // File 1: entry text as .txt
                String txtFileName = entryTitle.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                if (txtFileName.isEmpty()) txtFileName = "entry";
                File txtFile = new File(cacheDir, txtFileName + ".txt");
                try (FileOutputStream fos = new FileOutputStream(txtFile)) {
                    fos.write(finalShareText.getBytes("UTF-8"));
                }
                Uri txtUri = FileProvider.getUriForFile(DetailActivity.this,
                        getPackageName() + ".fileprovider", txtFile);

                java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
                uris.add(txtUri);

                // Files 2+: saved attachments (decrypt from AttachmentStore)
                for (Attachment a : savedAttsSnapshot) {
                    if (removalsSnapshot.contains(a.getId())) continue;
                    try {
                        byte[] bytes = attachmentStore.read(a.getId());
                        File attFile = new File(cacheDir, a.getName());
                        try (FileOutputStream fos = new FileOutputStream(attFile)) { fos.write(bytes); }
                        uris.add(FileProvider.getUriForFile(DetailActivity.this,
                                getPackageName() + ".fileprovider", attFile));
                    } catch (Exception ignored) { /* skip unreadable file */ }
                }
                // Pending adds (bytes already in memory)
                for (PendingAttachment pa : addsSnapshot) {
                    try {
                        File attFile = new File(cacheDir, pa.name);
                        try (FileOutputStream fos = new FileOutputStream(attFile)) { fos.write(pa.bytes); }
                        uris.add(FileProvider.getUriForFile(DetailActivity.this,
                                getPackageName() + ".fileprovider", attFile));
                    } catch (Exception ignored) { /* skip */ }
                }

                Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("*/*");
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, entryTitle);
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                ClipData clip = ClipData.newRawUri("", uris.get(0));
                for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                shareIntent.setClipData(clip);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(shareIntent, "Share via");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                runOnUiThread(() -> {
                    progress.dismiss();
                    startActivity(chooser);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(DetailActivity.this,
                            "Could not attach file — sharing text only.", Toast.LENGTH_SHORT).show();
                    startActivity(Intent.createChooser(
                            buildTextOnlyShareIntent(finalShareText), "Share via"));
                });
            }
        }).start();
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

    // ── v24: Attachment section ───────────────────────────────────────────────

    /** Initial setup of attachment section — binds container and Add button. */
    private void setupAttachmentSection() {
        // v20: checklist has no attachment section
        if (EntryType.CHECKLIST.equals(entryType)) return;

        attachmentListContainer = findViewById(R.id.attachmentListContainer);
        btnAddAttachment        = findViewById(R.id.btnAddAttachment);

        btnAddAttachment.setOnClickListener(v -> {
            // Enforce 5-file limit
            int currentCount = (existingEntry != null ? existingEntry.getAttachments().size() : 0)
                    - pendingRemovals.size() + pendingAdds.size();
            if (currentCount >= MAX_ATTACHMENT_COUNT) {
                Toast.makeText(this,
                        "Maximum " + MAX_ATTACHMENT_COUNT + " attachments per entry.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Add Attachment")
                    .setItems(new String[]{"📷  Camera", "📁  From files"}, (d, which) -> {
                        if (which == 0) launchCamera();
                        else attachmentPicker.launch(new String[]{"*/*"});
                    })
                    .show();
        });

        renderAttachmentList();
    }

    /**
     * Rebuilds the attachment list UI from current state.
     *
     * VIEW mode, 1 file  → single row directly.
     * VIEW mode, 2+ files, collapsed → one tappable summary row "📎 N files".
     * VIEW mode, 2+ files, expanded  → collapse header + all file rows (no remove).
     * EDIT mode → always fully expanded, remove button visible on each row.
     */
    private void renderAttachmentList() {
        if (attachmentListContainer == null) return;
        attachmentListContainer.removeAllViews();

        // Build list of visible saved attachments
        java.util.List<Attachment> savedList = new java.util.ArrayList<>();
        if (existingEntry != null) {
            for (Attachment att : existingEntry.getAttachments()) {
                if (!pendingRemovals.contains(att.getId())) savedList.add(att);
            }
        }
        int totalCount = savedList.size() + pendingAdds.size();

        // Add button — edit mode only
        if (btnAddAttachment != null) {
            btnAddAttachment.setVisibility(isEditMode ? android.view.View.VISIBLE : android.view.View.GONE);
        }

        if (totalCount == 0) return;

        // VIEW mode with 2+ files: collapsed summary unless user tapped to expand
        if (!isEditMode && totalCount > 1 && !attachmentsExpanded) {
            addAttachSummaryRow(totalCount);
            return;
        }

        // VIEW mode + expanded: show a collapse header above the list
        if (!isEditMode && totalCount > 1) {
            addAttachCollapseRow(totalCount);
        }

        // ── Saved attachments ─────────────────────────────────────────────────
        for (Attachment att : savedList) {
            android.view.View row = getLayoutInflater()
                    .inflate(R.layout.item_attachment_row, attachmentListContainer, false);
            TextView    tvLabel   = row.findViewById(R.id.tvAttachLabel);
            ImageButton btnRemove = row.findViewById(R.id.btnAttachRowRemove);
            android.view.View card = row.findViewById(R.id.attachRowCard);

            tvLabel.setText(att.getName() + "   " + formatBytes(att.getSize()));
            card.setOnClickListener(v -> openSavedAttachmentWithProgress(att));

            btnRemove.setVisibility(isEditMode ? android.view.View.VISIBLE : android.view.View.GONE);
            btnRemove.setOnClickListener(v ->
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Remove Attachment")
                            .setMessage("Remove \"" + att.getName() + "\"?\n\nIt will be permanently deleted when you save.")
                            .setPositiveButton("Remove", (d, w) -> {
                                pendingRemovals.add(att.getId());
                                renderAttachmentList();
                            })
                            .setNegativeButton("Cancel", null)
                            .show());
            attachmentListContainer.addView(row);
        }

        // ── Pending adds (not yet saved to disk) ──────────────────────────────
        for (int i = 0; i < pendingAdds.size(); i++) {
            final int idx = i;
            PendingAttachment pa = pendingAdds.get(i);
            android.view.View row = getLayoutInflater()
                    .inflate(R.layout.item_attachment_row, attachmentListContainer, false);
            TextView    tvLabel   = row.findViewById(R.id.tvAttachLabel);
            ImageButton btnRemove = row.findViewById(R.id.btnAttachRowRemove);
            android.view.View card = row.findViewById(R.id.attachRowCard);

            tvLabel.setText(pa.name + "   " + formatBytes(pa.bytes.length) + "  ·  pending");
            card.setClickable(false);
            card.setOnClickListener(null);

            btnRemove.setVisibility(isEditMode ? android.view.View.VISIBLE : android.view.View.GONE);
            btnRemove.setOnClickListener(v -> {
                pendingAdds.remove(idx);
                renderAttachmentList();
            });
            attachmentListContainer.addView(row);
        }
    }

    /** Builds a single collapsed summary row: "📎  N files" — tap expands. */
    private void addAttachSummaryRow(int count) {
        float d = getResources().getDisplayMetrics().density;
        int pad  = Math.round(10 * d);
        int sz   = Math.round(20 * d);
        int gap  = Math.round(10 * d);
        int mb   = Math.round(6 * d);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.tile_bg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = mb;
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_attachment);
        icon.setColorFilter(0xFF757575);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(sz, sz);
        iconLp.setMarginEnd(gap);
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        TextView tv = new TextView(this);
        tv.setText(count + " files");
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setColorFilter(0xFF757575);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        row.addView(chevron);

        row.setOnClickListener(v -> {
            attachmentsExpanded = true;
            renderAttachmentList();
        });
        attachmentListContainer.addView(row);
    }

    /** Builds a collapse header row: "N files  ▲" — tap collapses back to summary. */
    private void addAttachCollapseRow(int count) {
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(10 * d);
        int sz  = Math.round(20 * d);
        int gap = Math.round(10 * d);
        int mb  = Math.round(6 * d);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = mb;
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_attachment);
        icon.setColorFilter(0xFF757575);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(sz, sz);
        iconLp.setMarginEnd(gap);
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        TextView tv = new TextView(this);
        tv.setText(count + " files  ▲");
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(tv);

        row.setOnClickListener(v -> {
            attachmentsExpanded = false;
            renderAttachmentList();
        });
        attachmentListContainer.addView(row);
    }

    /** Opens a saved attachment with a progress toast while decrypting. */
    private void openSavedAttachmentWithProgress(Attachment att) {
        Toast loading = Toast.makeText(this, "Opening…", Toast.LENGTH_SHORT);
        loading.show();
        new Thread(() -> {
            byte[] bytes;
            try {
                bytes = attachmentStore.read(att.getId());
            } catch (Exception e) {
                loading.cancel();
                runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                        .setTitle("File Not Found")
                        .setMessage("The attachment file could not be read. It may have been deleted.")
                        .setPositiveButton("OK", null)
                        .show());
                return;
            }
            loading.cancel();
            runOnUiThread(() -> openBytesAsFile(bytes, att.getName(), att.getMimeType()));
        }).start();
    }

    /** Writes bytes to cache and fires ACTION_VIEW intent. */
    private void openBytesAsFile(byte[] bytes, String name, String mimeType) {
        try {
            String entryId = (existingEntry != null) ? existingEntry.getId() : "pending";
            File cacheDir = new File(getCacheDir(), "attachments/" + entryId);
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
            File outFile = new File(cacheDir, name);
            try (FileOutputStream fos = new FileOutputStream(outFile)) { fos.write(bytes); }

            Uri fileUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", outFile);

            String mime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "*/*";

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, mime);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            viewIntent.setClipData(ClipData.newRawUri("attachment", fileUri));

            Intent chooser = Intent.createChooser(viewIntent, "Open with");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            try {
                startActivity(chooser);
            } catch (ActivityNotFoundException ex) {
                Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.attachment_open_error, Toast.LENGTH_SHORT).show();
        }
    }

    // v20: Camera capture ─────────────────────────────────────────────────────

    /** Checks camera permission then launches the camera capture intent. */
    private void launchCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 101);
            return;
        }
        startCameraCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startCameraCapture();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Creates a temp file and launches the camera. */
    private void startCameraCapture() {
        try {
            java.io.File cameraDir = new java.io.File(getCacheDir(), "camera");
            //noinspection ResultOfMethodCallIgnored
            cameraDir.mkdirs();
            java.io.File photoFile = new java.io.File(cameraDir, "capture_" + System.currentTimeMillis() + ".jpg");
            cameraOutputUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", photoFile);
            cameraPicker.launch(cameraOutputUri);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open camera", Toast.LENGTH_SHORT).show();
        }
    }

    /** Called when camera capture completes successfully — auto-compresses if needed, appends to pendingAdds. */
    private void handleCameraCapture() {
        try {
            byte[] bytes;
            try (InputStream is = getContentResolver().openInputStream(cameraOutputUri)) {
                if (is == null) throw new Exception("Cannot read captured photo");
                bytes = readStreamBytes(is);
            }

            // Delete the temp file immediately
            try {
                java.io.File tempFile = new java.io.File(cameraOutputUri.getPath());
                if (tempFile.exists()) tempFile.delete();
            } catch (Exception ignored) { }
            cameraOutputUri = null;

            // Auto-compress if over size limit
            if (bytes.length > MAX_SINGLE_BYTES) {
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) throw new Exception("Cannot decode photo");
                int[] qualities = {80, 60, 40};
                byte[] compressed = null;
                for (int quality : qualities) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos);
                    compressed = baos.toByteArray();
                    if (compressed.length <= MAX_SINGLE_BYTES) break;
                }
                bitmap.recycle();
                if (compressed == null || compressed.length > MAX_SINGLE_BYTES) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("File Too Large")
                            .setMessage(getString(R.string.file_too_large) + "  (" + formatBytes(bytes.length) + ")")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                bytes = compressed;
            }

            // Check 15 MB total limit across all attachments
            if (!checkTotalSizeLimit(bytes.length)) return;

            String fileName = "photo_" + new java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date()) + ".jpg";
            pendingAdds.add(new PendingAttachment(fileName, "image/jpeg", bytes));
            renderAttachmentList();

        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.attachment_open_error), Toast.LENGTH_SHORT).show();
        }
    }

    /** Called when user picks a file from SAF picker — appends to pendingAdds. */
    private void handleAttachmentPicked(Uri uri) {
        try {
            byte[] bytes;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new Exception("Cannot read file");
                bytes = readStreamBytes(is);
            }

            if (bytes.length > MAX_SINGLE_BYTES) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("File Too Large")
                        .setMessage(getString(R.string.file_too_large) + "  (" + formatBytes(bytes.length) + ")")
                        .setPositiveButton("OK", null).show();
                return;
            }

            // Check 15 MB total limit across all attachments
            if (!checkTotalSizeLimit(bytes.length)) return;

            // Get original filename
            String fileName = "attachment";
            android.database.Cursor cursor = getContentResolver().query(
                    uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) fileName = cursor.getString(idx);
                cursor.close();
            }

            // Resolve MIME type
            String mimeType = getContentResolver().getType(uri);
            if (mimeType == null || mimeType.isEmpty()) mimeType = "application/octet-stream";

            pendingAdds.add(new PendingAttachment(fileName, mimeType, bytes));
            renderAttachmentList();

        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.attachment_open_error), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Checks whether adding newBytes would exceed the 15 MB total limit.
     * Shows an error dialog and returns false if it would.
     */
    private boolean checkTotalSizeLimit(long newBytes) {
        long total = newBytes;
        if (existingEntry != null) {
            for (Attachment a : existingEntry.getAttachments()) {
                if (!pendingRemovals.contains(a.getId())) total += a.getSize();
            }
        }
        for (PendingAttachment pa : pendingAdds) total += pa.bytes.length;
        if (total > MAX_TOTAL_BYTES) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Total Size Exceeded")
                    .setMessage("Total attachments cannot exceed " + formatBytes(MAX_TOTAL_BYTES) + ".")
                    .setPositiveButton("OK", null).show();
            return false;
        }
        return true;
    }

    /** Reads all bytes from an InputStream. Compatible with API 23+. */
    private byte[] readStreamBytes(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buffer.write(chunk, 0, n);
        return buffer.toByteArray();
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

    private String formatDate(long millis) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(millis);
        java.text.SimpleDateFormat fmt = (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR))
                ? new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                : new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(millis));
    }

    // ── v24: PendingAttachment ────────────────────────────────────────────────

    /**
     * Holds a file the user has picked but that has not yet been written to
     * AttachmentStore. Lives only in memory until saveEntry() is called.
     * On discard (Back → Discard / btnBarDiscard), pendingAdds is cleared
     * and no files are ever written to disk.
     */
    static class PendingAttachment {
        final String name;
        final String mimeType;
        final byte[] bytes;

        PendingAttachment(String name, String mimeType, byte[] bytes) {
            this.name     = name;
            this.mimeType = mimeType;
            this.bytes    = bytes;
        }
    }
}
