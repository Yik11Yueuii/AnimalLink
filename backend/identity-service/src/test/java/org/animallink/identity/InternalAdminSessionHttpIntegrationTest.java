package org.animallink.identity;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.animallink.identity.application.AdminAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class InternalAdminSessionHttpIntegrationTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000951";
    private static final String PASSWORD = "correct horse battery staple";
    private static final byte[] SERVICE_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);
    private static final String CONFIGURED_KEY = "test-v1=" + Base64.getEncoder().encodeToString(SERVICE_KEY);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("internal_session_http_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("animallink.authentication.service-assertion.keys", () -> CONFIGURED_KEY);
        registry.add("animallink.authentication.login-protection.maximum-attempts", () -> "100");
        registry.add("animallink.authentication.login-protection.maximum-source-attempts", () -> "100");
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminAuthenticationService authenticationService;

    private String sessionToken;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM authentication_audit_event");
        jdbc.update("DELETE FROM admin_login_attempt_state");
        jdbc.update("DELETE FROM admin_session");
        jdbc.update("DELETE FROM admin_password_credential");
        jdbc.update("DELETE FROM external_identity");
        jdbc.update("DELETE FROM `user`");
        jdbc.update("INSERT INTO `user` (id,display_name,account_status,system_role) VALUES (?,?,?,?)",
                ADMIN, "管理员", "ACTIVE", "GOVERNANCE_ADMIN");
        authenticationService.provisionAdminCredential(ADMIN, "admin.root", PASSWORD);
        sessionToken = authenticationService.createAdminSession("admin.root", PASSWORD).rawToken();
    }

    @Test
    void validGatewayAssertionValidatesAdminSessionOverRealHttp() throws Exception {
        var response = validate(sessionToken, assertion("api-gateway", "identity-service",
                Instant.now(), Instant.now().plusSeconds(15), SERVICE_KEY, UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("userId", ADMIN)
                .containsEntry("systemRole", "GOVERNANCE_ADMIN")
                .doesNotContainKey("token").doesNotContainKey("passwordHash");
        assertThat(response.toString()).doesNotContain(sessionToken).doesNotContain("$argon2id$");
    }

    @Test
    void missingForgedExpiredAndWrongAudienceServiceCredentialsAreRejected() throws Exception {
        assertThat(validate(sessionToken, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders forged = new HttpHeaders();
        forged.set("X-Internal-Service", "api-gateway");
        assertThat(post(sessionToken, forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        byte[] wrongKey = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
        assertThat(validate(sessionToken, assertion("api-gateway", "identity-service",
                Instant.now(), Instant.now().plusSeconds(15), wrongKey, UUID.randomUUID().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(validate(sessionToken, assertion("api-gateway", "identity-service",
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), SERVICE_KEY,
                UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(validate(sessionToken, assertion("api-gateway", "animal-service",
                Instant.now(), Instant.now().plusSeconds(15), SERVICE_KEY, UUID.randomUUID().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void assertionReplayAndInvalidRevokedOrDisabledSessionsAreRejectedImmediately() throws Exception {
        String assertion = assertion("api-gateway", "identity-service", Instant.now(),
                Instant.now().plusSeconds(15), SERVICE_KEY, UUID.randomUUID().toString());
        assertThat(validate(sessionToken, assertion).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(validate(sessionToken, assertion).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(validate("A".repeat(43), freshAssertion()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Instant now = Instant.now();
        jdbc.update("UPDATE admin_session SET created_at=?, last_seen_at=?, idle_expires_at=? "
                        + "WHERE token_hash IS NOT NULL",
                java.sql.Timestamp.from(now.minusSeconds(7200)),
                java.sql.Timestamp.from(now.minusSeconds(5400)),
                java.sql.Timestamp.from(now.minusSeconds(3600)));
        assertThat(validate(sessionToken, freshAssertion()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        sessionToken = authenticationService.createAdminSession("admin.root", PASSWORD).rawToken();
        authenticationService.revokeCurrentSession(sessionToken, "TEST_REVOKE");
        assertThat(validate(sessionToken, freshAssertion()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        sessionToken = authenticationService.createAdminSession("admin.root", PASSWORD).rawToken();
        jdbc.update("UPDATE `user` SET account_status='DISABLED' WHERE id=?", ADMIN);
        assertThat(validate(sessionToken, freshAssertion()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void administratorRoleRemovalInvalidatesSessionImmediately() throws Exception {
        jdbc.update("UPDATE `user` SET system_role='USER' WHERE id=?", ADMIN);
        assertThat(validate(sessionToken, freshAssertion()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private org.springframework.http.ResponseEntity<Map> validate(String token, String assertion) {
        HttpHeaders headers = new HttpHeaders();
        if (assertion != null) headers.setBearerAuth(assertion);
        return post(token, headers);
    }

    private org.springframework.http.ResponseEntity<Map> post(String token, HttpHeaders headers) {
        return http.exchange("/internal/v1/auth/sessions/validate", HttpMethod.POST,
                new HttpEntity<>(Map.of("token", token), headers), Map.class);
    }

    private String freshAssertion() throws Exception {
        return assertion("api-gateway", "identity-service", Instant.now(), Instant.now().plusSeconds(15),
                SERVICE_KEY, UUID.randomUUID().toString());
    }

    private static String assertion(String subject, String audience, Instant issuedAt, Instant expiresAt,
                                    byte[] key, String tokenId) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject(subject).audience(audience)
                .claim("target", audience).issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt))
                .jwtID(tokenId).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID("test-v1").build(), claims);
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }
}
