package com.cryptex.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Manage Categories — lets the user show/hide any category tile on the home screen.
 * Hiding a category never deletes entries; it only suppresses the tile and search results.
 */
public class ManageCategoriesActivity extends BaseActivity {

    private StorageHelper storage;
    private Set<String>   hiddenTypes;
    private List<Entry>   allEntries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_categories);

        storage    = StorageHelper.getInstance(this);
        hiddenTypes = storage.getHiddenTypes();
        allEntries  = storage.loadEntries();

        EntryType.init(storage.loadCustomCategories());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rvCategories);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setAdapter(new CategoryVisibilityAdapter());
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class CategoryVisibilityAdapter
            extends RecyclerView.Adapter<CategoryVisibilityAdapter.VH> {

        private final String[] allTypes = EntryType.getAllTypes();

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_category_visibility, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            String type = allTypes[position];

            h.tvEmoji.setText(EntryType.getEmoji(type));
            h.tvName.setText(EntryType.getDisplayName(type));

            // Count non-archived entries for this type
            int count = 0;
            for (Entry e : allEntries) {
                if (!e.isArchived() && type.equals(e.getType())) count++;
            }
            h.tvCount.setText(count == 1 ? "1 entry" : count + " entries");

            // Temporarily detach listener before setting state to avoid recursive saves
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(!hiddenTypes.contains(type));
            h.sw.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    hiddenTypes.remove(type);
                } else {
                    hiddenTypes.add(type);
                }
                storage.setHiddenTypes(hiddenTypes);
            });
        }

        @Override
        public int getItemCount() { return allTypes.length; }

        class VH extends RecyclerView.ViewHolder {
            TextView tvEmoji, tvName, tvCount;
            Switch   sw;
            VH(View v) {
                super(v);
                tvEmoji = v.findViewById(R.id.tvCatEmoji);
                tvName  = v.findViewById(R.id.tvCatName);
                tvCount = v.findViewById(R.id.tvCatCount);
                sw      = v.findViewById(R.id.switchVisible);
            }
        }
    }
}
