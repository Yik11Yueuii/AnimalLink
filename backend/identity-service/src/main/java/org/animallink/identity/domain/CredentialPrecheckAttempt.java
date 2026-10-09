package org.animallink.identity.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record CredentialPrecheckAttempt(
        String id,
        String verificationId,
        int attemptNo,
        String intelligenceTaskId,
        CredentialPrecheckStatus status,
        String provider,
        String modelName,
        BigDecimal overallConfidence,
        String extractedSchoolName,
        String extractedPersonName,
        String credentialType,
        String consistencyFlags,
        String summary,
        String errorCategory,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public static CredentialPrecheckAttempt pending(String id, String verificationId, Instant now) {
        return new CredentialPrecheckAttempt(id, verificationId, 1, null, CredentialPrecheckStatus.PENDING,
                null, null, null, null, null, null, null, null, null, now, null, null);
    }
}
