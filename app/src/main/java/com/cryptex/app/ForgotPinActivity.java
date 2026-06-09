package com.cryptex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class ForgotPinActivity extends BaseActivity {

    // The security questions (index matches what is stored in prefs).
    // Index 2 = custom — actual text stored separately in KEY_SECURITY_Q_CUSTOM.
    public static final String[] QUESTIONS = {
            "What is your planet?",
            "How big is the universe?",
            "Write my own question…"
    };
    public static final int CUSTOM_QUESTION_INDEX = 2;

    private StorageHelper storage;

    // Step 1 — answer security question
    private LinearLayout layoutQuestion;
    private TextView     tvSecurityQuestion;
    private EditText     etAnswer;
    private TextView     tvAnswerError;
    private Button       btnConfirmAnswer;

    // Step 2 — set new PIN
    private LinearLayout layoutNewPin;
    private TextView     tvNewPinLabel;
    private View[]       dots;

    private StringBuilder pinInput   = new StringBuilder();
    private String        tempPin    = null;
    private boolean       confirming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_forgot_pin);

        storage = new StorageHelper(this);

        // ── Toolbar back button ───────────────────────────────────────────────
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // ── Step 1 views ──────────────────────────────────────────────────────
        layoutQuestion     = findViewById(R.id.layoutQuestion);
        tvSecurityQuestion = findViewById(R.id.tvSecurityQuestion);
        etAnswer           = findViewById(R.id.etAnswer);
        tvAnswerError      = findViewById(R.id.tvAnswerError);
        btnConfirmAnswer   = findViewById(R.id.btnConfirmAnswer);

        // ── Step 2 views ──────────────────────────────────────────────────────
        layoutNewPin   = findViewById(R.id.layoutNewPin);
        tvNewPinLabel  = findViewById(R.id.tvNewPinLabel);
        dots = new View[]{
                findViewById(R.id.dot1),
                findViewById(R.id.dot2),
                findViewById(R.id.dot3),
                findViewById(R.id.dot4)
        };

        // ── Load and display the stored security question ─────────────────────
        int qIndex = storage.getSecurityQuestionIndex();
        if (qIndex == ForgotPinActivity.CUSTOM_QUESTION_INDEX) {
            // Custom question — show the user's own text
            String custom = storage.getCustomSecurityQuestionText();
            tvSecurityQuestion.setText(custom.isEmpty() ? "Custom question" : custom);
        } else if (qIndex >= 0 && qIndex < QUESTIONS.length) {
            tvSecurityQuestion.setText(QUESTIONS[qIndex]);
        } else {
            // No security question set — should not normally happen
            tvSecurityQuestion.setText("No security question set.");
            btnConfirmAnswer.setEnabled(false);
        }

        // ── Step 1: Confirm answer ────────────────────────────────────────────
        btnConfirmAnswer.setOnClickListener(v -> {
            String answer = etAnswer.getText().toString().trim();
            if (answer.isEmpty()) {
                showAnswerError("Please enter your answer.");
                return;
            }
            if (storage.checkSecurityAnswer(answer)) {
                tvAnswerError.setVisibility(View.GONE);
                showStep2();
            } else {
                showAnswerError("Wrong answer. Try again.");
                etAnswer.setText("");
            }
        });

        // ── Step 2: PIN pad ───────────────────────────────────────────────────
        setupPinPad();
    }

    // ── Step 2 setup ─────────────────────────────────────────────────────────

    private void showStep2() {
        layoutQuestion.setVisibility(View.GONE);
        layoutNewPin.setVisibility(View.VISIBLE);
        tvNewPinLabel.setText(getString(R.string.set_pin));
        pinInput.setLength(0);
        confirming = false;
        tempPin    = null;
        updateDots();
    }

    private void setupPinPad() {
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

        Button btnBack2 = findViewById(R.id.btnBack2);
        if (btnBack2 != null) {
            btnBack2.setOnClickListener(v -> onBackspacePressed());
        }
    }

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
                    i < pinInput.length()
                            ? R.drawable.pin_dot_filled
                            : R.drawable.pin_dot_empty
            );
        }
    }

    private void onPinComplete() {
        String pin = pinInput.toString();

        if (!confirming) {
            // First entry — ask to confirm
            tempPin    = pin;
            confirming = true;
            tvNewPinLabel.setText(getString(R.string.confirm_pin));
            resetInput();
        } else {
            // Confirmation entry
            if (pin.equals(tempPin)) {
                // Save new PIN, clear ALL lock/attempt state, reset auto-lock timer
                storage.savePin(pin);
                storage.clearFailedAttempts();
                storage.clearPinLocked();
                storage.setBackgroundTimestamp(0); // prevent auto-lock from firing on resume

                // Go directly to MainActivity — clear the entire back stack
                // (PinActivity + ForgotPinActivity both removed — no double PIN screen)
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            } else {
                tvNewPinLabel.setText(getString(R.string.pin_mismatch_reset));
                resetInput();
                // Restart from first entry after a short delay
                layoutNewPin.postDelayed(() -> {
                    confirming = false;
                    tempPin    = null;
                    tvNewPinLabel.setText(getString(R.string.set_pin));
                }, 1200);
            }
        }
    }

    private void resetInput() {
        pinInput.setLength(0);
        updateDots();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void showAnswerError(String msg) {
        tvAnswerError.setText(msg);
        tvAnswerError.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        // Allow going back only from Step 1 (security question)
        if (layoutNewPin.getVisibility() == View.VISIBLE) {
            // Go back to Step 1
            layoutNewPin.setVisibility(View.GONE);
            layoutQuestion.setVisibility(View.VISIBLE);
            etAnswer.setText("");
            tvAnswerError.setVisibility(View.GONE);
        } else {
            finish();
        }
    }
}
