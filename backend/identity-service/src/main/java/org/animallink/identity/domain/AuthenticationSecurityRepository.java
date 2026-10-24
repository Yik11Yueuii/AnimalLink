package org.animallink.identity.domain;

import java.time.Duration;
import java.time.Instant;

public interface AuthenticationSecurityRepository {
    LoginAttemptDecision reserveLoginAttempt(String usernameKey, String normalizedUsername,
                                             String sourceFingerprint, Instant now,
                                             Duration attemptWindow, Duration blockDuration,
                                             int maximumUsernameAttempts, int maximumSourceAttempts);

    void completeLoginAttempt(String usernameKey, Instant usernameWindowStartedAt,
                              String sourceFingerprint, Instant sourceWindowStartedAt,
                              boolean authenticationFailed, Instant now);

    void insertAuditEvent(AuthenticationAuditEvent event);

    BootstrapState lockBootstrapState();

    long countGovernanceAdministrators();

    void insertGovernanceAdministrator(UserAccount user);

    boolean completeBootstrap(String userId, Instant completedAt, int expectedVersion);

    int deleteAuditEventsBefore(Instant cutoff, int limit);

    int deleteLoginAttemptStatesBefore(Instant cutoff, int limit);

    enum AuthenticationAuditType {
        ADMIN_INITIALIZATION_SUCCESS,
        ADMIN_INITIALIZATION_DENIED,
        ADMIN_LOGIN_SUCCESS,
        ADMIN_LOGIN_FAILURE,
        ADMIN_LOGIN_RATE_LIMITED,
        SESSION_REVOKED,
        ALL_SESSIONS_REVOKED,
        CREDENTIAL_UPGRADED
    }

    enum AuthenticationAuditOutcome {
        SUCCESS,
        FAILURE,
        DENIED
    }

    enum AuthenticationActorType {
        LOCAL_OPERATOR,
        ADMIN_CREDENTIAL,
        ADMIN_SESSION,
        SYSTEM
    }

    record LoginAttemptDecision(boolean allowed, Instant blockedUntil,
                                Instant usernameWindowStartedAt,
                                Instant sourceWindowStartedAt) {
    }

    record BootstrapState(String status, String completedUserId, Instant completedAt, int version) {
    }

    record AuthenticationAuditEvent(
            String id,
            AuthenticationAuditType eventType,
            AuthenticationAuditOutcome outcome,
            String subjectUserId,
            String normalizedUsername,
            AuthenticationActorType actorType,
            String actorReference,
            String sourceFingerprint,
            String reasonCode,
            String sessionId,
            Instant createdAt) {
    }
}
