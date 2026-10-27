package org.animallink.animal;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/demo",
        "animallink.authentication.animal-admin-assertion.keys=animal-v1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "animallink.messaging.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class HanshanCommunityDemoIntegrationTest {
    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String CAMPUS = "22000000-0000-4000-8000-000000000001";
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String XIAO_JU = "23000000-0000-4000-8000-000000000001";
    private static final String APPROVE_POST = "33000000-0000-4000-8000-000000000001";
    private static final String REJECT_POST = "33000000-0000-4000-8000-000000000002";
    private static final String PENDING_POST = "33000000-0000-4000-8000-000000000003";
    private static final String APPROVE_COMMENT = "34000000-0000-4000-8000-000000000001";
    private static final String PENDING_COMMENT = "34000000-0000-4000-8000-000000000002";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("hanshan_demo_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void demoFixtureRequiresRealReviewAndRemainsIdempotentAfterReview() throws Exception {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM animal WHERE campus_id=?", Integer.class, CAMPUS))
                .isEqualTo(3);
        assertThat(jdbc.queryForList(
                "SELECT display_name FROM animal WHERE campus_id=? ORDER BY id", String.class, CAMPUS))
                .containsExactly("小橘", "墨墨", "阿黄");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM post WHERE campus_id=?", Integer.class, CAMPUS))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM post WHERE campus_id=? AND review_status='PENDING'",
                Integer.class, CAMPUS)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM comment c JOIN post p ON p.id=c.post_id
                WHERE p.campus_id=? AND c.review_status='PENDING'
                """, Integer.class, CAMPUS)).isEqualTo(3);

        mockMvc.perform(get("/api/v1/public/campuses/{campusId}/feed", CAMPUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/v1/public/animals")
                        .param("campusId", CAMPUS).param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
        mockMvc.perform(get("/api/v1/public/animals/{animalId}", XIAO_JU))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campusId").value(CAMPUS))
                .andExpect(jsonPath("$.displayName").value("小橘"));

        reviewPost(APPROVE_POST, "approve", "虚构演示内容适合公开")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"));
        reviewPost(REJECT_POST, "reject", "未经正式流程不得声称已完成领养")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"));
        reviewComment(APPROVE_COMMENT, "approve", "评论适合公开")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"));

        mockMvc.perform(get("/api/v1/public/campuses/{campusId}/feed", CAMPUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(APPROVE_POST))
                .andExpect(jsonPath("$.items[0].animal.id").value(XIAO_JU))
                .andExpect(jsonPath("$.items[0].commentCount").value(1));
        mockMvc.perform(get("/api/v1/public/posts/{postId}", REJECT_POST))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/posts/{postId}", PENDING_POST))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/posts/{postId}/comments", APPROVE_POST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(APPROVE_COMMENT));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_governance_audit WHERE resource_id IN (?,?,?)",
                Integer.class, APPROVE_POST, REJECT_POST, APPROVE_COMMENT)).isEqualTo(3);
        assertThat(jdbc.queryForMap(
                "SELECT review_status,reviewed_by_user_id,reviewed_at FROM post WHERE id=?",
                APPROVE_POST))
                .containsEntry("review_status", "APPROVED")
                .containsEntry("reviewed_by_user_id", ADMIN);
        assertThat(jdbc.queryForObject(
                "SELECT reviewed_at FROM post WHERE id=?", Object.class, APPROVE_POST)).isNotNull();

        rerunHanshanFixture();

        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM post WHERE id=?", String.class, APPROVE_POST))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM post WHERE id=?", String.class, REJECT_POST))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM comment WHERE id=?", String.class, APPROVE_COMMENT))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM comment WHERE id=?", String.class, PENDING_COMMENT))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_governance_audit WHERE resource_id IN (?,?,?)",
                Integer.class, APPROVE_POST, REJECT_POST, APPROVE_COMMENT)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM post WHERE campus_id=?", Integer.class, CAMPUS))
                .isEqualTo(5);
    }

    private org.springframework.test.web.servlet.ResultActions reviewPost(
            String postId, String action, String reason) throws Exception {
        String path = "/api/v1/admin/community-review/posts/" + postId + "/" + action;
        return review(path, reason);
    }

    private org.springframework.test.web.servlet.ResultActions reviewComment(
            String commentId, String action, String reason) throws Exception {
        String path = "/api/v1/admin/community-review/comments/" + commentId + "/" + action;
        return review(path, reason);
    }

    private org.springframework.test.web.servlet.ResultActions review(String path, String reason)
            throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}")
                .header("Authorization", bearer(path)));
    }

    private String bearer(String path) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer("api-gateway").subject("api-gateway")
                .audience("animal-service").claim("target", "animal-service")
                .claim("actor_id", ADMIN).claim("system_role", "GOVERNANCE_ADMIN")
                .claim("scope", List.of("community.review.write"))
                .claim("request_method", "POST").claim("request_path", path)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(15)))
                .jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID("animal-v1").build(), claims);
        jwt.sign(new MACSigner(KEY));
        return "Bearer " + jwt.serialize();
    }

    private void rerunHanshanFixture() {
        ResourceDatabasePopulator fixture = new ResourceDatabasePopulator(
                new ClassPathResource("db/demo/R__local_demo_hanshan_community.sql"));
        fixture.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        fixture.execute(dataSource);
    }
}
