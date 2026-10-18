package org.animallink.animal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.animallink.animal.application.AnimalApplicationService;
import org.animallink.animal.application.AnimalIdentityProposalService;
import org.animallink.animal.application.AnimalMergeResult;
import org.animallink.animal.application.AnimalMergeService;
import org.animallink.animal.application.CorrectAnimalCommand;
import org.animallink.animal.application.IdentityGateway;
import org.animallink.animal.domain.AnimalMergeRecord;
import org.animallink.animal.domain.AnimalRepository;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.animallink.animal.domain.SelectedAnimalArchivedException;
import org.animallink.animal.domain.StateConflictException;
import org.animallink.animal.domain.UnauthorizedException;
import org.animallink.animal.infrastructure.JdbcAnimalMergeRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnimalMergeIntegrationTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String MEMBER = "00000000-0000-0000-0000-000000000011";
    private static final String CAMPUS = "10000000-0000-0000-0000-000000000001";
    private static final String OTHER_CAMPUS = "10000000-0000-0000-0000-000000000002";
    private static final AdoptionStub ADOPTION = new AdoptionStub();
    private static final TestDatabase DATABASE = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("animallink.adoption.base-url", ADOPTION::url);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.messaging.enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AnimalMergeService mergeService;
    @Autowired AnimalApplicationService animalService;
    @Autowired AnimalIdentityProposalService proposalService;
    @Autowired AnimalRepository animalRepository;
    @MockBean IdentityGateway identityGateway;
    @SpyBean JdbcAnimalMergeRepository mergeRepository;

    @BeforeEach
    void reset() {
        Mockito.reset(mergeRepository);
        clearDatabase();
        ADOPTION.reset();
        when(identityGateway.requireCurrentUser()).thenReturn(admin());
        when(identityGateway.campusMembership(anyString(), anyString()))
                .thenReturn(new IdentityGateway.MembershipFact(true, "STUDENT", "ACTIVE"));
    }

    @AfterAll
    static void close() {
        ADOPTION.stop();
        DATABASE.close();
    }

    @AfterEach
    void cleanup() {
        Mockito.reset(mergeRepository);
        clearDatabase();
    }

    @Test
    void migrationCreatesImmutableMergeSchemaWithExpectedMetadata() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='animal_merge_record'
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='animal_merge_record'
                  AND index_name='uk_animal_merge_record_source' AND non_unique=0
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='animal_merge_record'
                  AND index_name='idx_animal_merge_record_target_created'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema=DATABASE() AND table_name='animal_merge_record'
                  AND constraint_type='CHECK'
                  AND constraint_name='chk_animal_merge_record_distinct'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT delete_rule, update_rule FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE() AND table_name='animal_merge_record'
                """)).allSatisfy(row -> {
                    assertThat(row.get("DELETE_RULE")).isIn("RESTRICT", "NO ACTION");
                    assertThat(row.get("UPDATE_RULE")).isIn("RESTRICT", "NO ACTION");
                }).hasSize(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version IS NOT NULL AND success=1
                """, Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='animal_adoption_status_command'
                """, Integer.class)).isEqualTo(10);
    }

    @Test
    void adoptionLifecycleOpenCloseIsPersistentIdempotentAndFailClosed() throws Exception {
        String animal = animal();

        mvc.perform(adoptionTransition(animal, "open", "open-key", "  ready for adoption  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.animalId").value(animal))
                .andExpect(jsonPath("$.action").value("OPEN"))
                .andExpect(jsonPath("$.fromStatus").value("NOT_OPEN"))
                .andExpect(jsonPath("$.toStatus").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reason").value("ready for adoption"))
                .andExpect(jsonPath("$.replayed").value(false));
        assertThat(ADOPTION.calls.get()).isEqualTo(2);
        assertThat(animalValue(animal, "adoption_status")).isEqualTo("OPEN");
        assertThat(animalInt(animal, "version")).isEqualTo(1);
        assertThat(count("animal_adoption_status_command")).isEqualTo(1);

        mvc.perform(adoptionTransition(animal, "open", "open-key", "ready for adoption"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(ADOPTION.calls.get()).isEqualTo(2);
        assertThat(animalInt(animal, "version")).isEqualTo(1);
        assertThat(count("animal_adoption_status_command")).isEqualTo(1);

        mvc.perform(adoptionTransition(animal, "open", "open-key", "changed reason"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        ADOPTION.ineligible();
        mvc.perform(adoptionTransition(animal, "close", "close-key", "listing closed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(animalValue(animal, "adoption_status")).isEqualTo("OPEN");
        assertThat(animalInt(animal, "version")).isEqualTo(1);
        assertThat(count("animal_adoption_status_command")).isEqualTo(1);

        ADOPTION.eligible();
        mvc.perform(adoptionTransition(animal, "close", "close-key", "listing closed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromStatus").value("OPEN"))
                .andExpect(jsonPath("$.toStatus").value("NOT_OPEN"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(adoptionTransition(animal, "close", "close-key", "listing closed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(animalValue(animal, "adoption_status")).isEqualTo("NOT_OPEN");
        assertThat(animalInt(animal, "version")).isEqualTo(2);
        assertThat(count("animal_adoption_status_command")).isEqualTo(2);
    }

    @Test
    void mergedSourceRejectsNewStatusCommandsButReplaysItsHistoricalCommand() throws Exception {
        String source = animal();
        String target = animal();
        mvc.perform(adoptionTransition(source, "open", "historical-open", "open"))
                .andExpect(status().isOk());
        mvc.perform(adoptionTransition(source, "close", "historical-close", "close"))
                .andExpect(status().isOk());
        mvc.perform(merge(source, target, "duplicate")).andExpect(status().isOk());

        mvc.perform(adoptionTransition(source, "open", "historical-open", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.animalId").value(source))
                .andExpect(jsonPath("$.toStatus").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(adoptionTransition(source, "open", "new-open", "open again"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(animalValue(source, "identity_status")).isEqualTo("MERGED");
        assertThat(count("animal_adoption_status_command")).isEqualTo(2);
    }

    @Test
    void concurrentSameAdoptionCommandChangesStateAndAuditExactlyOnce() throws Exception {
        String animal = animal();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> request = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return mvc.perform(adoptionTransition(animal, "open", "concurrent-open", "open"))
                        .andReturn().getResponse().getStatus();
            };
            List<Future<Integer>> responses = executor.invokeAll(List.of(request, request));
            assertThat(responses.get(0).get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(responses.get(1).get(15, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }
        assertThat(animalValue(animal, "adoption_status")).isEqualTo("OPEN");
        assertThat(animalInt(animal, "version")).isEqualTo(1);
        assertThat(count("animal_adoption_status_command")).isEqualTo(1);
    }

    @Test
    void adoptionLifecycleValidatesAuthorizationHeadersReasonAndState() throws Exception {
        String animal = animal();
        doReturn(member()).when(identityGateway).requireCurrentUser();
        mvc.perform(adoptionTransition(animal, "open", "member", "open"))
                .andExpect(status().isForbidden());

        doReturn(admin()).when(identityGateway).requireCurrentUser();
        mvc.perform(post("/api/v1/admin/animals/{id}/adoption/open", animal)
                        .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"open\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(adoptionTransition(animal, "open", "bad key", "open"))
                .andExpect(status().isBadRequest());
        mvc.perform(adoptionTransition(animal, "open", "blank-reason", " "))
                .andExpect(status().isBadRequest());

        String archived = animal(CAMPUS, "CAT", "ARCHIVED", "NOT_OPEN", "CAMPUS", 0,
                "archived");
        String adopted = animal(CAMPUS, "CAT", "ACTIVE", "ADOPTED", "ADOPTED_HOME", 0,
                "adopted");
        String pending = animal(CAMPUS, "CAT", "ACTIVE", "HANDOVER_PENDING", "CAMPUS", 0,
                "pending");
        mvc.perform(adoptionTransition(archived, "open", "archived", "open"))
                .andExpect(status().isConflict());
        mvc.perform(adoptionTransition(adopted, "close", "adopted", "close"))
                .andExpect(status().isConflict());
        mvc.perform(adoptionTransition(pending, "close", "pending", "close"))
                .andExpect(status().isConflict());
    }

    @Test
    void mergeMovesLocalProjectionsConsolidatesFollowersAndPreservesHistory() throws Exception {
        String source = animal(CAMPUS, "CAT", "ACTIVE", "NOT_OPEN", "CAMPUS", 4, "source");
        String target = animal(CAMPUS, "CAT", "ACTIVE", "NOT_OPEN", "CAMPUS", 7, "target");
        String sourceMedia = media(source, "animals/source.jpg", 3, "RESTRICTED");
        String targetMedia = media(target, "animals/target.jpg", 3, "PUBLIC");
        String sourceTimeline = timeline(source, "CASE", id(), "CASE_RESULT", "RESTRICTED");
        String targetTimeline = timeline(target, "POST", id(), "POST_PUBLISHED", "PUBLIC");
        Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = Instant.parse("2026-02-01T00:00:00Z");
        follow("u1", source, later);
        follow("u2", target, later);
        follow("u3", source, earlier);
        follow("u3", target, later);
        String campusPost = insertPost(source, "CAMPUS_POST", null);
        String adoptionPost = insertPost(source, "POST_ADOPTION", id());
        jdbc.update("INSERT INTO post_media (id,post_id,object_key,content_type,media_type,size_bytes,sort_order,visibility) VALUES (?,?,?,'image/jpeg','IMAGE',12,0,'PUBLIC')",
                id(), campusPost, "posts/history.jpg");
        jdbc.update("INSERT INTO comment (id,post_id,author_user_id,content,status) VALUES (?,?,?,'history','ACTIVE')",
                id(), campusPost, MEMBER);
        jdbc.update("INSERT INTO post_like (user_id,post_id) VALUES (?,?)", MEMBER, campusPost);
        String proposalPost = insertPost(source, "CAMPUS_POST", null);
        String proposal = linkedProposal(source, proposalPost);
        String observationPost = insertPost(source, "CAMPUS_POST", null);
        String observation = observation(source, observationPost);

        String body = mvc.perform(merge(source, target, "  duplicate observation  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("MERGED"))
                .andExpect(jsonPath("$.targetStatus").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String mergeId = json.readTree(body).get("mergeId").asText();

        assertThat(animalValue(source, "identity_status")).isEqualTo("MERGED");
        assertThat(animalInt(source, "version")).isEqualTo(5);
        assertThat(animalInt(target, "version")).isEqualTo(7);
        assertThat(animalValue(target, "display_name")).isEqualTo("target");
        assertThat(jdbc.queryForList("SELECT id FROM animal_media WHERE animal_id=? ORDER BY id",
                String.class, target)).containsExactlyInAnyOrder(sourceMedia, targetMedia);
        assertThat(countWhere("animal_media", "animal_id", source)).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM timeline_entry WHERE animal_id=? ORDER BY id",
                String.class, target)).containsExactlyInAnyOrder(sourceTimeline, targetTimeline);
        assertThat(countWhere("timeline_entry", "animal_id", source)).isZero();
        assertThat(countWhere("animal_follow", "animal_id", source)).isZero();
        assertThat(jdbc.queryForList("SELECT user_id FROM animal_follow WHERE animal_id=? ORDER BY user_id",
                String.class, target)).containsExactly("u1", "u2", "u3");
        assertThat(jdbc.queryForObject("SELECT created_at FROM animal_follow WHERE user_id='u3' AND animal_id=?",
                Timestamp.class, target).toInstant()).isEqualTo(earlier);
        assertThat(postAnimal(campusPost)).isEqualTo(source);
        assertThat(postAnimal(adoptionPost)).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT resolution_animal_id FROM animal_identity_proposal WHERE id=?",
                String.class, proposal)).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT selected_animal_id FROM observation_finalization WHERE id=?",
                String.class, observation)).isEqualTo(source);
        assertThat(count("post_media")).isEqualTo(1);
        assertThat(count("comment")).isEqualTo(1);
        assertThat(count("post_like")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason FROM animal_merge_record WHERE id=?",
                String.class, mergeId)).isEqualTo("duplicate observation");

        mvc.perform(get("/api/v1/admin/animals/{id}/merge-history", target)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].mergeId").value(mergeId))
                .andExpect(jsonPath("$.items[0].actorUserId").value(ADMIN));
        mvc.perform(get("/api/v1/animals/{id}", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedAnimalId").value(source))
                .andExpect(jsonPath("$.canonicalAnimalId").value(target))
                .andExpect(jsonPath("$.id").value(target))
                .andExpect(jsonPath("$.displayName").value("target"));
        assertThat(animalRepository.searchActive(CAMPUS, "source", AnimalSpecies.CAT, 20, 0))
                .isEmpty();
        assertThat(animalRepository.recallCandidates(CAMPUS, AnimalSpecies.CAT, 20))
                .extracting("id").doesNotContain(source).contains(target);
    }

    @Test
    void mergedSourceCannotBeUsedByNewWritesOrProposalLinking() throws Exception {
        String source = animal();
        String target = animal();
        mergeService.merge(source, target, "same animal");
        doReturn(member()).when(identityGateway).requireCurrentUser();

        mvc.perform(post("/api/v1/animals/{id}/follow", source)
                        .header("X-User-Id", MEMBER))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/animals/{id}/follow", target)
                        .header("X-User-Id", MEMBER))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campusId\":\"" + CAMPUS + "\",\"animalId\":\""
                                + source + "\",\"textContent\":\"new\",\"media\":[]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/animals/{id}/post-adoption-posts", source)
                        .header("X-User-Id", MEMBER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"textContent\":\"new\",\"media\":[]}"))
                .andExpect(status().isConflict());

        String proposalPost = insertPost(null, "CAMPUS_POST", null);
        String proposal = pendingProposal(proposalPost);
        when(identityGateway.requireCurrentUser()).thenReturn(admin());
        assertThatThrownBy(() -> proposalService.linkExisting(proposal, source, "link"))
                .isInstanceOf(SelectedAnimalArchivedException.class);
    }

    @Test
    void adoptionPrecheckFailureModesAreFailClosedAndRunBeforeLocks() throws Exception {
        String source = animal();
        String target = animal();
        media(source, "animals/precheck.jpg", 0, "PUBLIC");
        timeline(source, "CASE", id(), "CASE_RESULT", "PUBLIC");
        follow(MEMBER, source, Instant.now());

        ADOPTION.ineligible();
        mvc.perform(merge(source, target, "blocked"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertUnchanged(source, target);

        ADOPTION.serverError();
        mvc.perform(merge(source, target, "dependency"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertUnchanged(source, target);

        ADOPTION.invalid();
        mvc.perform(merge(source, target, "invalid payload"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertUnchanged(source, target);

        ADOPTION.eligible();
        doAnswer(invocation -> {
            assertThat(ADOPTION.calls.get()).isEqualTo(4);
            return invocation.callRealMethod();
        }).when(mergeRepository).lockAnimals(anyString(), anyString());
        mergeService.merge(source, target, "call ordering");
        assertThat(ADOPTION.lastInternalService.get()).isEqualTo("animal-service");
    }

    @Test
    void authorizationAndReasonValidationAreEnforced() throws Exception {
        String source = animal();
        String target = animal();
        when(identityGateway.requireCurrentUser()).thenThrow(new UnauthorizedException("login"));
        mvc.perform(merge(source, target, "reason")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/animals/{id}/merge-history", source)
                        .header("X-User-Id", MEMBER))
                .andExpect(status().isUnauthorized());

        doReturn(member()).when(identityGateway).requireCurrentUser();
        mvc.perform(merge(source, target, "reason")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/animals/{id}/merge-history", source)
                        .header("X-User-Id", MEMBER))
                .andExpect(status().isForbidden());
        doReturn(new IdentityGateway.CurrentUser(ADMIN, "inactive", "SUSPENDED",
                "GOVERNANCE_ADMIN")).when(identityGateway).requireCurrentUser();
        mvc.perform(merge(source, target, "reason")).andExpect(status().isForbidden());

        doReturn(admin()).when(identityGateway).requireCurrentUser();
        mvc.perform(post("/api/v1/admin/animals/{id}/merge", source)
                        .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(merge("bad-id", target, "reason")).andExpect(status().isBadRequest());
        mvc.perform(merge(source, source, "reason")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/animals/{id}/merge", source)
                        .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"missing target\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/animals/{id}/merge", source)
                        .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetAnimalId\":\"" + target + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(merge(source, target, "   ")).andExpect(status().isBadRequest());
        mvc.perform(merge(source, target, "界".repeat(1001))).andExpect(status().isBadRequest());

        String validSource = animal();
        String validTarget = animal();
        mvc.perform(merge(validSource, validTarget, "界".repeat(1000)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(reason) FROM animal_merge_record WHERE source_animal_id=?",
                Integer.class, validSource)).isEqualTo(1000);
    }

    @Test
    void localStateCampusAndSpeciesRulesRejectWithoutMutation() {
        for (String[] sourceState : List.of(
                new String[]{"ARCHIVED", "NOT_OPEN", "CAMPUS"},
                new String[]{"ACTIVE", "OPEN", "CAMPUS"},
                new String[]{"ACTIVE", "HANDOVER_PENDING", "CAMPUS"},
                new String[]{"ACTIVE", "ADOPTED", "ADOPTED_HOME"})) {
            clearDatabase();
            String source = animal(CAMPUS, "CAT", sourceState[0], sourceState[1], sourceState[2], 0, "source");
            String target = animal();
            assertThatThrownBy(() -> mergeService.merge(source, target, "state"))
                    .isInstanceOf(StateConflictException.class);
            assertThat(count("animal_merge_record")).isZero();
        }

        for (String[] targetState : List.of(
                new String[]{"ARCHIVED", "NOT_OPEN", "CAMPUS"},
                new String[]{"ACTIVE", "OPEN", "CAMPUS"},
                new String[]{"ACTIVE", "HANDOVER_PENDING", "CAMPUS"},
                new String[]{"ACTIVE", "ADOPTED", "ADOPTED_HOME"})) {
            clearDatabase();
            String source = animal();
            String target = animal(CAMPUS, "CAT", targetState[0], targetState[1],
                    targetState[2], 0, "target");
            assertThatThrownBy(() -> mergeService.merge(source, target, "target state"))
                    .isInstanceOf(StateConflictException.class);
            assertThat(count("animal_merge_record")).isZero();
        }

        clearDatabase();
        String crossCampus = animal(CAMPUS, "CAT", "ACTIVE", "NOT_OPEN", "CAMPUS", 0, "source");
        String otherCampus = animal(OTHER_CAMPUS, "CAT", "ACTIVE", "NOT_OPEN", "CAMPUS", 0, "target");
        assertThatThrownBy(() -> mergeService.merge(crossCampus, otherCampus, "campus"))
                .isInstanceOf(StateConflictException.class);

        clearDatabase();
        String cat = animal(CAMPUS, "CAT", "ACTIVE", "NOT_OPEN", "CAMPUS", 0, "cat");
        String dog = animal(CAMPUS, "DOG", "ACTIVE", "NOT_OPEN", "CAMPUS", 0, "dog");
        assertThatThrownBy(() -> mergeService.merge(cat, dog, "species"))
                .isInstanceOf(StateConflictException.class);
        assertThat(count("animal_merge_record")).isZero();

        clearDatabase();
        String existing = animal();
        assertThatThrownBy(() -> mergeService.merge(id(), existing, "missing source"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> mergeService.merge(existing, id(), "missing target"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void participationRulesPreventRepeatChainsAndAllowManySourcesToOneTarget() {
        String first = animal();
        String target = animal();
        mergeService.merge(first, target, "first");
        assertThatThrownBy(() -> mergeService.merge(first, target, "repeat"))
                .isInstanceOf(StateConflictException.class);

        String chainTarget = animal();
        assertThatThrownBy(() -> mergeService.merge(target, chainTarget, "chain"))
                .isInstanceOf(StateConflictException.class);
        assertThatThrownBy(() -> mergeService.merge(chainTarget, first, "reverse"))
                .isInstanceOf(StateConflictException.class);

        String second = animal();
        mergeService.merge(second, target, "second source");
        assertThat(count("animal_merge_record")).isEqualTo(2);
        assertThat(animalValue(target, "identity_status")).isEqualTo("ACTIVE");
        assertThat(animalInt(target, "version")).isZero();
    }

    @Test
    void concurrentMergeScenariosSerializeWithoutCyclesOrDuplicateSources() throws Exception {
        String samePairSource = animal();
        String samePairTarget = animal();
        assertThat(runConcurrent(() -> mergeService.merge(samePairSource, samePairTarget, "one"),
                () -> mergeService.merge(samePairSource, samePairTarget, "two")))
                .containsExactlyInAnyOrder("OK", "CONFLICT");
        assertThat(count("animal_merge_record")).isEqualTo(1);
        assertThat(animalInt(samePairSource, "version")).isEqualTo(1);

        clearDatabase();
        String sameSource = animal();
        String differentTargetOne = animal();
        String differentTargetTwo = animal();
        assertThat(runConcurrent(() -> mergeService.merge(sameSource, differentTargetOne, "b"),
                () -> mergeService.merge(sameSource, differentTargetTwo, "c")))
                .containsExactlyInAnyOrder("OK", "CONFLICT");
        assertThat(count("animal_merge_record")).isEqualTo(1);

        clearDatabase();
        String cycleA = animal();
        String cycleB = animal();
        assertThat(runConcurrent(() -> mergeService.merge(cycleA, cycleB, "a-b"),
                () -> mergeService.merge(cycleB, cycleA, "b-a")))
                .containsExactlyInAnyOrder("OK", "CONFLICT");
        assertThat(count("animal_merge_record")).isEqualTo(1);

        clearDatabase();
        String sourceOne = animal();
        String sourceTwo = animal();
        String sharedTarget = animal();
        assertThat(runConcurrent(() -> mergeService.merge(sourceOne, sharedTarget, "a-c"),
                () -> mergeService.merge(sourceTwo, sharedTarget, "b-c")))
                .containsOnly("OK");
        assertThat(count("animal_merge_record")).isEqualTo(2);
        assertThat(animalValue(sharedTarget, "identity_status")).isEqualTo("ACTIVE");
        assertThat(animalInt(sharedTarget, "version")).isZero();
    }

    @Test
    void mergeSerializesWithArchiveAndCorrectWithoutLostUpdates() throws Exception {
        String archiveSource = animal();
        String archiveTarget = animal();
        List<String> archiveResults = runConcurrent(
                () -> mergeService.merge(archiveSource, archiveTarget, "merge"),
                () -> animalService.archive(archiveSource));
        assertThat(archiveResults).contains("OK", "CONFLICT");
        assertThat(archiveResults.stream().filter("OK"::equals).count()).isEqualTo(1);

        clearDatabase();
        String correctSource = animal();
        String correctTarget = animal();
        List<String> correctionResults = runConcurrent(
                () -> mergeService.merge(correctSource, correctTarget, "merge"),
                () -> animalService.correct(correctSource,
                        new CorrectAnimalCommand("corrected", null, null, null,
                                null, null, null, null)));
        assertThat(correctionResults.stream().filter("OK"::equals).count()).isBetween(1L, 2L);
        assertThat(animalInt(correctSource, "version")).isBetween(1, 2);
        assertThat(count("animal_merge_record")).isEqualTo(1);
    }

    @Test
    void rollbackCoversMediaTimelineFollowRecordAndSourceUpdateFailures() {
        assertRollbackWhen("media");
        assertRollbackWhen("timeline");
        assertRollbackWhen("follow");
        assertRollbackWhen("record");
        assertRollbackWhen("source-update");
    }

    @Test
    void mergeHistorySupportsIncomingAndOutgoingRecordsWithDeterministicPaging() throws Exception {
        String a = animal();
        String b = animal();
        String c = animal();
        AnimalMergeResult first = mergeService.merge(a, c, "first");
        AnimalMergeResult second = mergeService.merge(b, c, "second");

        JsonNode response = json.readTree(mvc.perform(get(
                        "/api/v1/admin/animals/{id}/merge-history?page=0&size=1", c)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString());
        assertThat(response.at("/items/0/mergeId").asText())
                .isIn(first.mergeId(), second.mergeId());
        mvc.perform(get("/api/v1/admin/animals/{id}/merge-history?size=0", c)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/animals/{id}/merge-history", a)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    private void assertRollbackWhen(String point) {
        Mockito.reset(mergeRepository);
        clearDatabase();
        ADOPTION.eligible();
        String source = animal();
        String target = animal();
        String media = media(source, "animals/rollback-" + point + ".jpg", 0, "PUBLIC");
        String timeline = timeline(source, "CASE", id(), "CASE_RESULT", "PUBLIC");
        follow(MEMBER, source, Instant.parse("2026-01-01T00:00:00Z"));
        RuntimeException failure = new RuntimeException(point);
        switch (point) {
            case "media" -> doThrow(failure).when(mergeRepository)
                    .reassignMedia(source, target);
            case "timeline" -> doThrow(failure).when(mergeRepository)
                    .reassignTimeline(source, target);
            case "follow" -> doThrow(failure).when(mergeRepository)
                    .consolidateFollowers(source, target);
            case "record" -> doThrow(failure).when(mergeRepository)
                    .insert(any(AnimalMergeRecord.class));
            case "source-update" -> doThrow(failure).when(mergeRepository)
                    .markSourceMerged(source, 0);
            default -> throw new IllegalArgumentException(point);
        }
        assertThatThrownBy(() -> mergeService.merge(source, target, point))
                .isSameAs(failure);
        assertThat(animalValue(source, "identity_status")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT animal_id FROM animal_media WHERE id=?",
                String.class, media)).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT animal_id FROM timeline_entry WHERE id=?",
                String.class, timeline)).isEqualTo(source);
        assertThat(countWhere("animal_follow", "animal_id", source)).isEqualTo(1);
        assertThat(countWhere("animal_follow", "animal_id", target)).isZero();
        assertThat(count("animal_merge_record")).isZero();
    }

    private List<String> runConcurrent(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<String> one = () -> outcome(first, barrier);
            Callable<String> two = () -> outcome(second, barrier);
            List<Future<String>> futures = executor.invokeAll(List.of(one, two));
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS),
                    futures.get(1).get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private String outcome(Runnable action, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            action.run();
            return "OK";
        } catch (StateConflictException exception) {
            return "CONFLICT";
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder merge(
            String source, String target, String reason) throws Exception {
        return post("/api/v1/admin/animals/{id}/merge", source)
                .header("X-User-Id", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new MergePayload(target, reason)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adoptionTransition(
            String animalId, String action, String key, String reason) {
        return post("/api/v1/admin/animals/{id}/adoption/{action}", animalId, action)
                .header("X-User-Id", ADMIN)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}");
    }

    private void assertUnchanged(String source, String target) {
        assertThat(animalValue(source, "identity_status")).isEqualTo("ACTIVE");
        assertThat(animalValue(target, "identity_status")).isEqualTo("ACTIVE");
        assertThat(countWhere("animal_media", "animal_id", source)).isEqualTo(1);
        assertThat(countWhere("timeline_entry", "animal_id", source)).isEqualTo(1);
        assertThat(countWhere("animal_follow", "animal_id", source)).isEqualTo(1);
        assertThat(count("animal_merge_record")).isZero();
    }

    private String animal() {
        return animal(CAMPUS, "CAT", "ACTIVE", "NOT_OPEN", "CAMPUS", 0, "animal");
    }

    private String animal(String campus, String species, String identity, String adoption,
                          String context, int version, String name) {
        String id = id();
        jdbc.update("""
                INSERT INTO animal
                    (id,campus_id,display_name,species,sex,sterilization_status,
                     identity_status,adoption_status,current_context,version)
                VALUES (?,?,?,?,'UNKNOWN','UNKNOWN',?,?,?,?)
                """, id, campus, name, species, identity, adoption, context, version);
        return id;
    }

    private String media(String animal, String objectKey, int sortOrder, String visibility) {
        String id = id();
        jdbc.update("""
                INSERT INTO animal_media
                    (id,animal_id,object_key,content_type,media_type,size_bytes,sort_order,visibility)
                VALUES (?,?,?,'image/jpeg','IMAGE',123,?,?)
                """, id, animal, objectKey, sortOrder, visibility);
        return id;
    }

    private String timeline(String animal, String sourceType, String sourceId,
                            String entryType, String visibility) {
        String id = id();
        jdbc.update("""
                INSERT INTO timeline_entry
                    (id,animal_id,source_type,source_id,entry_type,title,summary,occurred_at,visibility)
                VALUES (?,?,?,?,?,'title','summary',NOW(6),?)
                """, id, animal, sourceType, sourceId, entryType, visibility);
        return id;
    }

    private void follow(String user, String animal, Instant createdAt) {
        jdbc.update("INSERT INTO animal_follow (user_id,animal_id,created_at) VALUES (?,?,?)",
                user, animal, Timestamp.from(createdAt));
    }

    private String insertPost(String animal, String type, String relation) {
        String post = id();
        jdbc.update("""
                INSERT INTO post
                    (id,campus_id,animal_id,relation_id,author_user_id,post_type,text_content,
                     visibility,status,version)
                VALUES (?,?,?,?,?,?,'history','PUBLIC','ACTIVE',0)
                """, post, CAMPUS, animal, relation, MEMBER, type);
        return post;
    }

    private String linkedProposal(String animal, String post) {
        String proposal = id();
        jdbc.update("""
                INSERT INTO animal_identity_proposal
                    (id,campus_id,created_by_user_id,source_ai_task_id,source_matching_record_id,
                     post_id,proposed_species,proposed_sex,status,reviewed_at,reviewed_by,
                     resolution_animal_id,review_reason,version)
                VALUES (?,?,?,?,?,?,'CAT','UNKNOWN','LINKED_EXISTING',NOW(6),?,?, 'history',0)
                """, proposal, CAMPUS, MEMBER, id(), id(), post, ADMIN, animal);
        return proposal;
    }

    private String pendingProposal(String post) {
        String proposal = id();
        jdbc.update("""
                INSERT INTO animal_identity_proposal
                    (id,campus_id,created_by_user_id,source_ai_task_id,source_matching_record_id,
                     post_id,proposed_species,proposed_sex,status,version)
                VALUES (?,?,?,?,?,?,'CAT','UNKNOWN','PENDING_REVIEW',0)
                """, proposal, CAMPUS, MEMBER, id(), id(), post);
        return proposal;
    }

    private String observation(String animal, String post) {
        String observation = id();
        jdbc.update("""
                INSERT INTO observation_finalization
                    (id,source_matching_record_id,source_ai_task_id,user_id,campus_id,
                     decision_type,selected_animal_id,post_id,proposal_id,decision_hash)
                VALUES (?,?,?,?,?,'SELECT_EXISTING',?,?,NULL,?)
                """, observation, id(), id(), MEMBER, CAMPUS, animal, post, "a".repeat(64));
        return observation;
    }

    private String postAnimal(String post) {
        return jdbc.queryForObject("SELECT animal_id FROM post WHERE id=?", String.class, post);
    }

    private String animalValue(String animal, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM animal WHERE id=?",
                String.class, animal);
    }

    private int animalInt(String animal, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM animal WHERE id=?",
                Integer.class, animal);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class, value);
    }

    private void clearDatabase() {
        for (String table : List.of("community_content_report", "community_governance_audit",
                "observation_finalization", "animal_identity_proposal", "post_like", "comment",
                "post_media", "post", "animal_follow", "timeline_entry", "animal_media",
                "animal_adoption_status_command", "animal_merge_record",
                "processed_domain_event", "animal")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private IdentityGateway.CurrentUser admin() {
        return new IdentityGateway.CurrentUser(ADMIN, "admin", "ACTIVE", "GOVERNANCE_ADMIN");
    }

    private IdentityGateway.CurrentUser member() {
        return new IdentityGateway.CurrentUser(MEMBER, "member", "ACTIVE", "USER");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record MergePayload(String targetAnimalId, String reason) {
    }

    static class AdoptionStub {
        private final HttpServer server;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastInternalService = new AtomicReference<>();
        private volatile int status = 200;
        private volatile String body = "{\"eligible\":true,\"blockers\":[]}";

        AdoptionStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/internal/v1/adoption-merge-eligibility/check", this::check);
                server.createContext("/internal/v1/adoption-merge-eligibility/check-animal",
                        this::check);
                server.start();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        void reset() { calls.set(0); lastInternalService.set(null); eligible(); }
        void eligible() { status = 200; body = "{\"eligible\":true,\"blockers\":[]}"; }
        void ineligible() { status = 200; body = "{\"eligible\":false,\"blockers\":[{\"animalId\":\"x\",\"blockerCode\":\"ACTIVE_LISTING\"}]}"; }
        void serverError() { status = 503; body = "{}"; }
        void invalid() { status = 200; body = "{}"; }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void stop() { server.stop(0); }

        private void check(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            lastInternalService.set(exchange.getRequestHeaders().getFirst("X-Internal-Service"));
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    static class TestDatabase {
        private final String url;
        private final String username;
        private final String password;
        private final MySQLContainer<?> container;

        TestDatabase(String url, String username, String password, MySQLContainer<?> container) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.container = container;
        }

        static TestDatabase create() {
            if ("true".equalsIgnoreCase(System.getenv("ANIMALLINK_TEST_EXTERNAL_MYSQL"))) {
                return new TestDatabase("jdbc:mysql://127.0.0.1:"
                        + required("ANIMALLINK_TEST_MYSQL_PORT") + "/"
                        + required("ANIMALLINK_TEST_MYSQL_DATABASE")
                        + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC",
                        required("ANIMALLINK_TEST_MYSQL_USERNAME"),
                        required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null);
            }
            MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0.41")
                    .withDatabaseName("animal_merge_test")
                    .withUsername("test").withPassword("test");
            container.start();
            return new TestDatabase(container.getJdbcUrl(), container.getUsername(),
                    container.getPassword(), container);
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required");
            }
            return value;
        }

        String url() { return url; }
        String username() { return username; }
        String password() { return password; }
        void close() { if (container != null) container.stop(); }
    }
}
