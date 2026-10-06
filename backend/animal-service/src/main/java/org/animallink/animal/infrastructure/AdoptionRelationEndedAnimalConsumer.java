package org.animallink.animal.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "animallink.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class AdoptionRelationEndedAnimalConsumer {
    private static final Set<String> PAYLOAD_FIELDS = Set.of("eventId", "eventType", "eventVersion", "occurredAt", "relationId", "animalId");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AdoptionRelationEndedAnimalConsumer(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @RabbitListener(queues = "animal.timeline.adoption-ended")
    @Transactional
    public void consume(String message) {
        Event event = validate(parse(message));
        if (jdbc.update("INSERT IGNORE INTO processed_domain_event(event_id,event_type,processed_at) VALUES(?,?,?)", event.eventId(), "ADOPTION_RELATION_ENDED", Timestamp.from(Instant.now())) == 0) return;
        int changed = jdbc.update("UPDATE animal SET adoption_status='NOT_OPEN',current_context='CAMPUS',version=version+1 WHERE id=? AND identity_status='ACTIVE' AND adoption_status='ADOPTED' AND current_context='ADOPTED_HOME'", event.animalId());
        if (changed != 1) reject("invalid animal adoption relation end state");
        jdbc.update("INSERT INTO timeline_entry(id,animal_id,source_type,source_id,entry_type,title,summary,occurred_at,visibility,created_at) VALUES(?,?,'ADOPTION',?,'ADOPTION_ENDED','领养关系已结束',NULL,?,'PUBLIC',?)", UUID.randomUUID().toString(), event.animalId(), event.relationId(), Timestamp.from(event.occurredAt()), Timestamp.from(Instant.now()));
    }

    private Event validate(JsonNode payload) {
        if (!payload.isObject() || !fields(payload).equals(PAYLOAD_FIELDS)) reject("invalid ADOPTION_RELATION_ENDED contract");
        String eventId = text(payload, "eventId");
        String eventType = text(payload, "eventType");
        String relationId = text(payload, "relationId");
        String animalId = text(payload, "animalId");
        String occurredAt = text(payload, "occurredAt");
        if (eventId == null || !"ADOPTION_RELATION_ENDED".equals(eventType) || payload.path("eventVersion").asInt() != 1 || relationId == null || animalId == null || occurredAt == null) reject("invalid ADOPTION_RELATION_ENDED contract");
        try {
            return new Event(UUID.fromString(eventId).toString(), UUID.fromString(relationId).toString(), UUID.fromString(animalId).toString(), Instant.parse(occurredAt));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new AmqpRejectAndDontRequeueException("invalid ADOPTION_RELATION_ENDED contract", exception);
        }
    }

    private JsonNode parse(String message) {
        try { return json.readTree(message); }
        catch (Exception exception) { throw new AmqpRejectAndDontRequeueException("invalid ADOPTION_RELATION_ENDED payload", exception); }
    }

    private static Set<String> fields(JsonNode payload) {
        Set<String> fields = new HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static String text(JsonNode payload, String name) { return payload.hasNonNull(name) && !payload.get(name).asText().isBlank() ? payload.get(name).asText() : null; }
    private static void reject(String message) { throw new AmqpRejectAndDontRequeueException(message); }
    private record Event(String eventId, String relationId, String animalId, Instant occurredAt) { }
}
