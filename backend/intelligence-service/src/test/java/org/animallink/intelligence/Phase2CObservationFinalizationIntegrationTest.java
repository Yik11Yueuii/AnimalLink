package org.animallink.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.application.*;
import org.animallink.intelligence.domain.ApiExceptions.AnimalFinalizationRejected;
import org.animallink.intelligence.domain.ApiExceptions.FinalizationServiceUnavailable;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class Phase2CObservationFinalizationIntegrationTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000401";
    private static final String OTHER_USER_ID = "00000000-0000-0000-0000-000000000402";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000401";
    private static final String RANK_ONE = "20000000-0000-0000-0000-000000000401";
    private static final String RANK_TWO = "20000000-0000-0000-0000-000000000402";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_phase2c_intelligence_test")
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
    @MockBean ObservationFinalizationGateway finalizationGateway;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM matching_decision");
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
        when(finalizationGateway.finalizeObservation(any())).thenAnswer(invocation -> {
            ObservationFinalizationGateway.FinalizationCommand command =
                    invocation.getArgument(0);
            String postId = UUID.nameUUIDFromBytes(
                    ("post:" + command.sourceMatchingRecordId()).getBytes()).toString();
            if (command.decisionType().name().equals("NO_MATCH")) {
                return new ObservationFinalizationGateway.FinalizationResult(
                        postId, UUID.nameUUIDFromBytes(
                        ("proposal:" + command.sourceMatchingRecordId()).getBytes()).toString(),
                        null);
            }
            return new ObservationFinalizationGateway.FinalizationResult(
                    postId, null, command.selectedAnimalId());
        });
    }

    @Test
    void selectExistingPersistsRankScoreAndGroundTruthIncludingLowConfidence() throws Exception {
        String matchId = insertMatch(true, "SUCCEEDED");
        finalizeMatch(matchId, "SELECT_EXISTING", RANK_TWO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.selectedAnimalId").value(RANK_TWO))
                .andExpect(jsonPath("$.decision.selectedRank").value(2))
                .andExpect(jsonPath("$.decision.selectedScore").value(0.61))
                .andExpect(jsonPath("$.decision.hitAt1").value(false))
                .andExpect(jsonPath("$.decision.hitAt3").value(true))
                .andExpect(jsonPath("$.decision.hitAtK").value(true))
                .andExpect(jsonPath("$.createdPost.animalId").value(RANK_TWO))
                .andExpect(jsonPath("$.proposalId").doesNotExist());
        assertThat(jdbc.queryForObject("""
                SELECT selected_rank FROM matching_decision WHERE matching_record_id = ?
                """, Integer.class, matchId)).isEqualTo(2);

        mockMvc.perform(get("/api/v1/ai/matches/{id}/decision", matchId)
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hitAt1").value(false));
    }

    @Test
    void selectedAnimalMustComeFromCandidateSnapshot() throws Exception {
        String matchId = insertMatch(false, "SUCCEEDED");
        finalizeMatch(matchId, "SELECT_EXISTING", UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELECTED_ANIMAL_NOT_CANDIDATE"));
        verifyNoInteractions(finalizationGateway);
        assertThat(count("matching_decision")).isZero();
    }

    @Test
    void noMatchCreatesProposalDecisionWhileUnsureCreatesNoFormalRecord() throws Exception {
        String noMatchId = insertMatch(false, "SUCCEEDED");
        finalizeMatch(noMatchId, "NO_MATCH", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.decisionType").value("NO_MATCH"))
                .andExpect(jsonPath("$.decision.selectedRank").doesNotExist())
                .andExpect(jsonPath("$.decision.hitAt1").doesNotExist())
                .andExpect(jsonPath("$.createdPost.id").isString())
                .andExpect(jsonPath("$.createdPost.animalId").doesNotExist())
                .andExpect(jsonPath("$.proposalId").isString());

        clearInvocations(finalizationGateway);
        String unsureId = insertMatch(false, "SUCCEEDED");
        finalizeMatch(unsureId, "UNSURE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.decisionType").value("UNSURE"))
                .andExpect(jsonPath("$.createdPost").doesNotExist())
                .andExpect(jsonPath("$.proposalId").doesNotExist());
        verifyNoInteractions(finalizationGateway);
        assertThat(jdbc.queryForObject("""
                SELECT post_id IS NULL AND proposal_id IS NULL
                FROM matching_decision WHERE matching_record_id = ?
                """, Boolean.class, unsureId)).isTrue();
    }

    @Test
    void retriesAreIdempotentAndDifferentDecisionConflicts() throws Exception {
        String matchId = insertMatch(false, "SUCCEEDED");
        String first = finalizeMatch(matchId, "SELECT_EXISTING", RANK_ONE)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = finalizeMatch(matchId, "SELECT_EXISTING", RANK_ONE)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode firstJson = objectMapper.readTree(first);
        JsonNode secondJson = objectMapper.readTree(second);
        assertThat(secondJson.at("/decision/id").asText())
                .isEqualTo(firstJson.at("/decision/id").asText());
        assertThat(secondJson.at("/createdPost/id").asText())
                .isEqualTo(firstJson.at("/createdPost/id").asText());
        verify(finalizationGateway, times(1)).finalizeObservation(any());
        assertThat(count("matching_decision")).isEqualTo(1);

        finalizeMatch(matchId, "NO_MATCH", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH_ALREADY_FINALIZED"));
    }

    @Test
    void ownerMembershipTaskAndAnimalServiceFailuresAreDistinct() throws Exception {
        String ownerMatch = insertMatch(false, "SUCCEEDED");
        when(identityGateway.requireCurrentUser()).thenReturn(user(OTHER_USER_ID));
        finalizeMatch(ownerMatch, "UNSURE", null)
                .andExpect(status().isNotFound());

        when(identityGateway.requireCurrentUser()).thenReturn(user(USER_ID));
        when(identityGateway.campusMembership(USER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(false, null, null));
        finalizeMatch(ownerMatch, "UNSURE", null)
                .andExpect(status().isForbidden());

        when(identityGateway.campusMembership(USER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(true, "STUDENT", "ACTIVE"));
        String invalidTask = insertMatch(false, "FAILED");
        finalizeMatch(invalidTask, "UNSURE", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_TASK_NO_LONGER_VALID"));

        String unavailable = insertMatch(false, "SUCCEEDED");
        doThrow(new FinalizationServiceUnavailable("animal-service 不可用", null))
                .when(finalizationGateway).finalizeObservation(any());
        finalizeMatch(unavailable, "NO_MATCH", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANIMAL_SERVICE_UNAVAILABLE"));
    }

    @Test
    void animalValidationErrorsArePreservedForClients() throws Exception {
        String archived = insertMatch(false, "SUCCEEDED");
        doThrow(new AnimalFinalizationRejected(409,
                "SELECTED_ANIMAL_ARCHIVED", "Animal 已归档"))
                .when(finalizationGateway).finalizeObservation(any());
        finalizeMatch(archived, "SELECT_EXISTING", RANK_ONE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELECTED_ANIMAL_ARCHIVED"));

        String crossCampus = insertMatch(false, "SUCCEEDED");
        doThrow(new AnimalFinalizationRejected(409,
                "CROSS_CAMPUS_ANIMAL", "Animal 跨 Campus"))
                .when(finalizationGateway).finalizeObservation(any());
        finalizeMatch(crossCampus, "SELECT_EXISTING", RANK_ONE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CROSS_CAMPUS_ANIMAL"));
    }

    private org.springframework.test.web.servlet.ResultActions finalizeMatch(
            String matchingId, String type, String animalId) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("decisionType", type);
        if (animalId != null) body.put("selectedAnimalId", animalId);
        if (!"UNSURE".equals(type)) body.put("postText", "人工确认后的校园观察");
        return mockMvc.perform(post("/api/v1/ai/matches/{id}/finalize", matchingId)
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String insertMatch(boolean lowConfidence, String taskStatus) throws Exception {
        String taskId = UUID.randomUUID().toString();
        String resultId = UUID.randomUUID().toString();
        String matchId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        boolean succeeded = "SUCCEEDED".equals(taskStatus);
        String draft = """
                {"species":"CAT","sex":"UNKNOWN","coatColor":"橘白",
                 "distinctiveFeatures":["尾部环纹"],"estimatedCount":1,
                 "possibleAbnormality":false,"abnormalFlags":[],
                 "locationDescription":"东门","confidence":0.8,
                 "fieldConfidence":{},"warnings":[],"unknownFields":[]}
                """;
        jdbc.update("""
                INSERT INTO ai_task
                    (id, user_id, campus_id, task_type, status, model_provider,
                     model_name, prompt_version, input_summary, created_at, started_at,
                     completed_at, failed_at, error_code, error_message, confirmed_at,
                     confirmed_by, version)
                VALUES (?, ?, ?, 'ANIMAL_OBSERVATION_PARSE', ?, 'test', 'model', 'v1',
                    JSON_OBJECT('mediaCount', 1), ?, ?, ?, ?, ?, ?, ?, ?, 2)
                """, taskId, USER_ID, CAMPUS_ID, taskStatus, Timestamp.from(now),
                Timestamp.from(now), succeeded ? Timestamp.from(now) : null,
                succeeded ? null : Timestamp.from(now),
                succeeded ? null : "FAILED", succeeded ? null : "failed",
                succeeded ? Timestamp.from(now) : null, succeeded ? USER_ID : null);
        jdbc.update("""
                INSERT INTO ai_task_media (task_id, sort_order, object_key)
                VALUES (?, 0, 'ai-input/observation.jpg')
                """, taskId);
        if (succeeded) {
            jdbc.update("""
                    INSERT INTO ai_result
                        (id, task_id, result_type, raw_response, structured_json,
                         schema_version, created_at)
                    VALUES (?, ?, 'ANIMAL_OBSERVATION_DRAFT', ?, ?, '1.0', ?)
                    """, resultId, taskId, draft, draft, Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO ai_confirmation
                        (id, task_id, result_id, confirmed_structured_json,
                         was_modified, confirmed_by, confirmed_at)
                    VALUES (?, ?, ?, ?, false, ?, ?)
                    """, UUID.randomUUID().toString(), taskId, resultId, draft,
                    USER_ID, Timestamp.from(now));
        }
        jdbc.update("""
                INSERT INTO matching_record
                    (id, user_id, ai_task_id, campus_id, algorithm_version,
                     weight_version, experiment_code, weights_json, top_k,
                     candidate_count, low_confidence, created_at)
                VALUES (?, ?, ?, ?, 'test-v1', 'weights-v1', 'D',
                    JSON_OBJECT('image', 1.0), 3, 2, ?, ?)
                """, matchId, USER_ID, taskId, CAMPUS_ID, lowConfidence,
                Timestamp.from(now));
        insertCandidate(matchId, 1, RANK_ONE, 0.91);
        insertCandidate(matchId, 2, RANK_TWO, 0.61);
        return matchId;
    }

    private void insertCandidate(String matchId, int rank, String animalId, double score) {
        jdbc.update("""
                INSERT INTO matching_candidate
                    (id, matching_record_id, rank_number, animal_id, display_name,
                     species, final_score, reasons_json, missing_dimensions_json, created_at)
                VALUES (?, ?, ?, ?, ?, 'CAT', ?, JSON_ARRAY('TEST'),
                    JSON_ARRAY(), CURRENT_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), matchId, rank, animalId,
                "候选" + rank, score);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private IdentityGateway.CurrentUser user(String id) {
        return new IdentityGateway.CurrentUser(id, "测试用户", "ACTIVE", "USER");
    }
}
