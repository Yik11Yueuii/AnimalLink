package org.animallink.identity;

import org.animallink.identity.application.CredentialPrecheckClient;
import org.animallink.identity.application.CredentialPrecheckClientException;
import org.animallink.identity.infrastructure.HttpCredentialPrecheckClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.slf4j.MDC;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpCredentialPrecheckClientTest {
    @Test
    void postsExactInternalContractWithAttemptIdAsStableIdempotencyKey() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://intelligence.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://intelligence.test/internal/v1/credential-prechecks"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Service", "identity-service"))
                .andExpect(header("Idempotency-Key", "84000000-0000-0000-0000-000000000001"))
                .andExpect(header("X-Trace-Id", "trace-contract"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"attemptId":"84000000-0000-0000-0000-000000000001","verificationId":"84000000-0000-0000-0000-000000000002","applicantUserId":"84000000-0000-0000-0000-000000000003","campusId":"84000000-0000-0000-0000-000000000004","campusName":"测试大学","applicantName":"申请人","requestedMembershipType":"STUDENT","expectedGraduationDate":"2027-06-30"}
                        """))
                .andRespond(withSuccess("""
                        {"taskId":"84000000-0000-0000-0000-000000000009","status":"PASSED","provider":"mock","modelName":"local","overallConfidence":0.95,"extractedCampusName":"测试大学","extractedApplicantName":"申请人","credentialType":"CAMPUS_CREDENTIAL","consistencyFlags":[],"summary":"safe"}
                        """, MediaType.APPLICATION_JSON));

        MDC.put("traceId", "trace-contract");
        try {
            CredentialPrecheckClient.CredentialPrecheckResult result = new HttpCredentialPrecheckClient(builder.build()).precheck(request());
            assertThat(result.status()).isEqualTo("PASSED");
            assertThat(result.taskId()).isEqualTo("84000000-0000-0000-0000-000000000009");
            server.verify();
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void httpFailuresBecomeSafeCategoriesWithoutReturningResponseBody() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://intelligence.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://intelligence.test/internal/v1/credential-prechecks"))
                .andRespond(withServerError().body("sensitive provider output"));

        CredentialPrecheckClientException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> new HttpCredentialPrecheckClient(builder.build()).precheck(request()), CredentialPrecheckClientException.class);
        assertThat(exception.errorCategory()).isEqualTo("INTELLIGENCE_CONTRACT_ERROR");
    }

    private CredentialPrecheckClient.CredentialPrecheckRequest request() {
        return new CredentialPrecheckClient.CredentialPrecheckRequest(
                "84000000-0000-0000-0000-000000000001", "84000000-0000-0000-0000-000000000002",
                "84000000-0000-0000-0000-000000000003", "84000000-0000-0000-0000-000000000004",
                "测试大学", "申请人", "STUDENT", LocalDate.of(2027, 6, 30));
    }
}
