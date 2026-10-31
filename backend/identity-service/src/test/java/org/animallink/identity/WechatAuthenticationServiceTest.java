package org.animallink.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.animallink.identity.application.AuthenticationClock;
import org.animallink.identity.application.OpaqueSessionTokenCodec;
import org.animallink.identity.application.WechatAuthenticationService;
import org.animallink.identity.application.WechatCodeExchangeClient;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.WechatAuthenticationRepository;
import org.animallink.identity.infrastructure.SecureOpaqueSessionTokenCodec;
import org.junit.jupiter.api.Test;

class WechatAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void repeatedExchangeKeepsOneRegularUserAndIssuesRevocableOpaqueSessions() {
        var repository = new MemoryRepository();
        var clock = (AuthenticationClock) () -> NOW;
        var service = service(code -> new WechatCodeExchangeClient.ExchangeResult("open-id-123456", null), repository, clock);

        var first = service.login("valid-code-123");
        var second = service.login("valid-code-123");

        assertThat(first.userId()).isEqualTo(second.userId());
        assertThat(first.token()).hasSize(43).isNotEqualTo(second.token());
        assertThat(repository.users).hasSize(1);
        assertThat(repository.users.values()).allSatisfy(user -> {
            assertThat(user.systemRole()).isEqualTo(SystemRole.USER);
            assertThat(user.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
        });
        assertThat(service.validate(first.token()).userId()).isEqualTo(first.userId());
        assertThat(service.logout(first.token())).isTrue();
        assertThatThrownBy(() -> service.validate(first.token())).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void invalidClientCodeAndRejectedWechatExchangeFailClosed() {
        var repository = new MemoryRepository();
        var service = service(code -> { throw new WechatCodeExchangeClient.RejectedCodeException(); }, repository, () -> NOW);

        assertThatThrownBy(() -> service.login("short"))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.login("valid-code-123"))
                .isInstanceOf(WechatCodeExchangeClient.RejectedCodeException.class);
        assertThat(repository.users).isEmpty();
    }

    private static WechatAuthenticationService service(WechatCodeExchangeClient exchange,
                                                        MemoryRepository repository, AuthenticationClock clock) {
        OpaqueSessionTokenCodec tokens = new SecureOpaqueSessionTokenCodec();
        return new WechatAuthenticationService(exchange, repository, tokens, clock, "wx-test-app",
                Duration.ofDays(7), Duration.ofDays(30));
    }

    private static final class MemoryRepository implements WechatAuthenticationRepository {
        private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
        private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

        @Override public Optional<UserAccount> findUserByWechatSubject(String appId, String openId) {
            return Optional.ofNullable(users.get(appId + ':' + openId));
        }

        @Override public UserAccount createWechatUserIfAbsent(String appId, String openId, String displayName, Instant now) {
            return users.computeIfAbsent(appId + ':' + openId, ignored ->
                    new UserAccount(UUID.randomUUID().toString(), displayName, AccountStatus.ACTIVE, SystemRole.USER, now, now));
        }

        @Override public boolean insertSession(UserSession session) {
            return sessions.putIfAbsent(session.tokenHash(), session) == null;
        }

        @Override public Optional<ValidatedSession> findValidatedSession(String hash, Instant now) {
            UserSession session = sessions.get(hash);
            if (session == null || session.revokedAt() != null || !session.idleExpiresAt().isAfter(now)
                    || !session.absoluteExpiresAt().isAfter(now)) return Optional.empty();
            UserAccount user = users.values().stream().filter(candidate -> candidate.id().equals(session.userId())).findFirst().orElse(null);
            if (user == null || user.accountStatus() != AccountStatus.ACTIVE || user.systemRole() != SystemRole.USER) return Optional.empty();
            return Optional.of(new ValidatedSession(session, user));
        }

        @Override public boolean touchIfValid(String hash, Instant now, Instant proposedExpiry) {
            UserSession old = findValidatedSession(hash, now).map(ValidatedSession::session).orElse(null);
            if (old == null) return false;
            Instant idle = proposedExpiry.isBefore(old.absoluteExpiresAt()) ? proposedExpiry : old.absoluteExpiresAt();
            sessions.put(hash, new UserSession(old.id(), old.userId(), old.tokenHash(), old.createdAt(), now, idle,
                    old.absoluteExpiresAt(), null, null, old.version() + 1));
            return true;
        }

        @Override public boolean revokeByTokenHash(String hash, Instant at, String reason) {
            UserSession old = sessions.get(hash);
            if (old == null || old.revokedAt() != null) return false;
            sessions.put(hash, new UserSession(old.id(), old.userId(), old.tokenHash(), old.createdAt(), old.lastSeenAt(),
                    old.idleExpiresAt(), old.absoluteExpiresAt(), at, reason, old.version() + 1));
            return true;
        }
    }
}
