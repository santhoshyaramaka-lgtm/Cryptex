package com.cryptex.app;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onClick(Entry entry);
        void onLongClick(Entry entry);
    }

    private final List<Entry>          entries;
    private final OnItemClickListener  listener;
    private final StorageHelper        storage;
    private final List<Entry>          allEntries;   // full unfiltered list for save
    private final Runnable             onFavouriteChanged; // callback → triggers re-sort

    // Multi-select state
    private boolean selectionMode = false;
    private final Set<String> selectedIds = new HashSet<>();

    // Search highlight query — empty string means no highlight
    private String searchQuery = "";

    public EntryAdapter(List<Entry> entries, OnItemClickListener listener,
                        StorageHelper storage, List<Entry> allEntries,
                        Runnable onFavouriteChanged) {
        this.entries             = entries;
        this.listener            = listener;
        this.storage             = storage;
        this.allEntries          = allEntries;
        this.onFavouriteChanged  = onFavouriteChanged;
    }

    // ── Selection API ─────────────────────────────────────────────────────────

    public void enterSelectionMode(String firstId) {
        selectionMode = true;
        selectedIds.clear();
        selectedIds.add(firstId);
        notifyDataSetChanged();
    }

    public void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
    }

    public boolean isInSelectionMode()  { return selectionMode; }
    public int     getSelectedCount()   { return selectedIds.size(); }
    public Set<String> getSelectedIds() { return new HashSet<>(selectedIds); }

    private void toggleSelection(String id, int position) {
        if (selectedIds.contains(id)) selectedIds.remove(id);
        else selectedIds.add(id);
        notifyItemChanged(position);
    }

    // ── Search highlight API ──────────────────────────────────────────────────

    /** Set the query string whose occurrences should be highlighted in list items. */
    public void setSearchQuery(String query) {
        this.searchQuery = (query == null) ? "" : query.trim();
    }

    /**
     * Returns a SpannableString with every case-insensitive occurrence of
     * {@code query} highlighted using the app's search_highlight color.
     * Returns plain text if query is empty or not found.
     */
    private CharSequence highlight(android.content.Context ctx, String text, String query) {
        if (query.isEmpty() || text == null || text.isEmpty()) return text == null ? "" : text;
        SpannableString span = new SpannableString(text);
        String lowerText  = text.toLowerCase();
        String lowerQuery = query.toLowerCase();
        int bgColor   = ContextCompat.getColor(ctx, R.color.search_highlight);
        int textColor = ContextCompat.getColor(ctx, R.color.search_highlight_text);
        int start = 0;
        while ((start = lowerText.indexOf(lowerQuery, start)) != -1) {
            int end = start + lowerQuery.length();
            span.setSpan(new BackgroundColorSpan(bgColor),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(textColor),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end;
        }
        return span;
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_entry, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Entry entry      = entries.get(position);
        String type      = entry.getType();
        boolean isSelected = selectedIds.contains(entry.getId());

        // ── Type emoji icon ───────────────────────────────────────────────────
        holder.tvIcon.setText(EntryType.getEmoji(type));

        // ── Title (always field1) ─────────────────────────────────────────────
        holder.tvTitle.setText(highlight(holder.itemView.getContext(),
                entry.getDisplayTitle(), searchQuery));

        // ── Smart subtitle per type ───────────────────────────────────────────
        holder.tvSubtitle.setText(highlight(holder.itemView.getContext(),
                EntryType.getSubtitle(type, entry), searchQuery));

        // ── Timestamp ─────────────────────────────────────────────────────────
        long updatedAt = entry.getUpdatedAt();
        if (updatedAt > 0) {
            holder.tvTimestamp.setText(formatTimestamp(updatedAt));
            holder.tvTimestamp.setVisibility(View.VISIBLE);
        } else {
            holder.tvTimestamp.setVisibility(View.GONE);
        }

        // ── v9: Attachment indicator ──────────────────────────────────────────
        holder.tvAttachIndicator.setVisibility(
                entry.hasAttachment() ? View.VISIBLE : View.GONE);

        // ── v19: Archived badge ────────────────────────────────────────────────
        boolean archived = entry.isArchived();
        holder.tvArchivedBadge.setVisibility(archived ? View.VISIBLE : View.GONE);

        // ── Check / chevron visibility ────────────────────────────────────────
        if (selectionMode) {
            holder.ivStar.setVisibility(View.GONE);
            holder.ivCheck.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            holder.ivChevron.setVisibility(View.GONE);
        } else {
            // v19: hide star on archived entries — archived records cannot be pinned
            holder.ivStar.setVisibility(archived ? View.GONE : View.VISIBLE);
            holder.ivStar.setImageResource(
                    entry.isFavourite() ? R.drawable.ic_star_filled : R.drawable.ic_star_empty);
            holder.ivStar.setOnClickListener(v -> {
                boolean nowFav = !entry.isFavourite();
                entry.setFavourite(nowFav);
                // pinnedAt = now when starring, cleared when unstarring
                entry.setPinnedAt(nowFav ? System.currentTimeMillis() : 0);
                storage.saveEntries(allEntries);
                storage.setBackupPending(true);
                notifyItemChanged(holder.getAdapterPosition());
                if (onFavouriteChanged != null) onFavouriteChanged.run();
            });
            holder.ivCheck.setVisibility(View.GONE);
            holder.ivChevron.setVisibility(View.VISIBLE);
        }

        // ── Selected card highlight ───────────────────────────────────────────
        holder.card.setCardBackgroundColor(ContextCompat.getColor(
                holder.card.getContext(),
                isSelected ? R.color.selection_highlight : R.color.card_bg));

        // ── Click behaviour ───────────────────────────────────────────────────
        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(entry.getId(), holder.getAdapterPosition());
                listener.onLongClick(entry); // notify activity of count change
            } else {
                listener.onClick(entry);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) listener.onLongClick(entry);
            return true;
        });
    }

    @Override
    public int getItemCount() { return entries.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class ViewHolder extends RecyclerView.ViewHolder {
        CardView  card;
        TextView  tvTitle, tvSubtitle, tvIcon, tvTimestamp, tvAttachIndicator, tvArchivedBadge;
        ImageView ivStar, ivCheck, ivChevron;

        ViewHolder(View view) {
            super(view);
            card               = (CardView) view;
            tvTitle            = view.findViewById(R.id.tvTitle);
            tvSubtitle         = view.findViewById(R.id.tvSubtitle);
            tvIcon             = view.findViewById(R.id.tvIcon);
            tvTimestamp        = view.findViewById(R.id.tvTimestamp);
            tvAttachIndicator  = view.findViewById(R.id.tvAttachIndicator);
            tvArchivedBadge    = view.findViewById(R.id.tvArchivedBadge);
            ivStar             = view.findViewById(R.id.ivStar);
            ivCheck            = view.findViewById(R.id.ivCheck);
            ivChevron          = view.findViewById(R.id.ivChevron);
        }
    }

    // ── Timestamp formatter ───────────────────────────────────────────────────
    // Static cached formatters — created once, reused for every bind call.
    private static final SimpleDateFormat FMT_TIME     = new SimpleDateFormat("HH:mm",    Locale.getDefault());
    private static final SimpleDateFormat FMT_DAY_MON  = new SimpleDateFormat("d MMM",    Locale.getDefault());
    private static final SimpleDateFormat FMT_FULL     = new SimpleDateFormat("d MMM yy", Locale.getDefault());

    private String formatTimestamp(long millis) {
        Calendar now   = Calendar.getInstance();
        Calendar then  = Calendar.getInstance();
        then.setTimeInMillis(millis);

        boolean isToday   = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                         && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        boolean thisYear  = now.get(Calendar.YEAR) == then.get(Calendar.YEAR);

        Date date = new Date(millis);
        if (isToday) {
            return FMT_TIME.format(date);
        } else if (thisYear) {
            return FMT_DAY_MON.format(date);
        } else {
            return FMT_FULL.format(date);
        }
    }
}