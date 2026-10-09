package org.animallink.identity.domain;

public enum CredentialPrecheckStatus {
    PENDING,
    PROCESSING,
    PASSED,
    MANUAL_REVIEW_REQUIRED,
    UNAVAILABLE
}
