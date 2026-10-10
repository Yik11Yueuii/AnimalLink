package org.animallink.identity.application;

import org.animallink.identity.domain.CampusVerificationView;
import org.animallink.identity.domain.CredentialPrecheckStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminCampusVerificationView(
        CampusVerificationView verification,
        CredentialPrecheckView precheck) {

    public record CredentialPrecheckView(
            String attemptId,
            int attemptNo,
            CredentialPrecheckStatus status,
            String intelligenceTaskId,
            String provider,
            String modelName,
            BigDecimal overallConfidence,
            String extractedSchoolName,
            String extractedPersonName,
            String credentialType,
            List<String> consistencyFlags,
            String summary,
            String errorCategory,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt) {
    }
}
