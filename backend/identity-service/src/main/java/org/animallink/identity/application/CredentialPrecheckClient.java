package org.animallink.identity.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface CredentialPrecheckClient {
    CredentialPrecheckResult precheck(CredentialPrecheckRequest request);

    record CredentialPrecheckRequest(
            String attemptId,
            String verificationId,
            String applicantUserId,
            String campusId,
            String campusName,
            String applicantName,
            String requestedMembershipType,
            LocalDate expectedGraduationDate) {
    }

    record CredentialPrecheckResult(
            String taskId,
            String status,
            String provider,
            String modelName,
            BigDecimal overallConfidence,
            String extractedSchoolName,
            String extractedPersonName,
            String credentialType,
            List<String> consistencyFlags,
            String summary,
            String errorCategory) {
    }
}
