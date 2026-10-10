package org.animallink.identity.domain;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface CredentialPrecheckAttemptRepository {
    void insert(CredentialPrecheckAttempt attempt);
    List<CredentialPrecheckAttempt> findByVerificationId(String verificationId);
    Optional<CredentialPrecheckAttempt> findLatestByVerificationId(String verificationId);
    List<CredentialPrecheckAttempt> findPendingForDispatch(int limit);
    Optional<CredentialPrecheckAttempt> findAttemptById(String id);
    boolean claimPending(String id, Instant startedAt);
    boolean completeProcessing(String id, CredentialPrecheckWriteback result, Instant completedAt);
}
