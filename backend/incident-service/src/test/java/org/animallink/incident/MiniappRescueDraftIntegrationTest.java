package org.animallink.incident;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL/Flyway coverage for the Gateway-to-Incident ordinary-user draft contract. */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class MiniappRescueDraftIntegrationTest {
    static final String CAMPUS="72000000-0000-4000-8000-000000000001", OTHER="72000000-0000-4000-8000-000000000002";
    static final String OWNER="73000000-0000-4000-8000-000000000001", OTHER_USER="73000000-0000-4000-8000-000000000002";
    static final byte[] KEY="01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
    static final String KEYS="test="+Base64.getEncoder().encodeToString(KEY);
    static final IdentityStub IDENTITY=new IdentityStub();
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.41").withDatabaseName("miniapp_rescue_write").withUsername("test").withPassword("test");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.cloud.nacos.discovery.enabled",()->false);r.add("spring.cloud.nacos.config.enabled",()->false);r.add("animallink.messaging.enabled",()->false);r.add("animallink.identity.base-url",IDENTITY::url);r.add("animallink.minio.access-key",()->"test");r.add("animallink.minio.secret-key",()->"test-secret");r.add("animallink.authentication.incident-miniapp-assertion.current-key-id",()->"test");r.add("animallink.authentication.incident-miniapp-assertion.keys",()->KEYS);}
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
    @BeforeEach void reset(){jdbc.update("DELETE FROM event");jdbc.update("DELETE FROM event_draft");IDENTITY.status="ACTIVE";}
    @AfterAll static void stop(){IDENTITY.server.stop(0);}
    @Test void activeMemberOwnsDraftAndSubmitIsIdempotent() throws Exception {
        String collection="/api/v1/internal/miniapp/rescue-drafts";
        mvc.perform(post(collection).contentType("application/json").content(body(CAMPUS,"first")).header("Authorization",bearer(OWNER,"POST",collection))).andExpect(status().isOk()).andExpect(jsonPath("$.id").isNotEmpty());
        String draft=jdbc.queryForObject("SELECT id FROM event_draft",String.class);
        String item=collection+"/"+draft;
        mvc.perform(patch(item).contentType("application/json").content(body(CAMPUS,"edited")).header("Authorization",bearer(OWNER,"PATCH",item))).andExpect(status().isOk()).andExpect(jsonPath("$.description").value("edited"));
        mvc.perform(get(item).header("Authorization",bearer(OWNER,"GET",item))).andExpect(status().isOk()).andExpect(jsonPath("$.description").value("edited"));
        String submit=item+"/submit";
        mvc.perform(post(submit).header("Authorization",bearer(OWNER,"POST",submit))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REPORTED"));
        mvc.perform(post(submit).header("Authorization",bearer(OWNER,"POST",submit))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REPORTED"));
        org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM event",Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE source_draft_id=?",Integer.class,draft));
        org.junit.jupiter.api.Assertions.assertNotNull(jdbc.queryForObject("SELECT final_event_id FROM event_draft WHERE id=?",String.class,draft));
        mvc.perform(get("/api/v1/public/rescues").param("campusId",CAMPUS)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(patch(item).contentType("application/json").content(body(CAMPUS,"after submit")).header("Authorization",bearer(OWNER,"PATCH",item))).andExpect(status().isConflict());
    }
    @Test void rejectsOtherActorNonActiveMembershipAndReplayedAssertion() throws Exception {
        String path="/api/v1/internal/miniapp/rescue-drafts", token=bearer(OWNER,"POST",path);
        mvc.perform(post(path).contentType("application/json").content(body(CAMPUS,"x")).header("Authorization",token)).andExpect(status().isOk());
        mvc.perform(post(path).contentType("application/json").content(body(CAMPUS,"x")).header("Authorization",token)).andExpect(status().isUnauthorized());
        String draft=jdbc.queryForObject("SELECT id FROM event_draft",String.class), item=path+"/"+draft;
        mvc.perform(get(item).header("Authorization",bearer(OTHER_USER,"GET",item))).andExpect(status().isNotFound());
        IDENTITY.status="PENDING_REVIEW";
        mvc.perform(post(path).contentType("application/json").content(body(CAMPUS,"pending")).header("Authorization",bearer(OWNER,"POST",path))).andExpect(status().isForbidden());
        IDENTITY.status="ACTIVE";
        mvc.perform(post(path).contentType("application/json").content(body(OTHER,"cross campus")).header("Authorization",bearer(OWNER,"POST",path))).andExpect(status().isForbidden());
        mvc.perform(post(path).contentType("application/json").content(body(CAMPUS,"missing assertion"))).andExpect(status().isUnauthorized());
    }
    private static String body(String campus,String description){return "{\"campusId\":\""+campus+"\",\"description\":\""+description+"\",\"occurredAt\":\"2026-10-31T08:00:00Z\",\"publicLocationDescription\":\"公共区域\",\"media\":[]}";}
    private static String bearer(String actor,String method,String path) throws Exception {Instant now=Instant.now();var claims=new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway").audience("incident-service").claim("target","incident-service").claim("actor_id",actor).claim("system_role","USER").claim("scope",java.util.List.of("incident.rescue-draft.write")).claim("request_method",method).claim("request_path",path).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(15))).jwtID(UUID.randomUUID().toString()).build();var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID("test").build(),claims);jwt.sign(new MACSigner(KEY));return "Bearer "+jwt.serialize();}
    static final class IdentityStub {final HttpServer server;volatile String status="ACTIVE";IdentityStub(){try{server=HttpServer.create(new InetSocketAddress(0),0);server.createContext("/internal/v1/users/",e->{String p=e.getRequestURI().getPath();boolean owner=p.contains(OWNER);boolean target=p.endsWith("/"+CAMPUS);String json=(owner&&target)?"{\"exists\":true,\"status\":\""+status+"\"}":"{\"exists\":false}";byte[] b=json.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();}catch(IOException e){throw new IllegalStateException(e);}}String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}}
}
