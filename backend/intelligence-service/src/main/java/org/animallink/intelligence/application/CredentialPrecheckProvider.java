package org.animallink.intelligence.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Advisory-only adapter. Implementations must not retain credential bytes or provider raw output.
 */
public interface CredentialPrecheckProvider {
    String providerName();
    String modelName();
    ProviderResult precheck(ProviderRequest request);

    record ProviderRequest(byte[] credentialContent, String contentType, String campusName,
                           String applicantName, String requestedMembershipType,
                           LocalDate expectedGraduationDate) {
    }

    record ProviderResult(BigDecimal overallConfidence, String extractedCampusName,
                          String extractedApplicantName, String credentialType,
                          List<String> consistencyFlags) {
    }
}
