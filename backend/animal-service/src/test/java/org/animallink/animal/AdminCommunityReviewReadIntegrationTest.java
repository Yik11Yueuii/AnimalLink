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
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

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
class AdminCommunityReviewReadIntegrationTest {
    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String ADMIN = "00000000-0000-0000-0000-000000000701";
    private static final String CAMPUS = "10000000-0000-0000-0000-000000000701";
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000701";
    private static final String POSTS = "/api/v1/admin/community-review/posts";
    private static final String COMMENTS = "/api/v1/admin/community-review/comments";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("community_review_read_test")
            .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    private String pendingOld;
    private String pendingNew;
    private String approved;
    private String pendingComment;

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
        pendingOld = seedPost("较早待审核", "PENDING", "2026-10-24 00:00:00.000000");
        pendingNew = seedPost("较新待审核", "PENDING", "2026-10-25 00:00:00.000000");
        approved = seedPost("已审核内容", "APPROVED", "2026-10-23 00:00:00.000000");
        pendingComment = comment(pendingNew, "待审核评论", "PENDING");
        comment(pendingNew, "已审核评论", "APPROVED");
    }

    @Test
    void trustedAdminReadsFilteredQueuesDetailsAndStableSortWithoutSensitiveIds() throws Exception {
        mockMvc.perform(get(POSTS).header("Authorization", bearer("GET", POSTS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].postId").value(pendingNew))
                .andExpect(jsonPath("$.items[1].postId").value(pendingOld))
                .andExpect(jsonPath("$.items[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.items[0].authorUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].reviewedByUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].media").doesNotExist());

        mockMvc.perform(get(POSTS).param("sort", "OLDEST")
                        .header("Authorization", bearer("GET", POSTS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].postId").value(pendingOld));
        mockMvc.perform(get(POSTS).param("reviewStatus", "APPROVED")
                        .header("Authorization", bearer("GET", POSTS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].postId").value(approved));

        String postPath = POSTS + "/" + pendingNew;
        mockMvc.perform(get(postPath).header("Authorization", bearer("GET", postPath)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.postId").value(pendingNew))
                .andExpect(jsonPath("$.authorUserId").doesNotExist())
                .andExpect(jsonPath("$.reviewedByUserId").doesNotExist());

        mockMvc.perform(get(COMMENTS).header("Authorization", bearer("GET", COMMENTS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].commentId").value(pendingComment))
                .andExpect(jsonPath("$.items[0].authorUserId").doesNotExist());
        String commentPath = COMMENTS + "/" + pendingComment;
        mockMvc.perform(get(commentPath).header("Authorization", bearer("GET", commentPath)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commentId").value(pendingComment));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_governance_audit", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT review_status FROM post WHERE id=?", String.class,
                pendingNew)).isEqualTo("PENDING");
    }

    @Test
    void directAccessForgeryReplayAndWriteMethodsFailClosed() throws Exception {
        mockMvc.perform(get(POSTS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(POSTS).header("X-User-Id", ADMIN)
                        .header("X-Internal-Service", "api-gateway"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(POSTS).header("Authorization",
                        bearer("GET", POSTS, "identity-service", "GOVERNANCE_ADMIN",
                                List.of("community.review.read"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(POSTS).header("Authorization",
                        bearer("GET", POSTS, "animal-service", "USER",
                                List.of("community.review.read"))))
                .andExpect(status().isForbidden());

        String replay = bearer("GET", POSTS);
        mockMvc.perform(get(POSTS).header("Authorization", replay)).andExpect(status().isOk());
        mockMvc.perform(get(POSTS).header("Authorization", replay)).andExpect(status().isUnauthorized());

        mockMvc.perform(post(POSTS).header("Authorization", bearer("GET", POSTS)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(POSTS).header("Authorization", bearer("GET", POSTS)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(POSTS).header("Authorization", bearer("GET", POSTS)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicCommunityReadRemainsAnonymousAndUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/public/posts/{postId}", approved))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(approved));
    }

    private String seedPost(String text, String reviewStatus, String createdAt) {
        String id = UUID.randomUUID().toString();
        String reviewer = "PENDING".equals(reviewStatus) ? null : ADMIN;
        String reviewedAt = reviewer == null ? null : createdAt;
        jdbc.update("""
                INSERT INTO post
                  (id,campus_id,animal_id,author_user_id,post_type,text_content,visibility,status,
                   review_status,reviewed_by_user_id,reviewed_at,created_at,updated_at)
                VALUES (?,?,?,?,? ,?,'PUBLIC','ACTIVE',?,?,?,?,?)
                """, id, CAMPUS, ANIMAL, "00000000-0000-0000-0000-000000000702",
                "CAMPUS_POST", text, reviewStatus, reviewer, reviewedAt, createdAt, createdAt);
        return id;
    }

    private String comment(String postId, String text, String reviewStatus) {
        String id = UUID.randomUUID().toString();
        String reviewer = "PENDING".equals(reviewStatus) ? null : ADMIN;
        String reviewedAt = reviewer == null ? null : "2026-10-25 01:00:00.000000";
        jdbc.update("""
                INSERT INTO comment
                  (id,post_id,author_user_id,content,status,review_status,reviewed_by_user_id,reviewed_at)
                VALUES (?,?,?,?,'ACTIVE',?,?,?)
                """, id, postId, "00000000-0000-0000-0000-000000000703", text, reviewStatus,
                reviewer, reviewedAt);
        return id;
    }

    private String bearer(String method, String path) throws Exception {
        return bearer(method, path, "animal-service", "GOVERNANCE_ADMIN",
                List.of("community.review.read"));
    }

    private String bearer(String method, String path, String audience, String role,
                          List<String> scopes) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway")
                .audience(audience).claim("target", audience).claim("actor_id", ADMIN)
                .claim("system_role", role).claim("scope", scopes)
                .claim("request_method", method).claim("request_path", path)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(15)))
                .jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID("animal-v1").build(), claims);
        jwt.sign(new MACSigner(KEY));
        return "Bearer " + jwt.serialize();
    }
}
