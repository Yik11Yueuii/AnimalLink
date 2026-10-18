package org.animallink.animal.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import org.animallink.animal.application.AnimalCanonicalResolver;
import org.animallink.animal.application.CanonicalAnimal;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "animallink.messaging.enabled", havingValue = "true",
        matchIfMissing = true)
public class CaseResultTimelineConsumer {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AnimalCanonicalResolver canonicalResolver;

    public CaseResultTimelineConsumer(JdbcTemplate jdbc, ObjectMapper json,
                                      AnimalCanonicalResolver canonicalResolver) {
        this.jdbc = jdbc;
        this.json = json;
        this.canonicalResolver = canonicalResolver;
    }

    @RabbitListener(queues = "animal.timeline.case-result")
    @Transactional
    public void consume(String message) {
        JsonNode payload = parse(message);
        String outcome = text(payload, "outcome");
        String animalId = text(payload, "animalId");
        String caseId = text(payload, "caseId");
        String occurredAt = text(payload, "occurredAt");
        if (text(payload, "eventId") == null
                || !"CASE_RESULT_FINALIZED".equals(text(payload, "eventType"))
                || payload.path("eventVersion").asInt() != 1
                || caseId == null || animalId == null
                || !Set.of("RESOLVED", "UNRESOLVED", "CANCELLED").contains(outcome)
                || occurredAt == null) {
            reject();
        }

        String canonicalAnimalId;
        try {
            CanonicalAnimal canonical = canonicalResolver.resolvePublic(animalId);
            canonicalAnimalId = canonical.canonicalAnimalId();
        } catch (ResourceNotFoundException exception) {
            throw new AmqpRejectAndDontRequeueException(
                    "CASE_RESULT_FINALIZED references unavailable Animal", exception);
        }

        String title = switch (outcome) {
            case "RESOLVED" -> "救助处理已解决";
            case "UNRESOLVED" -> "救助处理未解决";
            default -> "救助处理已取消";
        };
        try {
            jdbc.update("""
                    INSERT INTO timeline_entry
                        (id, animal_id, source_type, source_id, entry_type, title, summary,
                         occurred_at, visibility, created_at)
                    VALUES (?, ?, 'CASE', ?, 'RESCUE_RESULT', ?, ?, ?, 'PUBLIC', ?)
                    """, UUID.randomUUID().toString(), canonicalAnimalId, caseId, title,
                    text(payload, "summary"), Timestamp.from(parseInstant(occurredAt)),
                    Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException exception) {
            String detail = String.valueOf(exception.getMostSpecificCause().getMessage());
            if (!detail.contains("uk_timeline_source")) {
                throw exception;
            }
        }
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new AmqpRejectAndDontRequeueException(
                    "invalid CASE_RESULT_FINALIZED payload", exception);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new AmqpRejectAndDontRequeueException(
                    "invalid CASE_RESULT_FINALIZED occurredAt", exception);
        }
    }

    private static void reject() {
        throw new AmqpRejectAndDontRequeueException("invalid CASE_RESULT_FINALIZED contract");
    }

    private static String text(JsonNode node, String key) {
        return node.hasNonNull(key) && !node.get(key).asText().isBlank()
                ? node.get(key).asText() : null;
    }
}
