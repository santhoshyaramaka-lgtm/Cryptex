package com.cryptex.app;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption / decryption for Cryptex backup files (.cxb).
 *
 * Binary file format:
 *   [4  bytes] Magic header: 'M','S','B','K'
 *   [1  byte]  Version (currently 1)
 *   [16 bytes] PBKDF2 salt (random per export)
 *   [12 bytes] GCM IV / nonce (random per export)
 *   [rest]     AES-256-GCM ciphertext + 16-byte auth tag
 *
 * Key derivation: PBKDF2WithHmacSHA256, 200 000 iterations, 256-bit key.
 *
 * Note: The magic header 'MSBK' is kept as-is for full backward
 * compatibility with existing backup files created before the rename.
 */
public class BackupCrypto {

    private static final byte[] MAGIC     = {'M', 'S', 'B', 'K'};
    private static final byte   VERSION   = 1;
    private static final int    SALT_LEN  = 16;
    private static final int    IV_LEN    = 12;
    private static final int    GCM_TAG_BITS   = 128;
    private static final int    PBKDF2_ITERS   = 200_000;
    private static final int    KEY_BITS       = 256;

    // ── Encrypt ──────────────────────────────────────────────────────────────

    /**
     * Encrypts a JSON string with the given password.
     * @return raw .cxb file bytes ready to write to disk
     */
    public static byte[] encrypt(String plaintext, String password) throws Exception {
        SecureRandom rng  = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] iv   = new byte[IV_LEN];
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(VERSION);
        out.write(salt);
        out.write(iv);
        out.write(ciphertext);
        return out.toByteArray();
    }

    // ── Decrypt ──────────────────────────────────────────────────────────────

    /**
     * Decrypts raw .cxb file bytes with the given password.
     * @return the decrypted JSON string
     * @throws WrongPasswordException if the auth tag fails (wrong password or corrupted file)
     * @throws InvalidFileException   if the magic header is not recognised
     */
    public static String decrypt(byte[] fileBytes, String password) throws Exception {
        // Minimum: 4 (magic) + 1 (ver) + 16 (salt) + 12 (iv) + 16 (tag) = 49
        if (fileBytes.length < 49) throw new InvalidFileException("File is too small to be a valid backup.");

        // Check magic header
        for (int i = 0; i < MAGIC.length; i++) {
            if (fileBytes[i] != MAGIC[i]) throw new InvalidFileException("Not a valid Cryptex backup file.");
        }

        int offset = MAGIC.length;
        byte version = fileBytes[offset++];
        if (version != VERSION) throw new InvalidFileException("Unsupported backup version: " + version);

        byte[] salt       = slice(fileBytes, offset, SALT_LEN); offset += SALT_LEN;
        byte[] iv         = slice(fileBytes, offset, IV_LEN);   offset += IV_LEN;
        byte[] ciphertext = slice(fileBytes, offset, fileBytes.length - offset);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

        try {
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, "UTF-8");
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException("Wrong password or corrupted backup file.");
        }
    }

    // ── Key Derivation ───────────────────────────────────────────────────────

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] slice(byte[] src, int offset, int length) {
        byte[] dest = new byte[length];
        System.arraycopy(src, offset, dest, 0, length);
        return dest;
    }

    // ── Custom Exceptions ────────────────────────────────────────────────────

    /** Thrown when the GCM auth tag does not match — wrong password or corrupted file. */
    public static class WrongPasswordException extends Exception {
        public WrongPasswordException(String msg) { super(msg); }
    }

    /** Thrown when the file header is not a recognised Cryptex backup. */
    public static class InvalidFileException extends Exception {
        public InvalidFileException(String msg) { super(msg); }
    }

    // ── v24: ZIP-format backup ─────────────────────────────────────────────────
    //
    // .cxb v2 file = standard ZIP containing:
    //   salt                    — 16 raw bytes (PBKDF2 salt, not encrypted)
    //   entries.enc             — IV(12) + AES-256-GCM ciphertext of entries JSON
    //
    // A single PBKDF2 key is derived and reused for all entries in the ZIP,
    // so the expensive derivation only runs once per export/import.
    // Detection: first 4 bytes == 'P','K',0x03,0x04  (standard ZIP magic).

    /** Returned by {@link #decryptZip} — holds the decrypted entries JSON. */
    public static class ZipContent {
        public final String json;
        public ZipContent(String json) {
            this.json = json;
        }
    }

    /**
     * Encrypts entries JSON into a ZIP-format .cxb backup file.
     * One PBKDF2 key is derived and shared across all entries to keep export fast.
     *
     * @param json      entries JSON string (from StorageHelper.exportToJson)
     * @param password  backup password chosen by the user
     * @return raw file bytes (standard ZIP — detectable by PK\x03\x04 magic)
     */
    public static byte[] encryptZip(String json, String password) throws Exception {
        SecureRandom rng  = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        rng.nextBytes(salt);
        SecretKey key = deriveKey(password, salt);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {

            // 1. Raw salt
            zos.putNextEntry(new java.util.zip.ZipEntry("salt"));
            zos.write(salt);
            zos.closeEntry();

            // 2. Entries JSON
            byte[] entriesEnc = encryptWithKey(json.getBytes("UTF-8"), key, rng);
            zos.putNextEntry(new java.util.zip.ZipEntry("entries.enc"));
            zos.write(entriesEnc);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    /**
     * Decrypts a ZIP-format .cxb v2 backup file.
     *
     * @throws WrongPasswordException if the GCM auth tag check fails for entries.enc
     * @throws InvalidFileException   if the ZIP is not a valid Cryptex v2 backup
     */
    public static ZipContent decryptZip(byte[] zipBytes, String password) throws Exception {
        // Pass 1: extract the salt so the key can be derived once
        byte[] salt = null;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("salt".equals(entry.getName())) {
                    salt = readZipEntry(zis);
                    break;
                }
                zis.closeEntry();
            }
        }
        if (salt == null || salt.length != SALT_LEN) {
            throw new InvalidFileException("Not a valid Cryptex v2 backup (missing salt entry).");
        }
        SecretKey key = deriveKey(password, salt);

        // Pass 2: decrypt entries
        String json = null;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if ("entries.enc".equals(name)) {
                    byte[] enc = readZipEntry(zis);
                    try {
                        json = new String(decryptWithKey(enc, key), "UTF-8");
                    } catch (AEADBadTagException e) {
                        throw new WrongPasswordException("Wrong password or corrupted backup file.");
                    }
                }
                zis.closeEntry();
            }
        }
        if (json == null) {
            throw new InvalidFileException("Not a valid Cryptex v2 backup (missing entries.enc).");
        }
        return new ZipContent(json);
    }

    /**
     * Fast check: returns true if the bytes are a Cryptex v2 ZIP backup.
     * Checks only the ZIP magic bytes (PK\x03\x04) — O(1), no decompression.
     */
    public static boolean isZipBackup(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    // ── v24: key-based encrypt/decrypt (shared key, no per-call PBKDF2) ───────

    /** Format: [IV(12)][AES-256-GCM ciphertext + 16-byte auth tag]. */
    private static byte[] encryptWithKey(byte[] plainBytes, SecretKey key,
                                          SecureRandom rng) throws Exception {
        byte[] iv = new byte[IV_LEN];
        rng.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plainBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(IV_LEN + ciphertext.length);
        out.write(iv);
        out.write(ciphertext);
        return out.toByteArray();
    }

    /** Decrypts a blob produced by {@link #encryptWithKey}. Throws AEADBadTagException on wrong key. */
    private static byte[] decryptWithKey(byte[] data, SecretKey key) throws Exception {
        if (data.length < IV_LEN + 16)
            throw new InvalidFileException("Encrypted ZIP entry is too small.");
        byte[] iv         = slice(data, 0, IV_LEN);
        byte[] ciphertext = slice(data, IV_LEN, data.length - IV_LEN);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    /** Reads all available bytes from a ZipInputStream entry without closing the stream. */
    private static byte[] readZipEntry(java.util.zip.ZipInputStream zis)
            throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = zis.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
