package org.animallink.incident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class RescueLifecycleIntegrationTest {
  static final String CAMPUS="63000000-0000-0000-0000-000000000001", REPORTER="63000000-0000-0000-0000-000000000011", OWNER="63000000-0000-0000-0000-000000000012"; static final Stub STUB=new Stub();
  @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.41").withDatabaseName("rescue_lifecycle_test").withUsername("test").withPassword("test");
  @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.cloud.nacos.discovery.enabled",()->false);r.add("spring.cloud.nacos.config.enabled",()->false);r.add("animallink.identity.base-url",STUB::url);r.add("animallink.animal.base-url",STUB::url);r.add("animallink.intelligence.base-url",STUB::url);r.add("animallink.minio.endpoint",()->"http://localhost:9000");r.add("animallink.minio.access-key",()->"a");r.add("animallink.minio.secret-key",()->"b");r.add("animallink.minio.bucket",()->"b");}
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
  @BeforeEach void clean(){STUB.members.clear();STUB.members.put(OWNER,"ACTIVE");jdbc.update("DELETE FROM outbox_event");jdbc.update("DELETE FROM case_action_media");jdbc.update("DELETE FROM case_action");jdbc.update("DELETE FROM case_participant");jdbc.update("DELETE FROM animal_case");jdbc.update("DELETE FROM evidence");jdbc.update("DELETE FROM event");jdbc.update("DELETE FROM event_draft");}
  @AfterAll static void stop(){STUB.server.stop(0);}
  @Test void claimActionResultAndRescueViewsStayConsistent()throws Exception{seed();mvc.perform(post("/api/v1/events/e-lifecycle/case/claim").header("X-User-Id",OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));String caseId=jdbc.queryForObject("SELECT id FROM animal_case WHERE event_id='e-lifecycle'",String.class);mvc.perform(post("/api/v1/cases/{id}/actions",caseId).header("X-User-Id",OWNER).contentType("application/json").content("{\"description\":\"arrived\",\"occurredAt\":\"2026-10-03T10:00:00Z\"}")).andExpect(status().isOk());mvc.perform(post("/api/v1/cases/{id}/result",caseId).header("X-User-Id",OWNER).contentType("application/json").content("{\"outcome\":\"RESOLVED\",\"summary\":\"done\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.caseStatus").value("RESOLVED"));mvc.perform(get("/api/v1/rescues").param("campusId",CAMPUS)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].rescueStatus").value("COMPLETED")).andExpect(jsonPath("$.items[0].resultCode").value("RESOLVED"));mvc.perform(get("/api/v1/rescues/e-lifecycle").header("X-User-Id",OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.caseInfo.status").value("RESOLVED")).andExpect(jsonPath("$.progress.length()").value(2));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,caseId));}
  private void seed(){Timestamp n=Timestamp.from(Instant.parse("2026-10-03T09:00:00Z"));jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)","d-lifecycle",REPORTER,CAMPUS,"draft",n,"public",n,n);jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,animal_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)","e-lifecycle","d-lifecycle",REPORTER,CAMPUS,"animal-lifecycle","event",n,n,"public","VERIFIED",n,n);}
  static class Stub {final HttpServer server;final Map<String,String> members=new ConcurrentHashMap<>();Stub(){try{server=HttpServer.create(new InetSocketAddress(0),0);server.createContext("/",this::handle);server.start();}catch(IOException e){throw new RuntimeException(e);}}String url(){return "http://localhost:"+server.getAddress().getPort();}void handle(HttpExchange e)throws IOException{String p=e.getRequestURI().getPath(),u=e.getRequestHeaders().getFirst("X-User-Id");String[] x=p.split("/");String subject=x.length>4?x[4]:u;String out=p.contains("volunteer-membership")?("ACTIVE".equals(members.get(subject))?"{\"exists\":true,\"active\":true,\"status\":\"ACTIVE\"}":"{\"exists\":false,\"active\":false}"):p.contains("animals/batch")?"[]":"{\"id\":\""+u+"\",\"systemRole\":\"USER\"}";byte[] b=out.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();}}
}
