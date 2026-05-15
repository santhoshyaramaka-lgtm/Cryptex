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
 * AES-256-GCM encryption / decryption for Cryptex backup files (.msb).
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
     * @return raw .msb file bytes ready to write to disk
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
     * Decrypts raw .msb file bytes with the given password.
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
}
