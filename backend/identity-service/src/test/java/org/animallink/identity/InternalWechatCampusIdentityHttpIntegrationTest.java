package org.animallink.identity;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.animallink.identity.application.CampusVerificationReviewService;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UserAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the gateway-signed ordinary-user channel can only act for its asserted actor. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class InternalWechatCampusIdentityHttpIntegrationTest {
    private static final String USER = "00000000-0000-0000-0000-000000000811";
    private static final String OTHER = "00000000-0000-0000-0000-000000000812";
    private static final String ADMIN = "00000000-0000-0000-0000-000000000814";
    private static final String CAMPUS = "00000000-0000-0000-0000-000000000813";
    private static final byte[] SERVICE_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);
    private static final String CONFIGURED_KEY = "test-v1=" + Base64.getEncoder().encodeToString(SERVICE_KEY);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("wechat_campus_identity_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("animallink.authentication.service-assertion.keys", () -> CONFIGURED_KEY);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired CampusVerificationReviewService reviews;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM volunteer_membership");
        jdbc.update("DELETE FROM campus_membership");
        jdbc.update("DELETE FROM credential_precheck_attempt");
        jdbc.update("DELETE FROM campus_verification");
        jdbc.update("DELETE FROM credential_material");
        jdbc.update("DELETE FROM external_identity WHERE provider = 'WECHAT_MINIPROGRAM'");
        jdbc.update("DELETE FROM campus");
        jdbc.update("DELETE FROM `user`");
        insertUser(USER, "普通用户");
        insertUser(OTHER, "其他用户");
        jdbc.update("INSERT INTO `user` (id, display_name, account_status, system_role) VALUES (?, ?, 'ACTIVE', 'GOVERNANCE_ADMIN')", ADMIN, "治理管理员");
        jdbc.update("INSERT INTO campus (id, name, short_name, city, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                CAMPUS, "测试社区", "测试", "测试市");
    }

    @Test
    void gatewayAssertionIsRequiredAndCanReadOnlyTheAssertedUsersSnapshot() throws Exception {
        assertThat(get(USER, (String) null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var snapshot = get(USER, freshAssertion(USER));
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody()).containsEntry("memberships", java.util.List.of())
                .containsEntry("applications", java.util.List.of());
        assertThat(snapshot.toString()).doesNotContain("userId").doesNotContain("reviewedBy");
        assertThat(get(ADMIN, freshAssertion(ADMIN)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(OTHER, freshAssertion(USER)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders forged = new HttpHeaders();
        forged.set("X-Internal-Service", "api-gateway");
        assertThat(get(USER, forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void signedGatewayRequestCreatesPendingApplicationForOnlyItsActorAndRejectsDuplicates() throws Exception {
        var created = post(USER, freshAssertion(USER));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "PENDING_REVIEW")
                .doesNotContainKey("userId").doesNotContainKey("reviewedBy");
        assertThat(jdbc.queryForObject("SELECT user_id FROM campus_verification WHERE campus_id = ?", String.class, CAMPUS))
                .isEqualTo(USER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_membership WHERE user_id = ?", Integer.class, USER))
                .isZero();

        assertThat(post(USER, freshAssertion(USER)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var otherSnapshot = get(OTHER, freshAssertion(OTHER));
        assertThat(otherSnapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherSnapshot.getBody()).containsEntry("applications", java.util.List.of());
    }

    @Test
    void existingApprovalStateMachineCreatesTheOnlyActiveMembership() throws Exception {
        assertThat(post(USER, freshAssertion(USER)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String applicationId = jdbc.queryForObject("SELECT id FROM campus_verification WHERE user_id = ?", String.class, USER);

        reviews.approve(applicationId, admin(), "人工核验通过");

        assertThat(jdbc.queryForObject("SELECT status FROM campus_verification WHERE id = ?", String.class, applicationId))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_membership WHERE user_id = ? AND campus_id = ? AND status = 'ACTIVE'", Integer.class, USER, CAMPUS))
                .isOne();
    }

    @Test
    void existingRejectionStateMachineNeverCreatesAnActiveMembership() throws Exception {
        assertThat(post(USER, freshAssertion(USER)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String applicationId = jdbc.queryForObject("SELECT id FROM campus_verification WHERE user_id = ?", String.class, USER);

        reviews.reject(applicationId, admin(), "资料不足");

        assertThat(jdbc.queryForObject("SELECT status FROM campus_verification WHERE id = ?", String.class, applicationId))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_membership WHERE user_id = ?", Integer.class, USER))
                .isZero();
    }

    private void insertUser(String id, String name) {
        jdbc.update("INSERT INTO `user` (id, display_name, account_status, system_role) VALUES (?, ?, 'ACTIVE', 'USER')", id, name);
    }

    private static UserAccount admin() {
        Instant now = Instant.now();
        return new UserAccount(ADMIN, "治理管理员", AccountStatus.ACTIVE, SystemRole.GOVERNANCE_ADMIN, now, now);
    }

    private org.springframework.http.ResponseEntity<Map> get(String actor, String assertion) {
        HttpHeaders headers = new HttpHeaders();
        if (assertion != null) headers.setBearerAuth(assertion);
        return get(actor, headers);
    }

    private org.springframework.http.ResponseEntity<Map> get(String actor, HttpHeaders headers) {
        return http.exchange("/internal/v1/wechat/campus-identities?actorUserId=" + actor, HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
    }

    private org.springframework.http.ResponseEntity<Map> post(String actor, String assertion) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(assertion);
        return http.exchange("/internal/v1/wechat/campus-identities/applications", HttpMethod.POST,
                new HttpEntity<>(Map.of("actorUserId", actor, "campusId", CAMPUS,
                        "requestedMembershipType", "STUDENT", "applicantName", "测试申请人"), headers), Map.class);
    }

    private static String freshAssertion(String actorUserId) throws Exception {
        Instant now = Instant.now();
        String tokenId = UUID.randomUUID().toString();
        String audience = "identity-service";
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway").audience(audience)
                .claim("target", audience).claim("actor", actorUserId)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(15)))
                .jwtID(tokenId).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID("test-v1").build(), claims);
        jwt.sign(new MACSigner(SERVICE_KEY));
        return jwt.serialize();
    }
}
