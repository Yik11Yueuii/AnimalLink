package org.animallink.identity.infrastructure;

import org.animallink.identity.application.CredentialPrecheckClient;
import org.animallink.identity.application.CredentialPrecheckClientException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
public class HttpCredentialPrecheckClient implements CredentialPrecheckClient {
    private final RestClient client;

    public HttpCredentialPrecheckClient(@Qualifier("intelligenceRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public CredentialPrecheckResult precheck(CredentialPrecheckRequest request) {
        try {
            Response body = client.post().uri("/internal/v1/credential-prechecks")
                    .header("X-Internal-Service", "identity-service")
                    .header("Idempotency-Key", request.attemptId())
                    .header("X-Trace-Id", traceId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(request.attemptId(), request.verificationId(), request.applicantUserId(), request.campusId(),
                            request.campusName(), request.applicantName(), request.requestedMembershipType(), request.expectedGraduationDate()))
                    .retrieve().body(Response.class);
            if (body == null) throw new CredentialPrecheckClientException("INTELLIGENCE_CONTRACT_ERROR");
            return new CredentialPrecheckResult(body.taskId(), body.status(), body.provider(), body.modelName(),
                    body.overallConfidence(), body.extractedCampusName(), body.extractedApplicantName(), body.credentialType(),
                    body.consistencyFlags(), body.summary(), body.errorCategory());
        } catch (CredentialPrecheckClientException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new CredentialPrecheckClientException(timeout(exception) ? "INTELLIGENCE_TIMEOUT" : "INTELLIGENCE_UNAVAILABLE", exception);
        } catch (RestClientResponseException exception) {
            throw new CredentialPrecheckClientException("INTELLIGENCE_CONTRACT_ERROR", exception);
        } catch (RuntimeException exception) {
            throw new CredentialPrecheckClientException("INTELLIGENCE_UNAVAILABLE", exception);
        }
    }

    private static String traceId() {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
    }

    private static boolean timeout(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    private record Request(String attemptId, String verificationId, String applicantUserId, String campusId,
                           String campusName, String applicantName, String requestedMembershipType,
                           String expectedGraduationDate) {
        private Request(String attemptId, String verificationId, String applicantUserId, String campusId,
                        String campusName, String applicantName, String requestedMembershipType,
                        LocalDate expectedGraduationDate) {
            this(attemptId, verificationId, applicantUserId, campusId, campusName, applicantName,
                    requestedMembershipType, expectedGraduationDate == null ? null : expectedGraduationDate.toString());
        }
    }
    private record Response(String taskId, String status, String provider, String modelName,
                            java.math.BigDecimal overallConfidence, String extractedCampusName,
                            String extractedApplicantName, String credentialType, List<String> consistencyFlags,
                            String summary, String errorCategory) { }
}
