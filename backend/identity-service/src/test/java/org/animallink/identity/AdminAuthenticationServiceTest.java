package org.animallink.identity;

import org.animallink.identity.application.AdminAuthenticationService;
import org.animallink.identity.application.AuthenticationClock;
import org.animallink.identity.application.PasswordHasher;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.AdminAuthenticationRepository;
import org.animallink.identity.domain.AdminSessionClientType;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserRepository;
import org.animallink.identity.infrastructure.Argon2idPasswordHasher;
import org.animallink.identity.infrastructure.SecureOpaqueSessionTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthenticationServiceTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000951";
    private static final String PASSWORD = "correct horse battery staple";
    private static final Instant START = Instant.parse("2026-10-19T00:00:00Z");

    private final MutableClock clock = new MutableClock();
    private final MutableUsers users = new MutableUsers();
    private InMemoryAuthenticationRepository repository;
    private FastPasswordHasher passwordHasher;
    private AdminAuthenticationService service;

    @BeforeEach
    void setUp() {
        clock.value = START;
        users.put(account(AccountStatus.ACTIVE, SystemRole.GOVERNANCE_ADMIN));
        repository = new InMemoryAuthenticationRepository(users);
        passwordHasher = new FastPasswordHasher();
        service = new AdminAuthenticationService(repository, users, passwordHasher,
                new SecureOpaqueSessionTokenCodec(), clock, Duration.ofMinutes(30), Duration.ofHours(8));
        service.provisionAdminCredential(ADMIN, "admin.root", PASSWORD);
    }

    @Test
    void correctPasswordCreatesOpaqueRandomSessionsWhileWrongPasswordIsRejected() {
        var first = service.createAdminSession(" ADMIN.ROOT ", PASSWORD);
        var second = service.createAdminSession("admin.root", PASSWORD);
        assertThat(first.rawToken()).hasSize(43).isNotEqualTo(second.rawToken());
        assertThat(repository.sessions.values()).extracting(AdminAuthenticationRepository.AdminSessionRecord::tokenHash)
                .doesNotContain(first.rawToken(), second.rawToken()).doesNotHaveDuplicates();
        assertThat(service.validateAdminSession(first.rawToken()).user().id()).isEqualTo(ADMIN);
        assertThatThrownBy(() -> service.createAdminSession("admin.root", "wrong password value"))
                .isInstanceOf(UnauthorizedException.class).hasMessage("用户名或密码错误");
    }

    @Test
    void idleAndAbsoluteExpiryAreClosedBoundaries() {
        var idle = service.createAdminSession("admin.root", PASSWORD);
        clock.value = START.plus(Duration.ofMinutes(30));
        assertThatThrownBy(() -> service.validateAdminSession(idle.rawToken()))
                .isInstanceOf(UnauthorizedException.class);

        clock.value = START;
        var absolute = service.createAdminSession("admin.root", PASSWORD);
        for (int i = 1; i < 24; i++) {
            clock.value = START.plus(Duration.ofMinutes(20L * i));
            service.validateAdminSession(absolute.rawToken());
        }
        clock.value = START.plus(Duration.ofHours(8));
        assertThatThrownBy(() -> service.validateAdminSession(absolute.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void currentAndAllRevocationAreImmediate() {
        var first = service.createAdminSession("admin.root", PASSWORD);
        var second = service.createAdminSession("admin.root", PASSWORD);
        assertThat(service.revokeCurrentSession(first.rawToken(), "USER_LOGOUT")).isTrue();
        assertThat(service.revokeCurrentSession(first.rawToken(), "USER_LOGOUT")).isFalse();
        assertThatThrownBy(() -> service.validateAdminSession(first.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(service.revokeAllSessions(ADMIN, "LOGOUT_ALL")).isEqualTo(1);
        assertThatThrownBy(() -> service.validateAdminSession(second.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void accountRoleStatusAndCredentialVersionAreCheckedOnEveryValidation() {
        var role = service.createAdminSession("admin.root", PASSWORD);
        users.put(account(AccountStatus.ACTIVE, SystemRole.USER));
        assertThatThrownBy(() -> service.validateAdminSession(role.rawToken()))
                .isInstanceOf(UnauthorizedException.class);

        users.put(account(AccountStatus.ACTIVE, SystemRole.GOVERNANCE_ADMIN));
        var status = service.createAdminSession("admin.root", PASSWORD);
        users.put(account(AccountStatus.DISABLED, SystemRole.GOVERNANCE_ADMIN));
        assertThatThrownBy(() -> service.validateAdminSession(status.rawToken()))
                .isInstanceOf(UnauthorizedException.class);

        users.put(account(AccountStatus.ACTIVE, SystemRole.GOVERNANCE_ADMIN));
        var version = service.createAdminSession("admin.root", PASSWORD);
        repository.incrementCredentialVersion(ADMIN);
        assertThatThrownBy(() -> service.validateAdminSession(version.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void successfulCredentialUpgradeIncrementsVersionAndRevokesOlderSessions() {
        var oldSession = service.createAdminSession("admin.root", PASSWORD);
        passwordHasher.upgradeRequired = true;
        var upgradedSession = service.createAdminSession("admin.root", PASSWORD);
        passwordHasher.upgradeRequired = false;

        assertThatThrownBy(() -> service.validateAdminSession(oldSession.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(service.validateAdminSession(upgradedSession.rawToken()).user().id()).isEqualTo(ADMIN);
        assertThat(repository.findCredentialByUserId(ADMIN).orElseThrow().credentialVersion()).isEqualTo(2);
    }

    @Test
    void concurrentCreationAndRevocationCannotLeaveAUsableSession() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AdminAuthenticationService.IssuedAdminSession>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.createAdminSession("admin.root", PASSWORD);
                }));
            }
            start.countDown();
            List<AdminAuthenticationService.IssuedAdminSession> sessions = new ArrayList<>();
            for (var future : futures) sessions.add(future.get());
            assertThat(sessions).extracting(AdminAuthenticationService.IssuedAdminSession::rawToken)
                    .doesNotHaveDuplicates();

            CountDownLatch race = new CountDownLatch(1);
            Future<?> validation = executor.submit(() -> {
                race.await();
                try {
                    service.validateAdminSession(sessions.getFirst().rawToken());
                } catch (UnauthorizedException ignored) {
                    // Revocation may linearize first.
                }
                return null;
            });
            Future<Integer> revocation = executor.submit(() -> {
                race.await();
                return service.revokeAllSessions(ADMIN, "CONCURRENT_REVOKE");
            });
            race.countDown();
            validation.get();
            assertThat(revocation.get()).isEqualTo(8);
            for (var session : sessions) {
                assertThatThrownBy(() -> service.validateAdminSession(session.rawToken()))
                        .isInstanceOf(UnauthorizedException.class);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void argon2idAndTokenPrimitivesUseExpectedFormats() {
        PasswordHasher hasher = new Argon2idPasswordHasher();
        String hash = hasher.encode(PASSWORD);
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(hasher.matches(PASSWORD, hash, Argon2idPasswordHasher.ALGORITHM)).isTrue();
        assertThat(hasher.matches("wrong password value", hash, Argon2idPasswordHasher.ALGORITHM)).isFalse();

        var tokens = new SecureOpaqueSessionTokenCodec();
        String first = tokens.generate();
        String second = tokens.generate();
        assertThat(first).hasSize(43).isNotEqualTo(second);
        assertThat(tokens.hash(first)).hasSize(64).isNotEqualTo(tokens.hash(second));
        assertThatThrownBy(() -> tokens.hash("not-a-valid-token")).isInstanceOf(IllegalArgumentException.class);
    }

    private UserAccount account(AccountStatus status, SystemRole role) {
        return new UserAccount(ADMIN, "管理员", status, role, START, START);
    }

    private static final class MutableClock implements AuthenticationClock {
        private volatile Instant value = START;
        @Override public Instant now() { return value; }
    }

    private static final class FastPasswordHasher implements PasswordHasher {
        private volatile boolean upgradeRequired;
        @Override public String algorithm() { return "ARGON2ID_V1"; }
        @Override public String encode(CharSequence rawPassword) { return "encoded:" + rawPassword; }
        @Override public boolean matches(CharSequence rawPassword, String encodedPassword, String algorithm) {
            return algorithm().equals(algorithm) && encodedPassword.equals(encode(rawPassword));
        }
        @Override public boolean needsUpgrade(String encodedPassword, String algorithm) { return upgradeRequired; }
    }

    private static final class MutableUsers implements UserRepository {
        private final Map<String, UserAccount> values = new ConcurrentHashMap<>();
        void put(UserAccount value) { values.put(value.id(), value); }
        @Override public Optional<UserAccount> findUserById(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<UserAccount> findUsersByIds(List<String> ids) {
            return ids.stream().map(values::get).filter(java.util.Objects::nonNull).toList();
        }
    }

    private static final class InMemoryAuthenticationRepository implements AdminAuthenticationRepository {
        private final MutableUsers users;
        private final Map<String, AdminCredentialRecord> credentials = new ConcurrentHashMap<>();
        private final Map<String, AdminSessionRecord> sessions = new ConcurrentHashMap<>();

        private InMemoryAuthenticationRepository(MutableUsers users) { this.users = users; }

        @Override public Optional<AdminCredentialRecord> findCredentialByUsername(String username) {
            return credentials.values().stream().filter(c -> c.identity().providerSubject().equals(username)).findFirst();
        }
        @Override public Optional<AdminCredentialRecord> findCredentialByUserId(String userId) {
            return credentials.values().stream().filter(c -> c.identity().userId().equals(userId)).findFirst();
        }
        @Override public synchronized void insertCredential(ExternalIdentityRecord identity, String hash,
                                                              String algorithm, int version) {
            if (findCredentialByUsername(identity.providerSubject()).isPresent()
                    || findCredentialByUserId(identity.userId()).isPresent()) throw new IllegalStateException("duplicate");
            credentials.put(identity.id(), new AdminCredentialRecord(identity, hash, algorithm, version,
                    identity.createdAt(), identity.updatedAt()));
        }
        @Override public synchronized int upgradeCredential(String id, int expected, String hash, String algorithm) {
            AdminCredentialRecord old = credentials.get(id);
            if (old == null || old.credentialVersion() != expected) return 0;
            credentials.put(id, new AdminCredentialRecord(old.identity(), hash, algorithm, expected + 1,
                    old.createdAt(), old.updatedAt()));
            return 1;
        }
        @Override public synchronized boolean insertSessionIfEligible(AdminSessionRecord session) {
            if (sessions.containsKey(session.tokenHash())) throw new IllegalStateException("duplicate");
            if (!validForCreation(session)) return false;
            sessions.put(session.tokenHash(), session);
            return true;
        }
        @Override public Optional<AdminSessionRecord> findSessionByTokenHash(String hash) {
            return Optional.ofNullable(sessions.get(hash));
        }
        @Override public synchronized boolean touchIfValidAdmin(String hash, Instant now, Instant proposed) {
            AdminSessionRecord old = sessions.get(hash);
            if (!valid(old, now)) return false;
            Instant idle = proposed.isBefore(old.absoluteExpiresAt()) ? proposed : old.absoluteExpiresAt();
            sessions.put(hash, new AdminSessionRecord(old.id(), old.userId(), old.clientType(), old.tokenHash(),
                    old.credentialVersion(), old.createdAt(), now, idle, old.absoluteExpiresAt(), null, null,
                    old.version() + 1));
            return true;
        }
        @Override public synchronized Optional<ValidatedAdminSession> findValidatedSession(String hash, Instant now) {
            AdminSessionRecord session = sessions.get(hash);
            if (!valid(session, now)) return Optional.empty();
            return Optional.of(new ValidatedAdminSession(session, users.findUserById(session.userId()).orElseThrow()));
        }
        @Override public synchronized boolean revokeByTokenHash(String hash, Instant at, String reason) {
            AdminSessionRecord old = sessions.get(hash);
            if (old == null || old.revokedAt() != null) return false;
            sessions.put(hash, revoked(old, at, reason));
            return true;
        }
        @Override public synchronized int revokeAllByUserId(String userId, Instant at, String reason) {
            int changed = 0;
            for (var entry : new ArrayList<>(sessions.entrySet())) {
                AdminSessionRecord old = entry.getValue();
                if (old.userId().equals(userId) && old.revokedAt() == null) {
                    sessions.put(entry.getKey(), revoked(old, at, reason));
                    changed++;
                }
            }
            return changed;
        }
        synchronized void incrementCredentialVersion(String userId) {
            AdminCredentialRecord old = findCredentialByUserId(userId).orElseThrow();
            credentials.put(old.identity().id(), new AdminCredentialRecord(old.identity(), old.passwordHash(),
                    old.passwordAlgorithm(), old.credentialVersion() + 1, old.createdAt(), old.updatedAt()));
        }
        private boolean valid(AdminSessionRecord session, Instant now) {
            if (session == null || session.revokedAt() != null || !session.idleExpiresAt().isAfter(now)
                    || !session.absoluteExpiresAt().isAfter(now)) return false;
            UserAccount user = users.findUserById(session.userId()).orElse(null);
            AdminCredentialRecord credential = findCredentialByUserId(session.userId()).orElse(null);
            return user != null && user.accountStatus() == AccountStatus.ACTIVE
                    && user.systemRole() == SystemRole.GOVERNANCE_ADMIN && credential != null
                    && credential.credentialVersion() == session.credentialVersion();
        }
        private boolean validForCreation(AdminSessionRecord session) {
            UserAccount user = users.findUserById(session.userId()).orElse(null);
            AdminCredentialRecord credential = findCredentialByUserId(session.userId()).orElse(null);
            return user != null && user.accountStatus() == AccountStatus.ACTIVE
                    && user.systemRole() == SystemRole.GOVERNANCE_ADMIN && credential != null
                    && credential.credentialVersion() == session.credentialVersion();
        }
        private AdminSessionRecord revoked(AdminSessionRecord old, Instant at, String reason) {
            return new AdminSessionRecord(old.id(), old.userId(), AdminSessionClientType.ADMIN_WEB,
                    old.tokenHash(), old.credentialVersion(), old.createdAt(), old.lastSeenAt(),
                    old.idleExpiresAt(), old.absoluteExpiresAt(), at, reason, old.version() + 1);
        }
    }
}
