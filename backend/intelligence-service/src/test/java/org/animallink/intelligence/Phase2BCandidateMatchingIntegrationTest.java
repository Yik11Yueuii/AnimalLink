package org.animallink.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.application.*;
import org.animallink.intelligence.domain.*;
import org.animallink.intelligence.domain.ApiExceptions.*;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class Phase2BCandidateMatchingIntegrationTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000101";
    private static final String OTHER_USER_ID = "00000000-0000-0000-0000-000000000102";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000101";
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-20T10:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_matching_test")
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
    @Autowired MatchingRepository matchingRepository;
    @MockBean IdentityGateway identityGateway;
    @MockBean AnimalCandidateGateway animalGateway;
    @MockBean MediaObjectGateway observationMediaGateway;
    @MockBean AnimalMediaObjectGateway animalMediaGateway;
    @MockBean ImageEmbeddingClient embeddingClient;

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
        when(embeddingClient.providerName()).thenReturn("test-embedding-provider");
        when(embeddingClient.modelName()).thenReturn("test-image-model");
        when(embeddingClient.modelVersion()).thenReturn("v1");
        when(observationMediaGateway.loadAll(anyList())).thenAnswer(invocation ->
                invocation.<List<String>>getArgument(0).stream().map(this::media).toList());
        when(animalMediaGateway.load(anyString())).thenAnswer(invocation -> media(invocation.getArgument(0)));
        when(embeddingClient.embed(any())).thenAnswer(invocation -> vector(invocation.<MediaObjectGateway.MediaInput>getArgument(0).objectKey()));
        when(animalGateway.recall(anyString(), anyString(), anyInt())).thenReturn(defaultCandidates());
    }

    @Test
    void ranksTopKPersistsExplainableScoresAndKeepsIndependentHistory() throws Exception {
        String taskId = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        String firstResponse = match(taskId, "{\"topK\":3,\"experimentCode\":\"D\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.candidateCount").value(3))
                .andExpect(jsonPath("$.candidates.length()").value(3))
                .andExpect(jsonPath("$.candidates[0].animalId").value("20000000-0000-0000-0000-000000000101"))
                .andExpect(jsonPath("$.candidates[0].rank").value(1))
                .andExpect(jsonPath("$.candidates[0].reasons.length()").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(firstResponse);
        assertThat(first.at("/candidates/0/finalScore").asDouble()).isBetween(0.0, 1.0);
        assertThat(first.at("/candidates/0/imageScore").asDouble()).isEqualTo(1.0);
        assertThat(first.at("/candidates/1/imageScore").asDouble()).isEqualTo(0.5);
        assertThat(first.at("/candidates/2/imageScore").asDouble()).isEqualTo(0.0);

        String secondResponse = match(taskId, "{\"topK\":1,\"experimentCode\":\"A\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String firstId = first.get("matchingRecordId").asText();
        String secondId = objectMapper.readTree(secondResponse).get("matchingRecordId").asText();
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching_record WHERE ai_task_id = ?", Integer.class, taskId)).isEqualTo(2);

        mockMvc.perform(get("/api/v1/ai/matches/{id}", firstId).header("X-User-Id", USER_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(3));
        when(identityGateway.requireCurrentUser()).thenReturn(user(OTHER_USER_ID));
        mockMvc.perform(get("/api/v1/ai/matches/{id}", firstId).header("X-User-Id", OTHER_USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void experimentProfilesRenormalizeOnlyAvailableEnabledDimensionsAndAreRecorded() throws Exception {
        AnimalCandidateGateway.CandidateSnapshot sparse = candidate(
                "20000000-0000-0000-0000-000000000101", "一致候选", "CAT", "UNKNOWN",
                "橘白", "尾部环纹", null, "animals/a.jpg", null);
        when(animalGateway.recall(anyString(), anyString(), anyInt())).thenReturn(List.of(sparse));
        for (String code : List.of("A", "B", "C", "D")) {
            String taskId = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
            String body = "{\"topK\":3,\"experimentCode\":\"" + code + "\"}";
            String response = match(taskId, body).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.experimentCode").value(code))
                    .andExpect(jsonPath("$.candidates[0].finalScore").isNumber())
                    .andReturn().getResponse().getContentAsString();
            JsonNode missing = objectMapper.readTree(response).at("/candidates/0/missingDimensions");
            if (code.equals("C") || code.equals("D")) assertThat(missing.toString()).contains("geo");
            if (code.equals("D")) assertThat(missing.toString()).contains("history");
            if (code.equals("A") || code.equals("B")) assertThat(missing.isEmpty()).isTrue();
            assertThat(objectMapper.readTree(response).at("/candidates/0/finalScore").asDouble()).isEqualTo(1.0);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT experiment_code) FROM matching_record", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching_record WHERE JSON_LENGTH(weights_json) = 4", Integer.class)).isEqualTo(4);
    }

    @Test
    void unknownSpeciesSkipsHardConstraintAndEmptyRecallReturnsSuccessfulEmptyList() throws Exception {
        when(animalGateway.recall(eq(CAMPUS_ID), eq("UNKNOWN"), eq(50))).thenReturn(List.of());
        String taskId = insertTask("SUCCEEDED", true, ObservationSpecies.UNKNOWN, null, null, null);
        match(taskId, "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.candidateCount").value(0))
                .andExpect(jsonPath("$.lowConfidence").value(true))
                .andExpect(jsonPath("$.decisionReason").value("NO_CANDIDATES"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
        verify(animalGateway).recall(CAMPUS_ID, "UNKNOWN", 50);
    }

    @Test
    void authorizationStateAndRequestBoundariesAreDistinct() throws Exception {
        String unconfirmed = insertTask("SUCCEEDED", false, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(unconfirmed, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_TASK_NOT_CONFIRMED"));

        String failed = insertTask("FAILED", false, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(failed, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_TASK_NOT_SUCCEEDED"));

        String owned = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(owned, "{\"topK\":11}").andExpect(status().isBadRequest());
        match(owned, "{\"topK\":10}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.topK").value(10));
        when(identityGateway.requireCurrentUser()).thenReturn(user(OTHER_USER_ID));
        match(owned, "{}").andExpect(status().isNotFound());
    }

    @Test
    void dependencyEmbeddingFailuresAndInvalidVectorsHaveDistinctErrors() throws Exception {
        String taskOne = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        when(animalGateway.recall(anyString(), anyString(), anyInt()))
                .thenThrow(new CandidateServiceUnavailable("Animal 候选服务暂不可用", null));
        match(taskOne, "{}").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANIMAL_SERVICE_UNAVAILABLE"));

        reset(animalGateway);
        when(animalGateway.recall(anyString(), anyString(), anyInt())).thenReturn(defaultCandidates());
        doThrow(new EmbeddingTimeout("Embedding 超时", null)).when(embeddingClient).embed(any());
        String taskTwo = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(taskTwo, "{}").andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("EMBEDDING_TIMEOUT"));

        doThrow(new EmbeddingUnavailable("Embedding 不可用")).when(embeddingClient).embed(any());
        String taskThree = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(taskThree, "{}").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMBEDDING_UNAVAILABLE"));

        doReturn(List.of(0.0, 0.0)).when(embeddingClient).embed(any());
        String taskFour = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(taskFour, "{}").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("INVALID_EMBEDDING_VECTOR"));

        doAnswer(invocation -> vector(invocation.<MediaObjectGateway.MediaInput>getArgument(0).objectKey()))
                .when(embeddingClient).embed(any());
        doThrow(new MediaNotFound("观察媒体不存在")).when(observationMediaGateway).loadAll(anyList());
        String taskFive = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(taskFive, "{}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void equalScoresUseAnimalIdAsStableTieBreakerAndLowScoresAreFlagged() throws Exception {
        AnimalCandidateGateway.CandidateSnapshot later = candidate(
                "20000000-0000-0000-0000-000000000102", "后序", "CAT", "UNKNOWN",
                null, null, null, "animals/c.jpg", null);
        AnimalCandidateGateway.CandidateSnapshot earlier = candidate(
                "20000000-0000-0000-0000-000000000101", "前序", "CAT", "UNKNOWN",
                null, null, null, "animals/c.jpg", null);
        when(animalGateway.recall(anyString(), anyString(), anyInt())).thenReturn(List.of(later, earlier));
        String taskId = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, null, null, null);
        match(taskId, "{\"topK\":2,\"experimentCode\":\"D\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lowConfidence").value(true))
                .andExpect(jsonPath("$.decisionReason").value("NO_STRONG_MATCH"))
                .andExpect(jsonPath("$.candidates[0].animalId").value(earlier.id()))
                .andExpect(jsonPath("$.candidates[1].animalId").value(later.id()))
                .andExpect(jsonPath("$.candidates[0].reasons").value(org.hamcrest.Matchers.hasItem("NO_STRONG_MATCH")));
    }

    @Test
    void embeddingCacheIsVersionedAndUniqueUnderConcurrentInsertion() throws Exception {
        when(animalGateway.recall(anyString(), anyString(), anyInt())).thenReturn(List.of(defaultCandidates().getFirst()));
        String firstTask = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(firstTask, "{}").andExpect(status().isCreated());
        String secondTask = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(secondTask, "{}").andExpect(status().isCreated());
        verify(embeddingClient, times(3)).embed(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM animal_embedding", Integer.class)).isEqualTo(1);

        when(embeddingClient.modelVersion()).thenReturn("v2");
        String thirdTask = insertTask("SUCCEEDED", true, ObservationSpecies.CAT, "橘白", "东门", OCCURRED_AT);
        match(thirdTask, "{}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM animal_embedding", Integer.class)).isEqualTo(2);

        AnimalEmbedding concurrent = new AnimalEmbedding(UUID.randomUUID().toString(),
                "20000000-0000-0000-0000-000000000999", "30000000-0000-0000-0000-000000000999",
                "animals/concurrent.jpg", "test", "concurrent-model", "v1", List.of(1.0, 0.0),
                Instant.now(), Instant.now());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<AnimalEmbedding> insert = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                AnimalEmbedding value = new AnimalEmbedding(UUID.randomUUID().toString(),
                        concurrent.animalId(), concurrent.mediaId(), concurrent.mediaObjectKey(),
                        concurrent.modelProvider(), concurrent.modelName(), concurrent.modelVersion(),
                        concurrent.vector(), concurrent.createdAt(), concurrent.updatedAt());
                return matchingRepository.saveEmbeddingOrLoadExisting(value);
            };
            List<Future<AnimalEmbedding>> futures = executor.invokeAll(List.of(insert, insert));
            assertThat(futures.get(0).get().id()).isEqualTo(futures.get(1).get().id());
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM animal_embedding WHERE animal_id = ? AND media_id = ?
                    """, Integer.class, concurrent.animalId(), concurrent.mediaId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions match(String taskId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/ai/tasks/{taskId}/match-candidates", taskId)
                .header("X-User-Id", USER_ID).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String insertTask(String status, boolean confirmed, ObservationSpecies species,
                              String coatColor, String location, Instant occurredAt) throws Exception {
        String taskId = UUID.randomUUID().toString();
        String resultId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        boolean succeeded = "SUCCEEDED".equals(status);
        jdbc.update("""
                INSERT INTO ai_task (id, user_id, campus_id, task_type, status, model_provider,
                    model_name, prompt_version, input_summary, created_at, started_at, completed_at,
                    failed_at, error_code, error_message, confirmed_at, confirmed_by, version)
                VALUES (?, ?, ?, 'ANIMAL_OBSERVATION_PARSE', ?, 'test', 'test-model', 'v1',
                    JSON_OBJECT('mediaCount', 1), ?, ?, ?, ?, ?, ?, ?, ?, 2)
                """, taskId, USER_ID, CAMPUS_ID, status, Timestamp.from(now), Timestamp.from(now),
                succeeded ? Timestamp.from(now) : null, succeeded ? null : Timestamp.from(now),
                succeeded ? null : "TEST_FAILURE", succeeded ? null : "failed",
                confirmed ? Timestamp.from(now) : null, confirmed ? USER_ID : null);
        jdbc.update("INSERT INTO ai_task_media (task_id, sort_order, object_key) VALUES (?, 0, 'observations/obs.jpg')", taskId);
        if (succeeded) {
            String draft = objectMapper.writeValueAsString(draft(species, coatColor, location, occurredAt));
            jdbc.update("""
                    INSERT INTO ai_result (id, task_id, result_type, raw_response, structured_json,
                        schema_version, created_at) VALUES (?, ?, 'ANIMAL_OBSERVATION_DRAFT', ?, ?, '1.0', ?)
                    """, resultId, taskId, draft, draft, Timestamp.from(now));
            if (confirmed) {
                jdbc.update("""
                        INSERT INTO ai_confirmation (id, task_id, result_id, confirmed_structured_json,
                            was_modified, confirmed_by, confirmed_at) VALUES (?, ?, ?, ?, false, ?, ?)
                        """, UUID.randomUUID().toString(), taskId, resultId, draft, USER_ID, Timestamp.from(now));
            }
        }
        return taskId;
    }

    private AnimalObservationDraft draft(ObservationSpecies species, String coatColor,
                                         String location, Instant occurredAt) {
        return new AnimalObservationDraft(species, ObservationSex.UNKNOWN, coatColor,
                coatColor == null ? List.of() : List.of("尾部环纹"), null, null, 1, false,
                List.of(), location, occurredAt, 0.8, Map.of(), List.of(), List.of());
    }

    private List<AnimalCandidateGateway.CandidateSnapshot> defaultCandidates() {
        return List.of(
                candidate("20000000-0000-0000-0000-000000000101", "高匹配", "CAT", "UNKNOWN",
                        "橘白", "尾部环纹", "东门", "animals/a.jpg", OCCURRED_AT.minusSeconds(3600)),
                candidate("20000000-0000-0000-0000-000000000102", "中匹配", "CAT", "MALE",
                        "黑色", "短尾", "西门", "animals/b.jpg", OCCURRED_AT.minusSeconds(86400 * 15)),
                candidate("20000000-0000-0000-0000-000000000103", "低匹配", "CAT", "FEMALE",
                        null, null, null, "animals/c.jpg", null));
    }

    private AnimalCandidateGateway.CandidateSnapshot candidate(String id, String name, String species,
            String sex, String coat, String features, String area, String mediaKey, Instant lastSeenAt) {
        return new AnimalCandidateGateway.CandidateSnapshot(id, CAMPUS_ID, name, species, sex,
                coat, features, area, List.of(new AnimalCandidateGateway.PublicMedia(
                id.replaceFirst("^2", "3"), mediaKey, "image/jpeg")), lastSeenAt, "近期公开记录");
    }

    private MediaObjectGateway.MediaInput media(String key) {
        return new MediaObjectGateway.MediaInput(key, "image/jpeg", key.getBytes(StandardCharsets.UTF_8));
    }

    private List<Double> vector(String key) {
        if (key.contains("a.jpg") || key.contains("obs.jpg")) return List.of(1.0, 0.0);
        if (key.contains("b.jpg")) return List.of(0.0, 1.0);
        return List.of(-1.0, 0.0);
    }

    private IdentityGateway.CurrentUser user(String id) {
        return new IdentityGateway.CurrentUser(id, "测试用户", "ACTIVE", "USER");
    }
}
