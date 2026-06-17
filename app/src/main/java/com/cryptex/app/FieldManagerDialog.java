package com.cryptex.app;

import android.content.Context;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class that shows the field manager dialog (Step 2).
 * Reused by: MainActivity (category creation/edit), TypeListActivity (new record),
 * DetailActivity (manage fields icon).
 */
public class FieldManagerDialog {

    /** Callback delivered when the user confirms the dialog. */
    public interface Callback {
        void onConfirm(List<CustomField> fields, boolean includeNotes);
    }

    /**
     * Shows the field manager dialog.
     *
     * @param context             The calling Activity context
     * @param title               Dialog title text
     * @param initialFields       Starting field list — copied internally, original never mutated
     * @param initialIncludeNotes Starting state of the Notes toggle
     * @param callback            Delivered on confirm with final fields + includeNotes
     */
    public static void show(Context context, String title,
                            List<CustomField> initialFields, boolean initialIncludeNotes,
                            Callback callback) {

        // Working copy — never mutate the caller's list
        List<CustomField> workingFields = new ArrayList<>();
        for (CustomField f : initialFields)
            workingFields.add(new CustomField(f.getLabel(), f.isSecret()));

        final boolean[] includeNotesState = { initialIncludeNotes };

        // ── Dialog title bar: title text + Notes checkbox ─────────────────────
        LinearLayout customTitle = new LinearLayout(context);
        customTitle.setOrientation(LinearLayout.HORIZONTAL);
        customTitle.setGravity(Gravity.CENTER_VERTICAL);
        customTitle.setPadding(dp(context, 24), dp(context, 20), dp(context, 16), dp(context, 8));

        TextView tvDialogTitle = new TextView(context);
        tvDialogTitle.setText(title);
        tvDialogTitle.setTextSize(18f);
        tvDialogTitle.setTextColor(0xFFFFFFFF);
        tvDialogTitle.setTypeface(tvDialogTitle.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvDialogTitle.setLayoutParams(titleLp);
        customTitle.addView(tvDialogTitle);

        CheckBox cbIncludeNotes = new CheckBox(context);
        cbIncludeNotes.setText("Notes");
        cbIncludeNotes.setTextSize(13f);
        cbIncludeNotes.setChecked(includeNotesState[0]);
        cbIncludeNotes.setOnCheckedChangeListener((btn, checked) -> includeNotesState[0] = checked);
        customTitle.addView(cbIncludeNotes);

        // ── Scrollable field list ──────────────────────────────────────────────
        ScrollView step2Scroll = new ScrollView(context);
        LinearLayout step2 = new LinearLayout(context);
        step2.setOrientation(LinearLayout.VERTICAL);
        step2.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        step2Scroll.addView(step2);

        final java.util.function.IntConsumer[] appendFieldRow = {null};
        final Runnable[] renderFields = {null};

        renderFields[0] = () -> {
            step2.removeAllViews();
            for (int i = 0; i < workingFields.size(); i++) {
                appendFieldRow[0].accept(i);
            }
            if (workingFields.size() < 5) {
                step2.addView(buildAddFieldButton(context, step2, workingFields,
                        appendFieldRow, renderFields));
            }
        };

        appendFieldRow[0] = (i) -> {
            final int idx = i;
            CustomField field = workingFields.get(i);

            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setLayoutParams(marginParams(context, 0, 0, 0, dp(context, 10)));

            LinearLayout labelRow = new LinearLayout(context);
            labelRow.setOrientation(LinearLayout.HORIZONTAL);
            labelRow.setGravity(Gravity.CENTER_VERTICAL);
            labelRow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            ImageButton btnUp = new ImageButton(context);
            btnUp.setBackground(null);
            btnUp.setImageResource(R.drawable.ic_arrow_up);
            btnUp.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
            btnUp.setEnabled(i > 0);
            btnUp.setAlpha(i > 0 ? 1f : 0.3f);
            btnUp.setOnClickListener(v -> {
                if (idx > 0) {
                    CustomField tmp = workingFields.get(idx - 1);
                    workingFields.set(idx - 1, workingFields.get(idx));
                    workingFields.set(idx, tmp);
                    renderFields[0].run();
                }
            });
            labelRow.addView(btnUp);

            ImageButton btnDown = new ImageButton(context);
            btnDown.setBackground(null);
            btnDown.setImageResource(R.drawable.ic_arrow_down);
            btnDown.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
            btnDown.setEnabled(i < workingFields.size() - 1);
            btnDown.setAlpha(i < workingFields.size() - 1 ? 1f : 0.3f);
            btnDown.setOnClickListener(v -> {
                if (idx < workingFields.size() - 1) {
                    CustomField tmp = workingFields.get(idx + 1);
                    workingFields.set(idx + 1, workingFields.get(idx));
                    workingFields.set(idx, tmp);
                    renderFields[0].run();
                }
            });
            labelRow.addView(btnDown);

            EditText etLabel = new EditText(context);
            etLabel.setHint("Field " + (i + 1) + " name");
            etLabel.setSingleLine(true);
            etLabel.setText(field.getLabel());
            etLabel.setSelection(etLabel.getText().length());
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            etLabel.setLayoutParams(labelParams);
            etLabel.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(android.text.Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    workingFields.get(idx).setLabel(s.toString());
                }
            });
            labelRow.addView(etLabel);

            TextView btnMore = new TextView(context);
            btnMore.setText("⋮");
            btnMore.setTextSize(20f);
            btnMore.setTextColor(0xFFAAAAAA);
            btnMore.setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2));
            btnMore.setGravity(Gravity.CENTER);
            btnMore.setOnClickListener(v -> {
                android.widget.PopupMenu popup = new android.widget.PopupMenu(context, btnMore);
                android.view.Menu menu = popup.getMenu();
                android.view.MenuItem itemMask = menu.add(0, 1, 0, "Mask");
                itemMask.setCheckable(true);
                itemMask.setChecked(workingFields.get(idx).isSecret());
                menu.add(0, 2, 1, "Remove");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        workingFields.get(idx).setSecret(!workingFields.get(idx).isSecret());
                        return true;
                    } else if (item.getItemId() == 2) {
                        workingFields.remove(idx);
                        renderFields[0].run();
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
            labelRow.addView(btnMore);

            wrapper.addView(labelRow);
            step2.addView(wrapper);
        };

        // If no fields, pre-add one blank field so an EditText exists when the dialog is created
        if (workingFields.isEmpty()) {
            workingFields.add(new CustomField("", false));
        }
        renderFields[0].run();

        // ── Build and show the dialog ──────────────────────────────────────────
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setCustomTitle(customTitle)
                .setView(step2Scroll)
                .setPositiveButton("Save", null)
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

            // Focus first EditText and force keyboard open after window has focus
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                android.view.View firstWrapper = step2.getChildAt(0);
                if (firstWrapper instanceof LinearLayout) {
                    android.view.View labelRow = ((LinearLayout) firstWrapper).getChildAt(0);
                    if (labelRow instanceof LinearLayout) {
                        android.view.View et = ((LinearLayout) labelRow).getChildAt(2);
                        if (et instanceof EditText) {
                            et.requestFocus();
                            android.view.inputmethod.InputMethodManager imm =
                                    (android.view.inputmethod.InputMethodManager)
                                            context.getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) imm.showSoftInput(et,
                                    android.view.inputmethod.InputMethodManager.SHOW_FORCED);
                        }
                    }
                }
            }, 200);

            btnPos.setOnClickListener(v -> {
                workingFields.removeIf(f -> f.getLabel().trim().isEmpty());
                if (workingFields.isEmpty() && !includeNotesState[0]) {
                    Toast.makeText(context, "Add at least one field or enable Notes.",
                            Toast.LENGTH_SHORT).show();
                    renderFields[0].run();
                    return;
                }
                dialog.dismiss();
                callback.onConfirm(new ArrayList<>(workingFields), includeNotesState[0]);
            });

            btnNeg.setOnClickListener(v -> dialog.dismiss());
        });

        dialog.show();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    static TextView buildAddFieldButton(Context context, LinearLayout step2,
            List<CustomField> workingFields,
            java.util.function.IntConsumer[] appendFieldRow, Runnable[] renderFields) {
        TextView btnAdd = new TextView(context);
        btnAdd.setText("+ Add Field");
        btnAdd.setTextColor(0xFF2196F3);
        btnAdd.setTextSize(14);
        btnAdd.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 8));
        btnAdd.setOnClickListener(v -> {
            workingFields.add(new CustomField("", false));
            int newIdx = workingFields.size() - 1;
            step2.removeViewAt(step2.getChildCount() - 1);
            appendFieldRow[0].accept(newIdx);
            if (workingFields.size() < 5) {
                step2.addView(buildAddFieldButton(context, step2, workingFields,
                        appendFieldRow, renderFields));
            }
            step2.post(() -> {
                int lastWrapper = step2.getChildCount() - (workingFields.size() < 5 ? 2 : 1);
                if (lastWrapper >= 0) {
                    android.view.View wrapperView = step2.getChildAt(lastWrapper);
                    if (wrapperView instanceof LinearLayout) {
                        android.view.View labelRowView = ((LinearLayout) wrapperView).getChildAt(0);
                        if (labelRowView instanceof LinearLayout) {
                            android.view.View et = ((LinearLayout) labelRowView).getChildAt(2);
                            if (et instanceof EditText) {
                                et.requestFocus();
                                android.view.inputmethod.InputMethodManager imm =
                                        (android.view.inputmethod.InputMethodManager)
                                                context.getSystemService(Context.INPUT_METHOD_SERVICE);
                                if (imm != null) imm.showSoftInput(et,
                                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                            }
                        }
                    }
                }
            });
        });
        return btnAdd;
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams marginParams(Context context,
            int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }
}
