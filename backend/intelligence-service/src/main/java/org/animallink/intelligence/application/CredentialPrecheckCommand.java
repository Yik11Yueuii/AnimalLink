package org.animallink.intelligence.application;

import java.time.LocalDate;

public record CredentialPrecheckCommand(
        String attemptId,
        String verificationId,
        String applicantUserId,
        String campusId,
        String campusName,
        String applicantName,
        String requestedMembershipType,
        LocalDate expectedGraduationDate) {
}
