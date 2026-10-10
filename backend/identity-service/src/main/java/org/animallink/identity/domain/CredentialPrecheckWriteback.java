package org.animallink.identity.domain;

import java.math.BigDecimal;

public record CredentialPrecheckWriteback(
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
        String errorCategory) {
}
