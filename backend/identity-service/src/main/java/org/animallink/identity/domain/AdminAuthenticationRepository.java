package org.animallink.identity.domain;

import java.time.Instant;
import java.util.Optional;

public interface AdminAuthenticationRepository {
    Optional<AdminCredentialRecord> findCredentialByUsername(String normalizedUsername);

    Optional<AdminCredentialRecord> findCredentialByUserId(String userId);

    void insertCredential(ExternalIdentityRecord identity, String passwordHash,
                          String passwordAlgorithm, int credentialVersion);

    int upgradeCredential(String externalIdentityId, int expectedVersion,
                          String passwordHash, String passwordAlgorithm);

    boolean insertSessionIfEligible(AdminSessionRecord session);

    Optional<AdminSessionRecord> findSessionByTokenHash(String tokenHash);

    boolean touchIfValidAdmin(String tokenHash, Instant now, Instant proposedIdleExpiry);

    Optional<ValidatedAdminSession> findValidatedSession(String tokenHash, Instant now);

    boolean revokeByTokenHash(String tokenHash, Instant revokedAt, String reason);

    int revokeAllByUserId(String userId, Instant revokedAt, String reason);

    record ExternalIdentityRecord(String id, String userId, ExternalIdentityProvider provider,
                                  String providerTenant, String providerSubject,
                                  Instant createdAt, Instant updatedAt) {
    }

    record AdminCredentialRecord(ExternalIdentityRecord identity, String passwordHash,
                                 String passwordAlgorithm, int credentialVersion,
                                 Instant createdAt, Instant updatedAt) {
    }

    record AdminSessionRecord(String id, String userId, AdminSessionClientType clientType,
                              String tokenHash, int credentialVersion, Instant createdAt,
                              Instant lastSeenAt, Instant idleExpiresAt, Instant absoluteExpiresAt,
                              Instant revokedAt, String revokeReason, int version) {
    }

    record ValidatedAdminSession(AdminSessionRecord session, UserAccount user) {
    }
}
