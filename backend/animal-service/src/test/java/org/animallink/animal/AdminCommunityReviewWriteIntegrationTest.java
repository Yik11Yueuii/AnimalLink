package org.animallink.animal;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "animallink.authentication.animal-admin-assertion.keys=animal-v1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "animallink.messaging.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AdminCommunityReviewWriteIntegrationTest {
    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String ADMIN = "00000000-0000-0000-0000-000000000801";
    private static final String CAMPUS = "10000000-0000-0000-0000-000000000801";
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000801";
    private static final String AUTHOR = "30000000-0000-0000-0000-000000000801";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("community_review_write_test")
            .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM community_governance_audit");
        jdbc.update("DELETE FROM comment");
        jdbc.update("DELETE FROM post_like");
        jdbc.update("DELETE FROM post_media");
        jdbc.update("DELETE FROM timeline_entry");
        jdbc.update("DELETE FROM post");
        jdbc.update("DELETE FROM animal_merge_record");
        jdbc.update("DELETE FROM animal");
        jdbc.update("""
                INSERT INTO animal
                  (id,campus_id,display_name,species,sex,identity_status,adoption_status,current_context)
                VALUES (?,?,'审核动物','CAT','UNKNOWN','ACTIVE','OPEN','CAMPUS')
                """, ANIMAL, CAMPUS);
    }

    @Test
    void trustedWriterApprovesAndRejectsWithReviewerAuditAndPublicReadConsistency() throws Exception {
        String post = seedPost("CAMPUS_POST", "PENDING", "ACTIVE", "PUBLIC");
        String comment = comment(post, "PENDING", "ACTIVE");

        perform(postPath(post, "approve"), "通过公开审核")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("APPROVED"));
        perform(commentPath(comment, "reject"), "评论不适合公开")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("REJECTED"));

        var reviewedPost = jdbc.queryForMap(
                "SELECT review_status,reviewed_by_user_id,reviewed_at FROM post WHERE id=?", post);
        assertThat(reviewedPost)
                .containsEntry("review_status", "APPROVED")
                .containsEntry("reviewed_by_user_id", ADMIN);
        assertThat(reviewedPost.get("reviewed_at")).isNotNull();
        var reviewedComment = jdbc.queryForMap(
                "SELECT review_status,reviewed_by_user_id,reviewed_at FROM comment WHERE id=?", comment);
        assertThat(reviewedComment)
                .containsEntry("review_status", "REJECTED")
                .containsEntry("reviewed_by_user_id", ADMIN);
        assertThat(reviewedComment.get("reviewed_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_governance_audit WHERE actor_user_id=?",
                Integer.class, ADMIN)).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                SELECT `action`,from_state,to_state,actor_user_id,reason
                  FROM community_governance_audit WHERE resource_id=?
                """, post))
                .containsEntry("action", "APPROVE")
                .containsEntry("from_state", "PENDING")
                .containsEntry("to_state", "APPROVED")
                .containsEntry("actor_user_id", ADMIN)
                .containsEntry("reason", "通过公开审核");

        mockMvc.perform(get("/api/v1/public/posts/{postId}", post))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(post));
        mockMvc.perform(get("/api/v1/public/posts/{postId}/comments", post))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

        perform(postPath(post, "approve"), "重复通过")
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_governance_audit WHERE resource_id=?",
                Integer.class, post)).isOne();
    }

    @Test
    void postAdoptionTimelineTracksReviewAndRollsBackOnInconsistency() throws Exception {
        String post = seedPost("POST_ADOPTION", "PENDING", "ACTIVE", "PUBLIC");
        timeline(post, "RESTRICTED");

        perform(postPath(post, "approve"), "通过回访内容")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT visibility FROM timeline_entry WHERE source_id=?", String.class, post))
                .isEqualTo("PUBLIC");

        perform(postPath(post, "reject"), "复核后拒绝")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT visibility FROM timeline_entry WHERE source_id=?", String.class, post))
                .isEqualTo("RESTRICTED");

        String inconsistent = seedPost("POST_ADOPTION", "PENDING", "ACTIVE", "PUBLIC");
        perform(postPath(inconsistent, "approve"), "时间线缺失应回滚")
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT review_status FROM post WHERE id=?",
                String.class, inconsistent)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_governance_audit WHERE resource_id=?",
                Integer.class, inconsistent)).isZero();
    }

    @Test
    void approvalNeverOverridesHiddenOrRemovedVisibility() throws Exception {
        String hidden = seedPost("CAMPUS_POST", "PENDING", "HIDDEN", "PUBLIC");
        String removed = seedPost("CAMPUS_POST", "PENDING", "REMOVED", "PUBLIC");

        perform(postPath(hidden, "approve"), "仅审核内容，不恢复隐藏状态")
                .andExpect(status().isOk());
        perform(postPath(removed, "approve"), "仅审核内容，不恢复移除状态")
                .andExpect(status().isOk());

        assertThat(jdbc.queryForMap(
                "SELECT status,review_status FROM post WHERE id=?", hidden))
                .containsEntry("status", "HIDDEN")
                .containsEntry("review_status", "APPROVED");
        assertThat(jdbc.queryForMap(
                "SELECT status,review_status FROM post WHERE id=?", removed))
                .containsEntry("status", "REMOVED")
                .containsEntry("review_status", "APPROVED");
        mockMvc.perform(get("/api/v1/public/posts/{postId}", hidden))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/posts/{postId}", removed))
                .andExpect(status().isNotFound());
    }

    @Test
    void directForgeryWrongScopeReplayMethodAndAdjacentRoutesFailClosed() throws Exception {
        String post = seedPost("CAMPUS_POST", "PENDING", "ACTIVE", "PUBLIC");
        String path = postPath(post, "approve");
        String body = "{\"reason\":\"审核\"}";

        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-User-Id", ADMIN).header("X-Internal-Service", "api-gateway"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer("POST", path,
                                List.of("community.review.read"), "GOVERNANCE_ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer("POST", path,
                                List.of("community.review.write"), "USER")))
                .andExpect(status().isForbidden());

        String replay = bearer("POST", path, List.of("community.review.write"),
                "GOVERNANCE_ADMIN");
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", replay)).andExpect(status().isOk());
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", replay)).andExpect(status().isUnauthorized());

        mockMvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer("POST", path,
                                List.of("community.review.write"), "GOVERNANCE_ADMIN")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path + "/extra").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer("POST", path,
                                List.of("community.review.write"), "GOVERNANCE_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentSameDecisionHasOneCommitAndOneAudit() throws Exception {
        String post = seedPost("CAMPUS_POST", "PENDING", "ACTIVE", "PUBLIC");
        String path = postPath(post, "approve");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> performStatus(path, "并发审核一"));
            var second = executor.submit(() -> performStatus(path, "并发审核二"));
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT review_status FROM post WHERE id=?",
                String.class, post)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_governance_audit WHERE resource_id=?",
                Integer.class, post)).isOne();
    }

    private int performStatus(String path, String reason) throws Exception {
        return perform(path, reason).andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions perform(String path, String reason)
            throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}")
                .header("Authorization", bearer("POST", path,
                        List.of("community.review.write"), "GOVERNANCE_ADMIN")));
    }

    private String postPath(String id, String action) {
        return "/api/v1/admin/community-review/posts/" + id + "/" + action;
    }

    private String commentPath(String id, String action) {
        return "/api/v1/admin/community-review/comments/" + id + "/" + action;
    }

    private String seedPost(String type, String review, String status, String visibility) {
        String id = UUID.randomUUID().toString();
        String relation = "POST_ADOPTION".equals(type) ? UUID.randomUUID().toString() : null;
        jdbc.update("""
                INSERT INTO post
                  (id,campus_id,animal_id,relation_id,author_user_id,post_type,text_content,
                   visibility,status,review_status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?, ?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, id, CAMPUS, ANIMAL, relation, AUTHOR, type, "审核内容", visibility,
                status, review);
        return id;
    }

    private String comment(String postId, String review, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO comment
                  (id,post_id,author_user_id,content,status,review_status)
                VALUES (?,?,?,?,?,?)
                """, id, postId, AUTHOR, "审核评论", status, review);
        return id;
    }

    private void timeline(String postId, String visibility) {
        jdbc.update("""
                INSERT INTO timeline_entry
                  (id,animal_id,source_type,source_id,entry_type,title,summary,occurred_at,visibility)
                VALUES (?,?, 'POST',?,'POST_ADOPTION_PUBLISHED','领养后动态','审核内容',
                        CURRENT_TIMESTAMP(6),?)
                """, UUID.randomUUID().toString(), ANIMAL, postId, visibility);
    }

    private String bearer(String method, String path, List<String> scopes, String role)
            throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway")
                .audience("animal-service").claim("target", "animal-service")
                .claim("actor_id", ADMIN).claim("system_role", role).claim("scope", scopes)
                .claim("request_method", method).claim("request_path", path)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(15)))
                .jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID("animal-v1").build(), claims);
        jwt.sign(new MACSigner(KEY));
        return "Bearer " + jwt.serialize();
    }
}
