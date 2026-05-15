package com.cryptex.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet that lets the user pick one of the 6 entry types.
 * Fires onTypePicked(type) on the host activity / fragment.
 */
public class TypePickerBottomSheet extends BottomSheetDialogFragment {

    public interface OnTypePickedListener {
        void onTypePicked(String type);
    }

    private OnTypePickedListener listener;

    public static TypePickerBottomSheet newInstance() {
        return new TypePickerBottomSheet();
    }

    public void setOnTypePickedListener(OnTypePickedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_type_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindTile(view, R.id.tileWebsite,  EntryType.WEBSITE);
        bindTile(view, R.id.tileCard,     EntryType.CARD);
        bindTile(view, R.id.tileBank,     EntryType.BANK);
        bindTile(view, R.id.tilePersonal, EntryType.PERSONAL);
        bindTile(view, R.id.tilePin,      EntryType.PIN);
        bindTile(view, R.id.tileNote,     EntryType.NOTE);
    }

    private void bindTile(View root, int tileId, String type) {
        root.findViewById(tileId).setOnClickListener(v -> {
            if (listener != null) listener.onTypePicked(type);
            dismiss();
        });
    }
}
