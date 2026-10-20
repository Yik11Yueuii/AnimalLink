package org.animallink.identity.application;

import org.animallink.identity.domain.AuthenticationSecurityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class AuthenticationSecurityMaintenanceService {
    private final AuthenticationSecurityRepository repository;
    private final AuthenticationClock clock;
    private final Duration auditRetention;
    private final Duration attemptStateRetention;
    private final int batchSize;

    public AuthenticationSecurityMaintenanceService(
            AuthenticationSecurityRepository repository,
            AuthenticationClock clock,
            @Value("${animallink.authentication.retention.audit:P180D}") Duration auditRetention,
            @Value("${animallink.authentication.retention.login-attempt-state:P1D}")
            Duration attemptStateRetention,
            @Value("${animallink.authentication.retention.cleanup-batch-size:1000}") int batchSize) {
        if (auditRetention.isNegative() || auditRetention.isZero()
                || attemptStateRetention.isNegative() || attemptStateRetention.isZero()
                || batchSize < 1 || batchSize > 10000) {
            throw new IllegalArgumentException("Authentication retention configuration is invalid");
        }
        this.repository = repository;
        this.clock = clock;
        this.auditRetention = auditRetention;
        this.attemptStateRetention = attemptStateRetention;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${animallink.authentication.retention.initial-delay-ms:3600000}",
            fixedDelayString = "${animallink.authentication.retention.fixed-delay-ms:3600000}")
    @Transactional
    public void purgeExpiredSecurityRecords() {
        var now = clock.now();
        repository.deleteLoginAttemptStatesBefore(now.minus(attemptStateRetention), batchSize);
        repository.deleteAuditEventsBefore(now.minus(auditRetention), batchSize);
    }
}
