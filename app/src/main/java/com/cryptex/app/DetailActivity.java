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
    private final TextView[]     labelViews  = new TextView[7];
    private final boolean[]      secretFlags = new boolean[7];
    // Track reveal state per secret field so we can reset on mode switch
    private final boolean[]      revealed    = new boolean[7];
    // Reference to each eye-toggle button so we can reset its icon on mode switch
    private final ImageButton[]  eyeButtons  = new ImageButton[7];

    // v29: per-record fields for custom categories
    private List<CustomField> activeRecordFields    = new ArrayList<>();
    private boolean           activeRecordIncludeNotes = true;

    // Top-bar buttons
    private ImageButton btnEdit, btnShare, btnDelete, btnArchive, btnOverflow;

    // v9: Unsaved-changes action bar
    private LinearLayout saveActionBar;

    // v24: Attachment state (replaces single pendingAttachmentName/Data from v9)
    private static final int  MAX_ATTACHMENT_COUNT  = 50;
    private static final long MAX_TOTAL_BYTES       = 200L * 1024 * 1024; // 200 MB total per entry
    private static final long MAX_SINGLE_BYTES      =  20L * 1024 * 1024; // 20 MB per file
    private AttachmentStore attachmentStore;
    // Files the user has added in this edit session but not yet saved
    private final List<PendingAttachment> pendingAdds = new ArrayList<>();
    // IDs of existing saved attachments the user has removed in this edit session
    private final Set<String> pendingRemovals = new HashSet<>();
    // Original names of saved attachments renamed this session — used to revert on discard
    private final java.util.Map<String, String> renamedOriginals = new java.util.HashMap<>();
    // Original groups of saved attachments moved this session — used to revert on discard
    private final java.util.Map<String, String> originalGroups = new java.util.HashMap<>();
    // Pending group name renames: old group name → new group name
    private final java.util.Map<String, String> renamedGroups = new java.util.LinkedHashMap<>();
    // Groups created this session with no files yet — shown as empty headers with [+]
    private final java.util.LinkedHashSet<String> pendingEmptyGroups = new java.util.LinkedHashSet<>();
    // Groups explicitly deleted via "Delete Group" this session — used to detect unsaved changes
    private final java.util.LinkedHashSet<String> deletedGroups = new java.util.LinkedHashSet<>();

    // v12: Clipboard auto-clear
    private static final long CLIPBOARD_CLEAR_DELAY_MS = 30_000; // 30 seconds
    private final android.os.Handler clipboardHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable clipboardClearRunnable = null;

    // v24: Attachment section views — bound in setupAttachmentSection()
    private LinearLayout attachmentListContainer;
    private LinearLayout attachmentSearchContainer; // persistent search bar container — never cleared
    private TextView     btnAddAttachment;
    private boolean      attachmentsExpanded = false; // VIEW mode: 2+ files collapsed by default

    // v12: Created date label shown in VIEW mode
    private TextView    tvCreatedAt;
    private View        noteEndDivider; // shown at end of Note body in VIEW mode only

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

    // v9: File picker launchers — single for when 1 slot remains, multi for 2–5 slots
    private ActivityResultLauncher<String[]>       attachmentPicker;
    private ActivityResultLauncher<String[]>       singleFilePicker;
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
        btnEdit         = findViewById(R.id.btnEdit);
        btnShare        = findViewById(R.id.btnShare);
        btnDelete       = findViewById(R.id.btnDelete);
        btnArchive      = findViewById(R.id.btnArchive);
        btnOverflow     = findViewById(R.id.btnOverflow);

        // v29: Load per-record fields for custom categories and Others
        if (EntryType.isPerRecordFields(entryType)) {
            if (existingEntry != null && !existingEntry.getRecordFields().isEmpty()) {
                // Existing record — use its own saved fields
                activeRecordFields       = new ArrayList<>(existingEntry.getRecordFields());
                activeRecordIncludeNotes = existingEntry.isRecordIncludeNotes();
            } else if (existingEntry == null) {
                // New record — read fields passed from TypeListActivity via Intent
                String rfJson = getIntent().getStringExtra("record_fields_json");
                activeRecordIncludeNotes = getIntent().getBooleanExtra("record_include_notes", true);
                if (rfJson != null && !rfJson.isEmpty()) {
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(rfJson);
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject fo = arr.getJSONObject(i);
                            activeRecordFields.add(new CustomField(
                                    fo.optString("label", "Field " + (i + 1)),
                                    fo.optBoolean("secret", false)));
                        }
                    } catch (Exception ignored) {}
                }
            } else {
                // Existing record with no recordFields — fall back to category fields
                CustomCategory cat = EntryType.findCustom(entryType);
                if (cat != null) {
                    activeRecordFields       = new ArrayList<>(cat.getFields());
                    activeRecordIncludeNotes = cat.isIncludeNotes();
                }
            }
        }

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
            // Revert any in-memory renames back to their original names
            if (existingEntry != null) {
                for (Attachment a : existingEntry.getAttachments()) {
                    String origName = renamedOriginals.get(a.getId());
                    if (origName != null) a.setName(origName);
                    String origGroup = originalGroups.get(a.getId());
                    if (origGroup != null) a.setGroup(origGroup);
                }
            }
            renamedOriginals.clear();
            originalGroups.clear();
            renamedGroups.clear();
            expandedGroups.clear();
            userCollapsedGroups.clear();
            pendingEmptyGroups.clear();
            deletedGroups.clear();
            finish();
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());

        btnEdit.setOnClickListener(v -> switchToEditMode());

        // Overflow ⋮ — visible in edit mode: Manage Fields (custom only), Share, Delete
        btnOverflow.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, btnOverflow);
            android.view.Menu menu = popup.getMenu();
            if (EntryType.isPerRecordFields(entryType)) {
                menu.add(0, 1, 0, "Manage Fields");
            }
            if (existingEntry != null) {
                menu.add(0, 2, 1, "Share");
                menu.add(0, 3, 2, "Delete");
            }
            popup.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1:
                        FieldManagerDialog.show(this, "Manage Fields",
                                activeRecordFields, activeRecordIncludeNotes,
                                (fields, includeNotes) -> rebuildFieldRowsAfterManage(fields, includeNotes));
                        return true;
                    case 2:
                        showShareDialog();
                        return true;
                    case 3:
                        showDeleteConfirm();
                        return true;
                }
                return false;
            });
            popup.show();
        });

        btnDelete.setOnClickListener(v -> showDeleteConfirm());

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
                                storage.saveEntriesJson(json); // save first — orphaned .enc files are harmless, broken references are not
                                storage.setBackupPending(true);
                                if (!attachmentsToDelete.isEmpty())
                                    attachmentStore.deleteAll(attachmentsToDelete);
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

        // v9: Register file picker launchers
        attachmentPicker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) handleAttachmentsPicked(uris);
                });
        singleFilePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) handleAttachmentsPicked(java.util.Collections.singletonList(uri));
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
            // All other types (built-in + custom) — normal field rows
            String[]  labels;
            boolean[] secret;
            if (EntryType.isPerRecordFields(entryType)) {
                // v29: build labels/secrets from per-record fields
                labels = buildLabelsFromRecordFields();
                secret = buildSecretsFromRecordFields();
            } else {
                labels = EntryType.getFieldLabels(entryType);
                secret = EntryType.getSecretFlags(entryType);
            }
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

        // Dates — Added + Modified, right-aligned, below attachments (tvDateLabel in XML)
        // Must be bound BEFORE applyModeUI() so setText calls work
        tvCreatedAt = findViewById(R.id.tvDateLabel);

        // ── Apply initial mode UI ─────────────────────────────────────────────
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
        // Clear attachment search so edit mode always shows the full list
        attachmentSearchVisible = false;
        attachmentSearchQuery = "";
        attachmentSearchEt = null;
        // Freshly populate edit fields from the live entry values
        for (int i = 0; i < 7; i++) {
            if (editViews[i] != null && existingEntry != null) {
                editViews[i].setText(existingEntry.getFieldByIndex(i + 1));
            }
        }
        applyModeUI();
        renderAttachmentList(); // v24: refresh attachment list for edit mode
    }

    private void switchToViewMode() {
        isEditMode = false;
        saveActionBar.setVisibility(View.GONE); // ensure action bar hidden on save
        // Sync view texts from the freshly saved entry
        for (int i = 0; i < 7; i++) {
            if (viewTexts[i] != null && existingEntry != null) {
                setViewText(i, existingEntry.getFieldByIndex(i + 1));
            }
        }
        applyModeUI();
        renderAttachmentList();
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
            if (tvCreatedAt != null) tvCreatedAt.setVisibility(View.GONE);
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
                    ? "Edit · " + existingEntry.getDisplayTitle()
                    : "New "  + EntryType.getDisplayName(entryType));

            btnEdit.setVisibility(View.GONE);
            btnShare.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
            btnArchive.setVisibility(View.GONE);
            // Show overflow ⋮ in edit mode (contains Manage Fields, Share, Delete)
            btnOverflow.setVisibility(View.VISIBLE);

            // Show edit fields, hide view texts; open eye for secret fields
            // Also restore label + row wrapper visibility (may have been hidden for empty fields in VIEW mode)
            for (int i = 0; i < 7; i++) {
                if (labelViews[i] != null) labelViews[i].setVisibility(View.VISIBLE);
                if (viewTexts[i] != null) {
                    android.view.ViewParent parent = viewTexts[i].getParent();
                    if (parent instanceof android.view.View) {
                        ((android.view.View) parent).setVisibility(View.VISIBLE);
                    }
                }
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
            btnOverflow.setVisibility(View.GONE);
            updateArchiveButton();

            // Show view texts, hide edit fields; close eye for secret fields
            // Hide label + row entirely when field is empty
            for (int i = 0; i < 7; i++) {
                if (editViews[i] != null) editViews[i].setVisibility(View.GONE);
                String val = existingEntry != null ? existingEntry.getFieldByIndex(i + 1) : "";
                boolean isEmpty = val.isEmpty();
                int vis = isEmpty ? View.GONE : View.VISIBLE;
                if (labelViews[i] != null) labelViews[i].setVisibility(vis);
                if (viewTexts[i] != null) {
                    // Always restore viewText visibility explicitly (may have been GONE in edit mode)
                    viewTexts[i].setVisibility(vis);
                    // Also show/hide the row wrapper (parent) so the eye button follows
                    android.view.ViewParent parent = viewTexts[i].getParent();
                    if (parent instanceof android.view.View) {
                        ((android.view.View) parent).setVisibility(vis);
                    }
                }
                if (!isEmpty && secretFlags[i]) resetReveal(i); // eye closed + text masked
            }
            // show date labels in VIEW mode (right-aligned, below attachments)
            if (tvCreatedAt != null) {
                if (existingEntry != null && existingEntry.getCreatedAt() > 0) {
                    tvCreatedAt.setText(buildDateLabel(existingEntry));
                    tvCreatedAt.setVisibility(View.VISIBLE);
                } else {
                    tvCreatedAt.setVisibility(View.GONE);
                }
            }
        }
    }

    /** Returns "Added: X\nModified: Y" (or just "Added: X" if never edited). */
    private String buildDateLabel(Entry e) {
        StringBuilder sb = new StringBuilder();
        if (e.getCreatedAt() > 0) {
            sb.append("Added:     ").append(formatDate(e.getCreatedAt()));
        }
        // Only show Modified if it meaningfully differs from Created (> 60s gap)
        // AND the formatted date strings are actually different (avoids "9 Jun / 9 Jun")
        if (e.getUpdatedAt() > 0 && e.getUpdatedAt() > e.getCreatedAt() + 60_000) {
            String createdStr  = formatDate(e.getCreatedAt());
            String modifiedStr = formatDate(e.getUpdatedAt());
            if (!modifiedStr.equals(createdStr)) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("Modified: ").append(modifiedStr);
            }
        }
        return sb.toString();
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
        EntryType.init(storage.loadCustomCategories());
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
        // v24: pending attachment adds, removals, or renames are unsaved changes
        if (!pendingAdds.isEmpty() || !pendingRemovals.isEmpty() || !renamedOriginals.isEmpty()
                || !originalGroups.isEmpty() || !renamedGroups.isEmpty()
                || !pendingEmptyGroups.isEmpty() || !deletedGroups.isEmpty()) return true;

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

        // End-of-note divider — thin line at 30% opacity, VIEW mode only
        noteEndDivider = new View(this);
        noteEndDivider.setBackgroundColor(getResources().getColor(R.color.text_secondary));
        noteEndDivider.setAlpha(0.30f);
        LinearLayout.LayoutParams endDivP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
        endDivP.topMargin = dpToPx(20);
        noteEndDivider.setLayoutParams(endDivP);
        noteEndDivider.setVisibility(View.GONE); // shown only in VIEW mode
        container.addView(noteEndDivider);
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
            btnShare.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
            // Use overflow ⋮ in edit mode (consistent with all other types)
            btnOverflow.setVisibility(existingEntry != null ? View.VISIBLE : View.GONE);
            if (editViews[0] != null) editViews[0].setVisibility(View.VISIBLE);
            if (editViews[6] != null) editViews[6].setVisibility(View.VISIBLE);
            if (viewTexts[0] != null) viewTexts[0].setVisibility(View.GONE);
            if (viewTexts[6] != null) viewTexts[6].setVisibility(View.GONE);
            if (noteEndDivider != null) noteEndDivider.setVisibility(View.GONE);
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
            btnOverflow.setVisibility(View.GONE);
            updateArchiveButton();
            if (editViews[0] != null) editViews[0].setVisibility(View.GONE);
            if (editViews[6] != null) editViews[6].setVisibility(View.GONE);
            if (viewTexts[0] != null) viewTexts[0].setVisibility(View.VISIBLE);
            if (viewTexts[6] != null) viewTexts[6].setVisibility(View.VISIBLE);
            if (noteEndDivider != null) noteEndDivider.setVisibility(View.VISIBLE);
            if (tvCreatedAt != null) {
                if (existingEntry != null && existingEntry.getCreatedAt() > 0) {
                    tvCreatedAt.setText(buildDateLabel(existingEntry));
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
        labelViews[index] = tvLabel;
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

    // ── v29: Per-record field helpers ─────────────────────────────────────────

    /** Builds a String[7] labels array from activeRecordFields (same shape as EntryType.getFieldLabels). */
    private String[] buildLabelsFromRecordFields() {
        String[] labels = new String[7];
        labels[0] = "Title"; // always implicit
        for (int i = 0; i < 5; i++) {
            labels[i + 1] = (i < activeRecordFields.size()) ? activeRecordFields.get(i).getLabel() : "";
        }
        labels[6] = activeRecordIncludeNotes ? "Notes" : "";
        return labels;
    }

    private boolean[] buildSecretsFromRecordFields() {
        boolean[] secrets = new boolean[7];
        secrets[0] = false; // Title never secret
        for (int i = 0; i < 5; i++) {
            secrets[i + 1] = (i < activeRecordFields.size()) && activeRecordFields.get(i).isSecret();
        }
        secrets[6] = false; // Notes never secret
        return secrets;
    }

    /**
     * Rebuilds the field rows after the field manager dialog confirms new fields.
     * Snapshots current EditText values first, then rebuilds, then restores by position.
     */
    private void rebuildFieldRowsAfterManage(List<CustomField> newFields, boolean newIncludeNotes) {
        // 1. Snapshot current values
        String[] snapshot = new String[7];
        for (int i = 0; i < 7; i++) {
            snapshot[i] = (editViews[i] != null) ? editViews[i].getText().toString().trim() : "";
        }

        // 2. Wipe old views
        LinearLayout container = findViewById(R.id.fieldsContainer);
        container.removeAllViews();
        for (int i = 0; i < 7; i++) {
            editViews[i]   = null;
            viewTexts[i]   = null;
            labelViews[i]  = null;
            eyeButtons[i]  = null;
            secretFlags[i] = false;
            revealed[i]    = false;
        }

        // 3. Update active fields
        activeRecordFields       = new ArrayList<>(newFields);
        activeRecordIncludeNotes = newIncludeNotes;

        // 4. Rebuild rows
        String[]  labels = buildLabelsFromRecordFields();
        boolean[] secret = buildSecretsFromRecordFields();
        for (int i = 0; i < 7; i++) {
            secretFlags[i] = secret[i];
            if (labels[i].isEmpty()) continue;
            buildFieldRow(container, i, labels[i], secret[i]);
        }

        // 5. Restore values by position (same slot index = same field)
        for (int i = 0; i < 7; i++) {
            if (editViews[i] != null && !snapshot[i].isEmpty()) {
                editViews[i].setText(snapshot[i]);
            }
        }

        // 6. Re-apply edit mode visibility
        for (int i = 0; i < 7; i++) {
            if (editViews[i]  != null) editViews[i].setVisibility(View.VISIBLE);
            if (viewTexts[i]  != null) viewTexts[i].setVisibility(View.GONE);
            if (labelViews[i] != null) labelViews[i].setVisibility(View.VISIBLE);
            if (secretFlags[i]) setRevealOpen(i);
        }
    }

    private void saveEntry() {
        // v20: checklist saves itself on every action — should never reach here
        if (EntryType.CHECKLIST.equals(entryType)) { finish(); return; }

        if (editViews[0] != null && editViews[0].getText().toString().trim().isEmpty()) {
            editViews[0].setError("Required");
            editViews[0].requestFocus();
            return;
        }

        // Duplicate title check — same category only, case-insensitive
        if (editViews[0] != null) {
            String newTitle = editViews[0].getText().toString().trim();
            for (Entry e : entries) {
                if (existingEntry != null && e.getId().equals(existingEntry.getId())) continue;
                if (!e.getType().equals(entryType)) continue;
                if (e.getField1().equalsIgnoreCase(newTitle)) {
                    editViews[0].setError("A record with this name already exists");
                    editViews[0].requestFocus();
                    return;
                }
            }
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
            // v29: persist per-record fields for custom categories
            if (EntryType.isPerRecordFields(entryType)) {
                existingEntry.setRecordFields(new ArrayList<>(activeRecordFields));
                existingEntry.setRecordIncludeNotes(activeRecordIncludeNotes);
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
            // v29: persist per-record fields for custom categories
            if (EntryType.isPerRecordFields(entryType)) {
                newEntry.setRecordFields(new ArrayList<>(activeRecordFields));
                newEntry.setRecordIncludeNotes(activeRecordIncludeNotes);
            }
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
        // v29: persist named attachment groups (groups with files + explicitly named empty groups)
        java.util.LinkedHashSet<String> namedGroupsSet = new java.util.LinkedHashSet<>();
        for (Attachment a : currentAttachments) {
            if (!a.getGroup().isEmpty()) namedGroupsSet.add(a.getGroup());
        }
        for (PendingAttachment pa : pendingAdds) {
            if (pa.group != null && !pa.group.isEmpty()) namedGroupsSet.add(pa.group);
        }
        namedGroupsSet.addAll(pendingEmptyGroups);
        existingEntry.setAttachmentGroups(new java.util.ArrayList<>(namedGroupsSet));
        // Capture pending state before clearing for background thread
        final List<PendingAttachment> toWrite = new ArrayList<>(pendingAdds);
        final Set<String> toDelete = new HashSet<>(pendingRemovals);
        pendingAdds.clear();
        pendingRemovals.clear();
        renamedOriginals.clear();
        originalGroups.clear();
        renamedGroups.clear();
        expandedGroups.clear();
        userCollapsedGroups.clear();
        pendingEmptyGroups.clear();
        deletedGroups.clear();

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
            switchToViewMode();
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
                    if (pa.group != null && !pa.group.isEmpty()) saved.setGroup(pa.group); // v27
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
                switchToViewMode();
            });
        }).start();
    }

    /**
     * Shows the share confirmation dialog.
     * v24: For entries with multiple attachments, lists all attachment filenames.
     */
    private void showDeleteConfirm() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Entry")
                .setMessage("Delete \"" + existingEntry.getDisplayTitle() + "\"? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    entries.remove(existingEntry);
                    final List<Attachment> attachmentsToDelete =
                            new ArrayList<>(existingEntry.getAttachments());
                    final String json = storage.exportToJson(entries);
                    if (json != null) {
                        new Thread(() -> {
                            storage.saveEntriesJson(json);
                            storage.setBackupPending(true);
                            attachmentStore.deleteAll(attachmentsToDelete);
                            runOnUiThread(() -> finish());
                        }).start();
                    } else {
                        new Thread(() -> {
                            attachmentStore.deleteAll(attachmentsToDelete);
                            runOnUiThread(() -> finish());
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showShareDialog() {
        // Build effective attachment list: saved – removals + pending adds
        List<Attachment> savedAtts = existingEntry != null
                ? existingEntry.getAttachments() : new ArrayList<>();
        List<Object> allFiles = new ArrayList<>();
        for (Attachment a : savedAtts) {
            if (!pendingRemovals.contains(a.getId())) allFiles.add(a);
        }
        for (PendingAttachment pa : pendingAdds) allFiles.add(pa);

        // No attachments — simple text-only confirmation
        if (allFiles.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Share Entry")
                    .setMessage("This will share your data as plain text.\n\nAre you sure?")
                    .setPositiveButton("Share", (d, w) -> shareEntry(true, new ArrayList<>()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        // Has attachments — Step 1: pick what to share
        new MaterialAlertDialogBuilder(this)
                .setTitle("Share Entry")
                .setAdapter(menuAdapter(new String[]{"\uD83D\uDCC4  Text only", "\uD83D\uDCCE  Attachments only", "\uD83D\uDDC2\uFE0F  Text + Attachments"}), (d, which) -> {
                    if (which == 0) {
                        // Text only — no file picker needed
                        shareEntry(true, new ArrayList<>());
                    } else {
                        boolean includeText = (which == 2);
                        if (allFiles.size() == 1) {
                            // Only one file — skip picker, share directly
                            shareEntry(includeText, allFiles);
                        } else {
                            // Multiple files — Step 2: pick which files
                            showFilePickerDialog(includeText, allFiles);
                        }
                    }
                })
                .show();
    }

    /** Step 2 of share flow — lets user pick which files to include, then shares. */
    private void showFilePickerDialog(boolean includeText, List<Object> allFiles) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int ph = dpToPx(24);
        int pv = dpToPx(16);
        layout.setPadding(ph, pv, ph, dpToPx(8));

        TextView tvHint = new TextView(this);
        tvHint.setText("Select files to include:");
        tvHint.setTextSize(14f);
        tvHint.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        layout.addView(tvHint);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(10)));
        layout.addView(spacer);

        List<CheckBox> checkBoxes = new ArrayList<>();
        for (Object file : allFiles) {
            String fname = file instanceof Attachment
                    ? ((Attachment) file).getName()
                    : ((PendingAttachment) file).name + "  (unsaved)";
            CheckBox chk = new CheckBox(this);
            chk.setText(fname);
            chk.setChecked(true);
            chk.setTextSize(13f);
            chk.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
            chk.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.text_primary, getTheme())));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dpToPx(4);
            chk.setLayoutParams(lp);
            layout.addView(chk);
            checkBoxes.add(chk);
        }

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(includeText ? "Text + Attachments" : "Attachments only")
                .setView(layout)
                .setPositiveButton("Share", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<Object> selected = new ArrayList<>();
            for (int i = 0; i < checkBoxes.size(); i++) {
                if (checkBoxes.get(i).isChecked()) selected.add(allFiles.get(i));
            }
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least one file.", Toast.LENGTH_SHORT).show();
                return;
            }
            dlg.dismiss();
            shareEntry(includeText, selected);
        });
    }

    /**
     * Performs the actual share.
     *
     * @param includeText   true = include entry text (.txt); false = attachments only
     * @param selectedFiles files to attach; empty = text-only share
     */
    private void shareEntry(boolean includeText, List<Object> selectedFiles) {
        // Build the entry text string (used when includeText=true)
        final String shareText;
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
            int checkedCount = 0;
            for (ChecklistItem item : items) { if (item.isChecked()) checkedCount++; }
            sb.append("─────────────────────────\n");
            sb.append(checkedCount).append(" of ").append(total).append(" done\n");
            sb.append("Shared from Cryptex");
            shareText = sb.toString();
        } else {
            String[] labels = EntryType.getFieldLabels(entryType);
            StringBuilder sb = new StringBuilder();
            sb.append(EntryType.getEmoji(entryType))
              .append("  ").append(EntryType.getDisplayName(entryType))
              .append("\n─────────────────────────\n");
            for (int i = 0; i < 7; i++) {
                if (labels[i].isEmpty()) continue;
                String val = isEditMode && editViews[i] != null
                        ? editViews[i].getText().toString().trim()
                        : (existingEntry != null ? existingEntry.getFieldByIndex(i + 1) : "");
                if (!val.isEmpty()) sb.append(labels[i]).append(":  ").append(val).append("\n");
            }
            sb.append("─────────────────────────\nShared from Cryptex");
            shareText = sb.toString();
        }

        // Text-only share — no files
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            startActivity(Intent.createChooser(
                    buildTextOnlyShareIntent(shareText), "Share via"));
            return;
        }

        // Has files — read on background thread
        final boolean finalIncludeText = includeText;
        final String entryId = (existingEntry != null && existingEntry.getId() != null)
                ? existingEntry.getId() : "pending";
        final String entryTitle = existingEntry != null ? existingEntry.getDisplayTitle() : "";
        final List<Object> filesToShare = new ArrayList<>(selectedFiles);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle("Preparing share…")
                .setView(pb)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "attachments/" + entryId);
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();

                java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();

                // Text file — only when includeText = true
                if (finalIncludeText) {
                    String txtFileName = entryTitle.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                    if (txtFileName.isEmpty()) txtFileName = "entry";
                    File txtFile = new File(cacheDir, txtFileName + ".txt");
                    try (FileOutputStream fos = new FileOutputStream(txtFile)) {
                        fos.write(shareText.getBytes("UTF-8"));
                    }
                    uris.add(FileProvider.getUriForFile(DetailActivity.this,
                            getPackageName() + ".fileprovider", txtFile));
                }

                // Selected attachment files
                for (Object file : filesToShare) {
                    try {
                        if (file instanceof Attachment) {
                            Attachment a = (Attachment) file;
                            byte[] bytes = attachmentStore.read(a.getId());
                            File attFile = new File(cacheDir, a.getName());
                            try (FileOutputStream fos = new FileOutputStream(attFile)) { fos.write(bytes); }
                            uris.add(FileProvider.getUriForFile(DetailActivity.this,
                                    getPackageName() + ".fileprovider", attFile));
                        } else if (file instanceof PendingAttachment) {
                            PendingAttachment pa = (PendingAttachment) file;
                            File attFile = new File(cacheDir, pa.name);
                            try (FileOutputStream fos = new FileOutputStream(attFile)) { fos.write(pa.bytes); }
                            uris.add(FileProvider.getUriForFile(DetailActivity.this,
                                    getPackageName() + ".fileprovider", attFile));
                        }
                    } catch (Exception ignored) { /* skip unreadable file */ }
                }

                if (uris.isEmpty()) {
                    // All files failed to read — fall back to text
                    runOnUiThread(() -> {
                        progress.dismiss();
                        startActivity(Intent.createChooser(
                                buildTextOnlyShareIntent(shareText), "Share via"));
                    });
                    return;
                }

                Intent shareIntent = uris.size() == 1
                        ? new Intent(Intent.ACTION_SEND)
                        : new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("*/*");
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, entryTitle);
                if (uris.size() == 1) {
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                }
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
                            buildTextOnlyShareIntent(shareText), "Share via"));
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

    /** Creates an ArrayAdapter with explicit text_primary color — ensures bright text in all dialogs. */
    private android.widget.ArrayAdapter<String> menuAdapter(String[] items) {
        int color = androidx.core.content.ContextCompat.getColor(this, R.color.text_primary);
        // Use layout resource 0 and build a fresh TextView — bypasses ALL theme/style inheritance.
        return new android.widget.ArrayAdapter<String>(this, 0, items) {
            @Override public android.view.View getView(int position, android.view.View convertView,
                    android.view.ViewGroup parent) {
                android.widget.TextView tv = (convertView instanceof android.widget.TextView)
                        ? (android.widget.TextView) convertView
                        : new android.widget.TextView(DetailActivity.this);
                tv.setText(getItem(position));
                tv.setTextColor(color);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
                int ph = dpToPx(16);
                int pv = dpToPx(14);
                tv.setPadding(ph, pv, ph, pv);
                tv.setMinHeight(dpToPx(48));
                tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
                return tv;
            }
        };
    }

    // ── v24: Attachment section ───────────────────────────────────────────────

    /** Initial setup of attachment section — binds container and Add button. */
    private void setupAttachmentSection() {
        // v20: checklist has no attachment section
        if (EntryType.CHECKLIST.equals(entryType)) return;

        attachmentListContainer = findViewById(R.id.attachmentListContainer);
        btnAddAttachment        = findViewById(R.id.btnAddAttachment);

        // Insert a persistent search container immediately before attachmentListContainer.
        // It is NEVER cleared — so the EditText inside it stays attached to the window
        // and the keyboard never dismisses while the user is typing.
        android.view.ViewGroup attachmentParent = (android.view.ViewGroup) attachmentListContainer.getParent();
        int attachmentIndex = attachmentParent.indexOfChild(attachmentListContainer);
        attachmentSearchContainer = new LinearLayout(this);
        attachmentSearchContainer.setOrientation(LinearLayout.VERTICAL);
        attachmentSearchContainer.setVisibility(View.GONE);
        attachmentParent.addView(attachmentSearchContainer, attachmentIndex);
        // attachmentListContainer is now at attachmentIndex+1

        // v29: restore saved attachment groups into pendingEmptyGroups so they appear in edit mode.
        // Groups that already have files are not added (they'll be rendered from the file's group field).
        if (existingEntry != null) {
            java.util.Set<String> groupsWithFiles = new java.util.HashSet<>();
            for (Attachment a : existingEntry.getAttachments()) {
                if (!a.getGroup().isEmpty()) groupsWithFiles.add(a.getGroup());
            }
            for (String g : existingEntry.getAttachmentGroups()) {
                if (!groupsWithFiles.contains(g)) pendingEmptyGroups.add(g);
            }
        }

        btnAddAttachment.setOnClickListener(v -> {
            int currentCount = (existingEntry != null ? existingEntry.getAttachments().size() : 0)
                    - pendingRemovals.size() + pendingAdds.size();
            int slotsLeft = MAX_ATTACHMENT_COUNT - currentCount;
            if (slotsLeft <= 0) {
                Toast.makeText(this,
                        "Maximum " + MAX_ATTACHMENT_COUNT + " attachments per entry.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // Build list of existing group names for picker
            java.util.LinkedHashSet<String> groupSet = new java.util.LinkedHashSet<>();
            if (existingEntry != null) {
                for (Attachment a : existingEntry.getAttachments()) {
                    if (!pendingRemovals.contains(a.getId()) && !a.getGroup().isEmpty())
                        groupSet.add(a.getGroup());
                }
            }
            for (PendingAttachment pa : pendingAdds) {
                if (!pa.group.isEmpty()) groupSet.add(pa.group);
            }
            // Apply any in-session group renames to the displayed names
            java.util.List<String> existingGroups = new java.util.ArrayList<>();
            for (String g : groupSet) {
                String resolved = g;
                for (java.util.Map.Entry<String, String> rename : renamedGroups.entrySet()) {
                    if (rename.getKey().equals(g)) { resolved = rename.getValue(); break; }
                }
                if (!existingGroups.contains(resolved)) existingGroups.add(resolved);
            }

            showAddAttachmentDialog(slotsLeft, existingGroups);
        });

        renderAttachmentList();
    }

    /**
     * Shows a simple two-option picker: Attach file (no group) or New Group.
     */
    private void showAddAttachmentDialog(int slotsLeft, java.util.List<String> existingGroups) {
        String slotHint = slotsLeft == 1 ? "  (1 slot remaining)" : "  (up to " + slotsLeft + " files)";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Add" + slotHint)
                .setAdapter(menuAdapter(new String[]{"\uD83D\uDCCE  Attach file", "\uD83D\uDCC1  New Group"}), (d, which) -> {
                    if (which == 0) {
                        // Attach file — no group
                        launchPickerForGroup(slotsLeft, "");
                    } else {
                        // New Group — prompt for name, then create empty group header
                        android.widget.EditText etGroupName = new android.widget.EditText(this);
                        etGroupName.setHint("Group name (e.g. Payslips)");
                        etGroupName.setSingleLine(true);
                        etGroupName.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
                        int pad = dpToPx(16);
                        etGroupName.setPadding(pad, pad, pad, pad);
                        AlertDialog nameDialog = new MaterialAlertDialogBuilder(this)
                                .setTitle("New Group")
                                .setView(etGroupName)
                                .setPositiveButton("Create", null)
                                .setNegativeButton("Cancel", null)
                                .create();
                        nameDialog.show();
                        nameDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v2 -> {
                            String groupName = etGroupName.getText().toString().trim();
                            if (groupName.isEmpty()) { etGroupName.setError("Required"); return; }
                            // Prevent duplicate group names
                            if (existingGroups.contains(groupName) || pendingEmptyGroups.contains(groupName)) {
                                etGroupName.setError("Group already exists");
                                return;
                            }
                            nameDialog.dismiss();
                            // Add as empty group — user taps [+] on the header to add files
                            pendingEmptyGroups.add(groupName);
                            renderAttachmentList();
                        });
                        etGroupName.post(() -> {
                            etGroupName.requestFocus();
                            android.view.inputmethod.InputMethodManager imm =
                                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                            if (imm != null) imm.showSoftInput(etGroupName,
                                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        });
                    }
                })
                .show();
    }

    /** Stores the target group for the pending pick, then launches camera or file picker. */
    private String pendingPickGroup = ""; // group to assign to files from the next pick session

    private void launchPickerForGroup(int slotsLeft, String group) {
        pendingPickGroup = group;
        String slotHint = slotsLeft == 1 ? "  (1 slot remaining)" : "  (up to " + slotsLeft + " files)";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Add files" + slotHint)
                .setAdapter(menuAdapter(new String[]{"\uD83D\uDCF7  Camera", "\uD83D\uDCC1  From files"}), (d, which) -> {
                    if (which == 0) {
                        launchCamera();
                    } else if (slotsLeft == 1) {
                        singleFilePicker.launch(new String[]{"*/*"});
                    } else {
                        attachmentPicker.launch(new String[]{"*/*"});
                    }
                })
                .show();
    }

    /**
     * Rebuilds the attachment list UI from current state — grouped rendering.
     *
     * 1 file   → single row with paperclip icon, no master header.
     * 2+ files → master "📎 Attachments (N)" collapse/expand row.
     *            VIEW mode: starts collapsed. EDIT mode: starts expanded.
     *            Named groups → folder header rows (indented files inside).
     *            Ungrouped files → flat rows at normal indent.
     */
    private void renderAttachmentList() {
        if (attachmentListContainer == null) return;
        attachmentListContainer.removeAllViews();

        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) attachmentListContainer.getLayoutParams();
        if (lp != null) lp.topMargin = dpToPx(isEditMode ? 0 : 8);
        attachmentListContainer.setLayoutParams(lp);

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

        if (totalCount == 0 && pendingEmptyGroups.isEmpty()) return;

        // Single file with no empty groups — show directly with icon, no master header
        if (totalCount == 1 && pendingEmptyGroups.isEmpty()) {
            // Build groups just to find the one item
            java.util.LinkedHashMap<String, java.util.List<Object>> singleGroups = new java.util.LinkedHashMap<>();
            for (Attachment att : savedList) {
                String g = att.getGroup();
                String resolved = g;
                for (java.util.Map.Entry<String, String> e2 : renamedGroups.entrySet()) {
                    if (e2.getKey().equals(g)) { resolved = e2.getValue(); break; }
                }
                if (!singleGroups.containsKey(resolved)) singleGroups.put(resolved, new java.util.ArrayList<>());
                singleGroups.get(resolved).add(att);
            }
            for (PendingAttachment pa : pendingAdds) {
                String g = pa.group != null ? pa.group : "";
                if (!singleGroups.containsKey(g)) singleGroups.put(g, new java.util.ArrayList<>());
                singleGroups.get(g).add(pa);
            }
            for (java.util.List<Object> items : singleGroups.values()) {
                for (Object item : items) {
                    if (item instanceof Attachment) addSavedAttachRow((Attachment) item, "", true);
                    else addPendingAttachRow((PendingAttachment) item, true);
                }
            }
            return;
        }

        // ── 2+ files — build grouped structure ───────────────────────────────
        java.util.LinkedHashMap<String, java.util.List<Object>> groups = new java.util.LinkedHashMap<>();
        for (Attachment att : savedList) {
            String g = att.getGroup();
            String resolved = g;
            for (java.util.Map.Entry<String, String> e2 : renamedGroups.entrySet()) {
                if (e2.getKey().equals(g)) { resolved = e2.getValue(); break; }
            }
            if (!groups.containsKey(resolved)) groups.put(resolved, new java.util.ArrayList<>());
            groups.get(resolved).add(att);
        }
        for (PendingAttachment pa : pendingAdds) {
            String g = pa.group != null ? pa.group : "";
            if (!groups.containsKey(g)) groups.put(g, new java.util.ArrayList<>());
            groups.get(g).add(pa);
        }

        java.util.List<String> namedGroupKeys = new java.util.ArrayList<>();
        for (String k : groups.keySet()) { if (!k.isEmpty()) namedGroupKeys.add(k); }
        // Include pending empty groups (created this session, no files yet)
        for (String eg : pendingEmptyGroups) {
            if (!namedGroupKeys.contains(eg)) namedGroupKeys.add(eg);
            if (!groups.containsKey(eg)) groups.put(eg, new java.util.ArrayList<>());
        }
        boolean hasUngrouped = groups.containsKey("");

        // Edit mode: default expand master + all groups (unless user explicitly collapsed)
        if (isEditMode && !userCollapsedGroups.contains("__master__")) {
            attachmentsExpanded = true;
        }
        if (isEditMode) {
            for (String k : namedGroupKeys) {
                if (!userCollapsedGroups.contains(k)) expandedGroups.add(k);
            }
        }

        // ── Master "Attachments (N)" row ──────────────────────────────────────
        addMasterAttachmentsRow(totalCount, attachmentsExpanded);

        // ── Search bar (VIEW mode, ≥6 files) — lives in attachmentSearchContainer ──
        // The container is separate from attachmentListContainer so removeAllViews()
        // never destroys the EditText and the keyboard stays open while typing.
        if (!isEditMode && totalCount >= 6) {
            if (attachmentSearchVisible) {
                attachmentSearchContainer.setVisibility(View.VISIBLE);
                if (attachmentSearchEt == null) buildAttachmentSearchBar();
            } else {
                attachmentSearchContainer.setVisibility(View.GONE);
            }
        } else {
            attachmentSearchContainer.setVisibility(View.GONE);
        }

        // Bug fix: search is independent of collapse state.
        // Only skip file rows if collapsed AND search is not active.
        if (!attachmentsExpanded && !attachmentSearchVisible) return;

        // Active filter — only apply when there is actual typed text.
        // Empty query = no filter (don't show all files just because bar is open).
        String filterQ = (!isEditMode && attachmentSearchVisible && !attachmentSearchQuery.trim().isEmpty())
                ? attachmentSearchQuery.trim().toLowerCase()
                : "";
        boolean filtering = !filterQ.isEmpty();

        // When search bar is open but nothing typed yet — show nothing in file list
        if (attachmentSearchVisible && !filtering) return;

        boolean anyVisible = false;

        // ── Render named groups (indented under master) ──────────────────────
        for (String groupKey : namedGroupKeys) {
            java.util.List<Object> allItems = groups.get(groupKey);
            // Apply filter: keep items whose filename contains the query
            java.util.List<Object> items = new java.util.ArrayList<>();
            if (filtering) {
                for (Object item : allItems) {
                    String fname = (item instanceof Attachment)
                            ? ((Attachment) item).getName()
                            : ((PendingAttachment) item).name;
                    if (fname.toLowerCase().contains(filterQ)) items.add(item);
                }
            } else {
                items.addAll(allItems);
            }
            // When filtering: hide groups with no matches.
            // In VIEW mode (not filtering): hide groups with no files (empty groups are edit-only).
            // In EDIT mode (not filtering): always show, even if 0 files (user can add to them).
            if (items.isEmpty() && (filtering || !isEditMode)) continue;
            anyVisible = true;
            boolean isExpanded = filtering || expandedGroups.contains(groupKey); // auto-expand when filtering
            addGroupHeaderRow(groupKey, items.size(), isExpanded, true);
            if (isExpanded) {
                for (Object item : items) {
                    if (item instanceof Attachment)
                        addSavedAttachRow((Attachment) item, groupKey, false, true, true);
                    else
                        addPendingAttachRow((PendingAttachment) item, false, true, true);
                }
            }
        }

        // ── Render ungrouped files (indented under master) ────────────────────
        if (hasUngrouped) {
            java.util.List<Object> allItems = groups.get("");
            java.util.List<Object> items = new java.util.ArrayList<>();
            if (filtering) {
                for (Object item : allItems) {
                    String fname = (item instanceof Attachment)
                            ? ((Attachment) item).getName()
                            : ((PendingAttachment) item).name;
                    if (fname.toLowerCase().contains(filterQ)) items.add(item);
                }
            } else {
                items.addAll(allItems);
            }
            for (Object item : items) {
                anyVisible = true;
                if (item instanceof Attachment) addSavedAttachRow((Attachment) item, "", false, true, false);
                else addPendingAttachRow((PendingAttachment) item, false, true, false);
            }
        }

        // ── No-results message when filter matches nothing ─────────────────────
        if (filtering && !anyVisible) {
            float d = getResources().getDisplayMetrics().density;
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("No attachments match");
            tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            tvEmpty.setTextColor(0xFF9E9E9E);
            tvEmpty.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            emptyLp.topMargin = Math.round(8 * d);
            emptyLp.bottomMargin = Math.round(8 * d);
            tvEmpty.setLayoutParams(emptyLp);
            attachmentListContainer.addView(tvEmpty);
        }
    }

    /** Builds the search EditText inside attachmentSearchContainer (called once per search session). */
    private void buildAttachmentSearchBar() {
        attachmentSearchContainer.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(8 * d);
        int sz  = Math.round(18 * d);
        int gap = Math.round(6 * d);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad / 2, pad, pad / 2);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        attachmentSearchEt = new android.widget.EditText(this);
        attachmentSearchEt.setHint("Search attachments…");
        attachmentSearchEt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        attachmentSearchEt.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        attachmentSearchEt.setHintTextColor(0xFF9E9E9E);
        attachmentSearchEt.setBackground(null);
        attachmentSearchEt.setSingleLine(true);
        attachmentSearchEt.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        attachmentSearchEt.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(attachmentSearchEt);

        ImageView btnClear = new ImageView(this);
        btnClear.setImageResource(R.drawable.ic_close);
        btnClear.setColorFilter(0xFF9E9E9E);
        btnClear.setVisibility(View.GONE);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(sz, sz);
        clLp.setMarginStart(gap);
        btnClear.setLayoutParams(clLp);
        btnClear.setClickable(true);
        btnClear.setFocusable(true);
        btnClear.setOnClickListener(v -> {
            attachmentSearchQuery = "";
            attachmentSearchEt.setText("");
            btnClear.setVisibility(View.GONE);
            // Only rebuild file rows — EditText stays in attachmentSearchContainer untouched
            renderAttachmentList();
            attachmentSearchEt.requestFocus();
        });
        bar.addView(btnClear);

        attachmentSearchEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                attachmentSearchQuery = s.toString();
                btnClear.setVisibility(attachmentSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                // Only clears and rebuilds attachmentListContainer (file rows).
                // attachmentSearchContainer (this EditText) is never touched — keyboard stays open.
                renderAttachmentList();
            }
        });

        attachmentSearchContainer.addView(bar);

        // Open keyboard on first appearance
        attachmentSearchEt.post(() -> {
            attachmentSearchEt.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(attachmentSearchEt,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /** Master collapse/expand row: "📎  Attachments (N)  ›/▼" */
    private void addMasterAttachmentsRow(int count, boolean isExpanded) {
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(10 * d);
        int sz  = Math.round(20 * d);
        int gap = Math.round(8 * d);
        int mb  = Math.round(4 * d);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackground(null);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = mb;
        row.setLayoutParams(rowLp);

        // Paperclip icon
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_attachment);
        icon.setColorFilter(0xFF757575);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(sz, sz);
        iconLp.setMarginEnd(gap);
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        // "Attachments (N)" label
        TextView tvLabel = new TextView(this);
        tvLabel.setText("Attachments  (" + count + ")");
        tvLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvLabel.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        tvLabel.setTypeface(tvLabel.getTypeface(), android.graphics.Typeface.BOLD);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        // Search icon — reserved for future version (blocked v28)

        // Chevron
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setRotation(isExpanded ? 90f : 0f);
        chevron.setColorFilter(0xFF757575);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(sz, sz);
        chLp.setMarginStart(gap);
        chevron.setLayoutParams(chLp);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            attachmentsExpanded = !attachmentsExpanded;
            if (isEditMode) {
                // Track master collapse so edit mode doesn't force re-expand
                if (attachmentsExpanded) userCollapsedGroups.remove("__master__");
                else userCollapsedGroups.add("__master__");
            }
            renderAttachmentList();
        });

        attachmentListContainer.addView(row);
    }

    /** Groups the user has explicitly expanded (view mode: tap to expand; edit mode: expanded by default). */
    private final java.util.Set<String> expandedGroups = new java.util.HashSet<>();
    /** Groups the user has explicitly collapsed while in edit mode. */
    private final java.util.Set<String> userCollapsedGroups = new java.util.HashSet<>();
    /** Whether the attachment search bar is currently visible (VIEW mode only). */
    private boolean attachmentSearchVisible = false;
    /** Current attachment search query (VIEW mode only). */
    private String attachmentSearchQuery = "";
    /** Persisted EditText for attachment search — kept alive across re-renders to preserve keyboard focus. */
    private android.widget.EditText attachmentSearchEt = null;

    // ── Attachment row/header builders ────────────────────────────────────────

    private void addGroupHeaderRow(String groupName, int count, boolean isExpanded) {
        addGroupHeaderRow(groupName, count, isExpanded, false);
    }

    private void addGroupHeaderRow(String groupName, int count, boolean isExpanded, boolean indent) {
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(10 * d);
        int sz  = Math.round(20 * d);
        int gap = Math.round(8 * d);
        int mb  = Math.round(4 * d);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackground(null);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = mb;
        if (indent) rowLp.setMarginStart(dpToPx(16));
        row.setLayoutParams(rowLp);

        // Folder icon on group header
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_folder);
        icon.setColorFilter(0xFF757575);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(sz, sz);
        iconLp.setMarginEnd(gap);
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        // Group name + count label
        TextView tvName = new TextView(this);
        tvName.setText(groupName + "  (" + count + ")");
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvName.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        tvName.setTypeface(tvName.getTypeface(), android.graphics.Typeface.BOLD);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvName);

        // Edit mode: only [+] add button as inline icon; rename/delete via long-press
        if (isEditMode) {
            ImageButton btnAdd = new ImageButton(this);
            btnAdd.setImageResource(R.drawable.ic_add);
            btnAdd.setBackground(null);
            btnAdd.setColorFilter(getResources().getColor(R.color.text_primary, getTheme()));
            btnAdd.setContentDescription("Add files to " + groupName);
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(sz, sz);
            addLp.setMarginStart(gap);
            btnAdd.setLayoutParams(addLp);
            btnAdd.setOnClickListener(v -> {
                int currentCount = (existingEntry != null ? existingEntry.getAttachments().size() : 0)
                        - pendingRemovals.size() + pendingAdds.size();
                int slotsLeft = MAX_ATTACHMENT_COUNT - currentCount;
                if (slotsLeft <= 0) {
                    Toast.makeText(this, "Maximum " + MAX_ATTACHMENT_COUNT + " attachments per entry.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                launchPickerForGroup(slotsLeft, groupName);
            });
            row.addView(btnAdd);
        }

        // Chevron — rotated when expanded
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setRotation(isExpanded ? 90f : 0f);
        chevron.setColorFilter(0xFF757575);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(sz, sz);
        chLp.setMarginStart(gap);
        chevron.setLayoutParams(chLp);
        row.addView(chevron);

        // Single tap: expand / collapse
        row.setOnClickListener(v -> {
            if (isEditMode) {
                if (expandedGroups.contains(groupName)) {
                    expandedGroups.remove(groupName);
                    userCollapsedGroups.add(groupName);
                } else {
                    expandedGroups.add(groupName);
                    userCollapsedGroups.remove(groupName);
                }
            } else {
                if (expandedGroups.contains(groupName)) expandedGroups.remove(groupName);
                else expandedGroups.add(groupName);
            }
            renderAttachmentList();
        });

        // Long press (edit mode only): Rename / Delete Group
        if (isEditMode) {
            row.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(DetailActivity.this)
                        .setTitle(groupName)
                        .setAdapter(menuAdapter(new String[]{"\u270F\uFE0F  Rename", "\uD83D\uDDD1\uFE0F  Delete Group"}), (dlg, which) -> {
                            if (which == 0) {
                                showRenameGroupDialog(groupName);
                            } else {
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle("Delete Group")
                                        .setMessage("Delete \"" + groupName + "\" and all its files?\n\nFiles will be permanently removed when you save.")
                                        .setPositiveButton("Delete", (d2, w2) -> deleteGroup(groupName))
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            }
                        })
                        .show();
                return true;
            });
        }

        attachmentListContainer.addView(row);
    }

    /**
     * After individually removing the last file from a named group, preserve the group
     * header in edit mode by adding it to pendingEmptyGroups.
     * This ensures the group only disappears via explicit "Delete Group", not via file deletion.
     */
    private void preserveGroupIfNowEmpty(String resolvedGroupName) {
        if (resolvedGroupName == null || resolvedGroupName.isEmpty()) return;
        // Check remaining saved files in this group (excluding already-flagged removals)
        if (existingEntry != null) {
            for (Attachment a : existingEntry.getAttachments()) {
                if (pendingRemovals.contains(a.getId())) continue;
                String resolved = a.getGroup();
                for (java.util.Map.Entry<String, String> e2 : renamedGroups.entrySet()) {
                    if (e2.getKey().equals(a.getGroup())) { resolved = e2.getValue(); break; }
                }
                if (resolved.equals(resolvedGroupName)) return; // still has files
            }
        }
        // Check remaining pending (unsaved) files in this group
        for (PendingAttachment pa : pendingAdds) {
            if (resolvedGroupName.equals(pa.group != null ? pa.group : "")) return; // still has files
        }
        // Group is now empty — keep it alive as an empty group
        pendingEmptyGroups.add(resolvedGroupName);
    }

    private void deleteGroup(String groupName) {
        // Mark all saved attachments in this group for removal
        if (existingEntry != null) {
            for (Attachment a : existingEntry.getAttachments()) {
                String resolved = a.getGroup();
                for (java.util.Map.Entry<String, String> e2 : renamedGroups.entrySet()) {
                    if (e2.getKey().equals(a.getGroup())) { resolved = e2.getValue(); break; }
                }
                if (resolved.equals(groupName)) {
                    originalGroups.putIfAbsent(a.getId(), a.getGroup());
                    pendingRemovals.add(a.getId());
                }
            }
        }
        // Remove any pending (not-yet-saved) attachments in this group
        pendingAdds.removeIf(pa -> groupName.equals(pa.group != null ? pa.group : ""));
        // If this group existed in saved storage, record the deletion so hasUnsavedChanges() fires
        if (existingEntry != null && existingEntry.getAttachmentGroups().contains(groupName)) {
            deletedGroups.add(groupName);
        }
        // Clean up all tracking for this group
        pendingEmptyGroups.remove(groupName);
        expandedGroups.remove(groupName);
        userCollapsedGroups.remove(groupName);
        renderAttachmentList();
    }

    private void showRenameGroupDialog(String currentDisplayName) {
        android.widget.EditText etName = new android.widget.EditText(this);
        etName.setText(currentDisplayName);
        etName.selectAll();
        int pad = dpToPx(16);
        etName.setPadding(pad, pad, pad, pad);
        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Rename Group")
                .setView(etName)
                .setPositiveButton("Rename", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = etName.getText().toString().trim();
            if (newName.isEmpty()) { etName.setError("Required"); return; }
            if (newName.equals(currentDisplayName)) { dlg.dismiss(); return; }
            // Apply rename to all saved attachments in this group
            if (existingEntry != null) {
                for (Attachment a : existingEntry.getAttachments()) {
                    String resolvedGroup = a.getGroup();
                    for (java.util.Map.Entry<String, String> e2 : renamedGroups.entrySet()) {
                        if (e2.getKey().equals(a.getGroup())) { resolvedGroup = e2.getValue(); break; }
                    }
                    if (resolvedGroup.equals(currentDisplayName)) {
                        // Track original group for revert
                        originalGroups.putIfAbsent(a.getId(), a.getGroup());
                        a.setGroup(newName);
                    }
                }
            }
            // Apply rename to pending adds in this group
            for (PendingAttachment pa : pendingAdds) {
                String paGroup = pa.group != null ? pa.group : "";
                if (paGroup.equals(currentDisplayName)) pa.group = newName;
            }
            // Track the rename for discard revert (original name → new name)
            // Remove the old entry and add new one so the chain stays correct
            String trueOriginal = currentDisplayName;
            for (java.util.Map.Entry<String, String> e2 : renamedGroups.entrySet()) {
                if (e2.getValue().equals(currentDisplayName)) { trueOriginal = e2.getKey(); break; }
            }
            renamedGroups.remove(trueOriginal);
            // Only record if it differs from what was on disk (original name)
            if (!newName.equals(trueOriginal)) renamedGroups.put(trueOriginal, newName);
            // Keep expandedGroups consistent
            if (expandedGroups.remove(currentDisplayName)) expandedGroups.add(newName);
            dlg.dismiss();
            renderAttachmentList();
        });
        etName.post(() -> {
            etName.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etName,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void addSavedAttachRow(Attachment att, String groupKey, boolean showIcon) {
        addSavedAttachRow(att, groupKey, showIcon, false, false);
    }

    private void addSavedAttachRow(Attachment att, String groupKey, boolean showIcon, boolean indent) {
        addSavedAttachRow(att, groupKey, showIcon, indent, false);
    }

    private void addSavedAttachRow(Attachment att, String groupKey, boolean showIcon, boolean indent, boolean doubleIndent) {
        android.view.View row = getLayoutInflater()
                .inflate(R.layout.item_attachment_row, attachmentListContainer, false);
        TextView    tvLabel   = row.findViewById(R.id.tvAttachLabel);
        ImageButton btnRename = row.findViewById(R.id.btnAttachRowRename);
        ImageButton btnRemove = row.findViewById(R.id.btnAttachRowRemove);
        android.view.View card = row.findViewById(R.id.attachRowCard);
        android.view.View ivIcon = row.findViewById(R.id.ivAttachIcon);
        if (ivIcon != null) ivIcon.setVisibility(showIcon
                ? android.view.View.VISIBLE : android.view.View.GONE);
        // Indent group files so they visually sit inside their group
        if (indent || doubleIndent) {
            LinearLayout.LayoutParams rowLp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (rowLp == null) rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMarginStart(dpToPx(doubleIndent ? 32 : 16));
            row.setLayoutParams(rowLp);
        }

        String displayName = att.getName().length() > 28
                ? att.getName().substring(0, 25) + "…" : att.getName();
        tvLabel.setText(displayName + "   " + formatBytes(att.getSize()));
        card.setOnClickListener(v -> openSavedAttachmentWithProgress(att));
        card.setBackground(null);

        // Hide inline rename/remove buttons — actions handled via long-press
        if (btnRename != null) btnRename.setVisibility(android.view.View.GONE);
        if (btnRemove != null) btnRemove.setVisibility(android.view.View.GONE);

        // Long press (edit mode): Rename / Delete
        if (isEditMode) {
            card.setLongClickable(true);
            card.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(DetailActivity.this)
                        .setTitle(att.getName())
                        .setAdapter(menuAdapter(new String[]{"\u270F\uFE0F  Rename", "\uD83D\uDDD1\uFE0F  Delete"}), (dlg, which) -> {
                            if (which == 0) {
                                android.widget.EditText etName = new android.widget.EditText(this);
                                etName.setText(att.getName());
                                etName.selectAll();
                                int p = dpToPx(16);
                                etName.setPadding(p, p, p, p);
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle("Rename Attachment")
                                        .setView(etName)
                                        .setPositiveButton("Rename", (d2, w2) -> {
                                            String newName = etName.getText().toString().trim();
                                            if (!newName.isEmpty() && !newName.equals(att.getName())) {
                                                renamedOriginals.putIfAbsent(att.getId(), att.getName());
                                                att.setName(newName);
                                                renderAttachmentList();
                                            }
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            } else {
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle("Remove Attachment")
                                        .setMessage("Remove \"" + att.getName() + "\"?\n\nIt will be permanently deleted when you save.")
                                        .setPositiveButton("Remove", (d2, w2) -> {
                                            pendingRemovals.add(att.getId());
                                            // Keep the group header alive if this was the last file
                                            preserveGroupIfNowEmpty(groupKey);
                                            renderAttachmentList();
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            }
                        })
                        .show();
                return true;
            });
        }
        attachmentListContainer.addView(row);
    }

    private void addPendingAttachRow(PendingAttachment pa, boolean showIcon) {
        addPendingAttachRow(pa, showIcon, false, false);
    }

    private void addPendingAttachRow(PendingAttachment pa, boolean showIcon, boolean indent) {
        addPendingAttachRow(pa, showIcon, indent, false);
    }

    private void addPendingAttachRow(PendingAttachment pa, boolean showIcon, boolean indent, boolean doubleIndent) {
        android.view.View row = getLayoutInflater()
                .inflate(R.layout.item_attachment_row, attachmentListContainer, false);
        TextView    tvLabel   = row.findViewById(R.id.tvAttachLabel);
        ImageButton btnRename = row.findViewById(R.id.btnAttachRowRename);
        ImageButton btnRemove = row.findViewById(R.id.btnAttachRowRemove);
        android.view.View card = row.findViewById(R.id.attachRowCard);
        android.view.View ivIcon = row.findViewById(R.id.ivAttachIcon);
        if (ivIcon != null) ivIcon.setVisibility(showIcon
                ? android.view.View.VISIBLE : android.view.View.GONE);
        if (indent || doubleIndent) {
            LinearLayout.LayoutParams rowLp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (rowLp == null) rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMarginStart(dpToPx(doubleIndent ? 32 : 16));
            row.setLayoutParams(rowLp);
        }

        String pendingDisplayName = pa.name.length() > 28
                ? pa.name.substring(0, 25) + "…" : pa.name;
        tvLabel.setText(pendingDisplayName + "   " + formatBytes(pa.bytes.length) + "  ·  pending");
        card.setBackground(null);
        card.setClickable(isEditMode);
        card.setOnClickListener(null);

        // Hide inline rename/remove buttons — actions handled via long-press
        if (btnRename != null) btnRename.setVisibility(android.view.View.GONE);
        if (btnRemove != null) btnRemove.setVisibility(android.view.View.GONE);

        // Long press (edit mode): Rename / Delete
        if (isEditMode) {
            card.setLongClickable(true);
            card.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(DetailActivity.this)
                        .setTitle(pa.name)
                        .setAdapter(menuAdapter(new String[]{"\u270F\uFE0F  Rename", "\uD83D\uDDD1\uFE0F  Delete"}), (dlg, which) -> {
                            if (which == 0) {
                                android.widget.EditText etName = new android.widget.EditText(this);
                                etName.setText(pa.name);
                                etName.selectAll();
                                int p = dpToPx(16);
                                etName.setPadding(p, p, p, p);
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle("Rename Attachment")
                                        .setView(etName)
                                        .setPositiveButton("Rename", (d2, w2) -> {
                                            String newName = etName.getText().toString().trim();
                                            if (!newName.isEmpty()) { pa.name = newName; renderAttachmentList(); }
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            } else {
                                String paGroup = pa.group != null ? pa.group : "";
                                pendingAdds.remove(pa);
                                // Keep the group header alive if this was the last file
                                preserveGroupIfNowEmpty(paGroup);
                                renderAttachmentList();
                            }
                        })
                        .show();
                return true;
            });
        }
        attachmentListContainer.addView(row);
    }

    private void addAddToGroupButton(String groupName) {
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(8 * d);
        int mb  = Math.round(4 * d);

        com.google.android.material.button.MaterialButton btn =
                new com.google.android.material.button.MaterialButton(this,
                        null, com.google.android.material.R.attr.borderlessButtonStyle);
        btn.setText("+ Add to " + groupName);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setPadding(pad, pad / 2, pad, pad / 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = mb;
        lp.setMarginStart(dpToPx(8));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> {
            int currentCount = (existingEntry != null ? existingEntry.getAttachments().size() : 0)
                    - pendingRemovals.size() + pendingAdds.size();
            int slotsLeft = MAX_ATTACHMENT_COUNT - currentCount;
            if (slotsLeft <= 0) {
                Toast.makeText(this, "Maximum " + MAX_ATTACHMENT_COUNT + " attachments per entry.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            launchPickerForGroup(slotsLeft, groupName);
        });
        attachmentListContainer.addView(btn);
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
            runOnUiThread(() -> openBytesAsFile(bytes, att.getId(), att.getName(), att.getMimeType()));
        }).start();
    }

    /** Writes bytes to cache and fires ACTION_VIEW intent. */
    private void openBytesAsFile(byte[] bytes, String attachmentId, String name, String mimeType) {
        try {
            String entryId = (existingEntry != null) ? existingEntry.getId() : "pending";
            File cacheDir = new File(getCacheDir(), "attachments/" + entryId);
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
            // Sanitize filename to prevent path traversal via crafted content-provider names.
            // Keeps alphanumerics, dots, hyphens and underscores; replaces everything else with _.
            String safeName = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            if (safeName.isEmpty()) safeName = "attachment";
            // Prefix with attachment UUID to prevent collision when two files share the same name.
            String cacheFileName = attachmentId + "_" + safeName;
            File outFile = new File(cacheDir, cacheFileName);
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

            // Check total size limit across all attachments
            if (!checkTotalSizeLimit(bytes.length)) return;

            String fileName = "photo_" + new java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date()) + ".jpg";
            pendingAdds.add(new PendingAttachment(fileName, "image/jpeg", bytes, pendingPickGroup));
            pendingEmptyGroups.remove(pendingPickGroup);
            renderAttachmentList();

        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.attachment_open_error), Toast.LENGTH_SHORT).show();
        }
    }

    /** Handles one or more files picked from the system file picker. */
    private void handleAttachmentsPicked(java.util.List<android.net.Uri> uris) {
        // Capture the target group for this pick session
        final String targetGroup = pendingPickGroup;
        // Snapshot current state on main thread before spawning background work
        final int slotStart = (existingEntry != null ? existingEntry.getAttachments().size() : 0)
                - pendingRemovals.size() + pendingAdds.size();
        long existingSize = 0;
        if (existingEntry != null) {
            for (Attachment a : existingEntry.getAttachments()) {
                if (!pendingRemovals.contains(a.getId())) existingSize += a.getSize();
            }
        }
        for (PendingAttachment pa : pendingAdds) existingSize += pa.bytes.length;
        final long sizeStart = existingSize;

        // Show progress while reading — file I/O can be significant for multiple large files
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle("Reading files\u2026")
                .setView(pb)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            java.util.List<PendingAttachment> newAdds = new java.util.ArrayList<>();
            int skippedTooLarge = 0;
            int skippedCap      = 0;
            int skippedTotal    = 0;
            int slotCount       = slotStart;
            long runningSize    = sizeStart;

            for (android.net.Uri uri : uris) {
                if (slotCount >= MAX_ATTACHMENT_COUNT) { skippedCap++; continue; }
                try {
                    byte[] bytes;
                    try (InputStream is = getContentResolver().openInputStream(uri)) {
                        if (is == null) { skippedTooLarge++; continue; }
                        bytes = readStreamBytes(is);
                    }
                    if (bytes.length > MAX_SINGLE_BYTES) { skippedTooLarge++; continue; }
                    if (runningSize + bytes.length > MAX_TOTAL_BYTES) { skippedTotal++; continue; }

                    String fileName = "attachment";
                    android.database.Cursor cursor = getContentResolver().query(
                            uri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) fileName = cursor.getString(idx);
                        cursor.close();
                    }
                    String mimeType = getContentResolver().getType(uri);
                    if (mimeType == null || mimeType.isEmpty()) mimeType = "application/octet-stream";
                    newAdds.add(new PendingAttachment(fileName, mimeType, bytes, targetGroup));
                    slotCount++;
                    runningSize += bytes.length;
                } catch (Exception e) {
                    skippedTooLarge++;
                }
            }

            final java.util.List<PendingAttachment> finalAdds = newAdds;
            final int fTooLarge = skippedTooLarge;
            final int fCap      = skippedCap;
            final int fTotal    = skippedTotal;

            runOnUiThread(() -> {
                progress.dismiss();
                pendingAdds.addAll(finalAdds);
                if (!finalAdds.isEmpty()) pendingEmptyGroups.remove(targetGroup);
                renderAttachmentList();

                int totalSkipped = fTooLarge + fCap + fTotal;
                if (totalSkipped > 0) {
                    String reason;
                    if (fCap > 0)
                        reason = "Maximum " + MAX_ATTACHMENT_COUNT + " attachments per entry.";
                    else if (fTotal > 0)
                        reason = "Total size limit (" + formatBytes(MAX_TOTAL_BYTES) + ") reached.";
                    else
                        reason = totalSkipped + " file(s) too large (max " + formatBytes(MAX_SINGLE_BYTES) + " each).";
                    Toast.makeText(DetailActivity.this,
                            totalSkipped + " file(s) skipped. " + reason, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /**
     * Checks whether adding newBytes would exceed the total limit.
     * Shows an error dialog and returns false if it would.
     * Used only for single-file paths (camera capture).
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
        String name;  // mutable — user can rename before saving
        String group; // v27: mutable — user can assign/change group before saving
        final String mimeType;
        final byte[] bytes;

        PendingAttachment(String name, String mimeType, byte[] bytes) {
            this(name, mimeType, bytes, "");
        }

        PendingAttachment(String name, String mimeType, byte[] bytes, String group) {
            this.name     = name;
            this.mimeType = mimeType;
            this.bytes    = bytes;
            this.group    = group != null ? group : "";
        }
    }
}
