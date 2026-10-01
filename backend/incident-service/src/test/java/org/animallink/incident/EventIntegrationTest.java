package org.animallink.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventIntegrationTest {
  static final String CAMPUS = "10000000-0000-0000-0000-000000000101", OTHER_CAMPUS = "10000000-0000-0000-0000-000000000202";
  static final String OWNER = "00000000-0000-0000-0000-000000000101", ADMIN = "00000000-0000-0000-0000-000000000999", BUCKET = "animallink-incident";
  static final Stub STUB = new Stub();
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("incident_event_test").withUsername("test").withPassword("test");
  @Container static final GenericContainer<?> MINIO = new GenericContainer<>("animallink-minio:RELEASE.2025-10-15T17-29-55Z").withExposedPorts(9000).withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin").withCommand("server /data");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("spring.cloud.nacos.discovery.enabled", () -> false); r.add("spring.cloud.nacos.config.enabled", () -> false);
    r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url); r.add("animallink.intelligence.base-url", STUB::url);
    r.add("animallink.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)); r.add("animallink.minio.access-key", () -> "minioadmin"); r.add("animallink.minio.secret-key", () -> "minioadmin"); r.add("animallink.minio.bucket", () -> BUCKET);
  }
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper objectMapper; @Autowired MinioClient minio;
  @BeforeEach void clean() throws Exception { if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build()); jdbc.update("DELETE FROM event_media"); jdbc.update("DELETE FROM evidence_media"); jdbc.update("DELETE FROM evidence"); jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM event_draft_media"); jdbc.update("DELETE FROM event_draft"); }
  @AfterAll static void stop() { STUB.stop(); }

  @Test void submitCreatesReportedEventFinalizesDraftCopiesMediaAndIsIdempotent() throws Exception {
    String temporaryKey = "incident-input/" + OWNER + "/submit.jpg"; byte[] content = "draft-image".getBytes(StandardCharsets.UTF_8);
    minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object(temporaryKey).stream(new ByteArrayInputStream(content), content.length, -1).contentType("image/jpeg").build());
    String draftId = createDraft(CAMPUS, ",\"media\":[{\"temporaryObjectKey\":\"" + temporaryKey + "\",\"contentType\":\"image/jpeg\",\"sizeBytes\":" + content.length + ",\"sortOrder\":1}]");
    String first = mvc.perform(post("/api/v1/event-drafts/{id}/submit", draftId).header("X-User-Id", OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REPORTED")).andExpect(jsonPath("$.campusId").value(CAMPUS)).andReturn().getResponse().getContentAsString();
    String eventId = objectMapper.readTree(first).required("id").asText(); String expectedKey = "events/" + eventId + "/01.jpg";
    Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE id=? AND source_draft_id=? AND status='REPORTED'", Integer.class, eventId, draftId));
    Assertions.assertEquals(eventId, jdbc.queryForObject("SELECT final_event_id FROM event_draft WHERE id=?", String.class, draftId));
    Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_draft WHERE id=? AND finalized_at IS NOT NULL", Integer.class, draftId));
    Assertions.assertEquals(expectedKey, jdbc.queryForObject("SELECT object_key FROM event_media WHERE event_id=?", String.class, eventId));
    Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_media WHERE event_id=? AND content_type='image/jpeg' AND size_bytes=? AND sort_order=1", Integer.class, eventId, content.length));
    Assertions.assertEquals(content.length, minio.statObject(StatObjectArgs.builder().bucket(BUCKET).object(expectedKey).build()).size());
    String second = mvc.perform(post("/api/v1/event-drafts/{id}/submit", draftId).header("X-User-Id", OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(eventId)).andExpect(jsonPath("$.status").value("REPORTED")).andReturn().getResponse().getContentAsString();
    Assertions.assertEquals(eventId, objectMapper.readTree(second).required("id").asText());
    Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE source_draft_id=?", Integer.class, draftId));
    Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_media WHERE event_id=?", Integer.class, eventId));
  }

  @Test void supportsReportedToVerifiedThenArchivedAndRejectsFurtherTransitions() throws Exception {
    String eventId = submit(createDraft(CAMPUS, ""));
    mvc.perform(post("/api/v1/admin/events/{id}/verify", eventId).header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
    Assertions.assertEquals("VERIFIED", jdbc.queryForObject("SELECT status FROM event WHERE id=?", String.class, eventId));
    mvc.perform(post("/api/v1/admin/events/{id}/archive", eventId).header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
    Assertions.assertEquals("ARCHIVED", jdbc.queryForObject("SELECT status FROM event WHERE id=?", String.class, eventId));
    mvc.perform(post("/api/v1/admin/events/{id}/reject", eventId).header("X-User-Id", ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_EVENT_TRANSITION"));
  }

  @Test void supportsRejectedAndDuplicateStatesAndRejectsInvalidTargetsAndMissingEvents() throws Exception {
    String rejected = submit(createDraft(CAMPUS, ""));
    mvc.perform(post("/api/v1/admin/events/{id}/reject", rejected).header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    Assertions.assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM event WHERE id=?", String.class, rejected));
    mvc.perform(post("/api/v1/admin/events/{id}/verify", rejected).header("X-User-Id", ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_EVENT_TRANSITION"));
    String canonical = submit(createDraft(CAMPUS, "")); String duplicate = submit(createDraft(CAMPUS, ""));
    mvc.perform(post("/api/v1/admin/events/{id}/mark-duplicate", duplicate).header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"canonicalEventId\":\"" + canonical + "\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DUPLICATE")).andExpect(jsonPath("$.duplicateOfEventId").value(canonical));
    Assertions.assertEquals(canonical, jdbc.queryForObject("SELECT duplicate_of_event_id FROM event WHERE id=?", String.class, duplicate));
    mvc.perform(post("/api/v1/admin/events/{id}/archive", duplicate).header("X-User-Id", ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_EVENT_TRANSITION"));
    mvc.perform(post("/api/v1/admin/events/{id}/mark-duplicate", canonical).header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"canonicalEventId\":\"" + canonical + "\"}"))
      .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    String otherCampus = submit(createDraft(OTHER_CAMPUS, ""));
    mvc.perform(post("/api/v1/admin/events/{id}/mark-duplicate", canonical).header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"canonicalEventId\":\"" + otherCampus + "\"}"))
      .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    mvc.perform(get("/api/v1/events/{id}", "00000000-0000-0000-0000-000000000404")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
  }

  private String createDraft(String campus, String suffix) throws Exception { String body = "{\"campusId\":\"" + campus + "\",\"description\":\"event draft\",\"occurredAt\":\"2026-10-01T00:00:00Z\",\"publicLocationDescription\":\"public\"" + suffix + "}"; String response = mvc.perform(post("/api/v1/event-drafts").header("X-User-Id", OWNER).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); return objectMapper.readTree(response).required("id").asText(); }
  private String submit(String draftId) throws Exception { String response = mvc.perform(post("/api/v1/event-drafts/{id}/submit", draftId).header("X-User-Id", OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REPORTED")).andReturn().getResponse().getContentAsString(); return objectMapper.readTree(response).required("id").asText(); }

  static class Stub { final HttpServer server; Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/", this::handle); server.start(); } catch (IOException e) { throw new RuntimeException(e); } } String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); }
    void handle(HttpExchange e) throws IOException { String path = e.getRequestURI().getPath(), user = e.getRequestHeaders().getFirst("X-User-Id"); String json; if (path.endsWith("/me")) json = "{\"id\":\"" + (user == null ? OWNER : user) + "\",\"systemRole\":\"" + (ADMIN.equals(user) ? "GOVERNANCE_ADMIN" : "USER") + "\"}"; else if (path.contains("campus-memberships")) json = "{\"exists\":true,\"status\":\"ACTIVE\"}"; else json = "{}"; byte[] bytes = json.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close(); }
  }
}