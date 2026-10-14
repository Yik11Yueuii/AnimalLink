package org.animallink.animal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CampusCirclePhase1CIntegrationTest {
    private static final String MEMBER_ID = "00000000-0000-0000-0000-000000000201";
    private static final String NON_MEMBER_ID = "00000000-0000-0000-0000-000000000202";
    private static final String SOCIAL_ID = "00000000-0000-0000-0000-000000000203";
    private static final String FAILURE_ID = "00000000-0000-0000-0000-000000000204";
    private static final String ADMIN_ID = "00000000-0000-0000-0000-000000000299";
    private static final String INACTIVE_ADMIN_ID = "00000000-0000-0000-0000-000000000298";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000201";
    private static final String OTHER_CAMPUS_ID = "10000000-0000-0000-0000-000000000202";
    private static final String ANIMAL_ID = "20000000-0000-0000-0000-000000000201";
    private static final String OTHER_ANIMAL_ID = "20000000-0000-0000-0000-000000000202";
    private static final IdentityStub IDENTITY = new IdentityStub();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_circle_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("animallink.identity.base-url", IDENTITY::baseUrl);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM community_governance_audit");
        jdbcTemplate.update("DELETE FROM post_like");
        jdbcTemplate.update("DELETE FROM comment");
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post");
        jdbcTemplate.update("DELETE FROM animal_follow");
        jdbcTemplate.update("DELETE FROM timeline_entry");
        jdbcTemplate.update("DELETE FROM animal_media");
        jdbcTemplate.update("DELETE FROM animal");
        insertAnimal(ANIMAL_ID, CAMPUS_ID, "小橘", "CAT", "ACTIVE");
        insertAnimal(OTHER_ANIMAL_ID, OTHER_CAMPUS_ID, "阿黄", "DOG", "ACTIVE");
    }

    @AfterAll
    static void stopIdentityStub() {
        IDENTITY.stop();
    }

    @Test
    void publicFeedIsCampusIsolatedOrderedAndBatchEnriched() throws Exception {
        String older = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "较早动态", "ACTIVE");
        String newer = insertPost(CAMPUS_ID, null, MEMBER_ID, "较新动态", "ACTIVE");
        insertPost(OTHER_CAMPUS_ID, OTHER_ANIMAL_ID, ADMIN_ID, "其他校园", "ACTIVE");
        insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "隐藏动态", "HIDDEN");
        jdbcTemplate.update("INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", NON_MEMBER_ID, newer);
        jdbcTemplate.update("""
                INSERT INTO comment (id, post_id, author_user_id, content, status)
                VALUES (?, ?, ?, '看到啦', 'ACTIVE')
                """, UUID.randomUUID().toString(), newer, MEMBER_ID);

        mockMvc.perform(get("/api/v1/campuses/{campusId}/feed", CAMPUS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newer))
                .andExpect(jsonPath("$.items[0].author.displayName").value("成员用户"))
                .andExpect(jsonPath("$.items[0].animal").doesNotExist())
                .andExpect(jsonPath("$.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.items[0].commentCount").value(1))
                .andExpect(jsonPath("$.items[1].id").value(older))
                .andExpect(jsonPath("$.items[1].animal.id").value(ANIMAL_ID));

        mockMvc.perform(get("/api/v1/campuses/{campusId}/feed", CAMPUS_ID)
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newer));
        mockMvc.perform(get("/api/v1/campuses/{campusId}/feed", CAMPUS_ID)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(older));
        mockMvc.perform(get("/api/v1/posts/{postId}", older))
                .andExpect(status().isOk()).andExpect(jsonPath("$.animal.displayName").value("小橘"));
    }

    @Test
    void publishingRequiresCampusMembershipAndValidSameCampusAnimal() throws Exception {
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, ANIMAL_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campusId").value(CAMPUS_ID))
                .andExpect(jsonPath("$.author.displayName").value("成员用户"))
                .andExpect(jsonPath("$.animal.id").value(ANIMAL_ID));

        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", NON_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", SOCIAL_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON)
                        .content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postRequest(CAMPUS_ID, OTHER_ANIMAL_ID)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postRequest(CAMPUS_ID, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.animal").doesNotExist());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", FAILURE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void commentsRequireMembershipWhileLikesRequireOnlyLoginAndAreUnique() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "可互动动态", "ACTIVE");
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", postId)
                        .header("X-User-Id", MEMBER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"状态看起来不错\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.displayName").value("成员用户"));
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", postId)
                        .header("X-User-Id", NON_MEMBER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"无成员权限\"}"))
                .andExpect(status().isForbidden());
        String hiddenPost = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "隐藏", "HIDDEN");
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", hiddenPost)
                        .header("X-User-Id", MEMBER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"不可评论\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(post("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(delete("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void followsAreLoginScopedUniquePaginatedAndHideArchivedAnimals() throws Exception {
        mockMvc.perform(post("/api/v1/animals/{animalId}/follow", ANIMAL_ID)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(post("/api/v1/animals/{animalId}/follow", ANIMAL_ID)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/users/me/animal-follows")
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].animal.id").value(ANIMAL_ID));

        jdbcTemplate.update("UPDATE animal SET identity_status = 'ARCHIVED' WHERE id = ?", ANIMAL_ID);
        mockMvc.perform(get("/api/v1/users/me/animal-follows")
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(delete("/api/v1/animals/{animalId}/follow", ANIMAL_ID)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void governanceHideIsAuthoritativeAndNeverWritesTimeline() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "待治理动态", "ACTIVE");
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", MEMBER_ID).header("X-System-Role", "GOVERNANCE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"越权尝试\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  不符合社区规范  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HIDDEN"));
        mockMvc.perform(get("/api/v1/posts/{postId}", postId)).andExpect(status().isNotFound());
        Long timelineCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM timeline_entry", Long.class);
        assertThat(timelineCount).isZero();
        assertThat(jdbcTemplate.queryForMap("SELECT `action`, reason FROM community_governance_audit"))
                .containsEntry("action", "HIDE").containsEntry("reason", "不符合社区规范");

        mockMvc.perform(post("/api/v1/admin/posts/{postId}/restore", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"复核后恢复\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/v1/posts/{postId}", postId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/restore", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复恢复\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"二次隐藏\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/posts/{postId}/governance-actions", postId)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].action").value("HIDE"));
    }

    @Test
    void commentGovernanceUpdatesPublicCountsAndAdminReads() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "评论治理", "ACTIVE");
        String visible = insertComment(postId, "保留评论", "ACTIVE");
        String governed = insertComment(postId, "需要隐藏", "ACTIVE");

        mockMvc.perform(post("/api/v1/admin/comments/{commentId}/hide", governed)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  包含不当信息  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HIDDEN"));
        mockMvc.perform(get("/api/v1/posts/{postId}/comments", postId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(visible));
        mockMvc.perform(get("/api/v1/posts/{postId}", postId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commentCount").value(1));

        mockMvc.perform(get("/api/v1/admin/comments").header("X-User-Id", ADMIN_ID)
                        .param("status", "HIDDEN").param("campusId", CAMPUS_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].commentId").value(governed));
        mockMvc.perform(get("/api/v1/admin/comments/{commentId}", governed)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentPostStatus").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/admin/posts/{postId}", postId)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeCommentCount").value(1))
                .andExpect(jsonPath("$.hiddenCommentCount").value(1));

        mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"临时隐藏父 Post\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/comments/{commentId}/restore", governed)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"复核后恢复\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/admin/comments/{commentId}", governed)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentPostStatus").value("HIDDEN"));
        mockMvc.perform(get("/api/v1/posts/{postId}/comments", postId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/comments/{commentId}/restore", governed)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复恢复\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mockMvc.perform(get("/api/v1/admin/comments/{commentId}/governance-actions", governed)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].action").value("RESTORE"));
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/restore", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"恢复父 Post\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/posts/{postId}/comments", postId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(post("/api/v1/admin/comments/{commentId}/hide", governed)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"再次隐藏\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/comments/{commentId}/governance-actions", governed)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
        List<java.util.Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT `action` AS action, from_state, to_state, reason
                FROM community_governance_audit
                WHERE resource_type = 'COMMENT' AND resource_id = ?
                ORDER BY created_at ASC, id ASC
                """, governed);
        assertThat(audits).hasSize(3);
        assertThat(audits).extracting(audit -> audit.get("action"))
                .containsExactly("HIDE", "RESTORE", "HIDE");
        assertThat(audits).extracting(audit -> audit.get("from_state"))
                .containsExactly("ACTIVE", "HIDDEN", "ACTIVE");
        assertThat(audits).extracting(audit -> audit.get("to_state"))
                .containsExactly("HIDDEN", "ACTIVE", "HIDDEN");
        assertThat(audits).extracting(audit -> audit.get("reason"))
                .containsExactly("包含不当信息", "复核后恢复", "再次隐藏");
    }

    @Test
    void governanceValidatesAuthorizationReasonsQueuesAndHiddenUnlikePrivacy() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "治理列表", "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO post_media
                    (id, post_id, object_key, content_type, media_type, size_bytes, sort_order, visibility)
                VALUES (?, ?, 'governance/private-object.jpg', 'image/jpeg', 'IMAGE', 12, 0, 'PUBLIC')
                """, UUID.randomUUID().toString(), postId);
        jdbcTemplate.update("INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", SOCIAL_ID, postId);
        jdbcTemplate.update("INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", NON_MEMBER_ID, postId);

        mockMvc.perform(get("/api/v1/admin/posts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/posts").header("X-User-Id", MEMBER_ID))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"隐藏以保护社区\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/posts").header("X-User-Id", ADMIN_ID)
                        .param("status", "HIDDEN").param("campusId", CAMPUS_ID)
                        .param("postType", "CAMPUS_POST"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].postId").value(postId));
        mockMvc.perform(get("/api/v1/admin/posts/{postId}", postId)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media[0].contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.media[0].objectKey").doesNotExist());
        mockMvc.perform(delete("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.count").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM post_like WHERE post_id = ?",
                Integer.class, postId)).isOne();

        mockMvc.perform(post("/api/v1/admin/posts/{postId}/restore", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"恢复\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/posts/{postId}", postId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.relationId").doesNotExist());
    }

    @Test
    void duplicatePostHideRaceHasOneSuccessOneConflictAndOneAudit() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "并发治理", "ACTIVE");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = List.of(
                    executor.submit(() -> governancePostStatus(postId, start)),
                    executor.submit(() -> governancePostStatus(postId, start)));
            start.countDown();
            assertThat(results.stream().map(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).sorted().toList()).containsExactly(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM post WHERE id = ?",
                Integer.class, postId)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_governance_audit "
                + "WHERE resource_type='POST' AND resource_id=?", Integer.class, postId)).isOne();
    }

    @Test
    void duplicateCommentHideRaceHasOneSuccessOneConflictAndOneAudit() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "评论并发", "ACTIVE");
        String commentId = insertComment(postId, "并发治理评论", "ACTIVE");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = List.of(
                    executor.submit(() -> governanceCommentStatus(commentId, start)),
                    executor.submit(() -> governanceCommentStatus(commentId, start)));
            start.countDown();
            assertThat(results.stream().map(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).sorted().toList()).containsExactly(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_governance_audit "
                + "WHERE resource_type='COMMENT' AND resource_id=?", Integer.class, commentId)).isOne();
    }

    @Test
    void allGovernanceMutationsRejectInvalidReasons() throws Exception {
        String activePost = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "有效 Post", "ACTIVE");
        String hiddenPost = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "隐藏 Post", "HIDDEN");
        String activeComment = insertComment(activePost, "有效 Comment", "ACTIVE");
        String hiddenComment = insertComment(activePost, "隐藏 Comment", "HIDDEN");

        assertInvalidReasons("/api/v1/admin/posts/{id}/hide", activePost);
        assertInvalidReasons("/api/v1/admin/posts/{id}/restore", hiddenPost);
        assertInvalidReasons("/api/v1/admin/comments/{id}/hide", activeComment);
        assertInvalidReasons("/api/v1/admin/comments/{id}/restore", hiddenComment);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_governance_audit",
                Integer.class)).isZero();
    }

    @Test
    void historicalHiddenContentCanBeRestoredWithoutSyntheticHideAudit() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "历史隐藏", "HIDDEN");
        String commentId = insertComment(postId, "历史隐藏评论", "HIDDEN");
        mockMvc.perform(post("/api/v1/admin/posts/{id}/restore", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"恢复历史内容\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/comments/{id}/restore", commentId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"恢复历史评论\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_governance_audit "
                + "WHERE `action`='HIDE'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM community_governance_audit "
                + "WHERE `action`='RESTORE'", Integer.class)).isEqualTo(2);
    }

    @Test
    void everyGovernanceRouteUsesTheSameAuthorizationGate() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "授权 Post", "ACTIVE");
        String commentId = insertComment(postId, "授权 Comment", "ACTIVE");
        List<Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>> routes = List.of(
                () -> get("/api/v1/admin/posts"),
                () -> get("/api/v1/admin/posts/{id}", postId),
                () -> get("/api/v1/admin/posts/{id}/governance-actions", postId),
                () -> get("/api/v1/admin/comments"),
                () -> get("/api/v1/admin/comments/{id}", commentId),
                () -> get("/api/v1/admin/comments/{id}/governance-actions", commentId),
                () -> governanceRequest("/api/v1/admin/posts/{id}/hide", postId),
                () -> governanceRequest("/api/v1/admin/posts/{id}/restore", postId),
                () -> governanceRequest("/api/v1/admin/comments/{id}/hide", commentId),
                () -> governanceRequest("/api/v1/admin/comments/{id}/restore", commentId));
        for (Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> route : routes) {
            mockMvc.perform(route.get()).andExpect(status().isUnauthorized());
            mockMvc.perform(route.get().header("X-User-Id", MEMBER_ID)).andExpect(status().isForbidden());
            mockMvc.perform(route.get().header("X-User-Id", INACTIVE_ADMIN_ID))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void databaseConstraintsEnforceLikeFollowAndAnimalReferences() {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "约束测试", "ACTIVE");
        jdbcTemplate.update("INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", MEMBER_ID, postId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", MEMBER_ID, postId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO post (id, campus_id, animal_id, author_user_id, text_content)
                VALUES (?, ?, ?, ?, '无效关联')
                """, UUID.randomUUID().toString(), CAMPUS_ID, UUID.randomUUID().toString(), MEMBER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("INSERT INTO animal_follow (user_id, animal_id) VALUES (?, ?)",
                MEMBER_ID, ANIMAL_ID);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO animal_follow (user_id, animal_id) VALUES (?, ?)", MEMBER_ID, ANIMAL_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO comment (id, post_id, author_user_id, content, status)
                VALUES (?, ?, ?, '无效 Post', 'ACTIVE')
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), MEMBER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertAnimal(String id, String campusId, String name, String species, String status) {
        jdbcTemplate.update("""
                INSERT INTO animal
                    (id, campus_id, display_name, species, sex, identity_status,
                     adoption_status, current_context)
                VALUES (?, ?, ?, ?, 'UNKNOWN', ?, 'NOT_OPEN', 'CAMPUS')
                """, id, campusId, name, species, status);
    }

    private String insertPost(String campusId, String animalId, String author, String text, String status) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO post
                    (id, campus_id, animal_id, author_user_id, post_type, text_content,
                     visibility, status, created_at)
                VALUES (?, ?, ?, ?, 'CAMPUS_POST', ?, 'PUBLIC', ?, CURRENT_TIMESTAMP(6))
                """, id, campusId, animalId, author, text, status);
        return id;
    }

    private String insertComment(String postId, String content, String status) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO comment (id, post_id, author_user_id, content, status)
                VALUES (?, ?, ?, ?, ?)
                """, id, postId, MEMBER_ID, content, status);
        return id;
    }

    private int governancePostStatus(String postId, CountDownLatch start) throws Exception {
        start.await();
        return mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"并发隐藏\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int governanceCommentStatus(String commentId, CountDownLatch start) throws Exception {
        start.await();
        return mockMvc.perform(post("/api/v1/admin/comments/{commentId}/hide", commentId)
                        .header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"并发隐藏评论\"}"))
                .andReturn().getResponse().getStatus();
    }

    private void assertInvalidReasons(String path, String id) throws Exception {
        mockMvc.perform(post(path, id).header("X-User-Id", ADMIN_ID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String body : List.of("{\"reason\":null}", "{\"reason\":\"\"}",
                "{\"reason\":\"   \"}", "{\"reason\":\"" + "字".repeat(1001) + "\"}")) {
            mockMvc.perform(post(path, id).header("X-User-Id", ADMIN_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder governanceRequest(
            String path, String id) {
        return post(path, id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"授权验证\"}");
    }

    private String postRequest(String campusId, String animalId) {
        String animal = animalId == null ? "null" : "\"" + animalId + "\"";
        return """
                {"campusId":"%s","animalId":%s,"textContent":"校园动态","media":[]}
                """.formatted(campusId, animal);
    }

    private static final class IdentityStub {
        private final HttpServer server;

        private IdentityStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/api/v1/users/me", this::user);
                server.createContext("/internal/v1/users/summaries", this::summaries);
                server.createContext("/internal/v1/users", this::membership);
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void user(HttpExchange exchange) throws IOException {
            String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
            if (MEMBER_ID.equals(userId)) {
                respond(exchange, 200, userJson(MEMBER_ID, "成员用户", "USER"));
            } else if (NON_MEMBER_ID.equals(userId)) {
                respond(exchange, 200, userJson(NON_MEMBER_ID, "西校成员", "USER"));
            } else if (SOCIAL_ID.equals(userId)) {
                respond(exchange, 200, userJson(SOCIAL_ID, "社会用户", "USER"));
            } else if (FAILURE_ID.equals(userId)) {
                respond(exchange, 200, userJson(FAILURE_ID, "依赖失败用户", "USER"));
            } else if (ADMIN_ID.equals(userId)) {
                respond(exchange, 200, userJson(ADMIN_ID, "治理管理员", "GOVERNANCE_ADMIN"));
            } else if (INACTIVE_ADMIN_ID.equals(userId)) {
                respond(exchange, 200, "{\"id\":\"" + INACTIVE_ADMIN_ID
                        + "\",\"displayName\":\"停用管理员\",\"accountStatus\":\"INACTIVE\","
                        + "\"systemRole\":\"GOVERNANCE_ADMIN\"}");
            } else {
                respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
            }
        }

        private void membership(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/" + MEMBER_ID + "/campus-memberships/" + CAMPUS_ID)) {
                respond(exchange, 200,
                        "{\"exists\":true,\"membershipType\":\"STUDENT\",\"status\":\"ACTIVE\"}");
            } else if (path.contains("/" + NON_MEMBER_ID + "/campus-memberships/" + OTHER_CAMPUS_ID)) {
                respond(exchange, 200,
                        "{\"exists\":true,\"membershipType\":\"ALUMNI\",\"status\":\"ACTIVE\"}");
            } else if (path.contains("/" + FAILURE_ID + "/campus-memberships/")) {
                respond(exchange, 500, "{\"code\":\"FAILURE\"}");
            } else {
                respond(exchange, 200, "{\"exists\":false,\"membershipType\":null,\"status\":null}");
            }
        }

        private void summaries(HttpExchange exchange) throws IOException {
            respond(exchange, 200, "[" + summaryJson(MEMBER_ID, "成员用户") + ","
                    + summaryJson(NON_MEMBER_ID, "西校成员") + ","
                    + summaryJson(SOCIAL_ID, "社会用户") + ","
                    + summaryJson(ADMIN_ID, "治理管理员") + "]");
        }

        private String userJson(String id, String name, String role) {
            return "{\"id\":\"" + id + "\",\"displayName\":\"" + name
                    + "\",\"accountStatus\":\"ACTIVE\",\"systemRole\":\"" + role + "\"}";
        }

        private String summaryJson(String id, String name) {
            return "{\"id\":\"" + id + "\",\"displayName\":\"" + name + "\"}";
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
