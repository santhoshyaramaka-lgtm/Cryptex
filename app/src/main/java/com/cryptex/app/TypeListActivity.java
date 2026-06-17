package com.cryptex.app;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Shows all entries for a specific type.
 * Extras required:
 *   "entry_type" — one of EntryType constants (e.g. EntryType.WEBSITE)
 */
public class TypeListActivity extends BaseActivity {

    private StorageHelper        storage;
    private AttachmentStore      attachmentStore;
    private List<Entry>          allEntries;
    private List<Entry>   filteredEntries;
    private EntryAdapter  adapter;

    private String  entryType;
    private String  searchQuery  = "";
    private int     sortMode     = 0;   // 0=Date newest, 1=Date oldest, 2=Name A→Z, 3=Name Z→A
    private boolean showArchived = false;

    // Sort mode constants
    private static final int SORT_DATE_NEW = 0;
    private static final int SORT_DATE_OLD = 1;
    private static final int SORT_NAME_AZ  = 2;
    private static final int SORT_NAME_ZA  = 3;

    private TextView             tvEntryCount;
    private TextView             tvEmpty;
    private LinearLayout         normalTitleRow;
    private LinearLayout         selectionTitleRow;
    private TextView             tvSelectionCount;
    private FloatingActionButton fab;
    private ImageButton          btnArchiveToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_type_list);

        entryType = getIntent().getStringExtra("entry_type");
        if (entryType == null) entryType = EntryType.WEBSITE;

        storage         = StorageHelper.getInstance(this);
        attachmentStore = new AttachmentStore(this);
        allEntries      = new ArrayList<>();
        filteredEntries = new ArrayList<>();

        // Load persisted sort mode for this type (default 0 = Date newest first)
        sortMode = storage.getSortMode(entryType);

        // ── Bind views ────────────────────────────────────────────────────────
        tvEntryCount      = findViewById(R.id.tvEntryCount);
        tvEmpty           = findViewById(R.id.tvEmpty);
        normalTitleRow    = findViewById(R.id.normalTitleRow);
        selectionTitleRow = findViewById(R.id.selectionTitleRow);
        tvSelectionCount  = findViewById(R.id.tvSelectionCount);
        fab               = findViewById(R.id.fab);
        btnArchiveToggle  = findViewById(R.id.btnArchiveToggle);

        // Top bar — emoji + type name
        ((TextView) findViewById(R.id.tvTypeEmoji)).setText(EntryType.getEmoji(entryType));
        ((TextView) findViewById(R.id.tvTypeName)).setText(EntryType.getDisplayName(entryType));

        // Back
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Sort — tap opens dialog with Date / Name options
        findViewById(R.id.btnSort).setOnClickListener(v -> showSortDialog());

        // Archive toggle — top bar button
        btnArchiveToggle.setOnClickListener(v -> {
            showArchived = !showArchived;
            applyFilter();
        });

        // Cancel selection
        findViewById(R.id.btnCancelSelection).setOnClickListener(v -> exitSelectionMode());

        // Share selected entries
        findViewById(R.id.btnShareSelection).setOnClickListener(v -> showShareSelectedDialog());

        // FAB — add new entry (hidden in archive view) / delete selected
        fab.setOnClickListener(v -> {
            if (adapter.isInSelectionMode()) {
                confirmDeleteSelected();
            } else {
                openNewEntry();
            }
        });

        // ── RecyclerView ──────────────────────────────────────────────────────
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null); // disable card flicker on every filter/sort/star update

        adapter = new EntryAdapter(filteredEntries, new EntryAdapter.OnItemClickListener() {
            @Override
            public void onClick(Entry entry) {
                openDetail(entry);
            }

            @Override
            public void onLongClick(Entry entry) {
                if (!adapter.isInSelectionMode()) {
                    adapter.enterSelectionMode(entry.getId());
                    enterSelectionUI();
                } else {
                    updateSelectionCount();
                    if (adapter.getSelectedCount() == 0) exitSelectionMode();
                }
            }
        }, storage, allEntries, () -> applyFilter()); // v8: pass storage + callback for favourite re-sort

        recyclerView.setAdapter(adapter);

        // ── Search ────────────────────────────────────────────────────────────
        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) {
                searchQuery = s.toString();
                applyFilter();
            }
        });

        // ── Back press ────────────────────────────────────────────────────────
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (adapter.isInSelectionMode()) {
                    exitSelectionMode();
                } else if (showArchived) {
                    showArchived = false;
                    applyFilter();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

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
        allEntries.clear();
        allEntries.addAll(storage.loadEntries());
        searchQuery = "";
        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.setText("");
        applyFilter();
    }

    // ── Filter & Sort ─────────────────────────────────────────────────────────

    private void applyFilter() {
        filteredEntries.clear();

        for (Entry e : allEntries) {
            // Only entries of this type
            if (!e.getType().equals(entryType)) continue;

            // v19: strict separation — archived view shows only archived, active view shows only active
            if (showArchived && !e.isArchived()) continue;
            if (!showArchived && e.isArchived()) continue;

            // Search across all 7 fields
            if (!searchQuery.isEmpty()) {
                boolean matched = false;
                String q = searchQuery.toLowerCase();
                for (int i = 1; i <= 7; i++) {
                    if (e.getFieldByIndex(i).toLowerCase().contains(q)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) continue;
            }

            filteredEntries.add(e);
        }

        // Single combined sort:
        // ★ Pinned → top, most recently starred first (pinnedAt descending) — never affected by sortMode
        // ☆ Regular → sorted by user's chosen sortMode
        Collections.sort(filteredEntries, (a, b) -> {
            // Step 1: pinned always above non-pinned
            if (a.isFavourite() != b.isFavourite())
                return a.isFavourite() ? -1 : 1;
            // Step 2: both pinned → most recently starred first
            if (a.isFavourite())
                return Long.compare(b.getPinnedAt(), a.getPinnedAt());
            // Step 3: both unpinned → apply sortMode
            switch (sortMode) {
                case SORT_DATE_OLD: return Long.compare(a.getUpdatedAt(), b.getUpdatedAt());
                case SORT_NAME_AZ:  return a.getDisplayTitle().compareToIgnoreCase(b.getDisplayTitle());
                case SORT_NAME_ZA:  return b.getDisplayTitle().compareToIgnoreCase(a.getDisplayTitle());
                default:            return Long.compare(b.getUpdatedAt(), a.getUpdatedAt()); // SORT_DATE_NEW
            }
        });

        // Entry count label
        int count = filteredEntries.size();
        String label = showArchived ? " archived" : " active";
        tvEntryCount.setText(count == 1 ? "1" + label + " entry" : count + label + " entries");

        // Add FAB hidden in archive view (can't add new entries there)
        fab.setVisibility(showArchived ? View.GONE : View.VISIBLE);

        // Archive button: hidden in archive view, grey if no archived entries, white if has some
        updateArchiveButtonState();

        adapter.setSearchQuery(searchQuery);
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    // ── Selection mode ────────────────────────────────────────────────────────

    private void enterSelectionUI() {
        normalTitleRow.setVisibility(View.GONE);
        selectionTitleRow.setVisibility(View.VISIBLE);
        fab.setImageResource(R.drawable.ic_delete);
        updateSelectionCount();
    }

    private void exitSelectionMode() {
        adapter.exitSelectionMode();
        normalTitleRow.setVisibility(View.VISIBLE);
        selectionTitleRow.setVisibility(View.GONE);
        fab.setImageResource(R.drawable.ic_add);
        fab.setVisibility(showArchived ? View.GONE : View.VISIBLE);
    }

    private void updateSelectionCount() {
        int count = adapter.getSelectedCount();
        tvSelectionCount.setText(count == 1 ? "1 selected" : count + " selected");
    }

    private void confirmDeleteSelected() {
        int count = adapter.getSelectedCount();
        if (count == 0) return;
        String msg = count == 1
                ? "Delete 1 entry? This cannot be undone."
                : "Delete " + count + " entries? This cannot be undone.";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Entries")
                .setMessage(msg)
                .setPositiveButton("Delete", (d, w) -> deleteSelected())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showShareSelectedDialog() {
        Set<String> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) return;

        // Collect selected entries in current list order
        List<Entry> selected = new ArrayList<>();
        for (Entry e : filteredEntries) {
            if (ids.contains(e.getId())) selected.add(e);
        }

        // Check if any entry has attachments
        boolean hasAnyAttachment = false;
        for (Entry e : selected) {
            if (!e.getAttachments().isEmpty()) { hasAnyAttachment = true; break; }
        }

        int count = selected.size();
        String countLabel = count == 1 ? "1 entry" : count + " entries";

        if (!hasAnyAttachment) {
            // No attachments — share text directly with a simple confirm
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Share Entries")
                    .setMessage("Share " + countLabel + " as plain text?")
                    .setPositiveButton("Share", (d, w) -> shareSelectedText(selected))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        // Has attachments — offer Text / Attachments only / Both via radio list
        final int[] choice = {0}; // 0 = Text, 1 = Attachments only, 2 = Both
        String[] options = {"Text", "Attachments only", "Both (text + attachments)"};
        final List<Entry> finalSelected = selected;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Share " + countLabel + " as")
                .setSingleChoiceItems(options, 0, (d, which) -> choice[0] = which)
                .setPositiveButton("Share", (d, w) -> {
                    if (choice[0] == 1) shareSelectedAttachments(finalSelected);
                    else if (choice[0] == 2) shareSelectedBoth(finalSelected);
                    else shareSelectedText(finalSelected);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Recursively deletes all contents of a directory (not the dir itself). */
    private static void clearDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) clearDir(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Shares text files + attachment files together, per entry in list order. */
    private void shareSelectedBoth(List<Entry> selected) {
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle("Preparing share...")
                .setView(pb)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "attachments/multi_share");
                clearDir(cacheDir); // clear stale files from previous share sessions
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();

                ArrayList<Uri> uris = new ArrayList<>();

                for (Entry e : selected) {
                    String safeId = e.getId() != null ? e.getId() : "entry";
                    File entryDir = new File(cacheDir, safeId);
                    //noinspection ResultOfMethodCallIgnored
                    entryDir.mkdirs();

                    // 1. Text file for this entry
                    String txtName = e.getDisplayTitle().replaceAll("[^a-zA-Z0-9_\\-]", "_");
                    if (txtName.isEmpty()) txtName = "entry";
                    File txtFile = new File(entryDir, txtName + ".txt");
                    try (FileOutputStream fos = new FileOutputStream(txtFile)) {
                        fos.write(buildEntryText(e).getBytes("UTF-8"));
                    }
                    uris.add(FileProvider.getUriForFile(TypeListActivity.this,
                            getPackageName() + ".fileprovider", txtFile));

                    // 2. Attachment files for this entry, in order
                    for (Attachment a : e.getAttachments()) {
                        try {
                            byte[] bytes = attachmentStore.read(a.getId());
                            File attFile = new File(entryDir, a.getName());
                            try (FileOutputStream fos = new FileOutputStream(attFile)) { fos.write(bytes); }
                            uris.add(FileProvider.getUriForFile(TypeListActivity.this,
                                    getPackageName() + ".fileprovider", attFile));
                        } catch (Exception ignored) { /* skip unreadable */ }
                    }
                }

                final Intent shareIntent;
                if (uris.size() == 1) {
                    shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("*/*");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    shareIntent.setClipData(ClipData.newRawUri("", uris.get(0)));
                } else {
                    shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    shareIntent.setType("*/*");
                    shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    ClipData clip = ClipData.newRawUri("", uris.get(0));
                    for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                    shareIntent.setClipData(clip);
                }
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(shareIntent, "Share via");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                runOnUiThread(() -> { progress.dismiss(); startActivity(chooser); });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(TypeListActivity.this,
                            "Could not prepare files.", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /** Builds formatted text for a single entry (used by both share methods). */
    private String buildEntryText(Entry e) {
        StringBuilder sb = new StringBuilder();
        if (EntryType.CHECKLIST.equals(e.getType())) {
            sb.append("\u2611\ufe0f  ").append(e.getDisplayTitle()).append("\n");
            sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
            java.util.List<ChecklistItem> items = e.getChecklistItems();
            for (ChecklistItem item : items) {
                if (!item.isChecked()) sb.append("\u2610 ").append(item.getText()).append("\n");
            }
            for (ChecklistItem item : items) {
                if (item.isChecked()) sb.append("\u2611 ").append(item.getText()).append("\n");
            }
            int total = items.size(), checked = 0;
            for (ChecklistItem item : items) { if (item.isChecked()) checked++; }
            sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
            sb.append(checked).append(" of ").append(total).append(" done");
        } else {
            String[] labels = EntryType.getFieldLabels(e.getType());
            sb.append(EntryType.getEmoji(e.getType()))
                    .append("  ").append(EntryType.getDisplayName(e.getType()))
                    .append("\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
            for (int i = 0; i < 7; i++) {
                if (labels[i].isEmpty()) continue;
                String val = e.getFieldByIndex(i + 1);
                if (!val.isEmpty()) sb.append(labels[i]).append(":  ").append(val).append("\n");
            }
            sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
        }
        return sb.toString();
    }

    /** Shares formatted text for all selected entries. */
    private void shareSelectedText(List<Entry> selected) {
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < selected.size(); i++) {
            if (i > 0) combined.append("\n\n");
            combined.append(buildEntryText(selected.get(i)));
        }
        combined.append("\nShared from Cryptex");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, combined.toString());
        startActivity(Intent.createChooser(intent, "Share via"));
    }

    /** Shares only the attachment files of all selected entries, in entry list order. */
    private void shareSelectedAttachments(List<Entry> selected) {
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle("Preparing share...")
                .setView(pb)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "attachments/multi_share");
                clearDir(cacheDir); // clear stale files from previous share sessions
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();

                ArrayList<Uri> uris = new ArrayList<>();

                for (Entry e : selected) {
                    if (e.getAttachments().isEmpty()) continue;
                    String safeId = e.getId() != null ? e.getId() : "entry";
                    File entryDir = new File(cacheDir, safeId);
                    //noinspection ResultOfMethodCallIgnored
                    entryDir.mkdirs();
                    for (Attachment a : e.getAttachments()) {
                        try {
                            byte[] bytes = attachmentStore.read(a.getId());
                            File attFile = new File(entryDir, a.getName());
                            try (FileOutputStream fos = new FileOutputStream(attFile)) { fos.write(bytes); }
                            uris.add(FileProvider.getUriForFile(TypeListActivity.this,
                                    getPackageName() + ".fileprovider", attFile));
                        } catch (Exception ignored) { /* skip unreadable */ }
                    }
                }

                if (uris.isEmpty()) {
                    runOnUiThread(() -> {
                        progress.dismiss();
                        Toast.makeText(TypeListActivity.this,
                                "No attachments could be read.", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                final Intent shareIntent;
                if (uris.size() == 1) {
                    shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("*/*");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    shareIntent.setClipData(ClipData.newRawUri("", uris.get(0)));
                } else {
                    shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    shareIntent.setType("*/*");
                    shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    ClipData clip = ClipData.newRawUri("", uris.get(0));
                    for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                    shareIntent.setClipData(clip);
                }
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(shareIntent, "Share via");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                runOnUiThread(() -> { progress.dismiss(); startActivity(chooser); });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(TypeListActivity.this,
                            "Could not read attachments.", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void deleteSelected() {
        Set<String> ids = adapter.getSelectedIds();
        // Collect attachment IDs for all selected entries before removing from list
        List<Attachment> attachmentsToDelete = new ArrayList<>();
        for (Entry e : allEntries) {
            if (ids.contains(e.getId())) attachmentsToDelete.addAll(e.getAttachments());
        }
        allEntries.removeIf(e -> ids.contains(e.getId()));
        filteredEntries.removeIf(e -> ids.contains(e.getId()));
        // Save and delete attachment files in background — bulk delete should feel instant
        final String json = storage.exportToJson(allEntries);
        final List<Attachment> toDelete = attachmentsToDelete;
        new Thread(() -> {
            if (json != null) {
                storage.saveEntriesJson(json); // save first — orphaned .enc files are harmless, broken references are not
                storage.setBackupPending(true);
            }
            attachmentStore.deleteAll(toDelete);
        }).start();
        exitSelectionMode();
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void openNewEntry() {
        if (EntryType.isPerRecordFields(entryType)) {
            // Custom category: show field manager first to define fields for this record
            // Template: fields from last saved record, or category's own fields if none exist
            List<CustomField> templateFields = new ArrayList<>();
            boolean templateNotes = true;
            // Find last saved record for this category to use as template
            Entry lastEntry = null;
            for (Entry e : allEntries) {
                if (e.getType().equals(entryType) && !e.isArchived()) lastEntry = e;
            }
            if (lastEntry != null && !lastEntry.getRecordFields().isEmpty()) {
                templateFields = lastEntry.getRecordFields();
                templateNotes  = lastEntry.isRecordIncludeNotes();
            } else if (EntryType.isCustom(entryType)) {
                // Custom category — fall back to category-level fields as template
                CustomCategory cat = EntryType.findCustom(entryType);
                if (cat != null) {
                    templateFields = cat.getFields();
                    templateNotes  = cat.isIncludeNotes();
                }
            }
            // OTHERS built-in — no category-level fields, templateFields stays empty (correct)
            final List<CustomField> finalTemplate = templateFields;
            final boolean finalNotes = templateNotes;
            FieldManagerDialog.show(this, "New Record Fields", finalTemplate, finalNotes,
                    (fields, includeNotes) -> {
                // Serialize fields to JSON to pass via Intent
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (CustomField f : fields) {
                        org.json.JSONObject fo = new org.json.JSONObject();
                        fo.put("label",  f.getLabel());
                        fo.put("secret", f.isSecret());
                        arr.put(fo);
                    }
                    Intent intent = new Intent(this, DetailActivity.class);
                    intent.putExtra("entry_type", entryType);
                    intent.putExtra("record_fields_json", arr.toString());
                    intent.putExtra("record_include_notes", includeNotes);
                    startActivity(intent);
                } catch (Exception e) {
                    // JSON build failed — fall back to plain new entry
                    Intent intent = new Intent(this, DetailActivity.class);
                    intent.putExtra("entry_type", entryType);
                    startActivity(intent);
                }
            });
        } else {
            // Built-in type: go straight to DetailActivity as before
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra("entry_type", entryType);
            startActivity(intent);
        }
    }

    private void openDetail(Entry entry) {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("entry_id", entry.getId());
        startActivity(intent);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Shows the sort dialog — 2 rows (Date / Name), each toggles direction on re-tap. */
    private void showSortDialog() {
        // Build labels dynamically reflecting current state
        boolean isDateActive = (sortMode == SORT_DATE_NEW || sortMode == SORT_DATE_OLD);
        boolean isNameActive = (sortMode == SORT_NAME_AZ  || sortMode == SORT_NAME_ZA);

        String dateLabel = "Date" + (isDateActive
                ? (sortMode == SORT_DATE_NEW ? "  ·  Newest first" : "  ·  Oldest first") : "");
        String nameLabel = "Name" + (isNameActive
                ? (sortMode == SORT_NAME_AZ  ? "  ·  A → Z"        : "  ·  Z → A")        : "");

        // ● active row, ○ inactive row
        String[] options = {
                (isDateActive ? "●  " : "○  ") + dateLabel,
                (isNameActive ? "●  " : "○  ") + nameLabel
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Sort by")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        // Date tapped
                        if (isDateActive) {
                            // Already on Date — flip direction
                            sortMode = (sortMode == SORT_DATE_NEW) ? SORT_DATE_OLD : SORT_DATE_NEW;
                        } else {
                            // Switch to Date — default newest first
                            sortMode = SORT_DATE_NEW;
                        }
                    } else {
                        // Name tapped
                        if (isNameActive) {
                            // Already on Name — flip direction
                            sortMode = (sortMode == SORT_NAME_AZ) ? SORT_NAME_ZA : SORT_NAME_AZ;
                        } else {
                            // Switch to Name — default A→Z
                            sortMode = SORT_NAME_AZ;
                        }
                    }
                    storage.setSortMode(entryType, sortMode);
                    applyFilter();
                })
                .show();
    }

    private void updateArchiveButtonState() {
        if (showArchived) {
            // Inside archive view — hide the button
            btnArchiveToggle.setVisibility(View.GONE);
            return;
        }
        btnArchiveToggle.setVisibility(View.VISIBLE);
        // Check if any archived entries exist for this type
        boolean hasArchived = false;
        for (Entry e : allEntries) {
            if (e.getType().equals(entryType) && e.isArchived()) {
                hasArchived = true;
                break;
            }
        }
        // Has archived → white/enabled; none → greyed out/disabled
        btnArchiveToggle.setEnabled(hasArchived);
        btnArchiveToggle.setAlpha(hasArchived ? 1.0f : 0.35f);
    }

    private void updateEmptyState() {
        boolean empty = filteredEntries.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            tvEmpty.setText(showArchived ? "No archived entries" : getString(R.string.no_entries));
        }
    }
}
