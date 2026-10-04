package org.animallink.animal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.animallink.animal.infrastructure.CaseResultTimelineConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class CaseTimelineConsumerIntegrationTest {
 private static final String ANIMAL_ID="90000000-0000-0000-0000-000000000001";
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.41").withDatabaseName("timeline_consumer_test").withUsername("test").withPassword("test");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.cloud.nacos.discovery.enabled",()->false);r.add("spring.cloud.nacos.config.enabled",()->false);}
 @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired MockMvc mvc;CaseResultTimelineConsumer consumer;
 @BeforeEach void clean(){consumer=new CaseResultTimelineConsumer(jdbc,json);jdbc.update("DELETE FROM timeline_entry");jdbc.update("DELETE FROM animal");jdbc.update("INSERT INTO animal(id,campus_id,display_name,species,sex,identity_status,adoption_status,current_context) VALUES(?,'campus-1','测试动物','CAT','UNKNOWN','ACTIVE','NOT_OPEN','CAMPUS')",ANIMAL_ID);}
 @Test void outcomesMapAndTimelineApiReadsProjection()throws Exception{consume("case-r","RESOLVED","resolved");consume("case-u","UNRESOLVED","unresolved");consume("case-c","CANCELLED","cancelled");assertEquals("救助处理已解决",title("case-r"));assertEquals("救助处理未解决",title("case-u"));assertEquals("救助处理已取消",title("case-c"));mvc.perform(get("/api/v1/animals/{id}/timeline",ANIMAL_ID)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items[0].sourceType").value("CASE")).andExpect(jsonPath("$.items[0].entryType").value("RESCUE_RESULT"));}
 @Test void duplicateDeliveryIsIdempotentAndAnimalUnchanged(){String before=jdbc.queryForMap("SELECT identity_status,adoption_status,current_context,display_name FROM animal WHERE id=?",ANIMAL_ID).toString();consume("case-dup","RESOLVED","same");consume("case-dup","RESOLVED","same");assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE source_type='CASE' AND source_id='case-dup'",Integer.class));assertEquals(before,jdbc.queryForMap("SELECT identity_status,adoption_status,current_context,display_name FROM animal WHERE id=?",ANIMAL_ID).toString());}
 @Test void invalidContractsRejectWithoutMutation(){assertThrows(AmqpRejectAndDontRequeueException.class,()->consumer.consume(event("case-bad","CONTINUE_OBSERVATION","x",ANIMAL_ID,1)));assertThrows(AmqpRejectAndDontRequeueException.class,()->consumer.consume(event("case-v","RESOLVED","x",ANIMAL_ID,2)));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry",Integer.class));}
 @Test void missingAnimalFailsAndCreatesNoOrphan(){assertThrows(DataIntegrityViolationException.class,()->consumer.consume(event("case-missing","RESOLVED","x","missing",1)));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry",Integer.class));}
 private void consume(String c,String outcome,String summary){consumer.consume(event(c,outcome,summary,ANIMAL_ID,1));}private String title(String c){return jdbc.queryForObject("SELECT title FROM timeline_entry WHERE source_id=?",String.class,c);}private String event(String c,String outcome,String summary,String animal,int version){return "{\"eventId\":\"event-"+c+"\",\"eventType\":\"CASE_RESULT_FINALIZED\",\"eventVersion\":"+version+",\"caseId\":\""+c+"\",\"animalId\":\""+animal+"\",\"outcome\":\""+outcome+"\",\"summary\":\""+summary+"\",\"occurredAt\":\""+Instant.parse("2026-10-03T10:00:00Z")+"\"}";}
}
