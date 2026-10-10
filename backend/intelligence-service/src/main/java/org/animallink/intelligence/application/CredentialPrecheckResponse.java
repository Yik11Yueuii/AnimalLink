package org.animallink.intelligence.application;

import java.math.BigDecimal;
import java.util.List;

public record CredentialPrecheckResponse(
        String taskId,
        String status,
        String provider,
        String modelName,
        BigDecimal overallConfidence,
        String extractedCampusName,
        String extractedApplicantName,
        String credentialType,
        List<String> consistencyFlags,
        String summary,
        String errorCategory) {
}
