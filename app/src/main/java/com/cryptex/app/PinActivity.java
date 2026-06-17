package com.cryptex.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.AnimationUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.Random;

public class PinActivity extends BaseActivity {

    private StorageHelper storage;
    private StringBuilder pinInput = new StringBuilder();
    private String tempPin = null;
    private boolean isSettingPin = false;
    private boolean isConfirmingPin = false;
    private boolean isLocked = false;

    private View[] dots;
    private LinearLayout layoutDots;
    private TextView tvAttempts;
    private TextView tvPinTitle;
    private Button btnForgotPin;
    private Button btnForgotPinLocked;
    private Button btnBiometric;
    private View layoutLocked;

    private final Handler handler = new Handler();

    private final int[] KEY_BTN_IDS = {
        R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
        R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9,
        R.id.btnBack
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_pin);

        storage = StorageHelper.getInstance(this);

        layoutDots         = findViewById(R.id.layoutDots);
        tvAttempts         = findViewById(R.id.tvAttempts);
        tvPinTitle         = findViewById(R.id.tvPinTitle);
        btnForgotPin       = findViewById(R.id.btnForgotPin);
        btnForgotPinLocked = findViewById(R.id.btnForgotPinLocked);
        layoutLocked       = findViewById(R.id.layoutLocked);

        // Rotating tagline — pick a random one each time lock screen opens
        TextView tvTagline = findViewById(R.id.tvTagline);
        if (tvTagline != null) {
            String[] taglines = getResources().getStringArray(R.array.pin_taglines);
            tvTagline.setText(taglines[new Random().nextInt(taglines.length)]);
        }

        dots = new View[]{
                findViewById(R.id.dot1),
                findViewById(R.id.dot2),
                findViewById(R.id.dot3),
                findViewById(R.id.dot4)
        };

        btnForgotPin.setOnClickListener(v -> launchForgotPin());
        btnForgotPinLocked.setOnClickListener(v -> launchForgotPin());

        // v17: biometric button — only shown in normal login mode if enabled
        btnBiometric = findViewById(R.id.btnBiometric);
        btnBiometric.setOnClickListener(v -> showBiometricPrompt());

        setupButtons();

        if (storage.isPinLocked()) {
            showLocked();
            return;
        }

        if (!storage.hasPin()) {
            isSettingPin = true;
        }

