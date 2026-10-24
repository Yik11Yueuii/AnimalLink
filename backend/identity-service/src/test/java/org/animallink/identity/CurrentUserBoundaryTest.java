package org.animallink.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserRepository;
import org.animallink.identity.infrastructure.DevelopmentHeaderCurrentUserProvider;
import org.animallink.identity.infrastructure.ProductionCurrentUserProvider;
import org.animallink.identity.application.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class CurrentUserBoundaryTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";

    @Test
    void productionProviderNeverTrustsDevelopmentHeader() {
        assertThrows(UnauthorizedException.class, () -> new ProductionCurrentUserProvider().requireCurrentUser());
    }

    @Test
    void productionProfileActuallySelectsFailClosedProvider() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(ProductionCurrentUserProvider.class, DevelopmentHeaderCurrentUserProvider.class)
                .run(context -> {
                    assertInstanceOf(ProductionCurrentUserProvider.class, context.getBean(CurrentUserProvider.class));
                    assertThrows(UnauthorizedException.class, () -> context.getBean(CurrentUserProvider.class).requireCurrentUser());
                });
    }

    @Test
    void explicitTestProfileResolvesOnlyActiveExistingUser() {
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("server.address", "127.0.0.1");
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", ADMIN);
        var now = Instant.now();
        var active = new UserAccount(ADMIN, "admin", AccountStatus.ACTIVE, SystemRole.GOVERNANCE_ADMIN, now, now);
        var suspended = new UserAccount(ADMIN, "admin", AccountStatus.SUSPENDED, SystemRole.GOVERNANCE_ADMIN, now, now);
        var provider = new DevelopmentHeaderCurrentUserProvider(request, repository(active), env);
        assertEquals(ADMIN, provider.requireCurrentUser().id());
        var suspendedProvider = new DevelopmentHeaderCurrentUserProvider(request, repository(suspended), env);
        assertThrows(UnauthorizedException.class, suspendedProvider::requireCurrentUser);
    }

    @Test
    void mixedProductionProfileOrNonLoopbackDevListenerFailsClosed() {
        var env = new MockEnvironment();
        env.setActiveProfiles("local", "prod");
        env.setProperty("server.address", "127.0.0.1");
        var request = new MockHttpServletRequest();
        var repository = repository(null);
        assertThrows(IllegalStateException.class, () -> new DevelopmentHeaderCurrentUserProvider(request, repository, env));
        env.setActiveProfiles("dev");
        env.setProperty("server.address", "0.0.0.0");
        assertThrows(IllegalStateException.class, () -> new DevelopmentHeaderCurrentUserProvider(request, repository, env));
        env.setActiveProfiles("test");
        assertThrows(IllegalStateException.class, () -> new DevelopmentHeaderCurrentUserProvider(request, repository, env));
    }

    private UserRepository repository(UserAccount account) {
        return new UserRepository() {
            public Optional<UserAccount> findUserById(String id) { return Optional.ofNullable(account); }
            public List<UserAccount> findUsersByIds(List<String> ids) { return List.of(); }
        };
    }
}
