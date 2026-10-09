package org.animallink.identity.domain;

import java.util.List;

public interface CredentialPrecheckAttemptRepository {
    void insert(CredentialPrecheckAttempt attempt);
    List<CredentialPrecheckAttempt> findByVerificationId(String verificationId);
}
