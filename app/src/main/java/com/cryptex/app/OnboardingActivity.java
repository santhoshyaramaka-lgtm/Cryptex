package com.cryptex.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ViewFlipper;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * OnboardingActivity — shown only on first install (or after app data clear).
 *
 * Steps:
 *   0 — User Agreement (scroll to bottom to unlock "I Agree")
 *   1 — How It Works (feature overview)
 *   2 — Security Question setup (mandatory, saved to StorageHelper)
 *   3 — Security Reminder (final screen before PIN setup)
 *
 * After step 3, PinActivity is launched for PIN setup.
 * On every subsequent launch, the activity redirects immediately to PinActivity.
 *
 * Existing users (those who already have a PIN set) are detected on the first
 * launch after upgrade and silently redirected — they never see onboarding.
 */
public class OnboardingActivity extends BaseActivity {

    private static final String PREFS_ONBOARDING  = "cryptex_onboarding";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";

    private ViewFlipper viewFlipper;
    private StorageHelper storage;

    // Step 0
    private ScrollView scrollAgree;
    private MaterialButton btnAgree;
    private TextView tvScrollHint;

    // Step 2
    private Spinner spinnerQuestion;
    private TextInputEditText etAnswer;
    private TextInputEditText etCustomQuestion;
    private TextInputLayout tilCustomQuestion;
    private TextView tvSqError;
    private int selectedQuestionIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);

        // ── Check if onboarding already done ─────────────────────────────────
        SharedPreferences onboardingPrefs = getSharedPreferences(
                PREFS_ONBOARDING, Context.MODE_PRIVATE);

        if (onboardingPrefs.getBoolean(KEY_ONBOARDING_DONE, false)) {
            goToPin();
            return;
        }

        // ── Existing user check (upgrading from pre-onboarding version) ───────
        // If a PIN already exists this is not a fresh install — skip onboarding.
        storage = StorageHelper.getInstance(this);
        if (storage.hasPin()) {
            markDoneAndGoToPin(onboardingPrefs);
            return;
        }

        setContentView(R.layout.activity_onboarding);

        viewFlipper = findViewById(R.id.viewFlipper);

        setupStep0();
        setupStep1();
        setupStep2();
        setupStep3();
    }

    // ── Step 0: Agreement ────────────────────────────────────────────────────

    private void setupStep0() {
        scrollAgree  = findViewById(R.id.scrollAgree);
        btnAgree     = findViewById(R.id.btnAgree);
        tvScrollHint = findViewById(R.id.tvScrollHint);

        btnAgree.setEnabled(false);
        btnAgree.setAlpha(0.5f);

        scrollAgree.getViewTreeObserver().addOnScrollChangedListener(() -> {
            View child = scrollAgree.getChildAt(0);
            if (child == null) return;
            int scrollRange = child.getHeight() - scrollAgree.getHeight();
            // Allow a 10px tolerance so the button unlocks just before the very last pixel
            if (scrollRange <= 0 || scrollAgree.getScrollY() >= scrollRange - 10) {
                btnAgree.setEnabled(true);
                btnAgree.setAlpha(1.0f);
                tvScrollHint.setVisibility(View.GONE);
            }
        });

        btnAgree.setOnClickListener(v -> viewFlipper.showNext());
    }

    // ── Step 1: How It Works ─────────────────────────────────────────────────

    private void setupStep1() {
        MaterialButton btnNext = findViewById(R.id.btnHowItNext);
        btnNext.setOnClickListener(v -> viewFlipper.showNext());
    }

    // ── Step 2: Security Question ────────────────────────────────────────────

    private void setupStep2() {
        spinnerQuestion   = findViewById(R.id.spinnerQuestion);
        etAnswer          = findViewById(R.id.etAnswer);
        etCustomQuestion  = findViewById(R.id.etCustomQuestion);
        tilCustomQuestion = findViewById(R.id.tilCustomQuestion);
        tvSqError         = findViewById(R.id.tvSqError);
        MaterialButton btnSave = findViewById(R.id.btnSqSave);

        // Build spinner with a prompt item at position 0
        String[] questions = ForgotPinActivity.QUESTIONS;
        String[] items = new String[questions.length + 1];
        items[0] = getString(R.string.onboarding_sq_pick_hint);
        System.arraycopy(questions, 0, items, 1, questions.length);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerQuestion.setAdapter(adapter);

        // Tint spinner text white; show/hide custom question field
        spinnerQuestion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedQuestionIndex = position; // 0 = prompt, 1-2 = preset, 3 = custom
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(0xFFFFFFFF);
                }
                // Show custom question input only when "Write my own question…" is selected
                boolean isCustom = (position - 1) == ForgotPinActivity.CUSTOM_QUESTION_INDEX;
                tilCustomQuestion.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnSave.setOnClickListener(v -> {
            tvSqError.setVisibility(View.GONE);

            // Validate: must pick a real question (not the prompt at index 0)
            if (selectedQuestionIndex == 0) {
                tvSqError.setText(R.string.onboarding_sq_empty_q);
                tvSqError.setVisibility(View.VISIBLE);
                return;
            }

            String answer = etAnswer.getText() != null
                    ? etAnswer.getText().toString().trim() : "";
            if (answer.isEmpty()) {
                tvSqError.setText(R.string.onboarding_sq_empty_a);
                tvSqError.setVisibility(View.VISIBLE);
                return;
            }

            // spinner offset by 1 (item 1 = question index 0)
            int qIndex = selectedQuestionIndex - 1;
            if (qIndex == ForgotPinActivity.CUSTOM_QUESTION_INDEX) {
                String customQ = etCustomQuestion.getText() != null
                        ? etCustomQuestion.getText().toString().trim() : "";
                if (customQ.isEmpty()) {
                    tvSqError.setText("Please enter your question text.");
                    tvSqError.setVisibility(View.VISIBLE);
                    return;
                }
                storage.setCustomSecurityQuestion(customQ, answer);
            } else {
                storage.setSecurityQuestion(qIndex, answer);
            }

            // Dismiss keyboard before flipping to the next screen
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etAnswer.getWindowToken(), 0);

            viewFlipper.showNext();
        });
    }

    // ── Step 3: Security Reminder ────────────────────────────────────────────

    private void setupStep3() {
        MaterialButton btnContinue = findViewById(R.id.btnReminderContinue);
        btnContinue.setOnClickListener(v -> {
            // Mark onboarding complete and launch PIN setup
            SharedPreferences onboardingPrefs = getSharedPreferences(
                    PREFS_ONBOARDING, Context.MODE_PRIVATE);
            onboardingPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply();
            goToPin();
        });
    }

    // ── Navigation helpers ───────────────────────────────────────────────────

    private void goToPin() {
        startActivity(new Intent(this, PinActivity.class));
        finish();
    }

    private void markDoneAndGoToPin(SharedPreferences onboardingPrefs) {
        onboardingPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply();
        goToPin();
    }

    // ── Back press ───────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        int displayed = viewFlipper.getDisplayedChild();
        if (displayed == 0) {
            // On first step: exit the app — user must agree to continue
            finishAffinity();
        } else {
            // Go to previous step
            viewFlipper.showPrevious();
        }
    }
}
