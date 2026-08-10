package com.cryptex.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
    private ImageButton btnNoteFormat; // Note type only — format picker in edit mode

    // Card type detection label (edit mode only, Card type only)
    private TextView cardTypeLabelView = null;

    // v9: Unsaved-changes action bar
    private LinearLayout saveActionBar;

    // v12: Clipboard auto-clear
    private static final long CLIPBOARD_CLEAR_DELAY_MS = 30_000; // 30 seconds
    private final android.os.Handler clipboardHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable clipboardClearRunnable = null;

    // v12: Created date label shown in VIEW mode
    private TextView    tvCreatedAt;
    private View        noteEndDivider; // shown at end of Note body in VIEW mode only

    // Note-body formatting toolbar (edit mode only)
    private static final int FORMAT_PARAGRAPH = 0;
    private static final int FORMAT_BULLET    = 1;
    private static final int FORMAT_NUMBERED  = 2;
    private static final int FORMAT_HEADING   = 3;
    private int              activeNoteFormat  = FORMAT_PARAGRAPH;
    private LinearLayout     noteFormatToolbar = null;
    private final TextView[] noteFormatBtns    = new TextView[4];

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_detail);

        storage = StorageHelper.getInstance(this);
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
        btnEdit         = findViewById(R.id.btnEdit);
        btnShare        = findViewById(R.id.btnShare);
        btnDelete       = findViewById(R.id.btnDelete);
        btnArchive      = findViewById(R.id.btnArchive);
        btnOverflow     = findViewById(R.id.btnOverflow);
        btnNoteFormat   = findViewById(R.id.btnNoteFormat);
        btnNoteFormat.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, btnNoteFormat);
            String[] labels = { "¶  Free text", "•  Bullet", "1.  Numbered", "H  Heading" };
            for (int i = 0; i < labels.length; i++) {
                String label = (i == activeNoteFormat ? "✓  " : "     ") + labels[i];
                popup.getMenu().add(0, i, i, label);
            }
            popup.setOnMenuItemClickListener(item -> {
                int fmt = item.getItemId();
                activeNoteFormat = fmt;
                updateNoteFormatIcon();
                // If cursor is on an empty line, insert prefix immediately
                EditText body = editViews[6];
                if (body == null || fmt == FORMAT_PARAGRAPH) return true;
                android.text.Editable e = body.getText();
                int cursor = body.getSelectionStart();
                if (cursor < 0) cursor = e.length();
                int lineStart = cursor;
                while (lineStart > 0 && e.charAt(lineStart - 1) != '\n') lineStart--;
                int lineEnd = cursor;
                while (lineEnd < e.length() && e.charAt(lineEnd) != '\n') lineEnd++;
                String currentLine = e.subSequence(lineStart, lineEnd).toString();
                if (!currentLine.trim().isEmpty()) return true;
                String prefix = "";
                switch (fmt) {
                    case FORMAT_BULLET:   prefix = "\u2022 "; break;
                    case FORMAT_NUMBERED:
                        int prevEnd   = lineStart > 0 ? lineStart - 1 : 0;
                        int prevStart = prevEnd;
                        while (prevStart > 0 && e.charAt(prevStart - 1) != '\n') prevStart--;
                        String prevLine = lineStart > 0 ? e.subSequence(prevStart, prevEnd).toString() : "";
                        java.util.regex.Matcher m =
                                java.util.regex.Pattern.compile("^(\\d+)\\.\\s").matcher(prevLine);
                        prefix = m.find() ? (Integer.parseInt(m.group(1)) + 1) + ". " : "1. ";
                        break;
                    case FORMAT_HEADING:  prefix = "## "; break;
                }
                if (!prefix.isEmpty()) {
                    e.replace(lineStart, lineEnd, prefix);
                    body.setSelection(lineStart + prefix.length());
                }
                return true;
            });
            popup.show();
        });

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
                // Existing record with no recordFields and no intent fields — nothing to load
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
                        // Serialize on main thread, write on background, finish() AFTER write
                        // so TypeListActivity.onResume() never reads stale data
                        final String json = storage.exportToJson(entries);
                        if (json != null) {
                            new Thread(() -> {
                                storage.saveEntriesJson(json);
                                storage.setBackupPending(true);
                                runOnUiThread(() -> finish());
                            }).start();
                        } else {
                            finish();
                        }
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
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
                if (viewTexts[6] != null) viewTexts[6].setText(renderNoteBodySpanned(existingEntry.getField7()));
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
                    // Card number: pre-format with spaces so TextWatcher starts clean
                    if (EntryType.CARD.equals(entryType) && i == 2) {
                        val = formatCardNumber(val);
                    }
                    if (editViews[i]  != null) editViews[i].setText(val);
                    if (viewTexts[i]  != null) setViewText(i, existingEntry.getFieldByIndex(i + 1));
                }
            }
        }

        // Dates — Added + Modified, right-aligned, below attachments (tvDateLabel in XML)
        // Must be bound BEFORE applyModeUI() so setText calls work
        tvCreatedAt = findViewById(R.id.tvDateLabel);

        // ── Apply initial mode UI ─────────────────────────────────────────────
        applyModeUI();
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
                String val = existingEntry.getFieldByIndex(i + 1);
                // Card number: pre-format with spaces before entering edit mode
                if (EntryType.CARD.equals(entryType) && i == 2) {
                    val = formatCardNumber(val);
                }
                editViews[i].setText(val);
            }
        }
        applyModeUI();
    }

    private void switchToViewMode() {
        isEditMode = false;
        saveActionBar.setVisibility(View.GONE); // ensure action bar hidden on save
        // Note format icon must be hidden immediately on any mode switch to view
        if (btnNoteFormat != null) btnNoteFormat.setVisibility(View.GONE);
        // Sync view texts from the freshly saved entry
        for (int i = 0; i < 7; i++) {
            if (viewTexts[i] != null && existingEntry != null) {
                setViewText(i, existingEntry.getFieldByIndex(i + 1));
            }
        }
        applyModeUI();
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
            // Card type label: keep current visibility (driven by TextWatcher)

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

    /** Sets the edit field to OPEN/visible for edit mode entry. */
    private void setRevealOpen(int i) {
        if (!secretFlags[i]) return;
        revealed[i] = true;
        if (editViews[i] != null) {
            // Numeric-only fields must stay on numeric keypad even when revealed
            // Card number and Expiry use TYPE_CLASS_PHONE to allow space/slash insertion by TextWatcher
            boolean isPhoneInput = EntryType.CARD.equals(entryType) && (i == 2 || i == 3);
            boolean isNumeric = (EntryType.CARD.equals(entryType) && (i == 4 || i == 5))
                    || (EntryType.BANK.equals(entryType) && i == 2)
                    || (EntryType.PIN.equals(entryType) && i == 1);
            editViews[i].setInputType(isPhoneInput
                    ? InputType.TYPE_CLASS_PHONE
                    : isNumeric
                            ? InputType.TYPE_CLASS_NUMBER
                            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
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
        tvBody.setTextIsSelectable(true); // Note body: native text selection in view mode
        LinearLayout.LayoutParams bodyVP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvBody.setLayoutParams(bodyVP);
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
        // ── Format TextWatcher: auto-prefix new lines based on active format ──
        etBody.addTextChangedListener(new android.text.TextWatcher() {
            private boolean applyingFmt = false;
            private int     insertStart = -1;
            private int     insertLen   = 0;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                insertStart = start;
                insertLen   = count;
            }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (applyingFmt) return;

                // ── Fallback to ¶ when user deletes the prefix on the current line ──
                // Skip this check when the change is an Enter key press — the new empty line
                // naturally has no prefix yet, which would falsely trigger the fallback.
                boolean isEnterKey = (insertLen == 1 && insertStart >= 0
                        && insertStart < e.length() && e.charAt(insertStart) == '\n');
                if (!isEnterKey && activeNoteFormat != FORMAT_PARAGRAPH) {
                    EditText body = editViews[6];
                    if (body != null) {
                        int cur = body.getSelectionStart();
                        if (cur < 0) cur = e.length();
                        int ls = cur;
                        while (ls > 0 && e.charAt(ls - 1) != '\n') ls--;
                        int le = cur;
                        while (le < e.length() && e.charAt(le) != '\n') le++;
                        String curLine = e.subSequence(ls, le).toString();
                        boolean prefixGone = false;
                        switch (activeNoteFormat) {
                            case FORMAT_BULLET:   prefixGone = !curLine.startsWith("• ");   break;
                            case FORMAT_NUMBERED: prefixGone = !curLine.matches("\\d+\\.\\s.*"); break;
                            case FORMAT_HEADING:  prefixGone = !curLine.startsWith("## ");  break;
                        }
                        if (prefixGone) {
                            activeNoteFormat = FORMAT_PARAGRAPH;
                            applyNoteFormatToolbarHighlight();
                        }
                    }
                }

                // Only act on single-char inserts that are a newline (Enter key)
                if (insertLen != 1 || insertStart < 0 || insertStart >= e.length()) return;
                if (e.charAt(insertStart) != '\n') return;

                int newLinePos         = insertStart;    // position of '\n' just inserted
                int cursorAfterNewline = newLinePos + 1; // new empty line starts here

                // Find the previous line (ends just before the '\n')
                int prevLineEnd   = newLinePos;
                int prevLineStart = prevLineEnd;
                while (prevLineStart > 0 && e.charAt(prevLineStart - 1) != '\n') prevLineStart--;
                String prevLine = e.subSequence(prevLineStart, prevLineEnd).toString();

                // Smart escape: if previous line was only the auto-prefix (no actual content),
                // remove it and revert to paragraph — avoids getting stuck in format mode
                boolean prevIsBulletOnly   = (activeNoteFormat == FORMAT_BULLET)   && prevLine.equals("• ");
                boolean prevIsNumberedOnly = (activeNoteFormat == FORMAT_NUMBERED) && prevLine.matches("\\d+\\.\\s");
                boolean prevIsHeadingOnly  = (activeNoteFormat == FORMAT_HEADING)  && prevLine.equals("## ");
                if (prevIsBulletOnly || prevIsNumberedOnly || prevIsHeadingOnly) {
                    applyingFmt = true;
                    e.delete(prevLineStart, cursorAfterNewline); // remove empty prefix + '\n'
                    activeNoteFormat = FORMAT_PARAGRAPH;
                    applyNoteFormatToolbarHighlight();
                    applyingFmt = false;
                    return;
                }

                // Determine prefix for the new line
                String prefix = "";
                switch (activeNoteFormat) {
                    case FORMAT_BULLET:
                        prefix = "• ";
                        break;
                    case FORMAT_NUMBERED:
                        java.util.regex.Matcher m =
                                java.util.regex.Pattern.compile("^(\\d+)\\.\\s").matcher(prevLine);
                        prefix = m.find() ? (Integer.parseInt(m.group(1)) + 1) + ". " : "1. ";
                        break;
                    case FORMAT_HEADING:
                        // Heading is one-shot — do NOT prefix the new line, just revert to ¶
                        activeNoteFormat = FORMAT_PARAGRAPH;
                        applyNoteFormatToolbarHighlight();
                        break;
                    default:
                        break;
                }
                if (!prefix.isEmpty()) {
                    applyingFmt = true;
                    e.insert(cursorAfterNewline, prefix);
                    applyingFmt = false;
                }
            }
        });
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
            if (btnNoteFormat != null) { btnNoteFormat.setVisibility(View.VISIBLE); updateNoteFormatIcon(); }
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
            if (btnNoteFormat != null) btnNoteFormat.setVisibility(View.GONE);
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

    /** Updates the format icon in the top bar to reflect the currently active format. */
    private void updateNoteFormatIcon() {
        if (btnNoteFormat == null) return;
        btnNoteFormat.setImageResource(R.drawable.ic_format_text);
    }

    /** Delegates to updateNoteFormatIcon() — kept for TextWatcher call sites. */
    private void applyNoteFormatToolbarHighlight() {
        updateNoteFormatIcon();
    }

    /**
     * Renders note body text as a SpannableStringBuilder.
     * Lines starting with "## " are stripped of the prefix and displayed bold + 15% larger.
     */
    private android.text.SpannableStringBuilder renderNoteBodySpanned(String text) {
        if (text == null || text.isEmpty()) return new android.text.SpannableStringBuilder();
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line      = lines[i];
            int    lineStart = sb.length();
            if (line.startsWith("## ")) {
                String heading = line.substring(3);
                sb.append(heading);
                int lineEnd = sb.length();
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        lineStart, lineEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.RelativeSizeSpan(1.15f),
                        lineStart, lineEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(line);
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb;
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
        if (!(EntryType.CARD.equals(entryType) && index == 2)) {
            row.addView(tvView);
        }

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
        // Card number field (Card type, index 2): FrameLayout with inline card type label, visible in both modes
        if (EntryType.CARD.equals(entryType) && index == 2) {
            etEdit.setInputType(InputType.TYPE_CLASS_PHONE);
            etEdit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(23)});

            // FrameLayout wraps tvView + etEdit so label overlays inside the box
            android.widget.FrameLayout cardFrame = new android.widget.FrameLayout(this);
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cardFrame.setLayoutParams(frameParams);

            // tvView inside frame — full width, extra right padding for label
            android.widget.FrameLayout.LayoutParams tvFp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            tvView.setLayoutParams(tvFp);
            tvView.setPadding(dpToPx(12), dpToPx(12), dpToPx(52), dpToPx(12));
            cardFrame.addView(tvView);

            // etEdit inside frame — full width, extra right padding for label
            android.widget.FrameLayout.LayoutParams etFp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            etEdit.setLayoutParams(etFp);
            etEdit.setPadding(dpToPx(12), dpToPx(12), dpToPx(52), dpToPx(12));
            cardFrame.addView(etEdit);

            // Card type label — overlaid inside box at right edge
            TextView cardTypeLabel = new TextView(this);
            cardTypeLabel.setTextSize(11f);
            cardTypeLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            cardTypeLabel.setTextColor(0xFF9E9E9E);
            android.widget.FrameLayout.LayoutParams labelFp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL | Gravity.END);
            labelFp.rightMargin = dpToPx(10);
            cardTypeLabel.setLayoutParams(labelFp);
            cardTypeLabel.setVisibility(View.GONE);
            cardFrame.addView(cardTypeLabel);
            cardTypeLabelView = cardTypeLabel;

            row.addView(cardFrame);

            etEdit.addTextChangedListener(new android.text.TextWatcher() {
                private boolean formatting = false;
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    if (formatting) return;
                    formatting = true;
                    String formatted = formatCardNumber(e.toString());
                    e.replace(0, e.length(), formatted);
                    formatting = false;
                    // Update card type label
                    String digits = e.toString().replace(" ", "");
                    String cardType = detectCardType(digits);
                    if (cardType != null) {
                        cardTypeLabel.setText(cardType);
                        cardTypeLabel.setVisibility(View.VISIBLE);
                    } else {
                        cardTypeLabel.setVisibility(View.GONE);
                    }
                    // Adjust CVV max length based on card type (AMEX = 4, others = 3)
                    if (editViews[4] != null) {
                        int cvvMax = "AMEX".equals(cardType) ? 4 : 3;
                        editViews[4].setFilters(new android.text.InputFilter[]{
                                new android.text.InputFilter.LengthFilter(cvvMax)});
                    }
                }
            });
        }
        // Expiry field (Card type, index 3 = field4): phone input (allows / insertion), auto-insert / after MM.
        // Max 5 chars: MM/YY
        if (EntryType.CARD.equals(entryType) && index == 3) {
            etEdit.setInputType(InputType.TYPE_CLASS_PHONE);
            etEdit.setHint("MM/YY");
            etEdit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(5)});
            etEdit.addTextChangedListener(new android.text.TextWatcher() {
                private boolean formatting = false;
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    if (formatting) return;
                    formatting = true;
                    String digits = e.toString().replace("/", "");
                    String formatted = digits.length() > 2
                            ? digits.substring(0, 2) + "/" + digits.substring(2)
                            : digits;
                    e.replace(0, e.length(), formatted);
                    formatting = false;
                }
            });
        }
        // CVV (index 4): numeric keypad, max 3 digits. PIN (index 5): numeric keypad.
        if (EntryType.CARD.equals(entryType) && index == 4) {
            etEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            etEdit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
        }
        if (EntryType.CARD.equals(entryType) && index == 5) {
            etEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        }
        // Account Number (index 2) on Bank: numeric keypad only
        if (EntryType.BANK.equals(entryType) && index == 2) {
            etEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        }
        // PIN / Code (index 1) on PIN entry type: numeric keypad only
        if (EntryType.PIN.equals(entryType) && index == 1) {
            etEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        }
        etEdit.setVisibility(View.GONE);   // hidden until EDIT mode
        editViews[index] = etEdit;
        if (!(EntryType.CARD.equals(entryType) && index == 2)) {
            row.addView(etEdit);
        }

        // ── Secret toggle button (eye) ────────────────────────────────────────
        if (isSecret && !isNotes) {
            // Tap on view-mode box to reveal/hide secret value (no eye button)
            revealed[index] = false;
            tvView.setOnClickListener(v -> {
                if (isEditMode) return;
                revealed[index] = !revealed[index];
                String raw = existingEntry != null
                        ? existingEntry.getFieldByIndex(index + 1) : "";
                // Card number: always display formatted with spaces when revealed
                String display = (EntryType.CARD.equals(entryType) && index == 2)
                        ? formatCardNumber(raw) : raw;
                // Bank Account Number: show ●●● (N digits) when re-masked
                String masked = (EntryType.BANK.equals(entryType) && index == 2)
                        ? "●●● (" + raw.length() + " digits)" : maskText(raw);
                tvView.setText(revealed[index] ? display : masked);
                tvView.setTextColor(getResources().getColor(
                        raw.isEmpty() ? R.color.hint_color : R.color.input_text));
            });
        }

        // ── Long-press to copy (VIEW mode only) ──────────────────────────────
        tvView.setLongClickable(true);
        tvView.setOnLongClickListener(v -> {
            String text = existingEntry != null
                    ? existingEntry.getFieldByIndex(index + 1) : "";
            // Card number: strip spaces before copying so clipboard gets clean digits
            if (EntryType.CARD.equals(entryType) && index == 2) {
                text = text.replace(" ", "");
            }
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
        // Note body (Note type, index 6): render ## heading lines as bold spanned text
        if (EntryType.NOTE.equals(entryType) && index == 6 && !value.isEmpty()) {
            viewTexts[index].setText(renderNoteBodySpanned(value));
            viewTexts[index].setTextColor(getResources().getColor(R.color.text_primary));
            return;
        }
        // Card number (Card type, index 2): display formatted + update inline card type label
        if (EntryType.CARD.equals(entryType) && index == 2) {
            if (!value.isEmpty()) {
                String formatted = formatCardNumber(value);
                viewTexts[index].setText(secretFlags[index] ? maskText(formatted) : formatted);
                viewTexts[index].setTextColor(getResources().getColor(R.color.input_text));
            } else {
                viewTexts[index].setText("—");
                viewTexts[index].setTextColor(getResources().getColor(R.color.hint_color));
            }
            if (cardTypeLabelView != null) {
                String digits = value.replace(" ", "");
                String cardType = detectCardType(digits);
                if (cardType != null) {
                    cardTypeLabelView.setText(cardType);
                    cardTypeLabelView.setVisibility(View.VISIBLE);
                } else {
                    cardTypeLabelView.setVisibility(View.GONE);
                }
            }
            return;
        }
        // Expiry (Card type, index 3): show ⚠ Expired warning in view mode if past date
        if (EntryType.CARD.equals(entryType) && index == 3 && !value.isEmpty()) {
            boolean expired = false;
            if (value.matches("^(0[1-9]|1[0-2])/[0-9]{2}$")) {
                int month = Integer.parseInt(value.substring(0, 2));
                int year  = 2000 + Integer.parseInt(value.substring(3));
                java.util.Calendar now = java.util.Calendar.getInstance();
                int curYear  = now.get(java.util.Calendar.YEAR);
                int curMonth = now.get(java.util.Calendar.MONTH) + 1;
                expired = (year < curYear) || (year == curYear && month < curMonth);
            }
            if (expired) {
                viewTexts[index].setText(value + "  ⚠ Expired");
                viewTexts[index].setTextColor(0xFFFF6F00); // amber warning
            } else {
                viewTexts[index].setText(value);
                viewTexts[index].setTextColor(getResources().getColor(R.color.input_text));
            }
            return;
        }
        // Bank Account Number (index 2): show ●●● (N digits) when masked
        if (EntryType.BANK.equals(entryType) && index == 2 && !value.isEmpty() && secretFlags[index]) {
            viewTexts[index].setText("●●● (" + value.length() + " digits)");
            viewTexts[index].setTextColor(getResources().getColor(R.color.input_text));
            return;
        }
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
        return "●●●●●";
    }

    /**
     * Detects card network from first digits. Returns "VISA", "MASTERCARD", "AMEX",
     * "DISCOVER", "JCB", or null if unknown.
     */
    private String detectCardType(String digits) {
        if (digits.length() < 1) return null;
        if (digits.startsWith("4"))                          return "VISA";
        if (digits.length() >= 2) {
            int two = Integer.parseInt(digits.substring(0, 2));
            if (two >= 51 && two <= 55)                      return "MC";
            if (two == 34 || two == 37)                      return "AMEX";
            if (two == 35)                                   return "JCB";
            if (two == 65)                                   return "DISC";
        }
        if (digits.length() >= 4 && digits.startsWith("6011")) return "DISC";
        return null;
    }

    /**
     * Formats a card number string for display: strips spaces then inserts a space
     * after every 4 digits. E.g. "4532015112830366" → "4532 0151 1283 0366".
     * Non-digit characters other than spaces are preserved as-is.
     */
    private String formatCardNumber(String raw) {
        // Strip all existing spaces first
        String digits = raw.replace(" ", "");
        if (digits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return sb.toString();
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
            existingEntry = newEntry;
        }

        final String json = storage.exportToJson(entries);
        if (json != null) {
            storage.saveEntriesJson(json);
            storage.setBackupPending(true);
        }
        switchToViewMode();
    }

    private void showDeleteConfirm() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Entry")
                .setMessage("Delete \"" + existingEntry.getDisplayTitle() + "\"? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    entries.remove(existingEntry);
                    final String json = storage.exportToJson(entries);
                    if (json != null) {
                        new Thread(() -> {
                            storage.saveEntriesJson(json);
                            storage.setBackupPending(true);
                            runOnUiThread(() -> finish());
                        }).start();
                    } else {
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showShareDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Share Entry")
                .setMessage("This will share your data as plain text.\n\nAre you sure?")
                .setPositiveButton("Share", (d, w) -> shareEntry())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareEntry() {
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

        startActivity(Intent.createChooser(buildTextOnlyShareIntent(shareText), "Share via"));
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
        btn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        btn.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));
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
