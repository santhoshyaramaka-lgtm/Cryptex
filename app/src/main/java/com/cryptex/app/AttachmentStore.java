package com.cryptex.app;

import android.content.Context;
import android.os.StatFs;

import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/**
 * Manages encrypted attachment files in the app's private internal storage.
 *
 * Storage layout:
 *   getFilesDir()/attachments/{uuid}.enc
 *
 * Each file is encrypted with AES-256-GCM-HKDF-4KB via EncryptedFile
 * using a dedicated Android Keystore key (hardware-backed where available).
 * This key is separate from the EncryptedSharedPreferences master key.
 *
 * Write safety: each file is written directly to its final path. If the write
 * fails the partial file is deleted immediately — the AEAD tag guarantees any
 * corruption is detected on read.
 *
 * v24: Added as part of multiple-attachment support.
 */
public class AttachmentStore {

    private static final String DIR_NAME  = "attachments";
    private static final String KEY_ALIAS = "cryptex_attachment_key_v1";
    private static final String FILE_EXT  = ".enc";

    private final Context context;
    private final File    attachmentDir;

    public AttachmentStore(Context context) {
        this.context       = context.getApplicationContext();
        this.attachmentDir = new File(this.context.getFilesDir(), DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        this.attachmentDir.mkdirs();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Encrypts and saves raw bytes as an attachment file.
     *
     * Steps:
     *   1. Check disk space (needs 1× data.length free)
     *   2. Generate collision-safe UUID
     *   3. Write encrypted bytes directly to the final .enc path
     *
     * @param data     raw file bytes to encrypt and store
     * @param name     original filename shown to the user
     * @param mimeType MIME type e.g. "image/jpeg"
     * @return Attachment metadata on success
     * @throws AttachmentException with a user-safe message on any failure
     */
    public Attachment save(byte[] data, String name, String mimeType) throws AttachmentException {
        if (data == null || data.length == 0) {
            throw new AttachmentException("File data is empty.");
        }

        checkDiskSpace(data.length);

        String id        = generateUniqueId();
        File   finalFile = fileForId(id);

        // EncryptedFile stores its per-file keyset in SharedPreferences keyed by
        // the file path. If we wrote to a .tmp and renamed to .enc, the keyset
        // would be registered under .tmp and reading from .enc would fail.
        // We therefore write directly to the final path. On failure we delete
        // the partial file — the AEAD tag ensures any corruption is detected.
        try {
            MasterKey masterKey = buildMasterKey();

            EncryptedFile encryptedFile = new EncryptedFile.Builder(
                    context,
                    finalFile,
                    masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build();

            try (OutputStream os = encryptedFile.openFileOutput()) {
                os.write(data);
            }

            return new Attachment(id, name, mimeType, data.length);

        } catch (AttachmentException e) {
            throw e;
        } catch (Exception e) {
            // Clean up partial file so future saves can reuse the same path
            if (finalFile.exists()) //noinspection ResultOfMethodCallIgnored
                finalFile.delete();
            throw new AttachmentException(
                    "Could not save attachment. " + e.getMessage());
        }
    }

    /**
     * Decrypts and returns the raw bytes of an attachment file.
     *
     * @param attachmentId the UUID from Attachment.getId()
     * @return raw decrypted file bytes
     * @throws AttachmentException if the file does not exist or decryption fails
     */
    public byte[] read(String attachmentId) throws AttachmentException {
        File file = fileForId(attachmentId);
        if (!file.exists()) {
            throw new AttachmentException(
                    "Attachment file not found. It may have been removed.");
        }

        try {
            MasterKey masterKey = buildMasterKey();
            EncryptedFile encryptedFile = new EncryptedFile.Builder(
                    context,
                    file,
                    masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build();

            try (InputStream is = encryptedFile.openFileInput()) {
                return readAllBytes(is);
            }

        } catch (AttachmentException e) {
            throw e;
        } catch (Exception e) {
            throw new AttachmentException(
                    "Could not read attachment. " + e.getMessage());
        }
    }

    /**
     * Writes raw bytes encrypted under a caller-supplied ID (UUID from backup restore).
     * Used during import to restore attachment files at their original IDs so the
     * Attachment metadata stored in each Entry still points to the correct file.
     * Idempotent: overwrites any existing file with the same ID.
     *
     * @param attachmentId the UUID to use as the filename (must be non-null/non-empty)
     * @param data         raw decrypted file bytes to encrypt and store
     * @throws AttachmentException on any I/O or crypto failure
     */
    public void writeById(String attachmentId, byte[] data) throws AttachmentException {
        if (attachmentId == null || attachmentId.isEmpty()) {
            throw new AttachmentException("Invalid attachment ID.");
        }
        // Delete existing file first — EncryptedFile.Builder rejects an already-existing path
        File target = fileForId(attachmentId);
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }

        // Write directly to the final path — see save() for why we don't use a temp file.
        try {
            MasterKey masterKey = buildMasterKey();
            EncryptedFile encryptedFile = new EncryptedFile.Builder(
                    context,
                    target,
                    masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build();

            try (java.io.OutputStream os = encryptedFile.openFileOutput()) {
                os.write(data);
            }

        } catch (AttachmentException e) {
            throw e;
        } catch (Exception e) {
            if (target.exists()) //noinspection ResultOfMethodCallIgnored
                target.delete();
            throw new AttachmentException("Could not write attachment. " + e.getMessage());
        }
    }

    /**
     * Deletes the encrypted file for a single attachment ID.
     * Safe to call even if the file does not exist.
     */
    public void delete(String attachmentId) {
        if (attachmentId == null || attachmentId.isEmpty()) return;
        File file = fileForId(attachmentId);
        if (file.exists()) //noinspection ResultOfMethodCallIgnored
            file.delete();
    }

    /**
     * Deletes all encrypted files for the given list of attachments.
     * Called when an entry is deleted — ensures no orphaned files remain.
     * Continues even if individual deletes fail (best-effort cleanup).
     */
    public void deleteAll(List<Attachment> attachments) {
        if (attachments == null) return;
        for (Attachment a : attachments) {
            delete(a.getId());
        }
    }

    /**
     * Returns true if the encrypted file for this attachment ID exists on disk.
     * Used to detect missing files before attempting to open them.
     */
    public boolean exists(String attachmentId) {
        if (attachmentId == null || attachmentId.isEmpty()) return false;
        return fileForId(attachmentId).exists();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Builds or loads the dedicated Android Keystore key for attachment encryption. */
    private MasterKey buildMasterKey() throws Exception {
        return new MasterKey.Builder(context, KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
    }

    /** Returns the .enc file path for a given attachment UUID. */
    private File fileForId(String id) {
        return new File(attachmentDir, id + FILE_EXT);
    }

    /**
     * Generates a UUID guaranteed not to collide with an existing file.
     * Collision is statistically impossible; the loop is a safety guard only.
     */
    private String generateUniqueId() {
        String id;
        int attempts = 0;
        do {
            id = UUID.randomUUID().toString();
            attempts++;
            if (attempts > 10) break; // unreachable in practice
        } while (fileForId(id).exists());
        return id;
    }

    /**
     * Checks available disk space in the attachment directory.
     * Throws AttachmentException if less than SPACE_FACTOR × requiredBytes is free.
     * If StatFs itself fails (unusual), proceeds — write will fail naturally if out of space.
     */
    private void checkDiskSpace(long requiredBytes) throws AttachmentException {
        try {
            StatFs stat      = new StatFs(attachmentDir.getPath());
            long  available  = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            if (available < requiredBytes) {
                throw new AttachmentException(
                        "Not enough storage space to save this attachment.");
            }
        } catch (AttachmentException e) {
            throw e;
        } catch (Exception ignored) {
            // StatFs failed — proceed and let the write fail naturally if truly full
        }
    }

    /**
     * Reads all bytes from an InputStream.
     * Manual implementation for API 23+ compatibility
     * (InputStream.readAllBytes() requires API 33).
     */
    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /** Thrown for all attachment file operations. Message is safe to show to the user. */
    public static class AttachmentException extends Exception {
        public AttachmentException(String message) { super(message); }
    }
}
