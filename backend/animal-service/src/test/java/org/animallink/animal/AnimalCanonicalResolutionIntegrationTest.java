package org.animallink.animal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.animallink.animal.application.IdentityGateway;
import org.animallink.animal.domain.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AnimalCanonicalResolutionIntegrationTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String MEMBER = "00000000-0000-0000-0000-000000000011";
    private static final String CAMPUS = "10000000-0000-0000-0000-000000000001";
    private static final String SOURCE = "20000000-0000-0000-0000-000000000001";
    private static final String TARGET = "20000000-0000-0000-0000-000000000002";
    private static final String THIRD = "20000000-0000-0000-0000-000000000003";
    private static final Instant OCCURRED = Instant.parse("2026-10-10T12:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("canonical_resolution_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.messaging.enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean IdentityGateway identityGateway;

    @BeforeEach
    void reset() {
        clearBusinessTables();
        Mockito.reset(identityGateway);
        when(identityGateway.requireCurrentUser()).thenReturn(admin());
        when(identityGateway.currentUserIfPresent()).thenReturn(Optional.empty());
        when(identityGateway.userSummaries(anySet())).thenAnswer(invocation ->
                invocation.<java.util.Set<String>>getArgument(0).stream().collect(
                        Collectors.toMap(id -> id,
                                id -> new IdentityGateway.UserSummary(id, "author"))));
    }

    @Test
    void publicDetailResolvesOneHopAndKeepsAdoptionCompatibleContract() throws Exception {
        animal(SOURCE, "source-private", "MERGED");
        animal(TARGET, "canonical-target", "ACTIVE");
        merge(SOURCE, TARGET);
        media(TARGET, "target.jpg", 0);
        media(TARGET, "source-migrated.jpg", 1);

        mvc.perform(get("/api/v1/animals/{id}", SOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedAnimalId").value(SOURCE))
                .andExpect(jsonPath("$.canonicalAnimalId").value(TARGET))
                .andExpect(jsonPath("$.id").value(TARGET))
                .andExpect(jsonPath("$.displayName").value("canonical-target"))
                .andExpect(jsonPath("$.adoptionStatus").value("NOT_OPEN"))
                .andExpect(jsonPath("$.media.length()").value(2));
        mvc.perform(get("/api/v1/animals/{id}", TARGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedAnimalId").value(TARGET))
                .andExpect(jsonPath("$.canonicalAnimalId").value(TARGET))
                .andExpect(jsonPath("$.id").value(TARGET))
                .andExpect(jsonPath("$.displayName").value("canonical-target"));

        mvc.perform(get("/api/v1/public/animals/{id}", SOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TARGET))
                .andExpect(jsonPath("$.displayName").value("canonical-target"))
                .andExpect(jsonPath("$.requestedAnimalId").doesNotExist())
                .andExpect(jsonPath("$.canonicalAnimalId").doesNotExist())
                .andExpect(jsonPath("$.media").doesNotExist());
    }

    @Test
    void publicResolutionFailsSafelyForUnknownArchivedMissingAndBrokenChain() throws Exception {
        String archived = id();
        String missingMapping = id();
        animal(archived, "archived", "ARCHIVED");
        animal(missingMapping, "broken", "MERGED");
        mvc.perform(get("/api/v1/animals/{id}", id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/animals/{id}", archived)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/animals/{id}", missingMapping)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/animals/{id}/timeline", missingMapping))
                .andExpect(status().isNotFound());

        animal(SOURCE, "source", "MERGED");
        animal(TARGET, "target", "ACTIVE");
        merge(SOURCE, TARGET);
        jdbc.update("UPDATE animal SET identity_status='ARCHIVED' WHERE id=?", TARGET);
        mvc.perform(get("/api/v1/animals/{id}", SOURCE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/animals/{id}", TARGET)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/animals/{id}/timeline", SOURCE))
                .andExpect(status().isNotFound());

        jdbc.update("UPDATE animal SET identity_status='MERGED' WHERE id=?", TARGET);
        animal(THIRD, "third", "ACTIVE");
        mergeRecord(TARGET, THIRD);
        mvc.perform(get("/api/v1/animals/{id}", SOURCE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/animals/{id}/timeline", SOURCE))
                .andExpect(status().isNotFound());
    }

    @Test
    void sourceAndTargetTimelineReturnSameMigratedCanonicalDataset() throws Exception {
        animal(SOURCE, "source", "MERGED");
        animal(TARGET, "target", "ACTIVE");
        merge(SOURCE, TARGET);
        String first = timeline(TARGET, "CASE", "case-1", OCCURRED.minusSeconds(60));
        String second = timeline(TARGET, "POST", "post-1", OCCURRED);

        String sourceJson = mvc.perform(get("/api/v1/animals/{id}/timeline", SOURCE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].animalId").value(TARGET))
                .andReturn().getResponse().getContentAsString();
        String targetJson = mvc.perform(get("/api/v1/animals/{id}/timeline", TARGET))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(sourceJson).isEqualTo(targetJson);
        assertThat(sourceJson).contains(first, second);
    }

    @Test
    void governanceDetailIsAuthorizedAndReturnsRawAnyStatusAnimal() throws Exception {
        String archived = id();
        animal(SOURCE, "raw-source-profile", "MERGED");
        animal(TARGET, "target-profile", "ACTIVE");
        animal(archived, "archived-profile", "ARCHIVED");
        merge(SOURCE, TARGET);

        doThrow(new UnauthorizedException("login"))
                .when(identityGateway).requireCurrentUser();
        mvc.perform(get("/api/v1/admin/animals/{id}", SOURCE))
                .andExpect(status().isUnauthorized());
        doReturn(new IdentityGateway.CurrentUser(MEMBER, "member", "ACTIVE", "USER"))
                .when(identityGateway).requireCurrentUser();
        mvc.perform(get("/api/v1/admin/animals/{id}", SOURCE))
                .andExpect(status().isForbidden());
        doReturn(admin()).when(identityGateway).requireCurrentUser();
        mvc.perform(get("/api/v1/admin/animals/{id}", SOURCE)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SOURCE))
                .andExpect(jsonPath("$.displayName").value("raw-source-profile"))
                .andExpect(jsonPath("$.identityStatus").value("MERGED"))
                .andExpect(jsonPath("$.canonicalAnimalId").value(TARGET))
                .andExpect(jsonPath("$.mergeId").isNotEmpty())
                .andExpect(jsonPath("$.credentials").doesNotExist());
        mvc.perform(get("/api/v1/admin/animals/{id}", TARGET)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.canonicalAnimalId").value(TARGET));
        mvc.perform(get("/api/v1/admin/animals/{id}", archived)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityStatus").value("ARCHIVED"))
                .andExpect(jsonPath("$.canonicalAnimalId").value(archived));
    }

    @Test
    void communityPublicProjectionUsesCanonicalAnimalWithoutRewritingHistory() throws Exception {
        animal(SOURCE, "source-private", "MERGED");
        animal(TARGET, "target-public", "ACTIVE");
        merge(SOURCE, TARGET);
        media(TARGET, "target-cover.jpg", 0);
        String sourcePost = post(SOURCE, null, "CAMPUS_POST", "ACTIVE", "source post");
        String targetPost = post(TARGET, null, "CAMPUS_POST", "ACTIVE", "target post");
        String noAnimalPost = post(null, null, "CAMPUS_POST", "ACTIVE", "no animal");
        String relationId = id();
        String postAdoption = post(SOURCE, relationId, "POST_ADOPTION", "ACTIVE", "history");
        String hidden = post(SOURCE, null, "CAMPUS_POST", "HIDDEN", "hidden");
        String removed = post(SOURCE, null, "CAMPUS_POST", "REMOVED", "removed");

        mvc.perform(get("/api/v1/posts/{id}", sourcePost))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.animal.id").value(TARGET))
                .andExpect(jsonPath("$.animal.displayName").value("target-public"));
        mvc.perform(get("/api/v1/posts/{id}", targetPost))
                .andExpect(status().isOk()).andExpect(jsonPath("$.animal.id").value(TARGET));
        mvc.perform(get("/api/v1/posts/{id}", noAnimalPost))
                .andExpect(status().isOk()).andExpect(jsonPath("$.animal").doesNotExist());
        mvc.perform(get("/api/v1/posts/{id}", postAdoption))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.animal.id").value(TARGET))
                .andExpect(jsonPath("$.relationId").doesNotExist());
        mvc.perform(get("/api/v1/posts/{id}", hidden)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/posts/{id}", removed)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/campuses/{id}/feed", CAMPUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + sourcePost
                        + "')].animal.id").value(TARGET));

        assertThat(jdbc.queryForObject("SELECT animal_id FROM post WHERE id=?", String.class,
                sourcePost)).isEqualTo(SOURCE);
        assertThat(jdbc.queryForObject("SELECT animal_id FROM post WHERE id=?", String.class,
                postAdoption)).isEqualTo(SOURCE);
        assertThat(jdbc.queryForObject("SELECT relation_id FROM post WHERE id=?", String.class,
                postAdoption)).isEqualTo(relationId);
        mvc.perform(get("/api/v1/admin/posts/{id}", sourcePost)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.animalId").value(SOURCE));
    }

    @Test
    void phase5GcLifecycleProjectionMigrationIsApplied() {
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history",
                Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",
                Integer.class)).isEqualTo(13);
    }

    private void clearBusinessTables() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'",
                String.class).forEach(table -> jdbc.execute("TRUNCATE TABLE `" + table + "`"));
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    private void animal(String id, String displayName, String status) {
        jdbc.update("""
                INSERT INTO animal
                    (id,campus_id,display_name,species,sex,coat_color,distinctive_features,
                     description,sterilization_status,typical_area,identity_status,
                     adoption_status,current_context,version)
                VALUES (?, ?, ?, 'CAT', 'UNKNOWN', 'black', ?, ?, 'UNKNOWN', 'north', ?,
                        'NOT_OPEN', 'CAMPUS', 0)
                """, id, CAMPUS, displayName, displayName + "-feature",
                displayName + "-description", status);
    }

    private void merge(String source, String target) {
        mergeRecord(source, target);
        jdbc.update("UPDATE animal SET identity_status='MERGED' WHERE id=?", source);
    }

    private void mergeRecord(String source, String target) {
        jdbc.update("""
                INSERT INTO animal_merge_record
                    (id,source_animal_id,target_animal_id,actor_user_id,reason)
                VALUES (?,?,?,?, 'duplicate')
                """, id(), source, target, ADMIN);
    }

    private void media(String animalId, String objectKey, int sortOrder) {
        jdbc.update("""
                INSERT INTO animal_media
                    (id,animal_id,object_key,content_type,media_type,size_bytes,sort_order,visibility)
                VALUES (?,?,?,'image/jpeg','IMAGE',100,?,'PUBLIC')
                """, id(), animalId, objectKey, sortOrder);
    }

    private String timeline(String animalId, String sourceType, String sourceId, Instant occurred) {
        String id = id();
        jdbc.update("""
                INSERT INTO timeline_entry
                    (id,animal_id,source_type,source_id,entry_type,title,occurred_at,visibility)
                VALUES (?,?,?,?, 'HISTORY','entry',?,'PUBLIC')
                """, id, animalId, sourceType, sourceId, Timestamp.from(occurred));
        return id;
    }

    private String post(String animalId, String relationId, String type, String status,
                        String content) {
        String id = id();
        jdbc.update("""
                INSERT INTO post
                    (id,campus_id,animal_id,relation_id,author_user_id,post_type,text_content,
                     visibility,status)
                VALUES (?,?,?,?,?,?,?,'PUBLIC',?)
                """, id, CAMPUS, animalId, relationId, MEMBER, type, content, status);
        return id;
    }

    private IdentityGateway.CurrentUser admin() {
        return new IdentityGateway.CurrentUser(ADMIN, "admin", "ACTIVE", "GOVERNANCE_ADMIN");
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}
