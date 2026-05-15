package com.cryptex.app;

/**
 * Represents a single stored entry.
 *
 * Fields:
 *   type          — one of EntryType constants (website, card, bank, personal, pin, note)
 *   field1–field7 — generic slots; meaning depends on type (see EntryType.getFieldLabels)
 *                   field7 is always "Notes" (multiline) for every type
 */
public class Entry {

    private String id;
    private String type;
    private String field1;
    private String field2;
    private String field3;
    private String field4;
    private String field5;
    private String field6;
    private String field7;
    private long    updatedAt     = 0;
    private long    createdAt     = 0; // v12: set once on creation, never changed on edit
    private boolean isFavourite   = false;
    private long    pinnedAt      = 0;
    private String  attachmentName = ""; // v9: original filename (e.g. "passport.pdf")
    private String  attachmentData = ""; // v9: Base64 encoded file content (empty = no attachment)

    public Entry(String id, String type,
                 String field1, String field2, String field3,
                 String field4, String field5, String field6,
                 String field7) {
        this.id     = id;
        this.type   = type != null ? type : EntryType.WEBSITE;
        this.field1 = field1 != null ? field1 : "";
        this.field2 = field2 != null ? field2 : "";
        this.field3 = field3 != null ? field3 : "";
        this.field4 = field4 != null ? field4 : "";
        this.field5 = field5 != null ? field5 : "";
        this.field6 = field6 != null ? field6 : "";
        this.field7 = field7 != null ? field7 : "";
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String getId()     { return id; }
    public String getType()   { return type; }
    public String getField1() { return field1; }
    public String getField2() { return field2; }
    public String getField3() { return field3; }
    public String getField4() { return field4; }
    public String getField5() { return field5; }
    public String getField6() { return field6; }
    public String getField7() { return field7; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setType(String type)     { this.type   = type; }
    public void setField1(String field1) { this.field1 = field1; }
    public void setField2(String field2) { this.field2 = field2; }
    public void setField3(String field3) { this.field3 = field3; }
    public void setField4(String field4) { this.field4 = field4; }
    public void setField5(String field5) { this.field5 = field5; }
    public void setField6(String field6) { this.field6 = field6; }
    public void setField7(String field7) { this.field7 = field7; }

    // ── updatedAt ─────────────────────────────────────────────────────────────
    public long getUpdatedAt()            { return updatedAt; }
    public void setUpdatedAt(long millis) { this.updatedAt = millis; }

    // ── createdAt (v12) ───────────────────────────────────────────────────────
    public long getCreatedAt()            { return createdAt; }
    public void setCreatedAt(long millis) { this.createdAt = millis; }

    // ── isFavourite ───────────────────────────────────────────────────────────
    public boolean isFavourite()              { return isFavourite; }
    public void    setFavourite(boolean fav)  { this.isFavourite = fav; }

    // ── pinnedAt ──────────────────────────────────────────────────────────────
    public long getPinnedAt()             { return pinnedAt; }
    public void setPinnedAt(long millis)  { this.pinnedAt = millis; }

    // ── Attachment (v9) ───────────────────────────────────────────────────────
    public String getAttachmentName()              { return attachmentName; }
    public void   setAttachmentName(String name)   { this.attachmentName = name != null ? name : ""; }
    public String getAttachmentData()              { return attachmentData; }
    public void   setAttachmentData(String data)   { this.attachmentData = data != null ? data : ""; }
    public boolean hasAttachment()                 { return !attachmentName.isEmpty(); }

    // ── Convenience: get field by 1-based index ───────────────────────────────
    public String getFieldByIndex(int index) {
        switch (index) {
            case 1: return field1;
            case 2: return field2;
            case 3: return field3;
            case 4: return field4;
            case 5: return field5;
            case 6: return field6;
            case 7: return field7;
            default: return "";
        }
    }

    public void setFieldByIndex(int index, String value) {
        switch (index) {
            case 1: field1 = value; break;
            case 2: field2 = value; break;
            case 3: field3 = value; break;
            case 4: field4 = value; break;
            case 5: field5 = value; break;
            case 6: field6 = value; break;
            case 7: field7 = value; break;
        }
    }

    // ── Display title (always field1) ─────────────────────────────────────────
    public String getDisplayTitle() {
        return field1.isEmpty() ? "(No Title)" : field1;
    }
}
