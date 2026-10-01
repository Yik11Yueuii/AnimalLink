package org.animallink.incident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.minio.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventReadIntegrationTest {
  static final String CAMPUS_A = "10000000-0000-0000-0000-000000000101", CAMPUS_B = "10000000-0000-0000-0000-000000000202";
  static final String REPORTER = "00000000-0000-0000-0000-000000000101", OTHER = "00000000-0000-0000-0000-000000000102", ADMIN = "00000000-0000-0000-0000-000000000999", BUCKET = "animallink-incident";
  static final Stub STUB = new Stub();
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("incident_read_test").withUsername("test").withPassword("test");
  @Container static final GenericContainer<?> MINIO = new GenericContainer<>("animallink-minio:RELEASE.2025-10-15T17-29-55Z").withExposedPorts(9000).withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin").withCommand("server /data");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("spring.cloud.nacos.discovery.enabled", () -> false); r.add("spring.cloud.nacos.config.enabled", () -> false);
    r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url); r.add("animallink.intelligence.base-url", STUB::url);
    r.add("animallink.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)); r.add("animallink.minio.access-key", () -> "minioadmin"); r.add("animallink.minio.secret-key", () -> "minioadmin"); r.add("animallink.minio.bucket", () -> BUCKET);
  }
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired MinioClient minio;
  @BeforeEach void clean() throws Exception { if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build()); jdbc.update("DELETE FROM event_media"); jdbc.update("DELETE FROM evidence_media"); jdbc.update("DELETE FROM evidence"); jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM event_draft"); }
  @AfterAll static void stop() { STUB.stop(); }

  @Test void listIsCampusScopedFiltersStatusAndAnimalAndStaysLightweight() throws Exception {
    seed("reported-a", CAMPUS_A, "animal-a", "REPORTED", "2026-10-01T00:03:00Z"); seed("verified-a", CAMPUS_A, "animal-a", "VERIFIED", "2026-10-01T00:02:00Z");
    seed("archived-a", CAMPUS_A, "animal-a", "ARCHIVED", "2026-10-01T00:01:00Z"); seed("rejected-a", CAMPUS_A, "animal-b", "REJECTED", "2026-10-01T00:01:00Z"); seed("duplicate-a", CAMPUS_A, "animal-b", "DUPLICATE", "2026-10-01T00:01:00Z"); seed("reported-b", CAMPUS_B, "animal-a", "REPORTED", "2026-10-01T00:04:00Z");
    mvc.perform(get("/api/v1/events").param("campusId", CAMPUS_A)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[*].id", contains("reported-a", "verified-a"))).andExpect(jsonPath("$.items[*].status", contains("REPORTED", "VERIFIED"))).andExpect(jsonPath("$.items[0].evidence", empty())).andExpect(jsonPath("$.items[0].media", empty())).andExpect(jsonPath("$.items[0].exactLocationDescription").value(nullValue())).andExpect(jsonPath("$.items[0].latitude").value(nullValue())).andExpect(jsonPath("$.items[0].longitude").value(nullValue()));
    mvc.perform(get("/api/v1/events").param("campusId", CAMPUS_A).param("status", "ARCHIVED")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value("archived-a"));
    mvc.perform(get("/api/v1/events").param("campusId", CAMPUS_A).param("animalId", "animal-a")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[*].id", contains("reported-a", "verified-a")));
    Assertions.assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM event", Integer.class)); Assertions.assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE campus_id=?", Integer.class, CAMPUS_A));
  }

  @Test void paginationUsesStableDescendingOrderWithoutDuplicatesOrOmissions() throws Exception {
    for (int i = 0; i < 5; i++) seed("page-" + i, CAMPUS_A, "animal-page", "REPORTED", "2026-10-01T00:0" + i + ":00Z");
    mvc.perform(get("/api/v1/events").param("campusId", CAMPUS_A).param("page", "0").param("size", "2")).andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.items[*].id", contains("page-4", "page-3")));
    mvc.perform(get("/api/v1/events").param("campusId", CAMPUS_A).param("page", "1").param("size", "2")).andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id", contains("page-2", "page-1")));
    mvc.perform(get("/api/v1/events").param("campusId", CAMPUS_A).param("page", "2").param("size", "2")).andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id", contains("page-0")));
    Assertions.assertEquals(List.of("page-4", "page-3", "page-2", "page-1", "page-0"), jdbc.queryForList("SELECT id FROM event WHERE campus_id=? ORDER BY reported_at DESC,id DESC", String.class, CAMPUS_A));
  }

  @Test void detailProtectsExactLocationButShowsItToReporterAndAdminAndReturnsMediaSummary() throws Exception {
    seed("detail-event", CAMPUS_A, "animal-a", "VERIFIED", "2026-10-01T00:01:00Z"); jdbc.update("UPDATE event SET exact_location_description=?, latitude=?, longitude=? WHERE id=?", "precise gate", 31.1234567, 121.7654321, "detail-event");
    byte[] bytes = "permanent-media".getBytes(StandardCharsets.UTF_8); String key = "events/detail-event/00.jpg"; minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key).stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType("image/jpeg").build()); jdbc.update("INSERT INTO event_media (id,event_id,object_key,content_type,size_bytes,sort_order,created_at) VALUES (?,?,?,?,?,?,?)", "media-detail", "detail-event", key, "image/jpeg", bytes.length, 0, Timestamp.from(Instant.parse("2026-10-01T00:01:00Z")));
    mvc.perform(get("/api/v1/events/{id}", "detail-event").header("X-User-Id", OTHER)).andExpect(status().isOk()).andExpect(jsonPath("$.publicLocationDescription").value("public-detail-event")).andExpect(jsonPath("$.exactLocationDescription").value(nullValue())).andExpect(jsonPath("$.latitude").value(nullValue())).andExpect(jsonPath("$.longitude").value(nullValue())).andExpect(jsonPath("$.media[0].contentType").value("image/jpeg")).andExpect(jsonPath("$.media[0].readUrl", containsString(key)));
    mvc.perform(get("/api/v1/events/{id}", "detail-event").header("X-User-Id", REPORTER)).andExpect(status().isOk()).andExpect(jsonPath("$.exactLocationDescription").value("precise gate")).andExpect(jsonPath("$.latitude").value(31.1234567)).andExpect(jsonPath("$.longitude").value(121.7654321));
    mvc.perform(get("/api/v1/events/{id}", "detail-event").header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.exactLocationDescription").value("precise gate")).andExpect(jsonPath("$.latitude").value(31.1234567)).andExpect(jsonPath("$.longitude").value(121.7654321));
  }

  @Test void missingEventUsesTheEventNotFoundCode() throws Exception { mvc.perform(get("/api/v1/events/{id}", "00000000-0000-0000-0000-000000000404")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND")); }

  private void seed(String id, String campus, String animal, String status, String reportedAt) { Timestamp time = Timestamp.from(Instant.parse(reportedAt)); String draftId = "draft-" + id; jdbc.update("INSERT INTO event_draft (id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)", draftId, REPORTER, campus, "draft-" + id, time, "public-" + id, time, time); jdbc.update("INSERT INTO event (id,source_draft_id,reporter_user_id,campus_id,animal_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", id, draftId, REPORTER, campus, animal, "event-" + id, time, time, "public-" + id, status, time, time); }
  static class Stub { final HttpServer server; Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/", this::handle); server.start(); } catch (IOException e) { throw new RuntimeException(e); } } String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); }
    void handle(HttpExchange e) throws IOException { String user = e.getRequestHeaders().getFirst("X-User-Id"); if (user == null) { e.sendResponseHeaders(401, -1); e.close(); return; } String json = "{\"id\":\"" + user + "\",\"systemRole\":\"" + (ADMIN.equals(user) ? "GOVERNANCE_ADMIN" : "USER") + "\"}"; byte[] bytes = json.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close(); }
  }
}