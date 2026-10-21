package org.animallink.identity.operations;

import org.animallink.identity.application.AdminAuthenticationService;
import org.animallink.identity.application.AdminBootstrapService;
import org.animallink.identity.application.AdminBootstrapTransaction;
import org.animallink.identity.application.AdminLoginAttemptTransaction;
import org.animallink.identity.application.AdminLoginProtectionService;
import org.animallink.identity.application.AdminSessionIssuer;
import org.animallink.identity.application.AuthenticationAuditRecorder;
import org.animallink.identity.infrastructure.Argon2idPasswordHasher;
import org.animallink.identity.infrastructure.JdbcAdminAuthenticationRepository;
import org.animallink.identity.infrastructure.JdbcAuthenticationSecurityRepository;
import org.animallink.identity.infrastructure.JdbcIdentityRepository;
import org.animallink.identity.infrastructure.SecureOpaqueSessionTokenCodec;
import org.animallink.identity.infrastructure.SystemAuthenticationClock;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Minimal non-web application context for the local first-administrator operation.
 *
 * <p>The explicit import list is intentional: bootstrap needs relational persistence,
 * password hashing, audit, and transaction support, but must not scan the normal
 * Identity application or instantiate HTTP, object-storage, discovery, remote-client,
 * or scheduled-task components.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({
        AdminBootstrapService.class,
        AdminBootstrapTransaction.class,
        AdminAuthenticationService.class,
        AdminLoginAttemptTransaction.class,
        AdminLoginProtectionService.class,
        AdminSessionIssuer.class,
        AuthenticationAuditRecorder.class,
        Argon2idPasswordHasher.class,
        JdbcAdminAuthenticationRepository.class,
        JdbcAuthenticationSecurityRepository.class,
        JdbcIdentityRepository.class,
        SecureOpaqueSessionTokenCodec.class,
        SystemAuthenticationClock.class
})
public class AdminBootstrapApplication {
}
