package com.cryptex.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {

    private StorageHelper    storage;
    private List<Entry>      allEntries;
    private List<Entry>      searchResults;
    private EntryAdapter     searchAdapter;

    private GridLayout       tileGrid;
    private ScrollView       tileGridLayout;
    private LinearLayout     searchResultsLayout;
    private TextView         tvSearchCount;
    private RecyclerView     rvSearchResults;
    private EditText         etSearch;
    private boolean          searchKeyboardDismissed = false; // tracks if we already dismissed keyboard

    // Cache the last-rendered tile counts so we only rebuild tiles when
    // something actually changed — avoids unnecessary XML inflation on every resume
    private java.util.Map<String, Integer> lastTileCounts = null;
    private java.util.Set<String>          hiddenTypes    = new java.util.HashSet<>();

    private boolean isListMode = true; // false = grid, true = list (default)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        storage       = StorageHelper.getInstance(this);
        allEntries    = new ArrayList<>();
        searchResults = new ArrayList<>();

        showBackupTipIfNeeded();

        // ── Bind views ────────────────────────────────────────────────────────
        tileGrid            = findViewById(R.id.tileGrid);
        tileGridLayout      = findViewById(R.id.tileGridLayout);
        searchResultsLayout = findViewById(R.id.searchResultsLayout);
        tvSearchCount       = findViewById(R.id.tvSearchCount);
        rvSearchResults     = findViewById(R.id.rvSearchResults);
        etSearch            = findViewById(R.id.etSearch);

        // Settings
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // View toggle (grid ↔ list)
        isListMode = getPreferences(MODE_PRIVATE).getBoolean("pref_list_mode", true);
        ImageButton btnViewToggle = findViewById(R.id.btnViewToggle);
        btnViewToggle.setImageResource(isListMode ? R.drawable.ic_grid : R.drawable.ic_list);
        btnViewToggle.setOnClickListener(v -> {
            isListMode = !isListMode;
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean("pref_list_mode", isListMode).apply();
            btnViewToggle.setImageResource(isListMode ? R.drawable.ic_grid : R.drawable.ic_list);
            lastTileCounts = null; // force full rebuild
            buildTileGrid();
        });

        // ── Search adapter (used when searching across all types) ─────────────
        rvSearchResults.setLayoutManager(new LinearLayoutManager(this));
        rvSearchResults.setItemAnimator(null); // disable card flicker on search result updates
        searchAdapter = new EntryAdapter(searchResults, new EntryAdapter.OnItemClickListener() {
            @Override
            public void onClick(Entry entry) {
                openDetail(entry);
            }
            @Override
            public void onLongClick(Entry entry) {
                // Long press in search → open detail directly (no selection mode on home)
                openDetail(entry);
            }
        }, storage, allEntries, null); // v8: no favourite re-sort needed on home search
        rvSearchResults.setAdapter(searchAdapter);

        // ── Back press ────────────────────────────────────────────────────────
        // Case 1: search active + keyboard open + results found
        //         → 1st back: dismiss keyboard (flag set), results stay visible
        //         → 2nd back: clear search → tile grid
        // Case 2: search active + no results OR no keyboard
        //         → 1st back: clear search → tile grid immediately
        // Case 3: search empty → exit app
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                String query = etSearch.getText().toString();
                if (!query.isEmpty()) {
                    boolean hasResults = !searchResults.isEmpty();
                    if (hasResults && !searchKeyboardDismissed) {
                        // First back: hide keyboard, remember we did so
                        searchKeyboardDismissed = true;
                        etSearch.clearFocus();
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                    } else {
                        // Second back (or no results): clear search → tile grid
                        searchKeyboardDismissed = false;
                        etSearch.setText("");
                    }
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // ── Search watcher ────────────────────────────────────────────────────
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) {
                searchKeyboardDismissed = false; // user is typing again — reset flag
                String query = s.toString().trim();
                if (query.isEmpty()) {
                    showTileGrid();
                } else {
                    showSearchResults(query);
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
        // One-time migration: uppercase all existing entry titles
        storage.migrateTitlesToCaps();
        // Reload hidden types so tile grid and search respect latest settings
        hiddenTypes = storage.getHiddenTypes();
        // Reload entries so tile counts are fresh after add/edit/delete
        allEntries.clear();
        allEntries.addAll(storage.loadEntries());
        // Force tile rebuild — hidden set may have changed even if entry counts haven't
        lastTileCounts = null;
        buildTileGrid();
        // If search was active refresh results too
        String query = etSearch.getText().toString().trim();
        if (!query.isEmpty()) {
            showSearchResults(query);
        }
    }

    // ── Backup tip dialog ─────────────────────────────────────────────────────

    private static final String PREFS_TIPS     = "cryptex_tips";
    private static final String KEY_BACKUP_TIP = "backup_tip_shown";

    private void showBackupTipIfNeeded() {
        // Skip if already shown
        SharedPreferences tips = getSharedPreferences(PREFS_TIPS, Context.MODE_PRIVATE);
        if (tips.getBoolean(KEY_BACKUP_TIP, false)) return;

        // Skip if user already has both a backup configured AND a security question set
        if (storage.hasBackupPassword() && storage.hasBackupUri() && storage.getSecurityQuestionIndex() != -1) {
            tips.edit().putBoolean(KEY_BACKUP_TIP, true).apply();
            return;
        }

        tips.edit().putBoolean(KEY_BACKUP_TIP, true).apply();

        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.backup_tip_title)
                        .setMessage(R.string.backup_tip_message)
                        .setPositiveButton(R.string.backup_tip_setup, (d, w) ->
                                startActivity(new Intent(this, SettingsActivity.class)))
                        .setNegativeButton(R.string.backup_tip_later, null)
                        .setCancelable(false)
                        .show();
        // Override button colour — CryptexAlertDialog uses accent (#000000) which looks disabled
        int blue = 0xFF2196F3;
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(blue);
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(blue);
    }

    // ── Tile Grid ─────────────────────────────────────────────────────────────

    private void buildTileGrid() {
        // Pre-compute counts in a single O(n) pass across ALL types (built-in + custom)
        String[] allTypes = EntryType.ALL_TYPES;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String type : allTypes) counts.put(type, 0);
        for (Entry e : allEntries) {
            // v19: exclude archived entries from tile counts
            if (e.isArchived()) continue;
            String t = e.getType();
            if (counts.containsKey(t)) counts.put(t, counts.get(t) + 1);
        }

        // Skip full rebuild if counts are identical to last render
        if (counts.equals(lastTileCounts)) return;
        lastTileCounts = counts;

        tileGrid.removeAllViews();

        if (isListMode) {
            // ── LIST mode ────────────────────────────────────────────────────
            tileGrid.setColumnCount(1);
            for (String type : allTypes) {
                // Skip hidden types
                if (hiddenTypes.contains(type)) continue;
                int count = counts.getOrDefault(type, 0);
                View row = LayoutInflater.from(this)
                        .inflate(R.layout.item_type_list, tileGrid, false);
                ((TextView) row.findViewById(R.id.tvTileEmoji)).setText(EntryType.getEmoji(type));
                ((TextView) row.findViewById(R.id.tvTileName)).setText(EntryType.getDisplayName(type));
                ((TextView) row.findViewById(R.id.tvTileCount)).setText(
                        count == 1 ? "1 entry" : count + " entries");
                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width  = GridLayout.LayoutParams.MATCH_PARENT;
                params.height = GridLayout.LayoutParams.WRAP_CONTENT;
                row.setLayoutParams(params);
                final String rowType = type;
                row.setOnClickListener(v -> openTypeList(rowType));
                tileGrid.addView(row);
            }

        } else {
            // ── GRID mode (default) ──────────────────────────────────────────
            tileGrid.setColumnCount(2);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int tileWidth   = (screenWidth / 2);

            for (String type : allTypes) {
                // Skip hidden types
                if (hiddenTypes.contains(type)) continue;
                int count = counts.getOrDefault(type, 0);
                View tile = LayoutInflater.from(this)
                        .inflate(R.layout.item_type_tile, tileGrid, false);

                ((TextView) tile.findViewById(R.id.tvTileEmoji)).setText(EntryType.getEmoji(type));
                ((TextView) tile.findViewById(R.id.tvTileName)).setText(EntryType.getDisplayName(type));
                ((TextView) tile.findViewById(R.id.tvTileCount)).setText(
                        count == 1 ? "1 entry" : count + " entries");

                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width  = tileWidth;
                params.height = GridLayout.LayoutParams.WRAP_CONTENT;
                tile.setLayoutParams(params);

                final String tileType = type;
                tile.setOnClickListener(v -> openTypeList(tileType));
                tileGrid.addView(tile);
            }
        }
    }
    // ── Layout helpers ────────────────────────────────────────────────────────

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void showSearchResults(String query) {
        searchResults.clear();
        String q = query.toLowerCase();

        for (Entry e : allEntries) {
            // v19: exclude archived from global search
            if (e.isArchived()) continue;
            // Exclude entries from hidden categories
            if (hiddenTypes.contains(e.getType())) continue;
            boolean matched = false;
            for (int i = 1; i <= 7; i++) {
                if (e.getFieldByIndex(i).toLowerCase().contains(q)) {
                    matched = true;
                    break;
                }
            }
            if (matched) searchResults.add(e);
        }

        int count = searchResults.size();
        tvSearchCount.setText(count == 0
                ? "No results for \"" + query + "\""
                : count + " result" + (count == 1 ? "" : "s") + " for \"" + query + "\"");

        searchAdapter.setSearchQuery(query);
        searchAdapter.notifyDataSetChanged();

        // Switch visibility: hide tiles, show results
        tileGridLayout.setVisibility(View.GONE);
        searchResultsLayout.setVisibility(View.VISIBLE);
    }

    private void showTileGrid() {
        searchResults.clear();
        searchAdapter.setSearchQuery("");
        searchAdapter.notifyDataSetChanged();
        tileGridLayout.setVisibility(View.VISIBLE);
        searchResultsLayout.setVisibility(View.GONE);
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void openTypeList(String type) {
        Intent intent = new Intent(this, TypeListActivity.class);
        intent.putExtra("entry_type", type);
        startActivity(intent);
    }

    private void openDetail(Entry entry) {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("entry_id", entry.getId());
        startActivity(intent);
    }
}
