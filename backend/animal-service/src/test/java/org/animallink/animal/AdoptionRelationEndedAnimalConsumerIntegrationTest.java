package org.animallink.animal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.animallink.animal.infrastructure.AdoptionRelationEndedAnimalConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(AdoptionRelationEndedAnimalConsumerIntegrationTest.Config.class)
class AdoptionRelationEndedAnimalConsumerIntegrationTest {
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000004";
    private static final TestDatabase DATABASE = TestDatabase.create();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
    }

    @SpyBean JdbcTemplate jdbc;
    @Autowired AdoptionRelationEndedAnimalConsumer consumer;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM processed_domain_event");
        jdbc.update("DELETE FROM timeline_entry");
        jdbc.update("DELETE FROM animal");
        jdbc.update("INSERT INTO animal(id,campus_id,display_name,species,sex,identity_status,adoption_status,current_context) VALUES(?,'c','a','CAT','UNKNOWN','ACTIVE','ADOPTED','ADOPTED_HOME')", ANIMAL);
    }

    @AfterAll
    static void stop() { DATABASE.close(); }

    @Test
    void validRelationEndedEventProjectsAnimalTimelineAndMarker() {
        String eventId = UUID.randomUUID().toString();
        String relationId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.parse("2026-10-05T00:00:00Z");
        consumer.consume(event(eventId, relationId, ANIMAL, occurredAt, 1));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT identity_status FROM animal WHERE id=?", String.class, ANIMAL));
        assertEquals("NOT_OPEN", jdbc.queryForObject("SELECT adoption_status FROM animal WHERE id=?", String.class, ANIMAL));
        assertEquals("CAMPUS", jdbc.queryForObject("SELECT current_context FROM animal WHERE id=?", String.class, ANIMAL));
        var timeline = jdbc.queryForMap("SELECT source_type,source_id,entry_type,title,summary,occurred_at FROM timeline_entry WHERE source_id=?", relationId);
        assertEquals("ADOPTION", timeline.get("source_type"));
        assertEquals(relationId, timeline.get("source_id"));
        assertEquals("ADOPTION_ENDED", timeline.get("entry_type"));
        assertEquals("领养关系已结束", timeline.get("title"));
        assertNull(timeline.get("summary"));
        assertEquals(occurredAt, jdbc.queryForObject("SELECT occurred_at FROM timeline_entry WHERE source_id=?", (resultSet, row) -> resultSet.getTimestamp(1).toInstant(), relationId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_domain_event WHERE event_id=? AND event_type='ADOPTION_RELATION_ENDED'", Integer.class, eventId));
    }

    @Test
    void sameEventIsIdempotentAndDifferentEventConflicts() {
        String relation = UUID.randomUUID().toString();
        String first = event(UUID.randomUUID().toString(), relation, ANIMAL, Instant.now(), 1);
        consumer.consume(first);
        consumer.consume(first);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), ANIMAL, Instant.now(), 1)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE entry_type='ADOPTION_ENDED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_domain_event", Integer.class));
    }

    @Test
    void malformedWrongTypeUnsupportedVersionAndInvalidStatesRejectWithoutMarker() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume("{}"));
        String base = event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), ANIMAL, Instant.now(), 1);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume(base.replace("ADOPTION_RELATION_ENDED", "ADOPTION_COMPLETED")));
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), ANIMAL, Instant.now(), 2)));
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume(base.substring(0, base.length() - 1) + ",\"extra\":true}"));
        for (String state : java.util.List.of("'OPEN','CAMPUS'", "'ADOPTED','CAMPUS'", "'NOT_OPEN','CAMPUS'")) {
            jdbc.update("UPDATE animal SET adoption_status=" + state.substring(0, state.indexOf(',')) + ",current_context=" + state.substring(state.indexOf(',') + 1) + " WHERE id=?", ANIMAL);
            assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), ANIMAL, Instant.now(), 1)));
            jdbc.update("UPDATE animal SET adoption_status='ADOPTED',current_context='ADOPTED_HOME' WHERE id=?", ANIMAL);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processed_domain_event", Integer.class));
    }

    @Test
    void timelineFailureRollsBackThenSameEventRetries() {
        String eventId = UUID.randomUUID().toString();
        String relation = UUID.randomUUID().toString();
        String message = event(eventId, relation, ANIMAL, Instant.now(), 1);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (invocation.getArgument(0, String.class).startsWith("INSERT INTO timeline_entry")) throw new DataAccessResourceFailureException("transient timeline failure");
            return invocation.callRealMethod();
        }).when(jdbc).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
        assertThrows(DataAccessResourceFailureException.class, () -> consumer.consume(message));
        assertState("ADOPTED", "ADOPTED_HOME");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processed_domain_event WHERE event_id=?", Integer.class, eventId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE source_id=?", Integer.class, relation));
        org.mockito.Mockito.reset(jdbc);
        consumer.consume(message);
        assertState("NOT_OPEN", "CAMPUS");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_domain_event WHERE event_id=?", Integer.class, eventId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE source_id=?", Integer.class, relation));
    }

    @Test
    void completedAndEndedTimelineEntriesCoexistForTheSameRelation() {
        String relationId = UUID.randomUUID().toString();
        Instant completedAt = Instant.parse("2026-10-05T00:00:00Z");
        Instant endedAt = Instant.parse("2026-10-05T01:00:00Z");
        insertTimeline(relationId, "ADOPTION_COMPLETED", "已完成领养交接", completedAt);

        consumer.consume(event(UUID.randomUUID().toString(), relationId, ANIMAL, endedAt, 1));

        assertState("NOT_OPEN", "CAMPUS");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_domain_event WHERE event_type='ADOPTION_RELATION_ENDED'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE source_type='ADOPTION' AND source_id=?", Integer.class, relationId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE source_type='ADOPTION' AND source_id=? AND entry_type='ADOPTION_COMPLETED'", Integer.class, relationId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE source_type='ADOPTION' AND source_id=? AND entry_type='ADOPTION_ENDED'", Integer.class, relationId));
    }

    @Test
    void sameTimelineSourceAndEntryTypeRemainsUnique() {
        String relationId = UUID.randomUUID().toString();
        insertTimeline(relationId, "ADOPTION_ENDED", "领养关系已结束", Instant.parse("2026-10-05T00:00:00Z"));

        assertThrows(DuplicateKeyException.class, () -> insertTimeline(relationId, "ADOPTION_ENDED", "领养关系已结束", Instant.parse("2026-10-05T01:00:00Z")));
    }

    @RepeatedTest(10)
    void concurrentSameEventConverges() throws Exception {
        String message = event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), ANIMAL, Instant.now(), 1);
        race(() -> consumer.consume(message), () -> consumer.consume(message), false);
        assertState("NOT_OPEN", "CAMPUS");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_domain_event", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE entry_type='ADOPTION_ENDED'", Integer.class));
    }

    @RepeatedTest(10)
    void concurrentDifferentEventsHaveOneWinner() throws Exception {
        race(() -> consumer.consume(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), ANIMAL, Instant.now(), 1)), () -> consumer.consume(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), ANIMAL, Instant.now(), 1)), true);
        assertState("NOT_OPEN", "CAMPUS");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_domain_event", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE entry_type='ADOPTION_ENDED'", Integer.class));
    }

    private void assertState(String adoptionStatus, String context) {
        assertEquals(adoptionStatus, jdbc.queryForObject("SELECT adoption_status FROM animal WHERE id=?", String.class, ANIMAL));
        assertEquals(context, jdbc.queryForObject("SELECT current_context FROM animal WHERE id=?", String.class, ANIMAL));
    }

    private void insertTimeline(String relationId, String entryType, String title, Instant occurredAt) {
        jdbc.update("INSERT INTO timeline_entry(id,animal_id,source_type,source_id,entry_type,title,summary,occurred_at,visibility,created_at) VALUES(?,?,'ADOPTION',?,?,?,NULL,?,'PUBLIC',?)", UUID.randomUUID().toString(), ANIMAL, relationId, entryType, title, java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(Instant.now()));
    }

    private void race(Runnable first, Runnable second, boolean oneReject) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        Future<Boolean> firstResult = pool.submit(() -> run(first, start, ready));
        Future<Boolean> secondResult = pool.submit(() -> run(second, start, ready));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        assertEquals(oneReject ? 1 : 2, (firstResult.get() ? 1 : 0) + (secondResult.get() ? 1 : 0));
        pool.shutdown();
    }

    private boolean run(Runnable action, CountDownLatch start, CountDownLatch ready) {
        ready.countDown();
        try { start.await(); action.run(); return true; }
        catch (AmqpRejectAndDontRequeueException exception) { return false; }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private static String event(String eventId, String relationId, String animalId, Instant occurredAt, int version) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"ADOPTION_RELATION_ENDED\",\"eventVersion\":" + version + ",\"occurredAt\":\"" + occurredAt + "\",\"relationId\":\"" + relationId + "\",\"animalId\":\"" + animalId + "\"}";
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @Bean AdoptionRelationEndedAnimalConsumer adoptionRelationEndedAnimalConsumer(JdbcTemplate jdbc, ObjectMapper json) { return new AdoptionRelationEndedAnimalConsumer(jdbc, json); }
    }

    static class TestDatabase {
        final String url;
        final String username;
        final String password;
        final MySQLContainer<?> container;
        TestDatabase(String url, String username, String password, MySQLContainer<?> container) { this.url = url; this.username = username; this.password = password; this.container = container; }
        static TestDatabase create() {
            if ("true".equalsIgnoreCase(System.getenv("ANIMALLINK_TEST_EXTERNAL_MYSQL"))) {
                int port = Integer.parseInt(required("ANIMALLINK_TEST_MYSQL_PORT"));
                return new TestDatabase("jdbc:mysql://127.0.0.1:" + port + "/" + required("ANIMALLINK_TEST_MYSQL_DATABASE") + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC", required("ANIMALLINK_TEST_MYSQL_USERNAME"), required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null);
            }
            MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("adoption_relation_ended_test").withUsername("test").withPassword("test");
            container.start();
            return new TestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword(), container);
        }
        private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the localhost external MySQL fallback"); return value; }
        String url() { return url; }
        String username() { return username; }
        String password() { return password; }
        void close() { if (container != null) container.stop(); }
    }
}
