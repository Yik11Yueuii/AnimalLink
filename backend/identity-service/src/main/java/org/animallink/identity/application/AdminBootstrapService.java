package org.animallink.identity.application;

import org.animallink.identity.domain.AdminBootstrapRejectedException;
import org.animallink.identity.domain.AuthenticationSecurityRepository.AuthenticationAuditOutcome;
import org.animallink.identity.domain.UserAccount;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

@Service
@Profile("admin-bootstrap")
public class AdminBootstrapService {
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final AdminBootstrapTransaction transaction;
    private final AuthenticationAuditRecorder auditRecorder;
    private final ApplicationContext applicationContext;

    public AdminBootstrapService(AdminBootstrapTransaction transaction,
                                 AuthenticationAuditRecorder auditRecorder,
                                 ApplicationContext applicationContext) {
        this.transaction = transaction;
        this.auditRecorder = auditRecorder;
        this.applicationContext = applicationContext;
    }

    /**
     * Local operations entry point. No controller is allowed to invoke this service.
     */
    public UserAccount initialize(LocalBootstrapAuthorization authorization, String displayName,
                                  String username, CharSequence rawPassword) {
        String operator = authorization == null ? null : authorization.operatorReference();
        if (applicationContext instanceof WebApplicationContext) {
            auditRecorder.recordBootstrapRejection(username, operator,
                    AuthenticationAuditOutcome.DENIED, "NON_LOCAL_RUNTIME");
            throw new AdminBootstrapRejectedException("NON_LOCAL_RUNTIME");
        }
        if (authorization == null || !authorization.interactivelyConfirmed()) {
            auditRecorder.recordBootstrapRejection(username, operator,
                    AuthenticationAuditOutcome.DENIED, "LOCAL_AUTHORIZATION_REQUIRED");
            throw new AdminBootstrapRejectedException("LOCAL_AUTHORIZATION_REQUIRED");
        }

        try {
            String normalizedDisplayName = requireDisplayName(displayName);
            String normalizedUsername = AuthenticationSecurityIdentifiers.requireUsername(username);
            requirePassword(rawPassword);
            return transaction.initialize(UUID.randomUUID().toString(), normalizedDisplayName,
                    normalizedUsername, rawPassword, operator);
        } catch (AdminBootstrapRejectedException rejected) {
            auditRecorder.recordBootstrapRejection(username, operator,
                    AuthenticationAuditOutcome.DENIED, rejected.reasonCode());
            throw rejected;
        } catch (IllegalArgumentException invalid) {
            auditRecorder.recordBootstrapRejection(username, operator,
                    AuthenticationAuditOutcome.DENIED, "INVALID_BOOTSTRAP_INPUT");
            throw invalid;
        } catch (RuntimeException failure) {
            auditRecorder.recordBootstrapRejection(username, operator,
                    AuthenticationAuditOutcome.FAILURE, "BOOTSTRAP_TRANSACTION_FAILED");
            throw failure;
        }
    }

    private static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty() || displayName.trim().length() > 80) {
            throw new IllegalArgumentException("Display name must contain 1-80 characters");
        }
        return displayName.trim();
    }

    private static void requirePassword(CharSequence password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must contain 12-128 characters");
        }
    }

    public record LocalBootstrapAuthorization(String operatorReference, boolean interactivelyConfirmed) {
        public static LocalBootstrapAuthorization confirmedInteractive(String operatorReference) {
            return new LocalBootstrapAuthorization(operatorReference, true);
        }
    }
}
