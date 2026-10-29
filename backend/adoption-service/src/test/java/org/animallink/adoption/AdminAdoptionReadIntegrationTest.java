package org.animallink.adoption;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.animallink.adoption.application.AdminAdoptionReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminAdoptionReadIntegrationTest {
    static final String APPLICATION = "72000000-0000-0000-0000-000000000001";
    static final byte[] KEY = "adoption-0123456789abcdef01234567".getBytes();
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("admin_adoption_read").withUsername("test").withPassword("test");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl); registry.add("spring.datasource.username", MYSQL::getUsername); registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false); registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.messaging.enabled", () -> false); registry.add("animallink.minio.access-key", () -> "test"); registry.add("animallink.minio.secret-key", () -> "test-secret");
        registry.add("animallink.authentication.adoption-admin-assertion.keys", () -> "adoption-v1=" + Base64.getEncoder().encodeToString(KEY));
    }
    @Autowired JdbcTemplate jdbc; @Autowired AdminAdoptionReadService service; @Autowired MockMvc mvc;
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM adoption_application"); jdbc.update("DELETE FROM adoption_listing"); Timestamp now = Timestamp.from(Instant.parse("2026-10-28T01:00:00Z"));
        jdbc.update("INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,published_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "72000000-0000-0000-0000-000000000002", "72000000-0000-0000-0000-000000000003", "PUBLISHED", "小橘领养信息", "领养说明", "72000000-0000-0000-0000-000000000004", now, now, now);
        jdbc.update("INSERT INTO adoption_application(id,listing_id,applicant_user_id,status,message,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                APPLICATION, "72000000-0000-0000-0000-000000000002", "72000000-0000-0000-0000-000000000005", "SUBMITTED", "申请说明", now, now);
    }
    @Test void readsRealMysqlApplicationWithListingAndOnlyPseudonymousApplicantReference() {
        var page = service.search("SUBMITTED", "小橘", "UPDATED_DESC", 0, 20);
        assertThat(page.total()).isEqualTo(1); assertThat(page.statusCounts().get("SUBMITTED")).isEqualTo(1);
        var detail = service.detail(APPLICATION); assertThat(detail.listing().title()).isEqualTo("小橘领养信息");
        assertThat(detail.applicantReference()).startsWith("user-…").doesNotContain("72000000-0000-0000-0000");
    }
    @Test void httpEndpointRequiresTrustedAssertionAndReturnsPseudonymousIdentity() throws Exception {
        mvc.perform(get("/api/v1/admin/applications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/applications").header("Authorization", "Bearer " + token("/api/v1/admin/applications")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].applicationId").value(APPLICATION))
                .andExpect(jsonPath("$.items[0].applicantReference").value("user-…00000005"))
                .andExpect(jsonPath("$.items[0].applicantUserId").doesNotExist());
    }
    private static String token(String path) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway").audience("adoption-service")
                .claim("target", "adoption-service").claim("actor_id", "72000000-0000-0000-0000-000000000099")
                .claim("system_role", "GOVERNANCE_ADMIN").claim("scope", List.of("adoption.application.read"))
                .claim("request_method", "GET").claim("request_path", path).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(15))).jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID("adoption-v1").build(), claims);
        jwt.sign(new MACSigner(KEY)); return jwt.serialize();
    }
}
