package org.animallink.identity.application;

import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.AdminBootstrapRejectedException;
import org.animallink.identity.domain.AuthenticationSecurityRepository;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationActorType;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditOutcome;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditType;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UserAccount;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Profile("admin-bootstrap")
public class AdminBootstrapTransaction {
    private final AuthenticationSecurityRepository securityRepository;
    private final AdminAuthenticationService authenticationService;
    private final AuthenticationClock clock;

    public AdminBootstrapTransaction(AuthenticationSecurityRepository securityRepository,
                                     AdminAuthenticationService authenticationService,
                                     AuthenticationClock clock) {
        this.securityRepository = securityRepository;
        this.authenticationService = authenticationService;
        this.clock = clock;
    }

    @Transactional
    public UserAccount initialize(String userId, String displayName, String normalizedUsername,
                                  CharSequence rawPassword, String operatorReference) {
        var state = securityRepository.lockBootstrapState();
        if (!"OPEN".equals(state.status())) {
            throw new AdminBootstrapRejectedException("BOOTSTRAP_ALREADY_COMPLETED");
        }
        if (securityRepository.countGovernanceAdministrators() != 0) {
            throw new AdminBootstrapRejectedException("EXISTING_GOVERNANCE_ADMIN");
        }

        Instant now = clock.now();
        var user = new UserAccount(userId, displayName, AccountStatus.ACTIVE,
                SystemRole.GOVERNANCE_ADMIN, now, now);
        securityRepository.insertGovernanceAdministrator(user);
        authenticationService.provisionAdminCredential(userId, normalizedUsername, rawPassword);
        securityRepository.insertAuditEvent(AdminLoginProtectionService.event(
                AuthenticationAuditType.ADMIN_INITIALIZATION_SUCCESS, AuthenticationAuditOutcome.SUCCESS,
                userId, normalizedUsername, AuthenticationActorType.LOCAL_OPERATOR,
                AuthenticationAuditRecorder.sanitizeOperator(operatorReference), null,
                "FIRST_ADMIN_CREATED", null, now));
        if (!securityRepository.completeBootstrap(userId, now, state.version())) {
            throw new IllegalStateException("Administrator bootstrap state changed concurrently");
        }
        return user;
    }
}
