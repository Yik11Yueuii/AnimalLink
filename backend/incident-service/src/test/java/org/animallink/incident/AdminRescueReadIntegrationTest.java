package org.animallink.incident;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.animallink.incident.application.AdminRescueReadService;
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
class AdminRescueReadIntegrationTest {
    static final String CAMPUS = "71000000-0000-0000-0000-000000000001";
    static final String EVENT = "71000000-0000-0000-0000-000000000002";
    static final String CASE = "71000000-0000-0000-0000-000000000003";
    static final byte[] KEY = "incident-0123456789abcdef01234567".getBytes();
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("admin_rescue_read").withUsername("test").withPassword("test");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl); registry.add("spring.datasource.username", MYSQL::getUsername); registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false); registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.messaging.enabled", () -> false); registry.add("animallink.minio.access-key", () -> "test"); registry.add("animallink.minio.secret-key", () -> "test-secret");
        registry.add("animallink.authentication.incident-admin-assertion.keys", () -> "incident-v1=" + Base64.getEncoder().encodeToString(KEY));
    }
    @Autowired JdbcTemplate jdbc; @Autowired AdminRescueReadService service; @Autowired MockMvc mvc;
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM case_action"); jdbc.update("DELETE FROM animal_case"); jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM event_draft");
        Timestamp now = Timestamp.from(Instant.parse("2026-10-28T01:00:00Z"));
        jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,exact_location_description,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "71000000-0000-0000-0000-000000000010", "71000000-0000-0000-0000-000000000011", CAMPUS, "draft", now, "公开区域", "精确位置", now, now);
        jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,animal_id,description,abnormality_summary,occurred_at,reported_at,public_location_description,exact_location_description,latitude,longitude,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                EVENT, "71000000-0000-0000-0000-000000000010", "71000000-0000-0000-0000-000000000011", CAMPUS, "71000000-0000-0000-0000-000000000012", "事件描述", "需要观察", now, now, "公开区域", "精确位置", 23.1, 116.6, "VERIFIED", now, now);
        jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,animal_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                CASE, EVENT, CAMPUS, "71000000-0000-0000-0000-000000000012", "71000000-0000-0000-0000-000000000013", "ACTIVE", "CLAIMED", now, now, now);
        jdbc.update("INSERT INTO case_action(id,case_id,actor_user_id,description,occurred_at,created_at) VALUES(?,?,?,?,?,?)",
                "71000000-0000-0000-0000-000000000014", CASE, "71000000-0000-0000-0000-000000000013", "已完成现场观察", now, now);
    }
    @Test void readsRealMysqlProjectionWithoutExactLocationOrActorIdentity() {
        var page = service.search(CAMPUS, "IN_PROGRESS", "观察", "UPDATED_DESC", 0, 20);
        assertThat(page.total()).isEqualTo(1); assertThat(page.items().getFirst().actionCount()).isEqualTo(1);
        var detail = service.detail(EVENT); assertThat(detail.progress()).extracting("description").containsExactly("已完成现场观察");
        assertThat(detail.toString()).doesNotContain("精确位置").doesNotContain("000000000013");
    }
    @Test void httpEndpointRequiresTrustedAssertionAndReturnsOnlySafeProjection() throws Exception {
        assertThat(service.search(CAMPUS, null, null, "UPDATED_DESC", 0, 20).total()).isEqualTo(1);
        mvc.perform(get("/api/v1/admin/rescues").param("campusId", CAMPUS)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/rescues").param("campusId", CAMPUS)
                        .header("Authorization", "Bearer " + token("/api/v1/admin/rescues")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eventId").value(EVENT))
                .andExpect(jsonPath("$.items[0].exactLocationDescription").doesNotExist())
                .andExpect(jsonPath("$.items[0].ownerUserId").doesNotExist());
    }
    private static String token(String path) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway").audience("incident-service")
                .claim("target", "incident-service").claim("actor_id", "71000000-0000-0000-0000-000000000099")
                .claim("system_role", "GOVERNANCE_ADMIN").claim("scope", List.of("incident.rescue.read"))
                .claim("request_method", "GET").claim("request_path", path).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(15))).jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID("incident-v1").build(), claims);
        jwt.sign(new MACSigner(KEY)); return jwt.serialize();
    }
}
