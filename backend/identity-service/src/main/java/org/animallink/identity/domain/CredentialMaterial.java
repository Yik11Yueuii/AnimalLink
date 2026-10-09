package org.animallink.identity.domain;

import java.time.Instant;

public record CredentialMaterial(
        String id,
        String ownerUserId,
        String objectKey,
        String contentType,
        Long sizeBytes,
        CredentialMaterialStatus status,
        Instant createdAt,
        Instant uploadedAt,
        Instant attachedAt) {
}
