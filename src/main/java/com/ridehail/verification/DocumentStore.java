package com.ridehail.verification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where document bytes live. Today: encrypted, in the database, so the API stays stateless across instances and the files are
 * covered by the same backups. If the volume ever warrants it, an object store (S3 or similar) can replace this one class: nothing
 * else touches the bytes. Callers hand over plain bytes; what is stored is always ciphertext.
 */
public interface DocumentStore {
    void put(UUID documentId, byte[] plain);

    Optional<byte[]> get(UUID documentId);

    void delete(UUID documentId);
}

@Component
class DatabaseDocumentStore implements DocumentStore {
    private final DocumentBlobRepository blobs;
    private final DocumentCipher cipher;

    DatabaseDocumentStore(DocumentBlobRepository blobs, DocumentCipher cipher) {
        this.blobs = blobs;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public void put(UUID documentId, byte[] plain) {
        byte[] sealed = cipher.encrypt(documentId, plain);
        blobs.findById(documentId).ifPresentOrElse(b -> b.setData(sealed), () -> blobs.save(new DocumentBlob(documentId, sealed)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> get(UUID documentId) {
        return blobs.findById(documentId).map(b -> cipher.decrypt(documentId, b.getData()));
    }

    @Override
    @Transactional
    public void delete(UUID documentId) {
        blobs.deleteById(documentId);
    }
}
