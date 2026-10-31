package com.ridehail.verification;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for identity documents. Layout: [1 byte key version][12 byte random nonce][ciphertext + 16 byte tag]. The document's
 * id is authenticated as well, so a stored blob cannot be swapped onto another document without detection. The version byte is
 * what makes rotating the key possible later without a flag day.
 */
@Component
class DocumentCipher {
    private static final byte VERSION = 1;
    private static final int NONCE = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    DocumentCipher(VerificationProperties props) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(props.encryptionKey().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ridehail.verification.encryption-key must be base64 (of 32 random bytes)");
        }
        if (raw.length != 32) throw new IllegalStateException("ridehail.verification.encryption-key must decode to exactly 32 bytes, not " + raw.length);
        this.key = new SecretKeySpec(raw, "AES");
    }

    byte[] encrypt(UUID documentId, byte[] plain) {
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            c.updateAAD(aad(documentId));
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(1 + NONCE + ct.length).put(VERSION).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a document", e);
        }
    }

    byte[] decrypt(UUID documentId, byte[] stored) {
        if (stored == null || stored.length < 1 + NONCE + 16 || stored[0] != VERSION) throw new IllegalStateException("Unreadable document");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, stored, 1, NONCE));
            c.updateAAD(aad(documentId));
            return c.doFinal(stored, 1 + NONCE, stored.length - 1 - NONCE);
        } catch (GeneralSecurityException e) {
            // wrong key, or the blob was altered or belongs to another document
            throw new IllegalStateException("A document could not be decrypted: wrong key or damaged data", e);
        }
    }

    private static byte[] aad(UUID id) {
        return id.toString().getBytes(StandardCharsets.UTF_8);
    }
}
