package org.animallink.identity.application;

import java.time.Instant;

public interface CredentialObjectStorage {
    SignedUrl createUploadUrl(String objectKey, String contentType, int expiresSeconds);
    StoredObject read(String objectKey);
    SignedUrl createReadUrl(String objectKey, int expiresSeconds);
    void deleteIfExists(String objectKey);

    record SignedUrl(String url, Instant expiresAt) {
    }

    record StoredObject(String contentType, long sizeBytes, byte[] content) {
    }
}
