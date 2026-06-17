package com.cryptex.app;

/**
 * A single field definition inside a CustomCategory.
 *
 * Each field has:
 *   label  — the name shown to the user (e.g. "Seed Phrase", "Account URL")
 *   secret — whether the value is masked by default with an eye-toggle reveal
 *
 * Fields map positionally to Entry.field1–field7:
 *   fields.get(0) → field1, fields.get(1) → field2, etc.
 * Unused slots (beyond the user-defined count) are simply ignored.
 */
public class CustomField implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private String  label;
    private boolean secret;

    public CustomField(String label, boolean secret) {
        this.label  = label  != null ? label.trim() : "";
        this.secret = secret;
    }

    public String  getLabel()                     { return label; }
    public boolean isSecret()                     { return secret; }

    public void setLabel(String label)            { this.label  = label != null ? label.trim() : ""; }
    public void setSecret(boolean secret)         { this.secret = secret; }
}
