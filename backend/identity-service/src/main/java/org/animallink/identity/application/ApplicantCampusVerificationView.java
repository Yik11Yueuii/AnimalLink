package org.animallink.identity.application;

import org.animallink.identity.domain.CampusVerificationView;

public record ApplicantCampusVerificationView(
        CampusVerificationView verification,
        ApplicantCredentialPrecheckStatus credentialPrecheckStatus) {
}
