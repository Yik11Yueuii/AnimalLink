package org.animallink.animal;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class CaseTimelineRabbitIntegrationTest {

    private static final String ANIMAL_ID = "90000000-0000-0000-0000-000000000101";
    private static final String CASE_ID = "90000000-0000-0000-0000-000000000102";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("timeline_rabbit_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.messaging.enabled", () -> true);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitListenerEndpointRegistry listeners;

    @BeforeEach
    void seedAnimal() {
        jdbc.update("DELETE FROM timeline_entry");
        jdbc.update("DELETE FROM animal");
        jdbc.update("INSERT INTO animal(id,campus_id,display_name,species,sex,identity_status,adoption_status,current_context) VALUES(?,?,'Rabbit测试动物','CAT','UNKNOWN','ACTIVE','NOT_OPEN','CAMPUS')",
                ANIMAL_ID, "90000000-0000-0000-0000-000000000103");
    }

    @AfterEach
    void stopListenerBeforeRabbitContainer() {
        listeners.stop();
    }

    @Test
    void rabbitMessageCreatesTimelineProjection() {
        String payload = """
                {"eventId":"90000000-0000-0000-0000-000000000104","eventType":"CASE_RESULT_FINALIZED","eventVersion":1,"caseId":"%s","animalId":"%s","outcome":"RESOLVED","summary":"rabbit end-to-end","occurredAt":"2026-10-03T10:00:00Z"}
                """.formatted(CASE_ID, ANIMAL_ID);

        rabbit.convertAndSend("animallink.domain", "incident.case.result-finalized", payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT source_type,source_id,entry_type,title,summary,visibility FROM timeline_entry WHERE animal_id=?",
                    ANIMAL_ID);
            assertThat(row.get("source_type")).isEqualTo("CASE");
            assertThat(row.get("source_id")).isEqualTo(CASE_ID);
            assertThat(row.get("entry_type")).isEqualTo("RESCUE_RESULT");
            assertThat(row.get("title")).isEqualTo("救助处理已解决");
            assertThat(row.get("summary")).isEqualTo("rabbit end-to-end");
            assertThat(row.get("visibility")).isEqualTo("PUBLIC");
        });
    }
}
