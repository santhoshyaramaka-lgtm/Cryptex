package com.cryptex.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        storage       = new StorageHelper(this);
        allEntries    = new ArrayList<>();
        searchResults = new ArrayList<>();

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

        // ── Search adapter (used when searching across all types) ─────────────
        rvSearchResults.setLayoutManager(new LinearLayoutManager(this));
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

        // ── Search watcher ────────────────────────────────────────────────────
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) {
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
        // Reload entries so tile counts are fresh after add/edit/delete
        allEntries.clear();
        allEntries.addAll(storage.loadEntries());
        buildTileGrid();
        // If search was active refresh results too
        String query = etSearch.getText().toString().trim();
        if (!query.isEmpty()) {
            showSearchResults(query);
        }
    }

    // ── Tile Grid ─────────────────────────────────────────────────────────────

    private void buildTileGrid() {
        tileGrid.removeAllViews();

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int tileWidth   = (screenWidth / 2);   // 2 columns, full width split

        // Pre-compute counts in a single O(n) pass — avoids O(n×types) inner loops
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String type : EntryType.ALL_TYPES) counts.put(type, 0);
        for (Entry e : allEntries) {
            Integer c = counts.get(e.getType());
            if (c != null) counts.put(e.getType(), c + 1);
        }

        for (String type : EntryType.ALL_TYPES) {
            int count = counts.getOrDefault(type, 0);

            // Inflate tile
            View tile = LayoutInflater.from(this)
                    .inflate(R.layout.item_type_tile, tileGrid, false);

            ((TextView) tile.findViewById(R.id.tvTileEmoji)).setText(EntryType.getEmoji(type));
            ((TextView) tile.findViewById(R.id.tvTileName)).setText(EntryType.getDisplayName(type));
            ((TextView) tile.findViewById(R.id.tvTileCount)).setText(
                    count == 1 ? "1 entry" : count + " entries");

            // Set tile to exactly half screen width
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width  = tileWidth;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            tile.setLayoutParams(params);

            // Tap → open TypeListActivity
            final String tileType = type;
            tile.setOnClickListener(v -> openTypeList(tileType));

            tileGrid.addView(tile);
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void showSearchResults(String query) {
        searchResults.clear();
        String q = query.toLowerCase();

        for (Entry e : allEntries) {
            for (int i = 1; i <= 7; i++) {
                if (e.getFieldByIndex(i).toLowerCase().contains(q)) {
                    searchResults.add(e);
                    break;
                }
            }
        }

        int count = searchResults.size();
        tvSearchCount.setText(count == 0
                ? "No results for \"" + query + "\""
                : count + " result" + (count == 1 ? "" : "s") + " for \"" + query + "\"");

        searchAdapter.notifyDataSetChanged();

        // Switch visibility: hide tiles, show results
        tileGridLayout.setVisibility(View.GONE);
        searchResultsLayout.setVisibility(View.VISIBLE);
    }

    private void showTileGrid() {
        searchResults.clear();
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
