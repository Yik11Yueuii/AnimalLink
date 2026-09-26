package org.animallink.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.application.IdentityGateway;
import org.animallink.intelligence.application.MediaObjectGateway;
import org.animallink.intelligence.application.MultimodalModelClient;
import org.animallink.intelligence.domain.AnimalObservationDraft;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.animallink.intelligence.domain.ObservationSex;
import org.animallink.intelligence.domain.ObservationSpecies;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class Phase2AMultimodalParsingIntegrationTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000101";
    private static final String OTHER_USER_ID = "00000000-0000-0000-0000-000000000102";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000101";
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");

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
    @MockBean IdentityGateway identityGateway;
    @MockBean MediaObjectGateway mediaGateway;
    @MockBean MultimodalModelClient modelClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM matching_candidate");
        jdbc.update("DELETE FROM matching_record");
        jdbc.update("DELETE FROM animal_embedding");
        jdbc.update("DELETE FROM ai_confirmation");
        jdbc.update("DELETE FROM ai_result");
        jdbc.update("DELETE FROM ai_task_media");
        jdbc.update("DELETE FROM ai_task");
        when(identityGateway.requireCurrentUser()).thenReturn(user(USER_ID));
        when(identityGateway.campusMembership(USER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(true, "STUDENT", "ACTIVE"));
        when(mediaGateway.loadAll(anyList())).thenReturn(List.of(new MediaObjectGateway.MediaInput(
                "ai-input/campus-cat.png", "image/png", "png".getBytes(StandardCharsets.UTF_8))));
        when(modelClient.providerName()).thenReturn("test-provider");
        when(modelClient.modelName()).thenReturn("test-model");
        when(modelClient.analyze(any())).thenReturn(new MultimodalModelClient.ModelResponse(
                draftJson(draft("教学楼东侧", OCCURRED_AT, ObservationSpecies.CAT, "橘白", false))));
    }

    @Test
    void validParsePersistsSucceededTaskAndOriginalResult() throws Exception {
        String response = performParse(parseRequest(false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.modelProvider").value("test-provider"))
                .andExpect(jsonPath("$.promptVersion").value("animal-observation-v1"))
                .andExpect(jsonPath("$.originalDraft.species").value("CAT"))
                .andExpect(jsonPath("$.originalDraft.locationDescription").value("教学楼东侧"))
                .andReturn().getResponse().getContentAsString();
        String taskId = objectMapper.readTree(response).get("taskId").asText();

        assertThat(jdbc.queryForObject("SELECT status FROM ai_task WHERE id = ?", String.class, taskId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT started_at IS NOT NULL AND completed_at IS NOT NULL FROM ai_task WHERE id = ?", Boolean.class, taskId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task_media WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
        String inputSummary = jdbc.queryForObject("SELECT input_summary FROM ai_task WHERE id = ?", String.class, taskId);
        assertThat(inputSummary).doesNotContain("教学楼").doesNotContain("base64");
    }

    @Test
    void unknownAndOptionalFieldsArePreservedWithoutGuessing() throws Exception {
        AnimalObservationDraft unknown = draft(null, null, ObservationSpecies.UNKNOWN, null, false);
        when(modelClient.analyze(any())).thenReturn(new MultimodalModelClient.ModelResponse(draftJson(unknown)));
        performParse(parseRequest(true)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalDraft.species").value("UNKNOWN"))
                .andExpect(jsonPath("$.originalDraft.coatColor").doesNotExist())
                .andExpect(jsonPath("$.originalDraft.locationDescription").doesNotExist())
                .andExpect(jsonPath("$.originalDraft.unknownFields[0]").value("species"));
    }

    @Test
    void mediaValidationErrorsDoNotCreateTask() throws Exception {
        when(mediaGateway.loadAll(anyList())).thenThrow(new MediaNotFound("媒体对象不存在"));
        performParse(parseRequest(false)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task", Integer.class)).isZero();

        doThrow(new UnsupportedMedia("不支持的媒体类型")).when(mediaGateway).loadAll(anyList());
        performParse(parseRequest(false)).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA"));
    }

    @Test
    void providerTimeoutAndUnavailableMarkTaskFailed() throws Exception {
        when(modelClient.analyze(any())).thenThrow(new ProviderTimeout("模型服务响应超时", null));
        performParse(parseRequest(false)).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("PROVIDER_TIMEOUT"));
        assertFailed("PROVIDER_TIMEOUT");

        jdbc.update("DELETE FROM ai_task_media");
        jdbc.update("DELETE FROM ai_task");
        doThrow(new ProviderUnavailable("模型服务不可用")).when(modelClient).analyze(any());
        performParse(parseRequest(false)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        assertFailed("PROVIDER_UNAVAILABLE");
    }

    @Test
    void invalidJsonSchemaAndEmptyResponsesMarkTaskFailed() throws Exception {
        for (String raw : List.of("```json\n{}\n```", "{}", "")) {
            jdbc.update("DELETE FROM ai_task_media");
            jdbc.update("DELETE FROM ai_task");
            when(modelClient.analyze(any())).thenReturn(new MultimodalModelClient.ModelResponse(raw));
            performParse(parseRequest(false)).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("INVALID_MODEL_RESPONSE"));
            assertFailed("INVALID_MODEL_RESPONSE");
        }
    }

    @Test
    void ownerCanReadButOtherUserSeesNotFound() throws Exception {
        String taskId = createTask();
        mockMvc.perform(get("/api/v1/ai/tasks/{taskId}", taskId).header("X-User-Id", USER_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(taskId));

        when(identityGateway.requireCurrentUser()).thenReturn(user(OTHER_USER_ID));
        mockMvc.perform(get("/api/v1/ai/tasks/{taskId}", taskId).header("X-User-Id", OTHER_USER_ID))
                .andExpect(status().isNotFound());

        String confirmBody = objectMapper.writeValueAsString(Map.of("draft",
                draft("教学楼东侧", OCCURRED_AT, ObservationSpecies.CAT, "橘白", false)));
        mockMvc.perform(post("/api/v1/ai/tasks/{taskId}/confirm", taskId)
                        .header("X-User-Id", OTHER_USER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/ai/tasks/{taskId}", "00000000-0000-0000-0000-000000000404")
                        .header("X-User-Id", OTHER_USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirmationKeepsOriginalAndStoresEditedVersionOnce() throws Exception {
        String taskId = createTask();
        AnimalObservationDraft edited = draft("教学楼东侧", OCCURRED_AT,
                ObservationSpecies.CAT, "橘白，左耳有缺口", true);
        String body = objectMapper.writeValueAsString(Map.of("draft", edited));
        mockMvc.perform(post("/api/v1/ai/tasks/{taskId}/confirm", taskId)
                        .header("X-User-Id", USER_ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalDraft.possibleAbnormality").value(false))
                .andExpect(jsonPath("$.confirmedDraft.possibleAbnormality").value(true))
                .andExpect(jsonPath("$.wasModified").value(true));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE task_id = ?", Integer.class, taskId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_confirmation WHERE task_id = ?", Integer.class, taskId)).isEqualTo(1);
        String original = jdbc.queryForObject("SELECT structured_json FROM ai_result WHERE task_id = ?", String.class, taskId);
        assertThat(original).contains("未见明显异常").doesNotContain("左耳有缺口");

        mockMvc.perform(post("/api/v1/ai/tasks/{taskId}/confirm", taskId)
                        .header("X-User-Id", USER_ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void failedTaskCannotBeConfirmed() throws Exception {
        when(modelClient.analyze(any())).thenReturn(new MultimodalModelClient.ModelResponse(""));
        String response = performParse(parseRequest(false)).andExpect(status().isBadGateway())
                .andReturn().getResponse().getContentAsString();
        String taskId = jdbc.queryForObject("SELECT id FROM ai_task", String.class);
        String body = objectMapper.writeValueAsString(Map.of("draft",
                draft("教学楼东侧", OCCURRED_AT, ObservationSpecies.CAT, "橘白", false)));
        mockMvc.perform(post("/api/v1/ai/tasks/{taskId}/confirm", taskId)
                        .header("X-User-Id", USER_ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertThat(response).contains("INVALID_MODEL_RESPONSE");
    }

    @Test
    void membershipAndClientOverrideBoundariesAreEnforced() throws Exception {
        when(identityGateway.campusMembership(USER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(false, null, null));
        performParse(parseRequest(false)).andExpect(status().isForbidden());
        verifyNoInteractions(mediaGateway);

        when(identityGateway.campusMembership(USER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(true, "STUDENT", "ACTIVE"));
        String override = parseRequest(false).replaceFirst("\\{", "{\\\"modelName\\\":\\\"forged\\\",\\\"status\\\":\\\"SUCCEEDED\\\",");
        performParse(override).andExpect(status().isBadRequest());
    }

    @Test
    void intelligenceSchemaDoesNotContainOutOfScopeBusinessTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()
                """, String.class).stream().map(String::toLowerCase).toList();
        assertThat(tables).contains("ai_task", "ai_result", "ai_confirmation", "ai_task_media",
                "animal_embedding", "matching_record", "matching_candidate");
        assertThat(tables).doesNotContain("animal", "post", "event", "case", "candidate_match", "outbox_event");
    }

    private org.springframework.test.web.servlet.ResultActions performParse(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/ai/animal-observation/parse")
                .header("X-User-Id", USER_ID).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String createTask() throws Exception {
        String response = performParse(parseRequest(false)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("taskId").asText();
    }

    private String parseRequest(boolean optionalMissing) throws Exception {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("campusId", CAMPUS_ID);
        value.put("mediaObjectKeys", List.of("ai-input/campus-cat.png"));
        value.put("text", optionalMissing ? "无法判断" : "看到一只橘白猫");
        if (!optionalMissing) {
            value.put("locationDescription", "教学楼东侧");
            value.put("occurredAt", OCCURRED_AT.toString());
        }
        return objectMapper.writeValueAsString(value);
    }

    private AnimalObservationDraft draft(String location, Instant occurredAt, ObservationSpecies species,
                                         String coatColor, boolean abnormal) {
        return new AnimalObservationDraft(species, ObservationSex.UNKNOWN, coatColor,
                coatColor == null ? List.of() : List.of("尾部有深色环纹"),
                abnormal ? "疑似活动受限" : "未见明显异常", null, 1, abnormal,
                abnormal ? List.of("疑似活动受限") : List.of(), location, occurredAt,
                species == ObservationSpecies.UNKNOWN ? 0.2 : 0.88,
                Map.of("species", species == ObservationSpecies.UNKNOWN ? 0.2 : 0.93, "sex", 0.1),
                List.of("AI 结果仅为可编辑草稿，需由用户确认"),
                species == ObservationSpecies.UNKNOWN ? List.of("species", "sex", "coatColor") : List.of("sex"));
    }

    private String draftJson(AnimalObservationDraft value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private IdentityGateway.CurrentUser user(String id) {
        return new IdentityGateway.CurrentUser(id, "测试用户", "ACTIVE", "USER");
    }

    private void assertFailed(String code) {
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ai_task", String.class)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT started_at IS NOT NULL FROM ai_task", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT failed_at IS NOT NULL FROM ai_task", Boolean.class)).isTrue();
    }
}
