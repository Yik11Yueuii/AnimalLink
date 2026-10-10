package org.animallink.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.application.CredentialMaterialGateway;
import org.animallink.intelligence.application.CredentialMaterialUnavailable;
import org.animallink.intelligence.application.CredentialPrecheckProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CampusCredentialPrecheckIntegrationTest {
    private static final String ATTEMPT_ID = "70000000-0000-0000-0000-000000000001";
    private static final String VERIFICATION_ID = "70000000-0000-0000-0000-000000000002";
    private static final String USER_ID = "70000000-0000-0000-0000-000000000003";
    private static final String CAMPUS_ID = "70000000-0000-0000-0000-000000000004";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_intelligence_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @MockBean CredentialMaterialGateway credentialMaterial;
    @MockBean CredentialPrecheckProvider provider;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ai_confirmation");
        jdbc.update("DELETE FROM ai_result");
        jdbc.update("DELETE FROM ai_task_media");
        jdbc.update("DELETE FROM ai_task");
        when(credentialMaterial.load(VERIFICATION_ID)).thenReturn(
                new CredentialMaterialGateway.CredentialMaterial("credential-bytes".getBytes(), "image/png"));
        when(provider.providerName()).thenReturn("test-provider");
        when(provider.modelName()).thenReturn("test-model");
        when(provider.precheck(any())).thenReturn(highMatch());
    }

    @Test
    void identityOnlyIdempotentPrecheckPersistsNormalizedResultWithoutCredentialBytes() throws Exception {
        String first = invoke("key-1", request()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.provider").value("test-provider"))
                .andExpect(jsonPath("$.rawResponse").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String taskId = objectMapper.readTree(first).path("taskId").asText();

        invoke("key-1", request()).andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("PASSED"));
        verify(provider, times(1)).precheck(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE task_type = 'CAMPUS_CREDENTIAL_PRECHECK'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT raw_response FROM ai_result WHERE task_id = ?", String.class, taskId))
                .isNull();
        String summary = jdbc.queryForObject("SELECT input_summary FROM ai_task WHERE id = ?", String.class, taskId);
        String result = jdbc.queryForObject("SELECT structured_json FROM ai_result WHERE task_id = ?", String.class, taskId);
        assertThat(summary).contains(ATTEMPT_ID, VERIFICATION_ID).doesNotContain("李 同学").doesNotContain("示例大学");
        assertThat(result).doesNotContain("credential-bytes").doesNotContain("objectKey").doesNotContain("bucket");
    }

    @Test
    void rejectsMissingOrWrongInternalCallerAndMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/internal/v1/credential-prechecks").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/v1/credential-prechecks").header("X-Internal-Service", "animal-service")
                        .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/v1/credential-prechecks").header("X-Internal-Service", "identity-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(provider, credentialMaterial);
    }

    @Test
    void conflictingReuseOfIdempotencyKeyIsRejected() throws Exception {
        invoke("same-key", request()).andExpect(status().isCreated());
        Map<String, Object> different = request();
        different.put("applicantName", "另一位同学");
        invoke("same-key", different).andExpect(status().isConflict());
        verify(provider, times(1)).precheck(any());
    }

    @Test
    void lowConfidenceMismatchAndFlagsRequireManualReview() throws Exception {
        when(provider.precheck(any())).thenReturn(new CredentialPrecheckProvider.ProviderResult(
                new BigDecimal("0.84"), "其他学校", "另一位同学", "CAMPUS_CREDENTIAL", List.of("NAME_MISMATCH")));
        invoke("manual-key", request()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MANUAL_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.consistencyFlags[0]").value("NAME_MISMATCH"));
    }

    @Test
    void unavailableAndMalformedProviderResultsBecomePersistedUnavailableAdvisories() throws Exception {
        when(credentialMaterial.load(VERIFICATION_ID)).thenThrow(new CredentialMaterialUnavailable(
                "IDENTITY_CREDENTIAL_GATEWAY_UNAVAILABLE", "unavailable"));
        invoke("unavailable-key", request()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.errorCategory").value("IDENTITY_CREDENTIAL_GATEWAY_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task", String.class)).isEqualTo("SUCCEEDED");

        jdbc.update("DELETE FROM ai_result");
        jdbc.update("DELETE FROM ai_task");
        reset(credentialMaterial, provider);
        when(provider.providerName()).thenReturn("test-provider");
        when(provider.modelName()).thenReturn("test-model");
        when(credentialMaterial.load(VERIFICATION_ID)).thenReturn(
                new CredentialMaterialGateway.CredentialMaterial("credential-bytes".getBytes(), "image/png"));
        when(provider.precheck(any())).thenReturn(new CredentialPrecheckProvider.ProviderResult(
                null, null, null, null, null));
        invoke("malformed-key", request()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.errorCategory").value("MALFORMED_CREDENTIAL_PRECHECK_RESULT"));
    }

    @Test
    void unsupportedCredentialMediaRequiresManualReviewAndReplaysWithoutProviderExecution() throws Exception {
        when(credentialMaterial.load(VERIFICATION_ID)).thenThrow(new CredentialMaterialUnavailable(
                "UNSUPPORTED_CREDENTIAL_MEDIA", "unsupported credential media"));

        String first = invoke("unsupported-media-key", request()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MANUAL_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.errorCategory").value("UNSUPPORTED_CREDENTIAL_MEDIA"))
                .andReturn().getResponse().getContentAsString();
        String taskId = objectMapper.readTree(first).path("taskId").asText();

        invoke("unsupported-media-key", request()).andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("MANUAL_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.errorCategory").value("UNSUPPORTED_CREDENTIAL_MEDIA"));

        verify(credentialMaterial, times(1)).load(VERIFICATION_ID);
        verify(provider, never()).precheck(any());
        assertThat(jdbc.queryForObject("SELECT raw_response FROM ai_result WHERE task_id = ?", String.class, taskId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE idempotency_key = ?", Integer.class,
                "unsupported-media-key")).isEqualTo(1);
    }

    @Test
    void malformedRequestIsRejectedBeforeCredentialAccess() throws Exception {
        Map<String, Object> invalid = request();
        invalid.remove("verificationId");
        invoke("bad-request", invalid).andExpect(status().isBadRequest());
        verifyNoInteractions(provider, credentialMaterial);
    }

    private org.springframework.test.web.servlet.ResultActions invoke(String key, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/internal/v1/credential-prechecks")
                .header("X-Internal-Service", "identity-service")
                .header("Idempotency-Key", key)
                .header("X-Trace-Id", "credential-test-trace")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> request() {
        return new java.util.LinkedHashMap<>(Map.of(
                "attemptId", ATTEMPT_ID,
                "verificationId", VERIFICATION_ID,
                "applicantUserId", USER_ID,
                "campusId", CAMPUS_ID,
                "campusName", "示例大学",
                "applicantName", "李 同学",
                "requestedMembershipType", "STUDENT",
                "expectedGraduationDate", LocalDate.of(2027, 6, 30).toString()));
    }

    private CredentialPrecheckProvider.ProviderResult highMatch() {
        return new CredentialPrecheckProvider.ProviderResult(new BigDecimal("0.95"), "示例大学",
                "李 同学", "CAMPUS_CREDENTIAL", List.of());
    }
}
