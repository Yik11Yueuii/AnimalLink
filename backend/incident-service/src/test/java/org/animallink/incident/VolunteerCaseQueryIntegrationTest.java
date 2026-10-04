package org.animallink.incident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class VolunteerCaseQueryIntegrationTest {
  static final String CAMPUS="62000000-0000-0000-0000-000000000001", OTHER_CAMPUS="62000000-0000-0000-0000-000000000002";
  static final String OWNER="62000000-0000-0000-0000-000000000011", TARGET="62000000-0000-0000-0000-000000000012", OTHER="62000000-0000-0000-0000-000000000013";
  static final Stub STUB=new Stub();
  @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.41").withDatabaseName("volunteer_query_test").withUsername("test").withPassword("test");
  @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.cloud.nacos.discovery.enabled",()->false);r.add("spring.cloud.nacos.config.enabled",()->false);r.add("animallink.identity.base-url",STUB::url);r.add("animallink.animal.base-url",STUB::url);r.add("animallink.intelligence.base-url",STUB::url);r.add("animallink.minio.endpoint",()->"http://localhost:9000");r.add("animallink.minio.access-key",()->"a");r.add("animallink.minio.secret-key",()->"b");r.add("animallink.minio.bucket",()->"b");}
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
  @BeforeEach void clean(){STUB.members.clear();STUB.members.put(OWNER,"ACTIVE");STUB.members.put(TARGET,"ACTIVE");STUB.members.put(OTHER,"ACTIVE");STUB.down=false;jdbc.update("DELETE FROM case_owner_audit_log");jdbc.update("DELETE FROM case_action_media");jdbc.update("DELETE FROM case_action");jdbc.update("DELETE FROM case_participant");jdbc.update("DELETE FROM animal_case");jdbc.update("DELETE FROM evidence");jdbc.update("DELETE FROM event");jdbc.update("DELETE FROM event_draft");}
  @AfterAll static void stop(){STUB.server.stop(0);}

  @Test void availableIncludesOnlyVerifiedUnclaimedCasesInCampus()throws Exception{seed("no-case",CAMPUS,"VERIFIED",null,null);String waiting=seed("waiting",CAMPUS,"VERIFIED","ACTIVE","WAITING_CLAIM");seed("claimed",CAMPUS,"VERIFIED","ACTIVE","CLAIMED");seed("reported",CAMPUS,"REPORTED",null,null);seed("other-campus",OTHER_CAMPUS,"VERIFIED",null,null);mvc.perform(work(OWNER,"AVAILABLE")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[?(@.eventId=='e-no-case')].relationship").value("AVAILABLE")).andExpect(jsonPath("$.items[?(@.caseId=='"+waiting+"')].relationship").value("AVAILABLE"));}
  @Test void activeIsLimitedToOwnerAndActiveParticipant()throws Exception{String owned=seed("owned",CAMPUS,"VERIFIED","ACTIVE","CLAIMED");String joined=seed("joined",CAMPUS,"VERIFIED","ACTIVE","CLAIMED");String unrelated=seed("unrelated",CAMPUS,"VERIFIED","ACTIVE","CLAIMED");jdbc.update("UPDATE animal_case SET owner_user_id=? WHERE id IN (?,?)",OTHER,joined,unrelated);participant(joined,TARGET,"ACTIVE");mvc.perform(work(OWNER,"ACTIVE")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].caseId").value(owned)).andExpect(jsonPath("$.items[0].relationship").value("OWNER"));mvc.perform(work(TARGET,"ACTIVE")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].caseId").value(joined)).andExpect(jsonPath("$.items[0].relationship").value("ACTIVE_PARTICIPANT"));}
  @Test void historyIncludesFormerParticipantAndFormerOwner()throws Exception{String formerParticipant=seed("former-participant",CAMPUS,"VERIFIED","CANCELLED","CLAIMED");participant(formerParticipant,TARGET,"LEFT");String formerOwner=seed("former-owner",CAMPUS,"VERIFIED","RESOLVED","CLAIMED");jdbc.update("UPDATE animal_case SET owner_user_id=? WHERE id=?",OTHER,formerOwner);jdbc.update("INSERT INTO case_owner_audit_log(id,case_id,previous_owner_user_id,new_owner_user_id,changed_by_user_id,change_type,reason,created_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),formerOwner,TARGET,OTHER,OTHER,"OWNER_TRANSFER","handoff",Timestamp.from(Instant.now()));mvc.perform(work(TARGET,"HISTORY")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[?(@.caseId=='"+formerParticipant+"')].relationship").value("FORMER_PARTICIPANT")).andExpect(jsonPath("$.items[?(@.caseId=='"+formerOwner+"')].relationship").value("FORMER_OWNER"));}
  @Test void workspaceFailsClosedForVolunteerAndDependencyFailures()throws Exception{seed("available",CAMPUS,"VERIFIED",null,null);STUB.members.put(OWNER,"PAUSED");mvc.perform(work(OWNER,"AVAILABLE")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_VOLUNTEER_REQUIRED"));STUB.members.put(OWNER,"ACTIVE");STUB.down=true;try{mvc.perform(work(OWNER,"AVAILABLE")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_SERVICE_UNAVAILABLE"));}finally{STUB.down=false;}}
  @Test void workspaceValidatesScopeAndPagination()throws Exception{mvc.perform(get("/api/v1/volunteer/cases").header("X-User-Id",OWNER).param("campusId",CAMPUS).param("scope","UNKNOWN")).andExpect(status().isBadRequest());mvc.perform(get("/api/v1/volunteer/cases").header("X-User-Id",OWNER).param("campusId",CAMPUS).param("scope","AVAILABLE").param("size","101")).andExpect(status().isBadRequest());}
  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder work(String user,String scope){return get("/api/v1/volunteer/cases").header("X-User-Id",user).param("campusId",CAMPUS).param("scope",scope);}
  private String seed(String suffix,String campus,String eventStatus,String caseStatus,String claim){Timestamp n=Timestamp.from(Instant.parse("2026-10-03T10:00:00Z"));String e="e-"+suffix,d="d-"+suffix,c="c-"+suffix;jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",d,OWNER,campus,"draft",n,"public",n,n);jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,description,abnormality_summary,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",e,d,OWNER,campus,"event","abnormal",n,n,"public",eventStatus,n,n);if(caseStatus!=null)jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",c,e,campus,"WAITING_CLAIM".equals(claim)?null:OWNER,caseStatus,claim,"WAITING_CLAIM".equals(claim)?null:n,n,n);return c;}
  private void participant(String c,String user,String state){Timestamp n=Timestamp.from(Instant.now());jdbc.update("INSERT INTO case_participant(id,case_id,user_id,status,invited_by_user_id,invited_at,joined_at,ended_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),c,user,state,OWNER,n,n,"ACTIVE".equals(state)?null:n,n,n);}
  static class Stub {final HttpServer server;final Map<String,String> members=new ConcurrentHashMap<>();volatile boolean down;Stub(){try{server=HttpServer.create(new InetSocketAddress(0),0);server.createContext("/",this::handle);server.start();}catch(IOException e){throw new RuntimeException(e);}}String url(){return "http://localhost:"+server.getAddress().getPort();}void handle(HttpExchange e)throws IOException{String p=e.getRequestURI().getPath(),u=e.getRequestHeaders().getFirst("X-User-Id");if(down&&p.contains("volunteer-membership")){e.sendResponseHeaders(503,-1);e.close();return;}String[] parts=p.split("/");String subject=parts.length>4?parts[4]:u;String out=p.contains("volunteer-membership")?("ACTIVE".equals(members.get(subject))?"{\"exists\":true,\"active\":true,\"status\":\"ACTIVE\"}":"{\"exists\":true,\"active\":false,\"status\":\"PAUSED\"}"):p.contains("animals/batch")?"[]":"{\"id\":\""+u+"\",\"systemRole\":\"USER\"}";byte[] body=out.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();}}
}
