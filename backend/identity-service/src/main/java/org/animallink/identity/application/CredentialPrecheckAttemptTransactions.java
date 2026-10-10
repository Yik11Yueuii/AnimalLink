package org.animallink.identity.application;

import org.animallink.identity.domain.CredentialPrecheckAttemptRepository;
import org.animallink.identity.domain.CredentialPrecheckWriteback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CredentialPrecheckAttemptTransactions {
    private final CredentialPrecheckAttemptRepository attempts;

    public CredentialPrecheckAttemptTransactions(CredentialPrecheckAttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Transactional
    public boolean claim(String attemptId, Instant startedAt) {
        return attempts.claimPending(attemptId, startedAt);
    }

    @Transactional
    public boolean complete(String attemptId, CredentialPrecheckWriteback result, Instant completedAt) {
        return attempts.completeProcessing(attemptId, result, completedAt);
    }
}
