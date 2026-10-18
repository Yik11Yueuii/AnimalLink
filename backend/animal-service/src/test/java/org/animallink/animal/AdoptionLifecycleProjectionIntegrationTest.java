package org.animallink.animal;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.animallink.animal.application.AdoptionLifecycleAuthorityGateway;
import org.animallink.animal.application.AdoptionLifecycleEvent;
import org.animallink.animal.application.AdoptionLifecycleProjectionService;
import org.animallink.animal.domain.DependencyUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "animallink.messaging.enabled=false")
class AdoptionLifecycleProjectionIntegrationTest {
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000091";
    private static final Instant STARTED = Instant.parse("2026-10-17T00:00:00Z");
    private static final TestDatabase DATABASE = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AdoptionLifecycleProjectionService projection;
    @MockBean AdoptionLifecycleAuthorityGateway authority;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM processed_domain_event");
        jdbc.update("DELETE FROM timeline_entry");
        jdbc.update("DELETE FROM animal_adoption_lifecycle_projection");
        jdbc.update("DELETE FROM animal");
        jdbc.update("""
                INSERT INTO animal
                  (id,campus_id,display_name,species,sex,identity_status,
                   adoption_status,current_context,version)
                VALUES(?,'campus','Lifecycle Animal','CAT','UNKNOWN','ACTIVE',
                       'OPEN','CAMPUS',0)
                """, ANIMAL);
    }

    @AfterAll
    static void stop() { DATABASE.close(); }

    @Test
    void initiatedAndCancelledTransitionsAreIdempotent() {
        String handover = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(handover, "PENDING", null, null));
        AdoptionLifecycleEvent initiated = event("ADOPTION_HANDOVER_INITIATED", handover, null);

        projection.reconcile(initiated);
        projection.reconcile(initiated);
        assertState("HANDOVER_PENDING", "CAMPUS", 1);

        when(authority.latest(ANIMAL)).thenReturn(authority(handover, "CANCELLED", null, null));
        projection.reconcile(event("ADOPTION_HANDOVER_CANCELLED", handover, null));
        assertState("NOT_OPEN", "CAMPUS", 2);
        assertEquals(2, count("processed_domain_event"));
    }

    @Test
    void completionBeforeInitiationConvergesWithoutRegression() {
        String handover = id();
        String relation = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(handover, "COMPLETED", relation, "ACTIVE"));

        projection.reconcile(event("ADOPTION_COMPLETED", handover, relation));
        assertState("ADOPTED", "ADOPTED_HOME", 1);
        projection.reconcile(event("ADOPTION_HANDOVER_INITIATED", handover, null));

        assertState("ADOPTED", "ADOPTED_HOME", 1);
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM timeline_entry
                WHERE source_id=? AND entry_type='ADOPTION_COMPLETED'
                """, Integer.class, relation));
    }

    @Test
    void relationEndBeforeCompletionConvergesToNotOpenAndKeepsBothTimelineFacts() {
        String handover = id();
        String relation = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(handover, "COMPLETED", relation, "ENDED"));

        projection.reconcile(event("ADOPTION_RELATION_ENDED", handover, relation));
        projection.reconcile(event("ADOPTION_COMPLETED", handover, relation));

        assertState("NOT_OPEN", "CAMPUS", 1);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM timeline_entry WHERE source_id=?",
                Integer.class, relation));
    }

    @Test
    void verifiedLegacyCompletedUsesCurrentAuthoritativeLifecycle() {
        String handover = id();
        String relation = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(
                handover, "COMPLETED", relation, "ACTIVE"));
        AdoptionLifecycleEvent legacy = new AdoptionLifecycleEvent(
                id(), "ADOPTION_COMPLETED", 1, STARTED.plusSeconds(20),
                ANIMAL, handover, null, relation);

        projection.reconcile(legacy);
        projection.reconcile(legacy);

        assertState("ADOPTED", "ADOPTED_HOME", 1);
        assertEquals(1, count("processed_domain_event"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM timeline_entry WHERE source_id=?", Integer.class, relation));
    }

    @Test
    void verifiedLegacyRelationEndedInfersCurrentHandoverFromRelation() {
        String handover = id();
        String relation = id();
        jdbc.update("UPDATE animal SET adoption_status='ADOPTED',current_context='ADOPTED_HOME' WHERE id=?", ANIMAL);
        when(authority.latest(ANIMAL)).thenReturn(authority(
                handover, "COMPLETED", relation, "ENDED"));

        projection.reconcile(new AdoptionLifecycleEvent(
                id(), "ADOPTION_RELATION_ENDED", 1, STARTED.plusSeconds(30),
                ANIMAL, null, null, relation));

        assertState("NOT_OPEN", "CAMPUS", 1);
        assertEquals(handover, jdbc.queryForObject(
                "SELECT handover_id FROM animal_adoption_lifecycle_projection WHERE animal_id=?",
                String.class, ANIMAL));
    }

    @Test
    void oldLegacyCompletedIsIsolatedAfterNewLifecycleStarts() {
        String newerHandover = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(
                newerHandover, "PENDING", null, null));
        AdoptionLifecycleEvent old = new AdoptionLifecycleEvent(
                id(), "ADOPTION_COMPLETED", 1, STARTED.minusSeconds(3600),
                ANIMAL, id(), null, id());

        projection.reconcile(old);
        projection.reconcile(old);

        assertState("OPEN", "CAMPUS", 0);
        assertEquals(1, count("processed_domain_event"));
        assertEquals(0, count("animal_adoption_lifecycle_projection"));
        assertEquals(0, count("timeline_entry"));
    }

    @Test
    void oldLegacyRelationEndedCannotRevertNewAdoptedLifecycle() {
        String newerHandover = id();
        String newerRelation = id();
        jdbc.update("UPDATE animal SET adoption_status='ADOPTED',current_context='ADOPTED_HOME',version=7 WHERE id=?", ANIMAL);
        when(authority.latest(ANIMAL)).thenReturn(authority(
                newerHandover, "COMPLETED", newerRelation, "ACTIVE"));
        AdoptionLifecycleEvent old = new AdoptionLifecycleEvent(
                id(), "ADOPTION_RELATION_ENDED", 1, STARTED.minusSeconds(3600),
                ANIMAL, null, null, id());

        projection.reconcile(old);

        assertState("ADOPTED", "ADOPTED_HOME", 7);
        assertEquals(1, count("processed_domain_event"));
        assertEquals(0, count("animal_adoption_lifecycle_projection"));
        assertEquals(0, count("timeline_entry"));
    }

    @Test
    void unverifiableLegacyRelationIsIsolatedWithoutMutation() {
        String handover = id();
        jdbc.update("UPDATE animal SET adoption_status='HANDOVER_PENDING',version=4 WHERE id=?", ANIMAL);
        when(authority.latest(ANIMAL)).thenReturn(authority(
                handover, "PENDING", null, null));

        projection.reconcile(new AdoptionLifecycleEvent(
                id(), "ADOPTION_RELATION_ENDED", 1, STARTED,
                ANIMAL, null, null, id()));

        assertState("HANDOVER_PENDING", "CAMPUS", 4);
        assertEquals(1, count("processed_domain_event"));
        assertEquals(0, count("animal_adoption_lifecycle_projection"));
    }

    @Test
    void sameHandoverWithDifferentRelationIsIsolated() {
        String handover = id();
        String currentRelation = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(
                handover, "COMPLETED", currentRelation, "ACTIVE"));

        projection.reconcile(new AdoptionLifecycleEvent(
                id(), "ADOPTION_COMPLETED", 1, STARTED,
                ANIMAL, handover, null, id()));

        assertState("OPEN", "CAMPUS", 0);
        assertEquals(1, count("processed_domain_event"));
        assertEquals(0, count("animal_adoption_lifecycle_projection"));
    }

    @Test
    void eventIdAlreadyUsedByAnotherTypeIsRejectedWithoutMutation() {
        String eventId = id();
        jdbc.update("INSERT INTO processed_domain_event(event_id,event_type,processed_at) VALUES(?,?,CURRENT_TIMESTAMP(6))",
                eventId, "ADOPTION_HANDOVER_INITIATED");

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> projection.reconcile(
                new AdoptionLifecycleEvent(eventId, "ADOPTION_COMPLETED", 1, STARTED,
                        ANIMAL, id(), null, id())));

        assertState("OPEN", "CAMPUS", 0);
        assertEquals(1, count("processed_domain_event"));
    }

    @RepeatedTest(10)
    void concurrentDuplicateLegacyCompletedChangesStateOnce() throws Exception {
        String handover = id();
        String relation = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(
                handover, "COMPLETED", relation, "ACTIVE"));
        AdoptionLifecycleEvent event = new AdoptionLifecycleEvent(
                id(), "ADOPTION_COMPLETED", 1, STARTED,
                ANIMAL, handover, null, relation);

        race(() -> projection.reconcile(event), () -> projection.reconcile(event));

        assertState("ADOPTED", "ADOPTED_HOME", 1);
        assertEquals(1, count("processed_domain_event"));
        assertEquals(1, count("timeline_entry"));
    }

    @RepeatedTest(10)
    void concurrentDuplicateLegacyEndedChangesStateOnce() throws Exception {
        String handover = id();
        String relation = id();
        jdbc.update("UPDATE animal SET adoption_status='ADOPTED',current_context='ADOPTED_HOME' WHERE id=?", ANIMAL);
        when(authority.latest(ANIMAL)).thenReturn(authority(
                handover, "COMPLETED", relation, "ENDED"));
        AdoptionLifecycleEvent event = new AdoptionLifecycleEvent(
                id(), "ADOPTION_RELATION_ENDED", 1, STARTED,
                ANIMAL, null, null, relation);

        race(() -> projection.reconcile(event), () -> projection.reconcile(event));

        assertState("NOT_OPEN", "CAMPUS", 1);
        assertEquals(1, count("processed_domain_event"));
        assertEquals(2, count("timeline_entry"));
    }

    @RepeatedTest(10)
    void delayedLegacyCompletedCannotRaceNewHandoverProjection() throws Exception {
        String newerHandover = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(
                newerHandover, "PENDING", null, null));
        AdoptionLifecycleEvent current = new AdoptionLifecycleEvent(
                id(), "ADOPTION_HANDOVER_INITIATED", 1, STARTED,
                ANIMAL, newerHandover, STARTED, null);
        AdoptionLifecycleEvent stale = new AdoptionLifecycleEvent(
                id(), "ADOPTION_COMPLETED", 1, STARTED.minusSeconds(60),
                ANIMAL, id(), null, id());

        race(() -> projection.reconcile(current), () -> projection.reconcile(stale));

        assertState("HANDOVER_PENDING", "CAMPUS", 1);
        assertEquals(2, count("processed_domain_event"));
    }

    @RepeatedTest(10)
    void delayedLegacyEndedCannotRaceNewCompletionProjection() throws Exception {
        String newerHandover = id();
        String newerRelation = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(
                newerHandover, "COMPLETED", newerRelation, "ACTIVE"));
        AdoptionLifecycleEvent current = new AdoptionLifecycleEvent(
                id(), "ADOPTION_COMPLETED", 2, STARTED,
                ANIMAL, newerHandover, STARTED, newerRelation);
        AdoptionLifecycleEvent stale = new AdoptionLifecycleEvent(
                id(), "ADOPTION_RELATION_ENDED", 1, STARTED.minusSeconds(60),
                ANIMAL, null, null, id());

        race(() -> projection.reconcile(current), () -> projection.reconcile(stale));

        assertState("ADOPTED", "ADOPTED_HOME", 1);
        assertEquals(2, count("processed_domain_event"));
        assertEquals(newerRelation, jdbc.queryForObject(
                "SELECT relation_id FROM animal_adoption_lifecycle_projection WHERE animal_id=?",
                String.class, ANIMAL));
    }

    @Test
    void oldLifecycleEventCannotPolluteNewerHandover() {
        String newer = id();
        when(authority.latest(ANIMAL)).thenReturn(new AdoptionLifecycleAuthorityGateway.Authority(
                true, ANIMAL, newer, "PENDING", STARTED.plusSeconds(3600),
                null, null, null, null));
        projection.reconcile(new AdoptionLifecycleEvent(id(), "ADOPTION_HANDOVER_INITIATED", 1,
                STARTED.plusSeconds(3600), ANIMAL, newer, STARTED.plusSeconds(3600), null));
        assertState("HANDOVER_PENDING", "CAMPUS", 1);

        projection.reconcile(new AdoptionLifecycleEvent(id(), "ADOPTION_HANDOVER_CANCELLED", 1,
                STARTED, ANIMAL, id(), STARTED, null));
        assertState("HANDOVER_PENDING", "CAMPUS", 1);
    }

    @Test
    void illegalAnimalStateRejectsAndAuthorityFailureRetriesWithoutMarker() {
        String handover = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(handover, "PENDING", null, null));
        jdbc.update("UPDATE animal SET identity_status='ARCHIVED' WHERE id=?", ANIMAL);
        AdoptionLifecycleEvent event = event("ADOPTION_HANDOVER_INITIATED", handover, null);
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> projection.reconcile(event));
        assertEquals(0, count("processed_domain_event"));

        jdbc.update("UPDATE animal SET identity_status='ACTIVE' WHERE id=?", ANIMAL);
        when(authority.latest(anyString())).thenThrow(
                new DependencyUnavailableException("transient"));
        assertThrows(DependencyUnavailableException.class,
                () -> projection.reconcile(event));
        assertEquals(0, count("processed_domain_event"));
    }

    private AdoptionLifecycleEvent event(String type, String handover, String relation) {
        int version = type.startsWith("ADOPTION_HANDOVER_") ? 1 : 2;
        return new AdoptionLifecycleEvent(id(), type, version, STARTED.plusSeconds(10),
                ANIMAL, handover, STARTED, relation);
    }

    private AdoptionLifecycleAuthorityGateway.Authority authority(
            String handover, String handoverStatus, String relation, String relationStatus) {
        Instant activated = relation == null ? null : STARTED.plusSeconds(20);
        Instant ended = "ENDED".equals(relationStatus) ? STARTED.plusSeconds(30) : null;
        return new AdoptionLifecycleAuthorityGateway.Authority(true, ANIMAL, handover,
                handoverStatus, STARTED, relation, relationStatus, activated, ended);
    }

    private void assertState(String adoptionStatus, String context, int version) {
        var row = jdbc.queryForMap("""
                SELECT adoption_status,current_context,version FROM animal WHERE id=?
                """, ANIMAL);
        assertEquals(adoptionStatus, row.get("adoption_status"));
        assertEquals(context, row.get("current_context"));
        assertEquals(version, ((Number) row.get("version")).intValue());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> one = pool.submit(() -> runAfterBarrier(first, ready, start));
            Future<?> two = pool.submit(() -> runAfterBarrier(second, ready, start));
            ready.await();
            start.countDown();
            one.get();
            two.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private void runAfterBarrier(Runnable action, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            action.run();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private record TestDatabase(String url, String username, String password,
                                MySQLContainer<?> container) {
        static TestDatabase create() {
            if ("true".equalsIgnoreCase(System.getenv("ANIMALLINK_TEST_EXTERNAL_MYSQL"))) {
                int port = Integer.parseInt(required("ANIMALLINK_TEST_MYSQL_PORT"));
                return new TestDatabase("jdbc:mysql://127.0.0.1:" + port + "/"
                        + required("ANIMALLINK_TEST_MYSQL_DATABASE")
                        + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC",
                        required("ANIMALLINK_TEST_MYSQL_USERNAME"),
                        required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null);
            }
            MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0.41")
                    .withDatabaseName("adoption_lifecycle_projection_test")
                    .withUsername("test").withPassword("test");
            container.start();
            return new TestDatabase(container.getJdbcUrl(), container.getUsername(),
                    container.getPassword(), container);
        }

        static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
            return value;
        }

        void close() { if (container != null) container.stop(); }
    }
}
