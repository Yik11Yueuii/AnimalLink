package org.animallink.animal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.animal.application.*;
import org.animallink.animal.domain.*;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class Phase2CObservationFinalizationIntegrationTest {
    private static final String MEMBER_ID = "00000000-0000-0000-0000-000000000301";
    private static final String ADMIN_ID = "00000000-0000-0000-0000-000000000399";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000301";
    private static final String OTHER_CAMPUS_ID = "10000000-0000-0000-0000-000000000302";
    private static final String ACTIVE_ANIMAL_ID = "20000000-0000-0000-0000-000000000301";
    private static final String ARCHIVED_ANIMAL_ID = "20000000-0000-0000-0000-000000000302";
    private static final String OTHER_CAMPUS_ANIMAL_ID = "20000000-0000-0000-0000-000000000303";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_phase2c_animal_test")
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
    @Autowired AnimalIdentityProposalService proposalService;
    @MockBean IdentityGateway identityGateway;
    @MockBean PermanentPostMediaGateway mediaGateway;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM observation_finalization");
        jdbc.update("DELETE FROM animal_identity_proposal");
        jdbc.update("DELETE FROM post_like");
        jdbc.update("DELETE FROM comment");
        jdbc.update("DELETE FROM post_media");
        jdbc.update("DELETE FROM post");
        jdbc.update("DELETE FROM animal_follow");
        jdbc.update("DELETE FROM timeline_entry");
        jdbc.update("DELETE FROM animal_media");
        jdbc.update("DELETE FROM animal");
        insertAnimal(ACTIVE_ANIMAL_ID, CAMPUS_ID, "小橘", "CAT", "ACTIVE");
        insertAnimal(ARCHIVED_ANIMAL_ID, CAMPUS_ID, "旧档案", "CAT", "ARCHIVED");
        insertAnimal(OTHER_CAMPUS_ANIMAL_ID, OTHER_CAMPUS_ID, "跨校动物", "DOG", "ACTIVE");
        when(identityGateway.campusMembership(MEMBER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(true, "STUDENT", "ACTIVE"));
        when(identityGateway.requireCurrentUser()).thenReturn(admin());
        when(mediaGateway.copyObservationMedia(anyList(), anyString()))
                .thenAnswer(invocation -> List.of(new PermanentPostMediaGateway.PermanentMedia(
                        "posts/" + invocation.getArgument(1) + "/01.jpg",
                        "image/jpeg", org.animallink.animal.domain.MediaType.IMAGE, 128, 0)));
    }

    @Test
    void selectExistingValidatesAnimalAndIsIdempotent() throws Exception {
        String matchingId = UUID.randomUUID().toString();
        String response = internalFinalize(matchingId, "SELECT_EXISTING", ACTIVE_ANIMAL_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.animalId").value(ACTIVE_ANIMAL_ID))
                .andExpect(jsonPath("$.proposalId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String postId = objectMapper.readTree(response).get("postId").asText();
        assertThat(jdbc.queryForObject("SELECT animal_id FROM post WHERE id = ?",
                String.class, postId)).isEqualTo(ACTIVE_ANIMAL_ID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_media WHERE post_id = ?",
                Integer.class, postId)).isEqualTo(1);

        internalFinalize(matchingId, "SELECT_EXISTING", ACTIVE_ANIMAL_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postId").value(postId));
        assertThat(count("post")).isEqualTo(1);
        assertThat(count("post_media")).isEqualTo(1);
        assertThat(count("observation_finalization")).isEqualTo(1);

        internalFinalize(matchingId, "NO_MATCH", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FINALIZE_CONFLICT"));

        internalFinalize(UUID.randomUUID().toString(), "SELECT_EXISTING", ARCHIVED_ANIMAL_ID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELECTED_ANIMAL_ARCHIVED"));
        internalFinalize(UUID.randomUUID().toString(), "SELECT_EXISTING",
                OTHER_CAMPUS_ANIMAL_ID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CROSS_CAMPUS_ANIMAL"));
        internalFinalize(UUID.randomUUID().toString(), "SELECT_EXISTING",
                UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SELECTED_ANIMAL_NOT_FOUND"));
    }

    @Test
    void noMatchCreatesProposalAndUnboundPostWithoutCreatingAnimal() throws Exception {
        int animalsBefore = count("animal");
        String matchingId = UUID.randomUUID().toString();
        JsonNode result = objectMapper.readTree(internalFinalize(matchingId, "NO_MATCH", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.animalId").doesNotExist())
                .andExpect(jsonPath("$.proposalId").isString())
                .andReturn().getResponse().getContentAsString());
        String proposalId = result.get("proposalId").asText();
        String postId = result.get("postId").asText();

        assertThat(animalsBefore).isEqualTo(count("animal"));
        assertThat(jdbc.queryForObject("SELECT animal_id FROM post WHERE id = ?",
                String.class, postId)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT source_matching_record_id FROM animal_identity_proposal WHERE id = ?
                """, String.class, proposalId)).isEqualTo(matchingId);

        when(identityGateway.requireCurrentUser()).thenReturn(member());
        mockMvc.perform(get("/api/v1/admin/animal-identity-proposals/{id}", proposalId)
                        .header("X-User-Id", MEMBER_ID))
                .andExpect(status().isForbidden());
        when(identityGateway.requireCurrentUser()).thenReturn(admin());
        mockMvc.perform(get("/api/v1/admin/animal-identity-proposals/{id}", proposalId)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.media[0].objectKey")
                        .value("posts/" + postId + "/01.jpg"));
    }

    @Test
    void membershipAndMediaFailuresFailClosedWithoutBusinessRows() throws Exception {
        when(identityGateway.campusMembership(MEMBER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(false, null, null));
        internalFinalize(UUID.randomUUID().toString(), "NO_MATCH", null)
                .andExpect(status().isForbidden());
        assertThat(count("post")).isZero();

        when(identityGateway.campusMembership(MEMBER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(true, "STUDENT", "ACTIVE"));
        when(mediaGateway.copyObservationMedia(anyList(), anyString()))
                .thenThrow(new MediaCopyException("复制失败", new RuntimeException("MinIO")));
        internalFinalize(UUID.randomUUID().toString(), "NO_MATCH", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_COPY_FAILED"));
        assertThat(count("post")).isZero();
        assertThat(count("animal_identity_proposal")).isZero();

        mockMvc.perform(post("/internal/v1/observation-finalizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(finalizeJson(UUID.randomUUID().toString(),
                                "NO_MATCH", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void governanceApproveLinkRejectAndTransactionsAreCorrect() throws Exception {
        String approveProposal = createNoMatchProposal();
        int beforeApprove = count("animal");
        mockMvc.perform(post("/api/v1/admin/animal-identity-proposals/{id}/approve-create",
                        approveProposal).header("X-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"新确认动物","species":"CAT",
                                 "sex":"UNKNOWN","typicalArea":"东门"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.resolutionAnimalId").isString());
        assertThat(count("animal")).isEqualTo(beforeApprove + 1);
        assertPostBound(approveProposal);
        assertThat(count("timeline_entry")).isZero();

        String linkProposal = createNoMatchProposal();
        mockMvc.perform(post("/api/v1/admin/animal-identity-proposals/{id}/link-existing",
                        linkProposal).header("X-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"animalId\":\"" + ACTIVE_ANIMAL_ID
                                + "\",\"reviewReason\":\"确认是小橘\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LINKED_EXISTING"))
                .andExpect(jsonPath("$.resolutionAnimalId").value(ACTIVE_ANIMAL_ID));
        assertPostBound(linkProposal);

        String rejectProposal = createNoMatchProposal();
        mockMvc.perform(post("/api/v1/admin/animal-identity-proposals/{id}/reject",
                        rejectProposal).header("X-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewReason\":\"信息不足\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.resolutionAnimalId").doesNotExist());
    }

    @Test
    void concurrentReviewAllowsExactlyOneWinner() throws Exception {
        String proposalId = createNoMatchProposal();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<String> link = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return proposalService.linkExisting(proposalId, ACTIVE_ANIMAL_ID,
                            "并发关联").proposal().status().name();
                } catch (ProposalAlreadyReviewedException exception) {
                    return "CONFLICT";
                }
            };
            Callable<String> reject = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return proposalService.reject(proposalId,
                            "并发拒绝").proposal().status().name();
                } catch (ProposalAlreadyReviewedException exception) {
                    return "CONFLICT";
                }
            };
            List<Future<String>> results = executor.invokeAll(List.of(link, reject));
            List<String> values = List.of(results.get(0).get(), results.get(1).get());
            assertThat(values).contains("CONFLICT");
            assertThat(values.stream().filter(value -> !"CONFLICT".equals(value)).count())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void approveCreateRollsBackAnimalAndProposalWhenPostBindingFails() throws Exception {
        String animalFailureProposal = createNoMatchProposal();
        int beforeInvalidAnimal = count("animal");
        assertThatThrownBy(() -> proposalService.approveCreate(animalFailureProposal,
                new ApproveProposalCommand(null, AnimalSpecies.CAT,
                        AnimalSex.UNKNOWN, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("animal")).isEqualTo(beforeInvalidAnimal);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM animal_identity_proposal WHERE id = ?
                """, String.class, animalFailureProposal)).isEqualTo("PENDING_REVIEW");

        String proposalId = createNoMatchProposal();
        String postId = jdbc.queryForObject("""
                SELECT post_id FROM animal_identity_proposal WHERE id = ?
                """, String.class, proposalId);
        jdbc.update("UPDATE post SET animal_id = ? WHERE id = ?", ACTIVE_ANIMAL_ID, postId);
        int animalCount = count("animal");
        assertThatThrownBy(() -> proposalService.approveCreate(proposalId,
                new ApproveProposalCommand("应回滚动物", AnimalSpecies.CAT,
                        AnimalSex.UNKNOWN, null, null, null, null)))
                .isInstanceOf(StateConflictException.class);
        assertThat(count("animal")).isEqualTo(animalCount);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM animal_identity_proposal WHERE id = ?
                """, String.class, proposalId)).isEqualTo("PENDING_REVIEW");
    }

    private org.springframework.test.web.servlet.ResultActions internalFinalize(
            String matchingId, String decisionType, String selectedAnimalId) throws Exception {
        return mockMvc.perform(post("/internal/v1/observation-finalizations")
                .header("X-Internal-Service", "intelligence-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(finalizeJson(matchingId, decisionType, selectedAnimalId)));
    }

    private String finalizeJson(String matchingId, String decisionType,
                                String selectedAnimalId) throws Exception {
        var payload = objectMapper.createObjectNode();
        payload.put("userId", MEMBER_ID);
        payload.put("campusId", CAMPUS_ID);
        payload.put("sourceAiTaskId", UUID.randomUUID().toString());
        payload.put("sourceMatchingRecordId", matchingId);
        payload.put("decisionType", decisionType);
        if (selectedAnimalId != null) payload.put("selectedAnimalId", selectedAnimalId);
        payload.put("postText", "今天在东门记录到一只橘白猫");
        payload.putArray("mediaObjectKeys").add("ai-input/observation.jpg");
        var draft = payload.putObject("confirmedDraft");
        draft.put("species", "CAT");
        draft.put("sex", "UNKNOWN");
        draft.put("coatColor", "橘白");
        draft.putArray("distinctiveFeatures").add("尾部环纹");
        draft.put("locationDescription", "东门");
        return objectMapper.writeValueAsString(payload);
    }

    private String createNoMatchProposal() throws Exception {
        String response = internalFinalize(UUID.randomUUID().toString(), "NO_MATCH", null)
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        return objectMapper.readTree(response).get("proposalId").asText();
    }

    private void assertPostBound(String proposalId) {
        String postId = jdbc.queryForObject("""
                SELECT post_id FROM animal_identity_proposal WHERE id = ?
                """, String.class, proposalId);
        assertThat(jdbc.queryForObject("SELECT animal_id FROM post WHERE id = ?",
                String.class, postId)).isNotBlank();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void insertAnimal(String id, String campusId, String name,
                              String species, String status) {
        jdbc.update("""
                INSERT INTO animal
                    (id, campus_id, display_name, species, sex, sterilization_status,
                     identity_status, adoption_status, current_context)
                VALUES (?, ?, ?, ?, 'UNKNOWN', 'UNKNOWN', ?, 'NOT_OPEN', 'CAMPUS')
                """, id, campusId, name, species, status);
    }

    private IdentityGateway.CurrentUser admin() {
        return new IdentityGateway.CurrentUser(ADMIN_ID, "治理管理员",
                "ACTIVE", "GOVERNANCE_ADMIN");
    }

    private IdentityGateway.CurrentUser member() {
        return new IdentityGateway.CurrentUser(MEMBER_ID, "校园成员",
                "ACTIVE", "USER");
    }
}
