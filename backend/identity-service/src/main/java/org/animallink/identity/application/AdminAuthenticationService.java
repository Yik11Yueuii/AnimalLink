package org.animallink.identity.application;

import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.AdminAuthenticationRepository;
import org.animallink.identity.domain.AdminAuthenticationRepository.AdminCredentialRecord;
import org.animallink.identity.domain.AdminAuthenticationRepository.AdminSessionRecord;
import org.animallink.identity.domain.AdminAuthenticationRepository.ExternalIdentityRecord;
import org.animallink.identity.domain.AdminAuthenticationRepository.ValidatedAdminSession;
import org.animallink.identity.domain.AdminSessionClientType;
import org.animallink.identity.domain.ExternalIdentityProvider;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AdminAuthenticationService {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int TOKEN_INSERT_ATTEMPTS = 3;

    private final AdminAuthenticationRepository authenticationRepository;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final OpaqueSessionTokenCodec tokenCodec;
    private final AuthenticationClock clock;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final String dummyPasswordHash;

    public AdminAuthenticationService(
            AdminAuthenticationRepository authenticationRepository,
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            OpaqueSessionTokenCodec tokenCodec,
            AuthenticationClock clock,
            @Value("${animallink.authentication.session.idle-timeout:PT30M}") Duration idleTimeout,
            @Value("${animallink.authentication.session.absolute-timeout:PT8H}") Duration absoluteTimeout) {
        if (idleTimeout.isZero() || idleTimeout.isNegative()
                || absoluteTimeout.isZero() || absoluteTimeout.isNegative()
                || idleTimeout.compareTo(absoluteTimeout) > 0) {
            throw new IllegalArgumentException("Authentication session timeouts are invalid");
        }
        this.authenticationRepository = authenticationRepository;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenCodec = tokenCodec;
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
        this.dummyPasswordHash = passwordHasher.encode("not-a-real-administrator-password");
    }

    /**
     * Intended for a future controlled bootstrap workflow. This method is not exposed by HTTP.
     */
    @Transactional
    public void provisionAdminCredential(String userId, String username, CharSequence rawPassword) {
        String normalizedUsername = normalizeUsername(username);
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

    @Transactional
    public IssuedAdminSession createAdminSession(String username, CharSequence rawPassword) {
        // Before any HTTP adapter is added, WEB-AUTH-1B-1B must wrap this operation with
        // login-attempt throttling and success/failure audit recording. This core alone is
        // intentionally not approved as an externally reachable login capability.
        String normalizedUsername = normalizeUsernameForAuthentication(username);
        AdminCredentialRecord credential = normalizedUsername == null ? null
                : authenticationRepository.findCredentialByUsername(normalizedUsername).orElse(null);
        String encoded = credential == null ? dummyPasswordHash : credential.passwordHash();
        String algorithm = credential == null ? passwordHasher.algorithm() : credential.passwordAlgorithm();
        boolean passwordMatches = passwordHasher.matches(rawPassword == null ? "" : rawPassword,
                encoded, algorithm);
        if (credential == null || !passwordMatches) {
            throw invalidCredentials();
        }

        UserAccount user = userRepository.findUserById(credential.identity().userId())
                .orElseThrow(AdminAuthenticationService::invalidCredentials);
        if (user.accountStatus() != AccountStatus.ACTIVE || user.systemRole() != SystemRole.GOVERNANCE_ADMIN) {
            throw invalidCredentials();
        }

        int credentialVersion = credential.credentialVersion();
        Instant now = clock.now();
        if (passwordHasher.needsUpgrade(credential.passwordHash(), credential.passwordAlgorithm())) {
            int changed = authenticationRepository.upgradeCredential(credential.identity().id(),
                    credentialVersion, passwordHasher.encode(rawPassword), passwordHasher.algorithm());
            if (changed != 1) {
                throw invalidCredentials();
            }
            authenticationRepository.revokeAllByUserId(user.id(), now, "CREDENTIAL_UPGRADED");
            credentialVersion++;
        }

        Instant absoluteExpiry = now.plus(absoluteTimeout);
        Instant idleExpiry = earlier(now.plus(idleTimeout), absoluteExpiry);
        for (int attempt = 0; attempt < TOKEN_INSERT_ATTEMPTS; attempt++) {
            String rawToken = tokenCodec.generate();
            var session = new AdminSessionRecord(UUID.randomUUID().toString(), user.id(),
                    AdminSessionClientType.ADMIN_WEB, tokenCodec.hash(rawToken), credentialVersion,
                    now, now, idleExpiry, absoluteExpiry, null, null, 0);
            try {
                if (!authenticationRepository.insertSessionIfEligible(session)) {
                    throw invalidCredentials();
                }
                return new IssuedAdminSession(rawToken, session.id(), user.id(), idleExpiry, absoluteExpiry);
            } catch (DuplicateKeyException collision) {
                // A secure-token or UUID collision is exceptionally unlikely; retry without exposing either value.
            }
        }
        throw new IllegalStateException("Unable to allocate a unique session identifier");
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
        return authenticationRepository.revokeByTokenHash(tokenHash, clock.now(), normalizeReason(reason));
    }

    @Transactional
    public int revokeAllSessions(String userId, String reason) {
        return authenticationRepository.revokeAllByUserId(userId, clock.now(), normalizeReason(reason));
    }

    private static String normalizeUsername(String username) {
        String normalized = normalizeUsernameForAuthentication(username);
        if (normalized == null) {
            throw new IllegalArgumentException("Username must be 3-64 ASCII letters, digits, dot, underscore or hyphen");
        }
        return normalized;
    }

    private static String normalizeUsernameForAuthentication(String username) {
        if (username == null) return null;
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return USERNAME.matcher(normalized).matches() ? normalized : null;
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

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("用户名或密码错误");
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
