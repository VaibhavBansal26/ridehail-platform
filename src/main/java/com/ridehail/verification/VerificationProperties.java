package com.ridehail.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rider verification: documents a rider must upload and an admin must approve before they can drive.
 *
 * @param required         whether riders must be approved to go online. A launch-time switch: it decides what a newly registered
 *                         rider starts as (unverified), and does not change riders who already exist.
 * @param maxDocumentBytes the largest document accepted
 * @param encryptionKey    base64 of 32 random bytes (AES-256). Documents are encrypted before they are stored, so a database dump or
 *                         a backup does not contain readable identity documents. Production has no default: losing this key loses
 *                         every document, so keep it in a secret store AND a separate offline copy.
 */
@ConfigurationProperties(prefix = "ridehail.verification")
public record VerificationProperties(boolean required, int maxDocumentBytes, String encryptionKey) {
    public VerificationProperties {
        if (maxDocumentBytes <= 0) maxDocumentBytes = 5 * 1024 * 1024;
        if (encryptionKey == null || encryptionKey.isBlank()) {
            // dev and tests only: the prod profile supplies the real one and refuses to start without it
            encryptionKey = "ZGV2LW9ubHktZG9jdW1lbnQta2V5LTMyLWJ5dGVzISE=";
        }
    }
}
