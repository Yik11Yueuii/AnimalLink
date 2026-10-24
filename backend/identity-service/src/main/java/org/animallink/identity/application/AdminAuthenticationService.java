package org.animallink.identity.application;

import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.AdminAuthenticationRepository;
import org.animallink.identity.domain.AdminAuthenticationRepository.AdminSessionRecord;
import org.animallink.identity.domain.AdminAuthenticationRepository.ExternalIdentityRecord;
import org.animallink.identity.domain.AdminAuthenticationRepository.ValidatedAdminSession;
import org.animallink.identity.domain.AuthenticationSecurityRepository;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationActorType;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditOutcome;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditType;
import org.animallink.identity.domain.ExternalIdentityProvider;
import org.animallink.identity.domain.LoginRateLimitedException;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AdminAuthenticationService {
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final AdminAuthenticationRepository authenticationRepository;
    private final AuthenticationSecurityRepository securityRepository;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final OpaqueSessionTokenCodec tokenCodec;
    private final AuthenticationClock clock;
    private final AdminLoginProtectionService loginProtectionService;
    private final AdminSessionIssuer sessionIssuer;
    private final Duration idleTimeout;

    public AdminAuthenticationService(
            AdminAuthenticationRepository authenticationRepository,
            AuthenticationSecurityRepository securityRepository,
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            OpaqueSessionTokenCodec tokenCodec,
            AuthenticationClock clock,
            AdminLoginProtectionService loginProtectionService,
            AdminSessionIssuer sessionIssuer,
            @Value("${animallink.authentication.session.idle-timeout:PT30M}") Duration idleTimeout,
            @Value("${animallink.authentication.session.absolute-timeout:PT8H}") Duration absoluteTimeout) {
        if (idleTimeout.isZero() || idleTimeout.isNegative()
                || absoluteTimeout.isZero() || absoluteTimeout.isNegative()
                || idleTimeout.compareTo(absoluteTimeout) > 0) {
            throw new IllegalArgumentException("Authentication session timeouts are invalid");
        }
        this.authenticationRepository = authenticationRepository;
        this.securityRepository = securityRepository;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenCodec = tokenCodec;
        this.clock = clock;
        this.loginProtectionService = loginProtectionService;
        this.sessionIssuer = sessionIssuer;
        this.idleTimeout = idleTimeout;
    }

    /**
     * Intended for a future controlled bootstrap workflow. This method is not exposed by HTTP.
     */
    @Transactional
    public void provisionAdminCredential(String userId, String username, CharSequence rawPassword) {
        String normalizedUsername = AuthenticationSecurityIdentifiers.requireUsername(username);
        requirePasswordPolicy(rawPassword);
        UserAccount user = userRepository.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("UserAccount does not exist"));
        if (user.accountStatus() != AccountStatus.ACTIVE || user.systemRole() != SystemRole.GOVERNANCE_ADMIN) {
            throw new IllegalArgumentException("Only an active governance administrator may receive credentials");
        }
        Instant now = clock.now();
        var identity = new ExternalIdentityRecord(UUID.randomUUID().toString(), userId,
                ExternalIdentityProvider.ADMIN_PASSWORD, "", normalizedUsername, now, now);
        authenticationRepository.insertCredential(identity, passwordHasher.encode(rawPassword),
                passwordHasher.algorithm(), 1);
    }

    public IssuedAdminSession createAdminSession(String username, CharSequence rawPassword) {
        return createAdminSession(username, rawPassword, "internal-application");
    }

    public IssuedAdminSession createAdminSession(String username, CharSequence rawPassword,
                                                  String trustedSourceKey) {
        var context = loginProtectionService.reserve(username, trustedSourceKey);
        if (!context.allowed()) {
            throw new LoginRateLimitedException();
        }
        try {
            return sessionIssuer.issue(context, rawPassword);
        } catch (UnauthorizedException invalidCredentials) {
            loginProtectionService.recordFailure(context);
            throw invalidCredentials;
        } catch (RuntimeException authenticationFailure) {
            try {
                loginProtectionService.releaseAbortedAttempt(context);
            } catch (RuntimeException releaseFailure) {
                authenticationFailure.addSuppressed(releaseFailure);
            }
            throw authenticationFailure;
        }
    }

    @Transactional
    public AuthenticatedAdminSession validateAdminSession(String rawToken) {
        String tokenHash = tokenHashOrUnauthorized(rawToken);
        AdminSessionRecord existing = authenticationRepository.findSessionByTokenHash(tokenHash)
                .orElseThrow(AdminAuthenticationService::invalidSession);
        Instant now = clock.now();
        Instant proposedIdleExpiry = earlier(now.plus(idleTimeout), existing.absoluteExpiresAt());
        if (!authenticationRepository.touchIfValidAdmin(tokenHash, now, proposedIdleExpiry)) {
            throw invalidSession();
        }
        ValidatedAdminSession validated = authenticationRepository.findValidatedSession(tokenHash, now)
                .orElseThrow(AdminAuthenticationService::invalidSession);
        return new AuthenticatedAdminSession(validated.session().id(), validated.user(),
                validated.session().idleExpiresAt(), validated.session().absoluteExpiresAt());
    }

    @Transactional
    public boolean revokeCurrentSession(String rawToken, String reason) {
        String tokenHash = tokenHashOrUnauthorized(rawToken);
        AdminSessionRecord session = authenticationRepository.findSessionByTokenHash(tokenHash).orElse(null);
        Instant now = clock.now();
        boolean revoked = authenticationRepository.revokeByTokenHash(
                tokenHash, now, normalizeReason(reason));
        if (revoked && session != null) {
            securityRepository.insertAuditEvent(AdminLoginProtectionService.event(
                    AuthenticationAuditType.SESSION_REVOKED, AuthenticationAuditOutcome.SUCCESS,
                    session.userId(), null, AuthenticationActorType.ADMIN_SESSION,
                    session.userId(), null, "SESSION_REVOKED", session.id(), now));
        }
        return revoked;
    }

    @Transactional
    public int revokeAllSessions(String userId, String reason) {
        Instant now = clock.now();
        int revoked = authenticationRepository.revokeAllByUserId(userId, now, normalizeReason(reason));
        securityRepository.insertAuditEvent(AdminLoginProtectionService.event(
                AuthenticationAuditType.ALL_SESSIONS_REVOKED, AuthenticationAuditOutcome.SUCCESS,
                userId, null, AuthenticationActorType.ADMIN_SESSION, userId, null,
                revoked == 0 ? "NO_ACTIVE_SESSIONS" : "ALL_ACTIVE_SESSIONS_REVOKED", null, now));
        return revoked;
    }

    private static void requirePasswordPolicy(CharSequence rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH
                || rawPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must contain 12-128 characters");
        }
    }

    private String tokenHashOrUnauthorized(String rawToken) {
        try {
            return tokenCodec.hash(rawToken);
        } catch (IllegalArgumentException exception) {
            throw invalidSession();
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 64) {
            throw new IllegalArgumentException("Session revocation reason must contain 1-64 characters");
        }
        return reason.trim();
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static UnauthorizedException invalidSession() {
        return new UnauthorizedException("管理员会话无效或已过期");
    }

    public record IssuedAdminSession(String rawToken, String sessionId, String userId,
                                     Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }

    public record AuthenticatedAdminSession(String sessionId, UserAccount user,
                                            Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }
}
