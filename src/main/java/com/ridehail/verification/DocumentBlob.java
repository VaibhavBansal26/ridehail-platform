package com.ridehail.verification;

import jakarta.persistence.*;
import java.util.UUID;

/** The encrypted bytes of one document, kept apart from the metadata so listing documents never loads a file. */
@Entity
@Table(name = "rider_document_blobs")
class DocumentBlob {
    @Id @Column(name = "document_id") private UUID documentId;
    /**
     * Ciphertext of a document (up to 5 MB by default). Declared as bytea, which is what Postgres uses (the migration creates the same)
     * and what the in-memory dev database accepts: left to Hibernate, a byte[] column is 255 bytes on that database, or a BLOB it does
     * not know, and either way no real photo fits.
     */
    @Column(nullable = false, columnDefinition = "bytea") private byte[] data;

    protected DocumentBlob() {}

    DocumentBlob(UUID documentId, byte[] data) {
        this.documentId = documentId;
        this.data = data;
    }

    byte[] getData() { return data; }
    void setData(byte[] data) { this.data = data; }
}
