package com.cryptex.app;

/**
 * Defines all 6 entry types, their field labels,
 * which fields are secret (masked), and which are multiline.
 *
 * Field slots: field1 – field7
 * field7 is always "Notes" (multiline, 5 lines) for every type.
 */
public class EntryType {

    // ── Type Constants ────────────────────────────────────────────────────────
    public static final String WEBSITE  = "website";
    public static final String CARD     = "card";
    public static final String BANK     = "bank";
    public static final String PERSONAL = "personal";
    public static final String PIN      = "pin";
    public static final String NOTE     = "note";

    // ── Display Names ─────────────────────────────────────────────────────────
    public static String getDisplayName(String type) {
        switch (type) {
            case WEBSITE:  return "Website / App";
            case CARD:     return "Card";
            case BANK:     return "Bank Details";
            case PERSONAL: return "Personal Info";
            case PIN:      return "PIN / Code";
            case NOTE:     return "Note";
            default:       return "Unknown";
        }
    }

    // ── Emoji Icons ───────────────────────────────────────────────────────────
    public static String getEmoji(String type) {
        switch (type) {
            case WEBSITE:  return "🌐";
            case CARD:     return "💳";
            case BANK:     return "🏦";
            case PERSONAL: return "👤";
            case PIN:      return "🔐";
            case NOTE:     return "📝";
            default:       return "📄";
        }
    }

    // ── Field Labels ──────────────────────────────────────────────────────────
    // Returns labels for field1–field7. Empty string = field not used for that type.
    public static String[] getFieldLabels(String type) {
        // Always 7 slots. Last slot (index 6) is always Notes.
        switch (type) {
            case WEBSITE:
                return new String[]{
                        "Title",        // field1
                        "URL",          // field2
                        "Username",     // field3
                        "Password",     // field4
                        "",             // field5 — unused
                        "",             // field6 — unused
                        "Notes"         // field7
                };
            case CARD:
                return new String[]{
                        "Card Name",        // field1
                        "Cardholder Name",  // field2
                        "Card Number",      // field3
                        "Expiry (MM/YY)",   // field4
                        "CVV",              // field5
                        "PIN",              // field6
                        "Notes"             // field7
                };
            case BANK:
                return new String[]{
                        "Bank Name",        // field1
                        "Account Holder",   // field2
                        "Account Number",   // field3
                        "IFSC Code",        // field4
                        "Branch",           // field5
                        "Customer ID",      // field6
                        "Notes"             // field7
                };
            case PERSONAL:
                return new String[]{
                        "Title",            // field1
                        "Full Name",        // field2
                        "ID Number",        // field3
                        "Date of Birth",    // field4
                        "",                 // field5 — unused
                        "",                 // field6 — unused
                        "Notes"             // field7
                };
            case PIN:
                return new String[]{
                        "Title",            // field1
                        "PIN / Code",       // field2
                        "",                 // field3 — unused
                        "",                 // field4 — unused
                        "",                 // field5 — unused
                        "",                 // field6 — unused
                        "Notes"             // field7
                };
            case NOTE:
                return new String[]{
                        "Title",            // field1
                        "",                 // field2 — unused
                        "",                 // field3 — unused
                        "",                 // field4 — unused
                        "",                 // field5 — unused
                        "",                 // field6 — unused
                        "Notes"             // field7
                };
            default:
                return new String[]{"Title", "", "", "", "", "", "Notes"};
        }
    }

    // ── Secret Fields ─────────────────────────────────────────────────────────
    // Returns which field indexes (0-based) should be masked/hidden by default.
    public static boolean[] getSecretFlags(String type) {
        // true = secret (masked), false = visible
        switch (type) {
            case WEBSITE:
                //          f1     f2     f3     f4      f5     f6     f7
                return new boolean[]{false, false, false, true,  false, false, false};
            case CARD:
                return new boolean[]{false, false, true,  false, true,  true,  false};
            case BANK:
                return new boolean[]{false, false, true,  false, false, true,  false};
            case PERSONAL:
                return new boolean[]{false, false, true,  false, false, false, false};
            case PIN:
                return new boolean[]{false, true,  false, false, false, false, false};
            case NOTE:
                return new boolean[]{false, false, false, false, false, false, false};
            default:
                return new boolean[]{false, false, false, false, false, false, false};
        }
    }

    // ── Subtitle for home screen card ─────────────────────────────────────────
    // Returns the best field value to show as subtitle on the entry card.
    public static String getSubtitle(String type, Entry e) {
        switch (type) {
            case WEBSITE:
                // Show username (field3)
                return e.getField3().isEmpty() ? e.getField2() : e.getField3();
            case CARD:
                // Show cardholder name (field2) instead of masked card number
                return e.getField2().isEmpty() ? "Card" : e.getField2();
            case BANK:
                // Show account holder (field2)
                return e.getField2().isEmpty() ? e.getField1() : e.getField2();
            case PERSONAL:
                // Show full name (field2)
                return e.getField2().isEmpty() ? e.getField1() : e.getField2();
            case PIN:
                // Don't reveal PIN — just show label
                return "PIN / Code";
            case NOTE:
                // Show first line of notes (field7)
                String notes = e.getField7();
                if (!notes.isEmpty()) {
                    String firstLine = notes.split("\n")[0];
                    return firstLine.length() > 40 ? firstLine.substring(0, 40) + "…" : firstLine;
                }
                return "Note";
            default:
                return "";
        }
    }

    // ── Legacy migration map ───────────────────────────────────────────────────
    // Maps old category names to the closest new type.
    public static String fromLegacyCategory(String category) {
        if (category == null) return WEBSITE;
        switch (category.toLowerCase()) {
            case "banking": return BANK;
            case "social":  return WEBSITE;
            case "work":    return WEBSITE;
            case "gaming":  return WEBSITE;
            case "general": return NOTE;
            default:        return WEBSITE;
        }
    }

    // ── All types (for tab/picker lists) ──────────────────────────────────────
    public static final String[] ALL_TYPES = {
            WEBSITE, CARD, BANK, PERSONAL, PIN, NOTE
    };
}
