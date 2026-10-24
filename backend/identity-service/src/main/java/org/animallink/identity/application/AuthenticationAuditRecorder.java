package org.animallink.identity.application;

import org.animallink.identity.domain.AuthenticationSecurityRepository;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationActorType;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditOutcome;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationAuditRecorder {
    private final AuthenticationSecurityRepository repository;
    private final AuthenticationClock clock;

    public AuthenticationAuditRecorder(AuthenticationSecurityRepository repository, AuthenticationClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBootstrapRejection(String username, String operatorReference,
                                         AuthenticationAuditOutcome outcome, String reasonCode) {
        repository.insertAuditEvent(AdminLoginProtectionService.event(
                AuthenticationAuditType.ADMIN_INITIALIZATION_DENIED, outcome, null,
                AuthenticationSecurityIdentifiers.normalizeUsername(username),
                AuthenticationActorType.LOCAL_OPERATOR, sanitizeOperator(operatorReference),
                null, reasonCode, null, clock.now()));
    }

    static String sanitizeOperator(String operatorReference) {
        if (operatorReference == null || operatorReference.isBlank()) return "unknown-local-operator";
        String normalized = operatorReference.trim();
        if (normalized.length() > 128) normalized = normalized.substring(0, 128);
        return normalized.replaceAll("[^A-Za-z0-9._:@\\\\-]", "_");
    }
}
