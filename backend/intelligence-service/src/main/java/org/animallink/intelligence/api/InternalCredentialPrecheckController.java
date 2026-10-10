package org.animallink.intelligence.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.animallink.intelligence.application.CredentialPrecheckCommand;
import org.animallink.intelligence.application.CredentialPrecheckExecution;
import org.animallink.intelligence.application.CredentialPrecheckService;
import org.animallink.intelligence.domain.ApiExceptions.Forbidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/internal/v1/credential-prechecks")
public class InternalCredentialPrecheckController {
    private final CredentialPrecheckService service;

    public InternalCredentialPrecheckController(CredentialPrecheckService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<?> precheck(
            @RequestHeader(name = "X-Internal-Service", required = false) String caller,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CredentialPrecheckRequest request) {
        if (!"identity-service".equals(caller)) {
            throw new Forbidden("internal credential precheck requires identity-service");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        CredentialPrecheckExecution result = service.precheck(idempotencyKey,
                new CredentialPrecheckCommand(request.attemptId(), request.verificationId(),
                        request.applicantUserId(), request.campusId(), request.campusName(),
                        request.applicantName(), request.requestedMembershipType(),
                        request.expectedGraduationDate()));
        return result.created() ? ResponseEntity.status(201).body(result.response())
                : ResponseEntity.ok(result.response());
    }

    record CredentialPrecheckRequest(
            @NotBlank String attemptId,
            @NotBlank String verificationId,
            @NotBlank String applicantUserId,
            @NotBlank String campusId,
            @NotBlank String campusName,
            @NotBlank String applicantName,
            @NotBlank String requestedMembershipType,
            LocalDate expectedGraduationDate) {
    }
}
