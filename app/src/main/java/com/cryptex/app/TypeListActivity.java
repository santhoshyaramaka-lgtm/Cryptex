package com.cryptex.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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

    private StorageHelper storage;
    private List<Entry>   allEntries;
    private List<Entry>   filteredEntries;
    private EntryAdapter  adapter;

    private String  entryType;
    private String  searchQuery  = "";
    private int     sortMode     = 0;   // 0=A→Z (default), 1=Z→A
    private boolean showArchived = false; // v19: hidden by default

    private TextView             tvEntryCount;
    private TextView             tvEmpty;
    private LinearLayout         normalTitleRow;
    private LinearLayout         selectionTitleRow;
    private TextView             tvSelectionCount;
    private FloatingActionButton fab;
    private FloatingActionButton fabArchive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_type_list);

        entryType = getIntent().getStringExtra("entry_type");
        if (entryType == null) entryType = EntryType.WEBSITE;

        storage         = new StorageHelper(this);
        allEntries      = new ArrayList<>();
        filteredEntries = new ArrayList<>();

        // ── Bind views ────────────────────────────────────────────────────────
        tvEntryCount      = findViewById(R.id.tvEntryCount);
        tvEmpty           = findViewById(R.id.tvEmpty);
        normalTitleRow    = findViewById(R.id.normalTitleRow);
        selectionTitleRow = findViewById(R.id.selectionTitleRow);
        tvSelectionCount  = findViewById(R.id.tvSelectionCount);
        fab               = findViewById(R.id.fab);
        fabArchive        = findViewById(R.id.fabArchive);

        // Top bar — emoji + type name
        ((TextView) findViewById(R.id.tvTypeEmoji)).setText(EntryType.getEmoji(entryType));
        ((TextView) findViewById(R.id.tvTypeName)).setText(EntryType.getDisplayName(entryType));

        // Back
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Sort — 2 modes: 0 = A→Z (default), 1 = Z→A
        findViewById(R.id.btnSort).setOnClickListener(v -> {
            sortMode = (sortMode + 1) % 2;
            String[] labels = {"A → Z", "Z → A"};
            Toast.makeText(this, "Sort: " + labels[sortMode], Toast.LENGTH_SHORT).show();
            applyFilter();
        });

        // Archive toggle FAB
        fabArchive.setOnClickListener(v -> {
            showArchived = !showArchived;
            updateArchiveFabState();
            applyFilter();
        });

        // Cancel selection
        findViewById(R.id.btnCancelSelection).setOnClickListener(v -> exitSelectionMode());

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
                    updateArchiveFabState();
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
        // ★ Pinned → top, most recently starred first (pinnedAt descending)
        // ☆ Regular → A→Z (sortMode 0) or Z→A (sortMode 1)
        Collections.sort(filteredEntries, (a, b) -> {
            // Step 1: pinned always above non-pinned
            if (a.isFavourite() != b.isFavourite())
                return a.isFavourite() ? -1 : 1;
            // Step 2: both pinned → most recently starred first
            if (a.isFavourite())
                return Long.compare(b.getPinnedAt(), a.getPinnedAt());
            // Step 3: both regular → A→Z or Z→A
            if (sortMode == 1)
                return b.getDisplayTitle().compareToIgnoreCase(a.getDisplayTitle());
            else
                return a.getDisplayTitle().compareToIgnoreCase(b.getDisplayTitle());
        });

        // Entry count
        int count = filteredEntries.size();
        String label = showArchived ? " archived" : " active";
        tvEntryCount.setText(count == 1 ? "1" + label + " entry" : count + label + " entries");

        // Hide both FABs in archive view
        fab.setVisibility(showArchived ? View.GONE : View.VISIBLE);
        fabArchive.setVisibility(showArchived ? View.GONE : View.VISIBLE);

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

    private void deleteSelected() {
        Set<String> ids = adapter.getSelectedIds();
        allEntries.removeIf(e -> ids.contains(e.getId()));
        filteredEntries.removeIf(e -> ids.contains(e.getId()));
        storage.saveEntries(allEntries);
        storage.setBackupPending(true);
        exitSelectionMode();
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void openNewEntry() {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("entry_type", entryType);
        startActivity(intent);
    }

    private void openDetail(Entry entry) {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("entry_id", entry.getId());
        startActivity(intent);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateArchiveFabState() {
        if (showArchived) {
            fabArchive.setAlpha(1.0f);
            fabArchive.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(this, R.color.btn_bg)));
            fabArchive.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(this, R.color.btn_text)));
        } else {
            fabArchive.setAlpha(0.45f);
            fabArchive.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(this, R.color.card_bg)));
            fabArchive.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)));
        }
    }

    private void updateEmptyState() {
        tvEmpty.setVisibility(filteredEntries.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
