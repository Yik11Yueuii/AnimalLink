package org.animallink.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CaseActionMediaIntegrationTest {
  static final String CAMPUS="20000000-0000-0000-0000-000000000001", OWNER="20000000-0000-0000-0000-000000000011", TARGET="20000000-0000-0000-0000-000000000012", OTHER="20000000-0000-0000-0000-000000000013", ADMIN="20000000-0000-0000-0000-000000000999", REPORTER="20000000-0000-0000-0000-000000000014", BUCKET="case-action-media";
  static final Stub STUB=new Stub();
  @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.41").withDatabaseName("case_action_media_test").withUsername("test").withPassword("test");
  @Container static final GenericContainer<?> MINIO=new GenericContainer<>("animallink-minio:RELEASE.2025-10-15T17-29-55Z").withExposedPorts(9000).withEnv("MINIO_ROOT_USER","minioadmin").withEnv("MINIO_ROOT_PASSWORD","minioadmin").withCommand("server /data");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.cloud.nacos.discovery.enabled",()->false);r.add("spring.cloud.nacos.config.enabled",()->false);r.add("animallink.identity.base-url",STUB::url);r.add("animallink.animal.base-url",STUB::url);r.add("animallink.intelligence.base-url",STUB::url);r.add("animallink.minio.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));r.add("animallink.minio.access-key",()->"minioadmin");r.add("animallink.minio.secret-key",()->"minioadmin");r.add("animallink.minio.bucket",()->BUCKET);}
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper; @Autowired MinioClient minio;
  @BeforeEach void reset() throws Exception {if(!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()))minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());STUB.memberships.clear();STUB.memberships.put(OWNER,"ACTIVE");STUB.memberships.put(TARGET,"ACTIVE");STUB.memberships.put(OTHER,"ACTIVE");jdbc.update("DELETE FROM case_action_media");jdbc.update("DELETE FROM case_action");jdbc.update("DELETE FROM case_participant");jdbc.update("DELETE FROM animal_case");jdbc.update("DELETE FROM evidence");jdbc.update("DELETE FROM event");jdbc.update("DELETE FROM event_draft");}
  @AfterAll static void stop(){STUB.stop();}

  @Test void ownerFormalizesOrderedMediaAndKeepsBusinessState() throws Exception {String c=seed("owner");put(OWNER,"late.jpg","image/jpeg","late");put(OWNER,"early.mp4","video/mp4","early");String body=request("owner",media(OWNER,"late.jpg","image/jpeg",4,1)+","+media(OWNER,"early.mp4","video/mp4",5,0));String response=mvc.perform(post("/api/v1/cases/{id}/actions",c).header("X-User-Id",OWNER).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.media.length()").value(2)).andExpect(jsonPath("$.media[0].sortOrder").value(0)).andExpect(jsonPath("$.media[0].readUrl").isNotEmpty()).andExpect(jsonPath("$.media[0].objectKey").doesNotExist()).andExpect(jsonPath("$.media[0].object_key").doesNotExist()).andReturn().getResponse().getContentAsString();String action=mapper.readTree(response).required("id").asText();assertEquals(2,count("case_action_media"));assertEquals(0,count("evidence"));assertEquals("VERIFIED",jdbc.queryForObject("SELECT status FROM event WHERE id=?",String.class,"e-owner"));assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM animal_case WHERE id=?",String.class,c));assertEquals("CLAIMED",jdbc.queryForObject("SELECT claim_status FROM animal_case WHERE id=?",String.class,c));assertEquals(OWNER,jdbc.queryForObject("SELECT owner_user_id FROM animal_case WHERE id=?",String.class,c));mvc.perform(get("/api/v1/cases/{id}/actions",c).header("X-User-Id",OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(action)).andExpect(jsonPath("$.items[0].media[0].sortOrder").value(0));}
  @Test void activeCollaboratorAndAdminCanReadMediaButOtherCannot() throws Exception {String c=seed("read");participant(c,TARGET);put(TARGET,"target.jpg","image/jpeg","target");create(c,TARGET,request("target",media(TARGET,"target.jpg","image/jpeg",6,0))).andExpect(status().isOk()).andExpect(jsonPath("$.actorUserId").value(TARGET));mvc.perform(get("/api/v1/cases/{id}/actions",c).header("X-User-Id",TARGET)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].media[0].readUrl").isNotEmpty());mvc.perform(get("/api/v1/cases/{id}/actions",c).header("X-User-Id",ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].media[0].readUrl").isNotEmpty());mvc.perform(get("/api/v1/cases/{id}/actions",c).header("X-User-Id",OTHER)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CASE_ACTION_ACCESS_REQUIRED")).andExpect(jsonPath("$.readUrl").doesNotExist());}
  @Test void invalidAndUnauthorizedMediaDoNotCreateRows() throws Exception {String c=seed("invalid");put(OWNER,"real.jpg","image/jpeg","real");int before=count("case_action");create(c,OTHER,request("other",media(OTHER,"missing.jpg","image/jpeg",1,0))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CASE_ACTION_ACCESS_REQUIRED"));create(c,OWNER,request("foreign",media(OTHER,"real.jpg","image/jpeg",4,0))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MEDIA"));create(c,OWNER,request("bad",media(OWNER,"real.jpg","application/pdf",4,0))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MEDIA"));create(c,OWNER,request("mismatch",media(OWNER,"real.jpg","image/jpeg",3,0))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MEDIA"));create(c,OWNER,request("missing",media(OWNER,"missing.jpg","image/jpeg",1,0))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MINIO_UNAVAILABLE"));assertEquals(before,count("case_action"));assertEquals(0,count("case_action_media"));}

  @Test void sameSortOrderMediaHaveUniqueKeysAndAreOrderedByMediaId() throws Exception {String c=seed("same-sort");put(OWNER,"one.jpg","image/jpeg","one");put(OWNER,"two.jpg","image/jpeg","two");String response=create(c,OWNER,request("same",media(OWNER,"one.jpg","image/jpeg",3,0)+","+media(OWNER,"two.jpg","image/jpeg",3,0))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();var media=mapper.readTree(response).required("media");assertEquals(0,media.get(0).required("sortOrder").asInt());assertEquals(0,media.get(1).required("sortOrder").asInt());assertEquals(true,media.get(0).required("mediaId").asText().compareTo(media.get(1).required("mediaId").asText())<0);assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT object_key) FROM case_action_media",Integer.class));}
  @Test void pausedOwnerCannotCreateMediaAction() throws Exception {String c=seed("paused-media");put(OWNER,"real.jpg","image/jpeg","real");STUB.memberships.put(OWNER,"PAUSED");create(c,OWNER,request("paused",media(OWNER,"real.jpg","image/jpeg",4,0))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_VOLUNTEER_REQUIRED"));assertEquals(0,count("case_action"));assertEquals(0,count("case_action_media"));}
  @Test void mediaForeignKeyRejectsOrphanMetadata() {Timestamp now=Timestamp.from(Instant.now());assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO case_action_media(id,case_action_id,object_key,content_type,size_bytes,sort_order,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),"missing-action","case-actions/missing/00.jpg","image/jpeg",1,0,now));}
  private org.springframework.test.web.servlet.ResultActions create(String c,String user,String body) throws Exception{return mvc.perform(post("/api/v1/cases/{id}/actions",c).header("X-User-Id",user).contentType(MediaType.APPLICATION_JSON).content(body));}
  private String request(String description,String media){return "{\"description\":\""+description+"\",\"occurredAt\":\"2026-10-03T10:00:00Z\",\"media\":["+media+"]}";}
  private String media(String owner,String name,String type,long size,int order){return "{\"temporaryObjectKey\":\"incident-input/"+owner+"/"+name+"\",\"contentType\":\""+type+"\",\"sizeBytes\":"+size+",\"sortOrder\":"+order+"}";}
  private void put(String owner,String name,String type,String value) throws Exception {byte[] b=value.getBytes(StandardCharsets.UTF_8);minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object("incident-input/"+owner+"/"+name).stream(new ByteArrayInputStream(b),b.length,-1).contentType(type).build());}
  private void participant(String c,String user){Timestamp n=Timestamp.from(Instant.now());jdbc.update("INSERT INTO case_participant(id,case_id,user_id,status,invited_by_user_id,invited_at,joined_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),c,user,"ACTIVE",OWNER,n,n,n,n);}
  private String seed(String suffix){Timestamp n=Timestamp.from(Instant.now());String e="e-"+suffix,d="d-"+suffix,c="c-"+suffix;jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",d,REPORTER,CAMPUS,"d",n,"p",n,n);jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",e,d,REPORTER,CAMPUS,"e",n,n,"p","VERIFIED",n,n);jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",c,e,CAMPUS,OWNER,"ACTIVE","CLAIMED",n,n,n);return c;}
  private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
  static class Stub {final HttpServer server;final Map<String,String> memberships=new ConcurrentHashMap<>();Stub(){try{server=HttpServer.create(new InetSocketAddress(0),0);server.createContext("/",this::handle);server.start();}catch(IOException e){throw new RuntimeException(e);}}String url(){return "http://localhost:"+server.getAddress().getPort();}void stop(){server.stop(0);}void handle(HttpExchange e)throws IOException{String path=e.getRequestURI().getPath(),header=e.getRequestHeaders().getFirst("X-User-Id");String[] parts=path.split("/");String subject=parts.length>4?parts[4]:header;String json=path.contains("volunteer-membership")?("ACTIVE".equals(memberships.get(subject))?"{\"exists\":true,\"active\":true,\"status\":\"ACTIVE\"}":"{\"exists\":true,\"active\":false,\"status\":\"PAUSED\"}"):"{\"id\":\""+header+"\",\"systemRole\":\""+(ADMIN.equals(header)?"GOVERNANCE_ADMIN":"USER")+"\"}";byte[] body=json.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();}}
}
