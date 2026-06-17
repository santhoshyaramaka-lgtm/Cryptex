package com.cryptex.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines all 7 built-in entry types, their field labels,
 * which fields are secret (masked), and which are multiline.
 *
 * v26: Added dynamic custom category support via init() + in-memory cache.
 * All existing switch-case methods are unchanged; custom IDs fall through
 * to the dynamic resolver in the default branch.
 */
public class EntryType {

    // ── Type Constants ────────────────────────────────────────────────────────
    public static final String WEBSITE   = "website";
    public static final String CARD      = "card";
    public static final String BANK      = "bank";
    public static final String PERSONAL  = "personal";
    public static final String PIN       = "pin";
    public static final String NOTE      = "note";
    public static final String CHECKLIST = "checklist"; // v20
    public static final String OTHERS    = "others";    // v29: built-in catch-all with per-record fields

    /** Prefix that identifies a custom category ID (vs a built-in type constant). */
    public static final String CUSTOM_PREFIX = "custom_";

    // ── Display Names ─────────────────────────────────────────────────────────
    public static String getDisplayName(String type) {
        switch (type) {
            case WEBSITE:  return "Website";
            case CARD:     return "Card";
            case BANK:     return "Bank Details";
            case PERSONAL: return "Personal Info";
            case PIN:      return "PIN / Code";
            case NOTE:     return "Note";
            case CHECKLIST: return "Checklist"; // v20
            case OTHERS:    return "Others";    // v29
            default:
                CustomCategory cc = findCustom(type);
                return cc != null ? cc.getName() : "Unknown";
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
            case CHECKLIST: return "☑️"; // v20
            case OTHERS:    return "📦"; // v29
            default:
                CustomCategory ccE = findCustom(type);
                return ccE != null ? ccE.getEmoji() : "📁";
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
            case CHECKLIST: // v20 — only title; items stored separately
                return new String[]{
                        "Title",            // field1
                        "",                 // field2–7 unused
                        "", "", "", "", ""
                };
            case OTHERS: // v29 — fields defined per-record; return empty as safe fallback
                return new String[]{"Title", "", "", "", "", "", ""};
            default:
                CustomCategory ccF = findCustom(type);
                return ccF != null ? ccF.getFieldLabels()
                        : new String[]{"Title", "", "", "", "", "", "Notes"};
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
            case CHECKLIST: // v20 — no secret fields
                return new boolean[]{false, false, false, false, false, false, false};
            case OTHERS: // v29 — secret flags defined per-record
                return new boolean[]{false, false, false, false, false, false, false};
            default:
                CustomCategory ccS = findCustom(type);
                return ccS != null ? ccS.getSecretFlags()
                        : new boolean[]{false, false, false, false, false, false, false};
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
            case CHECKLIST: { // v20
                int total   = e.getChecklistItems().size();
                int checked = 0;
                for (ChecklistItem item : e.getChecklistItems()) {
                    if (item.isChecked()) checked++;
                }
                if (total == 0) return "No items";
                return checked + " of " + total + " done";
            }
            case OTHERS: {
                // Show first non-empty field after title as subtitle
                String sub = e.getField2();
                if (!sub.isEmpty()) return sub;
                sub = e.getField3();
                if (!sub.isEmpty()) return sub;
                return "";
            }
            default:
                // Custom category — show first non-empty field after field1 as subtitle
                if (isCustom(type)) {
                    String sub = e.getField2();
                    if (!sub.isEmpty()) return sub;
                    sub = e.getField3();
                    if (!sub.isEmpty()) return sub;
                    return "";
                }
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
            WEBSITE, CARD, BANK, PERSONAL, PIN, OTHERS, NOTE, CHECKLIST
    };

    // ── v26: Dynamic custom category support ──────────────────────────────────

    /**
     * In-memory cache of custom categories loaded from StorageHelper.
     * Populated by init() on every MainActivity.onResume() — cheap list copy,
     * never touches disk. All resolver methods check this list in their
     * default branch after the built-in switch-case.
     */
    private static List<CustomCategory> customCategories = new ArrayList<>();

    /**
     * Call this from MainActivity.onResume() after loading custom categories
     * from StorageHelper.  Safe to call with null (treated as empty list).
     */
    public static void init(List<CustomCategory> customs) {
        customCategories = customs != null ? customs : new ArrayList<>();
    }

    /** Returns true if the type string belongs to a user-created custom category. */
    public static boolean isCustom(String type) {
        return type != null && type.startsWith(CUSTOM_PREFIX);
    }

    /**
     * Returns true if this type uses per-record field definitions.
     * Covers both the built-in Others category and all user-created custom categories.
     */
    public static boolean isPerRecordFields(String type) {
        return OTHERS.equals(type) || isCustom(type);
    }

    /** Looks up the CustomCategory for a given id, or null if not found. */
    public static CustomCategory findCustom(String id) {
        for (CustomCategory c : customCategories) {
            if (c.getId().equals(id)) return c;
        }
        return null;
    }

    /**
     * Returns all type IDs: the 7 built-in types followed by any custom
     * categories currently loaded.  Replaces direct use of ALL_TYPES[] where
     * a complete list is needed (home tiles, PDF picker, etc.).
     */
    public static String[] getAllTypes() {
        String[] result = new String[ALL_TYPES.length + customCategories.size()];
        System.arraycopy(ALL_TYPES, 0, result, 0, ALL_TYPES.length);
        for (int i = 0; i < customCategories.size(); i++) {
            result[ALL_TYPES.length + i] = customCategories.get(i).getId();
        }
        return result;
    }
}
