package com.cryptex.app;

/**
 * Metadata for a single attachment on an entry.
 *
 * The actual file bytes are stored separately as an encrypted file
 * in internal storage managed by AttachmentStore.
 * This object only holds the reference information stored in the entry JSON.
 *
 * v24: Added as part of multiple-attachment support.
 */
public class Attachment {

    private String id;        // UUID — matches the filename in AttachmentStore
    private String name;      // Original filename shown to the user
    private String mimeType;  // MIME type e.g. "image/jpeg", "application/pdf"
    private long   size;      // File size in bytes (pre-encryption original size)

    public Attachment(String id, String name, String mimeType, long size) {
        this.id       = id       != null ? id       : "";
        this.name     = name     != null ? name     : "";
        this.mimeType = mimeType != null ? mimeType : "";
        this.size     = size;
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String getId()       { return id; }
    public String getName()     { return name; }
    public String getMimeType() { return mimeType; }
    public long   getSize()     { return size; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setId(String id)             { this.id       = id != null ? id : ""; }
    public void setName(String name)         { this.name     = name != null ? name : ""; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType != null ? mimeType : ""; }
    public void setSize(long size)           { this.size     = size; }
}
