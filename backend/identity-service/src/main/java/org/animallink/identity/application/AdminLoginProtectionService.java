package org.animallink.identity.application;

import org.animallink.identity.domain.AuthenticationSecurityRepository;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationActorType;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditEvent;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditOutcome;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditType;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AdminLoginProtectionService {
    private static final int DEADLOCK_RETRY_ATTEMPTS = 5;
    private final AuthenticationSecurityRepository repository;
    private final AuthenticationClock clock;
    private final AdminLoginAttemptTransaction attemptTransaction;
    private final int maximumAttempts;
    private final int maximumSourceAttempts;
    private final Duration attemptWindow;
    private final Duration blockDuration;

    public AdminLoginProtectionService(
            AuthenticationSecurityRepository repository,
            AuthenticationClock clock,
            AdminLoginAttemptTransaction attemptTransaction,
            @Value("${animallink.authentication.login-protection.maximum-attempts:5}") int maximumAttempts,
            @Value("${animallink.authentication.login-protection.maximum-source-attempts:20}") int maximumSourceAttempts,
            @Value("${animallink.authentication.login-protection.attempt-window:PT15M}") Duration attemptWindow,
            @Value("${animallink.authentication.login-protection.block-duration:PT15M}") Duration blockDuration) {
        if (maximumAttempts < 1 || maximumAttempts > 100 || maximumSourceAttempts < maximumAttempts
                || maximumSourceAttempts > 1000 || attemptWindow.isNegative() || attemptWindow.isZero()
                || blockDuration.isNegative() || blockDuration.isZero()) {
            throw new IllegalArgumentException("Login-protection configuration is invalid");
        }
        this.repository = repository;
        this.clock = clock;
        this.attemptTransaction = attemptTransaction;
        this.maximumAttempts = maximumAttempts;
        this.maximumSourceAttempts = maximumSourceAttempts;
        this.attemptWindow = attemptWindow;
        this.blockDuration = blockDuration;
    }

    public LoginAttemptContext reserve(String suppliedUsername, String trustedSourceKey) {
        CannotAcquireLockException lastFailure = null;
        for (int attempt = 0; attempt < DEADLOCK_RETRY_ATTEMPTS; attempt++) {
            try {
                return attemptTransaction.reserve(suppliedUsername, trustedSourceKey,
                        maximumAttempts, maximumSourceAttempts, attemptWindow, blockDuration);
            } catch (CannotAcquireLockException transientConflict) {
                lastFailure = transientConflict;
                Thread.onSpinWait();
            }
        }
        throw lastFailure;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(LoginAttemptContext context) {
        repository.completeLoginAttempt(context.usernameKey(), context.usernameWindowStartedAt(),
                context.sourceFingerprint(), context.sourceWindowStartedAt(), true, clock.now());
        repository.insertAuditEvent(event(AuthenticationAuditType.ADMIN_LOGIN_FAILURE,
                AuthenticationAuditOutcome.FAILURE, null, context.normalizedUsername(),
                AuthenticationActorType.ADMIN_CREDENTIAL, null, context.sourceFingerprint(),
                "INVALID_CREDENTIALS", null, clock.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseAbortedAttempt(LoginAttemptContext context) {
        repository.completeLoginAttempt(context.usernameKey(), context.usernameWindowStartedAt(),
                context.sourceFingerprint(), context.sourceWindowStartedAt(), false, clock.now());
    }

    static AuthenticationAuditEvent event(AuthenticationAuditType type, AuthenticationAuditOutcome outcome,
                                            String subjectUserId, String username,
                                            AuthenticationActorType actorType, String actorReference,
                                            String sourceFingerprint, String reasonCode,
                                            String sessionId, Instant now) {
        return new AuthenticationAuditEvent(UUID.randomUUID().toString(), type, outcome, subjectUserId,
                username, actorType, actorReference, sourceFingerprint, reasonCode, sessionId, now);
    }

    public record LoginAttemptContext(String normalizedUsername, String usernameKey,
                                      Instant usernameWindowStartedAt, String sourceFingerprint,
                                      Instant sourceWindowStartedAt, boolean allowed) {
    }
}
