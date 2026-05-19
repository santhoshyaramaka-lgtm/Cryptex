package com.cryptex.app;

import java.util.UUID;

/**
 * A single item inside a Checklist entry.
 * v20: Checklist category
 */
public class ChecklistItem {

    private String  id;
    private String  text;
    private boolean checked;

    public ChecklistItem(String id, String text, boolean checked) {
        this.id      = id;
        this.text    = text;
        this.checked = checked;
    }

    /** Creates a new unchecked item with a fresh UUID. */
    public static ChecklistItem create(String text) {
        return new ChecklistItem(UUID.randomUUID().toString(), text, false);
    }

    public String  getId()                        { return id; }
    public String  getText()                      { return text; }
    public void    setText(String text)           { this.text = text; }
    public boolean isChecked()                    { return checked; }
    public void    setChecked(boolean checked)    { this.checked = checked; }
}
