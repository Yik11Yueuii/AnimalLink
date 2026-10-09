package org.animallink.identity.domain;

import java.time.Instant;
import java.util.Optional;

public interface CredentialMaterialRepository {
    void insert(CredentialMaterial material);
    Optional<CredentialMaterial> findById(String materialId);
    Optional<CredentialMaterial> findOwnedById(String materialId, String ownerUserId);
    Optional<CredentialMaterial> findByIdForUpdate(String materialId);
    boolean markReady(String materialId, Instant uploadedAt, String contentType, long sizeBytes);
    void markInvalid(String materialId);
    boolean attachReady(String materialId, Instant attachedAt);
}
