package com.cryptex.app;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A user-defined entry category.
 *
 * Each custom category has:
 *   id     — stable UUID prefixed with "custom_"; used as Entry.type for all
 *             entries in this category; never changes after creation.
 *   name   — user-supplied display name (e.g. "Crypto Wallet")
 *   emoji  — single character shown on the home tile and detail header
 *   fields — ordered list of 1–7 CustomField definitions mapping to
 *             Entry.field1–field7 positionally
 *
 * v26: Custom categories feature.
 */
public class CustomCategory {

    private String            id;
    private String            name;
    private String            emoji;
    private List<CustomField> fields;
    private boolean           includeNotes; // v29: whether Notes field is shown for this category

    public CustomCategory(String id, String name, String emoji, List<CustomField> fields) {
        this(id, name, emoji, fields, true);
    }

    public CustomCategory(String id, String name, String emoji, List<CustomField> fields, boolean includeNotes) {
        this.id           = id     != null ? id     : "";
        this.name         = name   != null ? name   : "";
        this.emoji        = emoji  != null && !emoji.isEmpty() ? emoji : "📁";
        this.fields       = fields != null ? fields : new ArrayList<>();
        this.includeNotes = includeNotes;
    }

    /** Creates a new custom category with a unique prefixed ID. */
    public static CustomCategory create(String name, String emoji, List<CustomField> fields) {
        String id = EntryType.CUSTOM_PREFIX + UUID.randomUUID().toString().replace("-", "");
        return new CustomCategory(id, name, emoji, fields);
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String            getId()           { return id; }
    public String            getName()         { return name; }
    public String            getEmoji()        { return emoji; }
    public List<CustomField> getFields()       { return fields; }
    public boolean           isIncludeNotes()  { return includeNotes; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setName(String name)               { this.name   = name   != null ? name   : ""; }
    public void setEmoji(String emoji)             { this.emoji  = emoji  != null && !emoji.isEmpty() ? emoji : "📁"; }
    public void setFields(List<CustomField> fields){ this.fields = fields != null ? fields : new ArrayList<>(); }
    public void setIncludeNotes(boolean includeNotes) { this.includeNotes = includeNotes; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns field labels as a String[7], empty-string padded for unused slots.
     * Slot 6 (index 6) is always "Notes" — reserved, not user-defined.
     * Matches the shape expected by EntryType.getFieldLabels().
     */
    public String[] getFieldLabels() {
        String[] labels = new String[7];
        labels[0] = "Title"; // always implicit — field1 is always the entry title
        for (int i = 0; i < 5; i++) {
            labels[i + 1] = (i < fields.size()) ? fields.get(i).getLabel() : "";
        }
        labels[6] = includeNotes ? "Notes" : ""; // v29: only include Notes if opted in
        return labels;
    }

    /**
     * Returns secret flags as a boolean[7], false-padded for unused slots.
     * Slot 6 (Notes) is never secret.
     * Matches the shape expected by EntryType.getSecretFlags().
     */
    public boolean[] getSecretFlags() {
        boolean[] secrets = new boolean[7];
        secrets[0] = false; // Title is never secret
        for (int i = 0; i < 5; i++) {
            secrets[i + 1] = (i < fields.size()) && fields.get(i).isSecret();
        }
        secrets[6] = false; // Notes is never masked
        return secrets;
    }
}
