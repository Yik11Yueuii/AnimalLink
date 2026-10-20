package org.animallink.identity.application;

import org.animallink.identity.application.AdminLoginProtectionService.LoginAttemptContext;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.AdminAuthenticationRepository;
import org.animallink.identity.domain.AdminAuthenticationRepository.AdminSessionRecord;
import org.animallink.identity.domain.AdminSessionClientType;
import org.animallink.identity.domain.AuthenticationSecurityRepository;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationActorType;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditOutcome;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditType;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AdminSessionIssuer {
    private static final int TOKEN_INSERT_ATTEMPTS = 3;

    private final AdminAuthenticationRepository authenticationRepository;
    private final AuthenticationSecurityRepository securityRepository;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final OpaqueSessionTokenCodec tokenCodec;
    private final AuthenticationClock clock;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final String dummyPasswordHash;

    public AdminSessionIssuer(AdminAuthenticationRepository authenticationRepository,
                              AuthenticationSecurityRepository securityRepository,
                              UserRepository userRepository, PasswordHasher passwordHasher,
                              OpaqueSessionTokenCodec tokenCodec, AuthenticationClock clock,
                              @Value("${animallink.authentication.session.idle-timeout:PT30M}") Duration idleTimeout,
                              @Value("${animallink.authentication.session.absolute-timeout:PT8H}") Duration absoluteTimeout) {
        if (idleTimeout.isZero() || idleTimeout.isNegative() || absoluteTimeout.isZero()
                || absoluteTimeout.isNegative() || idleTimeout.compareTo(absoluteTimeout) > 0) {
            throw new IllegalArgumentException("Authentication session timeouts are invalid");
        }
        this.authenticationRepository = authenticationRepository;
        this.securityRepository = securityRepository;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenCodec = tokenCodec;
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
        this.dummyPasswordHash = passwordHasher.encode("not-a-real-administrator-password");
    }

    @Transactional
    public AdminAuthenticationService.IssuedAdminSession issue(LoginAttemptContext context,
                                                                CharSequence rawPassword) {
        String normalizedUsername = context.normalizedUsername();
        var credential = normalizedUsername == null ? null
                : authenticationRepository.findCredentialByUsername(normalizedUsername).orElse(null);
        String encoded = credential == null ? dummyPasswordHash : credential.passwordHash();
        String algorithm = credential == null ? passwordHasher.algorithm() : credential.passwordAlgorithm();
        boolean passwordMatches = passwordHasher.matches(rawPassword == null ? "" : rawPassword,
                encoded, algorithm);
        if (credential == null || !passwordMatches) throw invalidCredentials();

        var user = userRepository.findUserById(credential.identity().userId())
                .orElseThrow(AdminSessionIssuer::invalidCredentials);
        if (user.accountStatus() != AccountStatus.ACTIVE || user.systemRole() != SystemRole.GOVERNANCE_ADMIN) {
            throw invalidCredentials();
        }

        int credentialVersion = credential.credentialVersion();
        Instant now = clock.now();
        if (passwordHasher.needsUpgrade(credential.passwordHash(), credential.passwordAlgorithm())) {
            int changed = authenticationRepository.upgradeCredential(credential.identity().id(),
                    credentialVersion, passwordHasher.encode(rawPassword), passwordHasher.algorithm());
            if (changed != 1) throw invalidCredentials();
            authenticationRepository.revokeAllByUserId(user.id(), now, "CREDENTIAL_UPGRADED");
            credentialVersion++;
            securityRepository.insertAuditEvent(AdminLoginProtectionService.event(
                    AuthenticationAuditType.CREDENTIAL_UPGRADED, AuthenticationAuditOutcome.SUCCESS,
                    user.id(), normalizedUsername, AuthenticationActorType.ADMIN_CREDENTIAL,
                    user.id(), context.sourceFingerprint(), "HASH_PARAMETERS_UPGRADED", null, now));
        }

        Instant absoluteExpiry = now.plus(absoluteTimeout);
        Instant idleExpiry = earlier(now.plus(idleTimeout), absoluteExpiry);
        for (int attempt = 0; attempt < TOKEN_INSERT_ATTEMPTS; attempt++) {
            String rawToken = tokenCodec.generate();
            var session = new AdminSessionRecord(UUID.randomUUID().toString(), user.id(),
                    AdminSessionClientType.ADMIN_WEB, tokenCodec.hash(rawToken), credentialVersion,
                    now, now, idleExpiry, absoluteExpiry, null, null, 0);
            try {
                if (!authenticationRepository.insertSessionIfEligible(session)) throw invalidCredentials();
            } catch (DuplicateKeyException collision) {
                // A secure-token or UUID collision is exceptionally unlikely; retry without exposing either value.
                continue;
            }
            securityRepository.completeLoginAttempt(
                    context.usernameKey(), context.usernameWindowStartedAt(),
                    context.sourceFingerprint(), context.sourceWindowStartedAt(), false, now);
            securityRepository.insertAuditEvent(AdminLoginProtectionService.event(
                    AuthenticationAuditType.ADMIN_LOGIN_SUCCESS, AuthenticationAuditOutcome.SUCCESS,
                    user.id(), normalizedUsername, AuthenticationActorType.ADMIN_CREDENTIAL,
                    user.id(), context.sourceFingerprint(), "CREDENTIALS_ACCEPTED", session.id(), now));
            return new AdminAuthenticationService.IssuedAdminSession(
                    rawToken, session.id(), user.id(), idleExpiry, absoluteExpiry);
        }
        throw new IllegalStateException("Unable to allocate a unique session identifier");
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("用户名或密码错误");
    }
}