        updateTitle();
        updateAttempts();
    }

    // ── Keypad setup ──────────────────────────────────────────────────────────

    private void setupButtons() {
        int[] btnIds = {
                R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        };
        String[] digits = {"0","1","2","3","4","5","6","7","8","9"};

        for (int i = 0; i < btnIds.length; i++) {
            final String digit = digits[i];
            Button btn = findViewById(btnIds[i]);
            if (btn != null) btn.setOnClickListener(v -> onDigitPressed(digit));
        }

        Button btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            // Short press = backspace
            btnBack.setOnClickListener(v -> onBackspacePressed());
            // Long press = reveal Forgot PIN
            btnBack.setOnLongClickListener(v -> {
                btnForgotPin.setVisibility(View.VISIBLE);
                return true;
            });
        }
    }

    // ── Input handling ────────────────────────────────────────────────────────

    private void onDigitPressed(String digit) {
        if (pinInput.length() >= 4) return;
        pinInput.append(digit);
        updateDots();
        if (pinInput.length() == 4) onPinComplete();
    }

    private void onBackspacePressed() {
        if (pinInput.length() > 0) {
            pinInput.deleteCharAt(pinInput.length() - 1);
            updateDots();
        }
    }

    private void updateDots() {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackgroundResource(
                    i < pinInput.length() ? R.drawable.pin_dot_filled : R.drawable.pin_dot_empty
            );
        }
    }

    // ── PIN completion ────────────────────────────────────────────────────────

    private void onPinComplete() {
        if (isLocked) return;
        String pin = pinInput.toString();

        if (isSettingPin) {
            if (!isConfirmingPin) {
                tempPin = pin;
                isConfirmingPin = true;
                // Flash dots briefly to signal "now confirm"
                flashDotsConfirm();
                resetInput();
                updateTitle(); // → "Confirm PIN"
            } else {
                if (pin.equals(tempPin)) {
                    storage.savePin(pin);
                    goToMain();
                } else {
                    // Mismatch: shake + red dots, restart setup
                    shakeAndRed(() -> {
                        isConfirmingPin = false;
                        tempPin = null;
                        resetInput();
                    });
                }
            }
        } else {
            if (storage.checkPin(pin)) {
                storage.clearFailedAttempts();
                goToMain();
            } else {
                int attempts = storage.getFailedAttempts() + 1;
                storage.setFailedAttempts(attempts);
                if (attempts >= 3) {
                    shakeAndRed(() -> {
                        storage.setPinLocked(true);
                        showLocked();
                    });
                } else {
                    shakeAndRed(() -> {
                        updateAttempts();
                        resetInput();
                    });
                }
            }
        }
    }

    // ── Dot animations ────────────────────────────────────────────────────────

    /** Shake the dot row and briefly turn all dots red, then run callback. */
    private void shakeAndRed(Runnable after) {
        // Turn dots red
        for (View dot : dots) dot.setBackgroundResource(R.drawable.pin_dot_error);
        // Shake
        layoutDots.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pin_shake));
        // After 500 ms restore + run callback
        handler.postDelayed(() -> {
            for (View dot : dots) dot.setBackgroundResource(R.drawable.pin_dot_empty);
            if (after != null) after.run();
        }, 500);
    }

    /** Briefly fill all dots white then clear — signals "first entry accepted, now confirm". */
    private void flashDotsConfirm() {
        for (View dot : dots) dot.setBackgroundResource(R.drawable.pin_dot_filled);
        handler.postDelayed(() -> {
            for (View dot : dots) dot.setBackgroundResource(R.drawable.pin_dot_empty);
        }, 300);
    }

    // ── Attempts display ──────────────────────────────────────────────────────

    private void updateTitle() {
        if (tvPinTitle == null) return;
        if (isSettingPin) {
            tvPinTitle.setVisibility(View.VISIBLE);
            tvPinTitle.setText(isConfirmingPin
                    ? getString(R.string.confirm_pin)   // "Confirm PIN"
                    : getString(R.string.set_pin));     // "Set New PIN"
            // Hide biometric button during PIN setup
            if (btnBiometric != null) btnBiometric.setVisibility(View.GONE);
        } else {
            tvPinTitle.setVisibility(View.GONE);        // normal login — no title
            // Show biometric button only if enabled and hardware available
            if (btnBiometric != null) {
                boolean bioEnabled = storage.isBiometricEnabled();
                if (bioEnabled) {
                    BiometricManager bm = BiometricManager.from(this);
                    boolean canAuth = bm.canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_WEAK)
                            == BiometricManager.BIOMETRIC_SUCCESS;
                    btnBiometric.setVisibility(canAuth ? View.VISIBLE : View.GONE);
                    // Auto-trigger biometric prompt on first open
                    if (canAuth) handler.postDelayed(this::showBiometricPrompt, 300);
                } else {
                    btnBiometric.setVisibility(View.GONE);
                }
            }
        }
    }

    // ── v17: Biometric prompt ─────────────────────────────────────────────────

    private void showBiometricPrompt() {
        if (isSettingPin || isLocked) return;

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_prompt_title))
                .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("\u200B")
                .build();

        BiometricPrompt biometricPrompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        goToMain();
                    }
                    @Override
                    public void onAuthenticationError(int errorCode,
                            @NonNull CharSequence errString) {
                        // User tapped "Use PIN instead" or dismissed — do nothing, PIN stays active
                        super.onAuthenticationError(errorCode, errString);
                    }
                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        // Wrong biometric — Android shows its own retry UI, no action needed
                    }
                });

        biometricPrompt.authenticate(promptInfo);
    }

    private void updateAttempts() {
        int attempts = storage.getFailedAttempts();
        if (attempts > 0 && attempts < 3) {
            tvAttempts.setText(getString(R.string.pin_attempts_left, 3 - attempts));
            tvAttempts.setVisibility(View.VISIBLE);
        } else {
            tvAttempts.setVisibility(View.GONE);
        }
    }

    // ── Lock overlay ──────────────────────────────────────────────────────────

    private void showLocked() {
        isLocked = true;
        disableKeypad();
        layoutLocked.setVisibility(View.VISIBLE);
    }

    private void disableKeypad() {
        for (int id : KEY_BTN_IDS) {
            Button btn = findViewById(id);
            if (btn != null) {
                btn.setEnabled(false);
                btn.setAlpha(0.2f);
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void launchForgotPin() {
        startActivity(new Intent(this, ForgotPinActivity.class));
    }

    private void goToMain() {
        storage.setForcedLock(false);
        storage.setBackgroundTimestamp(0);

        boolean resumeOnSuccess = getIntent().getBooleanExtra("resume_on_success", false);
        if (resumeOnSuccess) {
            // Auto-lock scenario: PIN screen was pushed on top of the existing
            // back stack. Simply finishing here brings the user back to exactly
            // the screen they were on before the lock triggered.
            finish();
        } else {
            // First launch / PIN setup: no back stack exists — go to MainActivity.
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }

    private void resetInput() {
        pinInput.setLength(0);
        updateDots();
    }

    @Override
    public void onBackPressed() {
        finishAffinity();
    }
}
