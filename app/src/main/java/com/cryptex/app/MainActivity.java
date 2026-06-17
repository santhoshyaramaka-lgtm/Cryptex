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

    private boolean isListMode = false; // false = grid (default), true = list

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
        isListMode = getPreferences(MODE_PRIVATE).getBoolean("pref_list_mode", false);
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
        // v26: load custom categories into EntryType resolver
        EntryType.init(storage.loadCustomCategories());
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

        // Skip if user already has a backup configured (existing users upgrading)
        if (storage.hasBackupPassword() && storage.hasBackupUri()) {
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
        String[] allTypes = EntryType.getAllTypes();
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
            boolean listDividerAdded = false;
            for (String type : allTypes) {
                // Skip hidden types
                if (hiddenTypes.contains(type)) continue;
                // Insert divider before first custom category
                if (!listDividerAdded && EntryType.isCustom(type)) {
                    tileGrid.addView(makeSectionDivider(GridLayout.LayoutParams.MATCH_PARENT));
                    listDividerAdded = true;
                }
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
                if (EntryType.isCustom(type)) {
                    row.setOnLongClickListener(v -> { showCustomCategoryOptions(type); return true; });
                }
                tileGrid.addView(row);
            }
            // "+ New Category" list row
            View addRow = LayoutInflater.from(this)
                    .inflate(R.layout.item_type_list, tileGrid, false);
            ((TextView) addRow.findViewById(R.id.tvTileEmoji)).setText("➕");
            ((TextView) addRow.findViewById(R.id.tvTileName)).setText("New Category");
            ((TextView) addRow.findViewById(R.id.tvTileCount)).setText("");
            GridLayout.LayoutParams addRowParams = new GridLayout.LayoutParams();
            addRowParams.width  = GridLayout.LayoutParams.MATCH_PARENT;
            addRowParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
            addRow.setLayoutParams(addRowParams);
            addRow.setOnClickListener(v -> showCreateCategoryDialog());
            tileGrid.addView(addRow);

        } else {
            // ── GRID mode (default) ──────────────────────────────────────────
            tileGrid.setColumnCount(2);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int tileWidth   = (screenWidth / 2);

            boolean gridDividerAdded = false;
            for (String type : allTypes) {
                // Insert full-width divider before first custom category
                if (!gridDividerAdded && EntryType.isCustom(type)) {
                    View divider = makeSectionDivider(tileWidth * 2);
                    GridLayout.LayoutParams dp = new GridLayout.LayoutParams();
                    dp.width     = tileWidth * 2;
                    dp.height    = GridLayout.LayoutParams.WRAP_CONTENT;
                    dp.columnSpec = GridLayout.spec(0, 2);
                    divider.setLayoutParams(dp);
                    tileGrid.addView(divider);
                    gridDividerAdded = true;
                }
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

                if (EntryType.isCustom(type)) {
                    tile.setOnLongClickListener(v -> {
                        showCustomCategoryOptions(type);
                        return true;
                    });
                }
                tileGrid.addView(tile);
            }

            // "＋ New Category" tile (always last)
            View addTile = LayoutInflater.from(this)
                    .inflate(R.layout.item_type_tile, tileGrid, false);
            ((TextView) addTile.findViewById(R.id.tvTileEmoji)).setText("➕");
            ((TextView) addTile.findViewById(R.id.tvTileName)).setText("New Category");
            ((TextView) addTile.findViewById(R.id.tvTileCount)).setText("");
            GridLayout.LayoutParams addParams = new GridLayout.LayoutParams();
            addParams.width  = tileWidth;
            addParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
            addTile.setLayoutParams(addParams);
            addTile.setOnClickListener(v -> showCreateCategoryDialog());
            tileGrid.addView(addTile);

            // Spacer if total tile count is odd
            int totalTiles = allTypes.length + 1;
            if (totalTiles % 2 != 0) {
                View spacer = new View(this);
                GridLayout.LayoutParams sp = new GridLayout.LayoutParams();
                sp.width  = tileWidth;
                sp.height = 0;
                spacer.setLayoutParams(sp);
                tileGrid.addView(spacer);
            }
        }
    }

    private View makeSectionDivider(int widthPx) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                widthPx, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(14), dp(12), dp(6));
        container.setLayoutParams(lp);

        View line1 = new View(this);
        line1.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                dp(1), 1f);
        line1.setLayoutParams(lp1);

        TextView label = new TextView(this);
        label.setText("Your Categories");
        label.setTextColor(0xAAFFFFFF);
        label.setTextSize(11f);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        label.setAllCaps(true);
        LinearLayout.LayoutParams lpl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpl.setMargins(dp(10), 0, dp(10), 0);
        label.setLayoutParams(lpl);

        View line2 = new View(this);
        line2.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                dp(1), 1f);
        line2.setLayoutParams(lp2);

        container.addView(line1);
        container.addView(label);
        container.addView(line2);
        return container;
    }

    // ── v26: Custom category long-press options ───────────────────────────────

    private void showCustomCategoryOptions(String typeId) {
        CustomCategory cat = EntryType.findCustom(typeId);
        if (cat == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(cat.getEmoji() + "  " + cat.getName())
                .setItems(new String[]{"Edit", "Delete"}, (d, which) -> {
                    if (which == 0) showEditCategoryDialog(cat);
                    else            showDeleteCategoryConfirm(cat);
                })
                .show();
    }

    // ── v26: Create / Edit category dialog ───────────────────────────────────

    private void showCreateCategoryDialog() {
        showCategoryDialog(null);
    }

    private void showEditCategoryDialog(CustomCategory existing) {
        showCategoryDialog(existing);
    }

    /**
     * Name & Emoji dialog — saves the category directly.
     * Fields are defined per-record when creating a new entry inside the category.
     *
     * If {@code existing} is null a new category is created; otherwise the
     * existing one is updated in-place.
     */
    private void showCategoryDialog(CustomCategory existing) {
        boolean isEdit = (existing != null);

        // ── Step 1: Combined emoji + name input ───────────────────────────────
        LinearLayout step1 = new LinearLayout(this);
        step1.setOrientation(LinearLayout.VERTICAL);
        step1.setPadding(dp(16), dp(8), dp(16), dp(8));

        EditText etName = new EditText(this);
        etName.setHint("e.g. 🗃️ My Category");
        etName.setTextSize(16);
        etName.setSingleLine(true);
        etName.setLayoutParams(marginParams(0, 0, 0, dp(8)));
        if (isEdit) etName.setText(existing.getEmoji() + " " + existing.getName());
        step1.addView(etName);

        TextView tvStep1Error = new TextView(this);
        tvStep1Error.setTextColor(0xFFD32F2F);
        tvStep1Error.setVisibility(View.GONE);
        tvStep1Error.setTextSize(13);
        step1.addView(tvStep1Error);

        // ── Build the AlertDialog ─────────────────────────────────────────────
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isEdit ? "Edit Category" : "New Category")
                .setView(step1)
                .setPositiveButton(isEdit ? "Next" : "Next", null)
                .setNegativeButton("Cancel", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        dialog.setOnShowListener(di -> {
            android.widget.Button btnPos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            android.widget.Button btnNeg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            int blue = 0xFF2196F3;
            btnPos.setTextColor(blue);
            btnNeg.setTextColor(blue);

            btnPos.setOnClickListener(v -> {
                    // ── Validate Step 1 ───────────────────────────────────────
                    String raw   = etName.getText().toString().trim();
                    String[] parsedEmoji = { "🗃️" };
                    String[] parsedName  = { raw };
                    if (!raw.isEmpty()) {
                        int firstCp = raw.codePointAt(0);
                        int firstCharLen = Character.charCount(firstCp);
                        if (firstCp > 0xFFFF || (firstCp >= 0x2600 && firstCp <= 0x27BF)
                                || (firstCp >= 0x1F000 && firstCp <= 0x1FFFF)) {
                            parsedEmoji[0] = raw.substring(0, firstCharLen);
                            parsedName[0]  = raw.substring(firstCharLen).trim();
                        }
                    }
                    if (parsedName[0].isEmpty()) {
                        tvStep1Error.setText("Please enter a category name.");
                        tvStep1Error.setVisibility(View.VISIBLE);
                        return;
                    }
                    java.util.Set<String> reserved = new java.util.HashSet<>();
                    reserved.add("Website"); reserved.add("Card"); reserved.add("Bank Details");
                    reserved.add("Personal Info"); reserved.add("PIN / Code");
                    reserved.add("Note"); reserved.add("Checklist");
                    for (CustomCategory c : storage.loadCustomCategories()) {
                        if (isEdit && c.getId().equals(existing.getId())) continue;
                        reserved.add(c.getName().trim());
                    }
                    final String nameForCheck = parsedName[0];
                    if (reserved.stream().anyMatch(r -> r.equalsIgnoreCase(nameForCheck))) {
                        tvStep1Error.setText("A category with this name already exists.");
                        tvStep1Error.setVisibility(View.VISIBLE);
                        return;
                    }
                    tvStep1Error.setVisibility(View.GONE);
                    dialog.dismiss();

                    // Save category with name + emoji only (fields are defined per-record)
                    List<CustomCategory> categories = storage.loadCustomCategories();
                    if (isEdit) {
                        for (CustomCategory c : categories) {
                            if (c.getId().equals(existing.getId())) {
                                c.setName(parsedName[0]);
                                c.setEmoji(parsedEmoji[0]);
                                break;
                            }
                        }
                    } else {
                        CustomCategory newCat = CustomCategory.create(
                                parsedName[0], parsedEmoji[0], new ArrayList<>());
                        categories.add(newCat);
                    }
                    storage.saveCustomCategories(categories);
                    EntryType.init(categories);
                    lastTileCounts = null;
                    allEntries.clear();
                    allEntries.addAll(storage.loadEntries());
                    buildTileGrid();
            });

            btnNeg.setOnClickListener(v -> dialog.dismiss());
        });

        dialog.show();
    }

    // ── v26: Delete category confirmation ────────────────────────────────────

    private void showDeleteCategoryConfirm(CustomCategory cat) {
        // Count entries belonging to this category
        int entryCount = 0;
        for (Entry e : allEntries) {
            if (e.getType().equals(cat.getId())) entryCount++;
        }

        String msg = entryCount == 0
                ? "Delete \"" + cat.getName() + "\"? This cannot be undone."
                : "Delete \"" + cat.getName() + "\" and all " + entryCount
                  + (entryCount == 1 ? " entry" : " entries") + " inside? This cannot be undone.";

        AlertDialog confirm = new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Category")
                .setMessage(msg)
                .setPositiveButton("Delete", null)
                .setNegativeButton("Cancel", null)
                .create();

        confirm.setOnShowListener(di -> {
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFFD32F2F);
            confirm.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF2196F3);
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                // Remove all entries of this category, collecting attachments to delete
                List<Entry> updated = storage.loadEntries();
                List<Attachment> attachmentsToDelete = new ArrayList<>();
                for (Entry e : updated) {
                    if (e.getType().equals(cat.getId())) attachmentsToDelete.addAll(e.getAttachments());
                }
                updated.removeIf(e -> e.getType().equals(cat.getId()));
                storage.saveEntries(updated);
                storage.setBackupPending(true);

                // Delete attachment files in background — save JSON first so if killed mid-op
                // the entries are gone from storage before files are removed (orphaned files are
                // harmless; entries referencing missing files would cause open errors).
                if (!attachmentsToDelete.isEmpty()) {
                    final List<Attachment> toDelete = attachmentsToDelete;
                    new Thread(() -> new AttachmentStore(MainActivity.this).deleteAll(toDelete)).start();
                }

                // Remove category definition
                List<CustomCategory> categories = storage.loadCustomCategories();
                categories.removeIf(c -> c.getId().equals(cat.getId()));
                storage.saveCustomCategories(categories);
                EntryType.init(categories);

                // Force full tile rebuild
                lastTileCounts = null;
                allEntries.clear();
                allEntries.addAll(storage.loadEntries());
                buildTileGrid();
                confirm.dismiss();
            });
        });
        confirm.show();
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams marginParams(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
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
            // Also match on attachment filenames
            if (!matched) {
                for (Attachment a : e.getAttachments()) {
                    if (a.getName().toLowerCase().contains(q)) {
                        matched = true;
                        break;
                    }
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
