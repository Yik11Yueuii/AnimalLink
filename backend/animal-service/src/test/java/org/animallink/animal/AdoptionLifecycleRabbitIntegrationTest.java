package org.animallink.animal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.animallink.animal.application.AdoptionLifecycleAuthorityGateway;
import org.animallink.animal.domain.DependencyUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "animallink.messaging.enabled=true",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class AdoptionLifecycleRabbitIntegrationTest {
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000092";
    private static final Instant STARTED = Instant.parse("2026-10-17T00:00:00Z");
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("adoption_lifecycle_rabbit_test")
            .withUsername("test").withPassword("test");
    private static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        MYSQL.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @MockBean AdoptionLifecycleAuthorityGateway authority;

    @BeforeEach
    void clean() {
        listeners.stop();
        RabbitAdmin admin = new RabbitAdmin(rabbit);
        TopicExchange domain = new TopicExchange("animallink.domain", true, false);
        DirectExchange quarantine = new DirectExchange(
                "animallink.adoption-lifecycle.quarantine", true, false);
        Queue completed = new Queue("animal.timeline.adoption-completed", true, false, false);
        Queue ended = new Queue("animal.timeline.adoption-ended", true, false, false);
        Queue isolated = new Queue("animal.adoption-lifecycle.quarantine", true, false, false);
        Queue caseResult = new Queue("animal.timeline.case-result", true, false, false);
        Queue initiated = new Queue("animal.adoption-lifecycle.handover-initiated", true, false, false);
        Queue cancelled = new Queue("animal.adoption-lifecycle.handover-cancelled", true, false, false);
        admin.declareExchange(domain);
        admin.declareExchange(quarantine);
        admin.declareQueue(completed);
        admin.declareQueue(ended);
        admin.declareQueue(isolated);
        admin.declareQueue(caseResult);
        admin.declareQueue(initiated);
        admin.declareQueue(cancelled);
        admin.declareBinding(BindingBuilder.bind(completed).to(domain)
                .with("adoption.handover.completed"));
        admin.declareBinding(BindingBuilder.bind(ended).to(domain)
                .with("adoption.relation.ended"));
        admin.declareBinding(BindingBuilder.bind(isolated).to(quarantine)
                .with("adoption.lifecycle.unverified"));
        admin.declareBinding(BindingBuilder.bind(caseResult).to(domain)
                .with("incident.case.result-finalized"));
        admin.declareBinding(BindingBuilder.bind(initiated).to(domain)
                .with("adoption.handover.initiated"));
        admin.declareBinding(BindingBuilder.bind(cancelled).to(domain)
                .with("adoption.handover.cancelled"));
        admin.purgeQueue(completed.getName(), false);
        admin.purgeQueue(ended.getName(), false);
        admin.purgeQueue(isolated.getName(), false);
        listeners.start();
        jdbc.update("DELETE FROM processed_domain_event");
        jdbc.update("DELETE FROM timeline_entry");
        jdbc.update("DELETE FROM animal_adoption_lifecycle_projection");
        jdbc.update("DELETE FROM animal");
        jdbc.update("""
                INSERT INTO animal
                  (id,campus_id,display_name,species,sex,identity_status,
                   adoption_status,current_context,version)
                VALUES(?,'campus','Rabbit Animal','CAT','UNKNOWN','ACTIVE',
                       'OPEN','CAMPUS',0)
                """, ANIMAL);
    }

    @Test
    void verifiedLegacyCompletedTraversesRealRabbitConsumer() {
        String eventId = id();
        String handover = id();
        String relation = id();
        when(authority.latest(ANIMAL)).thenReturn(authority(
                handover, "COMPLETED", relation, "ACTIVE"));

        rabbit.convertAndSend("animallink.domain", "adoption.handover.completed",
                completed(eventId, handover, relation));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertState("ADOPTED", "ADOPTED_HOME", 1);
            assertEquals(1, marker(eventId));
        });
    }

    @Test
    void delayedLegacyEndedIsRecordedButCannotRevertNewLifecycle() {
        String eventId = id();
        String currentRelation = id();
        jdbc.update("UPDATE animal SET adoption_status='ADOPTED',current_context='ADOPTED_HOME',version=8 WHERE id=?", ANIMAL);
        when(authority.latest(ANIMAL)).thenReturn(authority(
                id(), "COMPLETED", currentRelation, "ACTIVE"));

        rabbit.convertAndSend("animallink.domain", "adoption.relation.ended",
                ended(eventId, id()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertState("ADOPTED", "ADOPTED_HOME", 8);
            assertEquals(1, marker(eventId));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM animal_adoption_lifecycle_projection", Integer.class));
        });
    }

    @Test
    void authorityFailureIsBoundedlyRetriedThenQuarantinedWithoutMarker() {
        String eventId = id();
        when(authority.latest(anyString())).thenThrow(
                new DependencyUnavailableException("authority unavailable"));

        rabbit.convertAndSend("animallink.domain", "adoption.handover.completed",
                completed(eventId, id(), id()));

        Message quarantined = rabbit.receive("animal.adoption-lifecycle.quarantine", 15_000);
        assertNotNull(quarantined);
        assertState("OPEN", "CAMPUS", 0);
        assertEquals(0, marker(eventId));
    }

    private AdoptionLifecycleAuthorityGateway.Authority authority(
            String handover, String handoverStatus, String relation, String relationStatus) {
        return new AdoptionLifecycleAuthorityGateway.Authority(
                true, ANIMAL, handover, handoverStatus, STARTED,
                relation, relationStatus,
                relation == null ? null : STARTED.plusSeconds(20), null);
    }

    private void assertState(String adoption, String context, int version) {
        var row = jdbc.queryForMap(
                "SELECT adoption_status,current_context,version FROM animal WHERE id=?", ANIMAL);
        assertEquals(adoption, row.get("adoption_status"));
        assertEquals(context, row.get("current_context"));
        assertEquals(version, ((Number) row.get("version")).intValue());
    }

    private int marker(String eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_domain_event WHERE event_id=?",
                Integer.class, eventId);
    }

    private static String completed(String eventId, String handover, String relation) {
        return "{\"eventId\":\"" + eventId
                + "\",\"eventType\":\"ADOPTION_COMPLETED\",\"eventVersion\":1"
                + ",\"occurredAt\":\"2026-10-17T00:00:20Z\",\"relationId\":\"" + relation
                + "\",\"handoverId\":\"" + handover + "\",\"animalId\":\"" + ANIMAL + "\"}";
    }

    private static String ended(String eventId, String relation) {
        return "{\"eventId\":\"" + eventId
                + "\",\"eventType\":\"ADOPTION_RELATION_ENDED\",\"eventVersion\":1"
                + ",\"occurredAt\":\"2026-10-17T00:00:30Z\",\"relationId\":\"" + relation
                + "\",\"animalId\":\"" + ANIMAL + "\"}";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
