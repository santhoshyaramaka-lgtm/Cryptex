package com.cryptex.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet that lets the user pick a type filter for the home screen.
 * Fires onFilterPicked(type) — type is "All" or one of EntryType constants.
 * Shows a checkmark next to the currently active filter.
 */
public class FilterBottomSheet extends BottomSheetDialogFragment {

    public interface OnFilterPickedListener {
        void onFilterPicked(String type);
    }

    private OnFilterPickedListener listener;
    private String currentType = "All";

    public static FilterBottomSheet newInstance(String currentType) {
        FilterBottomSheet sheet = new FilterBottomSheet();
        sheet.currentType = currentType;
        return sheet;
    }

    public void setOnFilterPickedListener(OnFilterPickedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_filter, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Show checkmark on the currently active row
        showCheck(view, currentType);

        // Bind rows
        bindRow(view, R.id.rowAll,      "All");
        bindRow(view, R.id.rowWebsite,  EntryType.WEBSITE);
        bindRow(view, R.id.rowCard,     EntryType.CARD);
        bindRow(view, R.id.rowBank,     EntryType.BANK);
        bindRow(view, R.id.rowPersonal, EntryType.PERSONAL);
        bindRow(view, R.id.rowPin,      EntryType.PIN);
        bindRow(view, R.id.rowNote,     EntryType.NOTE);
    }

    private void bindRow(View root, int rowId, String type) {
        root.findViewById(rowId).setOnClickListener(v -> {
            if (listener != null) listener.onFilterPicked(type);
            dismiss();
        });
    }

    /** Makes the checkmark visible for the active type row. */
    private void showCheck(View view, String type) {
        int checkId;
        switch (type) {
            case EntryType.WEBSITE:  checkId = R.id.checkWebsite;  break;
            case EntryType.CARD:     checkId = R.id.checkCard;     break;
            case EntryType.BANK:     checkId = R.id.checkBank;     break;
            case EntryType.PERSONAL: checkId = R.id.checkPersonal; break;
            case EntryType.PIN:      checkId = R.id.checkPin;      break;
            case EntryType.NOTE:     checkId = R.id.checkNote;     break;
            default:                 checkId = R.id.checkAll;      break;
        }
        TextView check = view.findViewById(checkId);
        if (check != null) check.setVisibility(View.VISIBLE);
    }
}
