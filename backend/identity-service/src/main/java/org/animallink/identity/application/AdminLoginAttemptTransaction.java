package org.animallink.identity.application;

import org.animallink.identity.application.AdminLoginProtectionService.LoginAttemptContext;
import org.animallink.identity.domain.AuthenticationSecurityRepository;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationActorType;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditOutcome;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AdminLoginAttemptTransaction {
    private final AuthenticationSecurityRepository repository;
    private final AuthenticationClock clock;

    public AdminLoginAttemptTransaction(AuthenticationSecurityRepository repository,
                                        AuthenticationClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginAttemptContext reserve(String suppliedUsername, String trustedSourceKey,
                                       int maximumAttempts, int maximumSourceAttempts,
                                       Duration attemptWindow, Duration blockDuration) {
        String normalized = AuthenticationSecurityIdentifiers.normalizeUsername(suppliedUsername);
        String securityUsername = AuthenticationSecurityIdentifiers.usernameForSecurityState(suppliedUsername);
        String usernameKey = AuthenticationSecurityIdentifiers.usernameKey(securityUsername);
        String sourceFingerprint = AuthenticationSecurityIdentifiers.requireTrustedSource(trustedSourceKey);
        Instant now = clock.now();
        var decision = repository.reserveLoginAttempt(usernameKey, securityUsername, sourceFingerprint, now,
                attemptWindow, blockDuration, maximumAttempts, maximumSourceAttempts);
        var context = new LoginAttemptContext(normalized, usernameKey, decision.usernameWindowStartedAt(),
                sourceFingerprint, decision.sourceWindowStartedAt(), decision.allowed());
        if (!decision.allowed()) {
            repository.insertAuditEvent(AdminLoginProtectionService.event(
                    AuthenticationAuditType.ADMIN_LOGIN_RATE_LIMITED,
                    AuthenticationAuditOutcome.DENIED, null, normalized,
                    AuthenticationActorType.ADMIN_CREDENTIAL, null, sourceFingerprint,
                    "ATTEMPT_LIMIT_ACTIVE", null, now));
        }
        return context;
    }
}
