package org.animallink.animal.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import org.animallink.animal.application.AdoptionLifecycleEvent;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

@Component
public class AdoptionLifecycleEventParser {
    private static final Set<String> TYPES = Set.of(
            "ADOPTION_HANDOVER_INITIATED", "ADOPTION_HANDOVER_CANCELLED",
            "ADOPTION_COMPLETED", "ADOPTION_RELATION_ENDED");
    private final ObjectMapper json;

    public AdoptionLifecycleEventParser(ObjectMapper json) {
        this.json = json;
    }

    public AdoptionLifecycleEvent parse(String message, String expectedType) {
        try {
            JsonNode payload = json.readTree(message);
            String eventId = uuid(text(payload, "eventId"));
            String eventType = text(payload, "eventType");
            int eventVersion = payload.path("eventVersion").asInt(-1);
            Instant occurredAt = instant(text(payload, "occurredAt"));
            String animalId = uuid(text(payload, "animalId"));
            String handoverId = optionalUuid(payload, "handoverId");
            Instant lifecycleStartedAt = optionalInstant(payload, "lifecycleStartedAt");
            String relationId = optionalUuid(payload, "relationId");

            if (!expectedType.equals(eventType) || !TYPES.contains(eventType)) reject();
            if (eventType.startsWith("ADOPTION_HANDOVER_")) {
                if (eventVersion != 1 || handoverId == null || lifecycleStartedAt == null) reject();
            } else if (eventVersion == 2) {
                if (handoverId == null || lifecycleStartedAt == null || relationId == null) reject();
            } else if (eventVersion != 1 || relationId == null
                    || ("ADOPTION_COMPLETED".equals(eventType) && handoverId == null)) {
                reject();
            }
            return new AdoptionLifecycleEvent(eventId, eventType, eventVersion,
                    occurredAt, animalId, handoverId, lifecycleStartedAt, relationId);
        } catch (AmqpRejectAndDontRequeueException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AmqpRejectAndDontRequeueException(
                    "invalid " + expectedType + " payload", exception);
        }
    }

    private static String text(JsonNode payload, String name) {
        if (payload == null || !payload.isObject() || !payload.hasNonNull(name)
                || payload.get(name).asText().isBlank()) reject();
        return payload.get(name).asText();
    }

    private static String optionalUuid(JsonNode payload, String name) {
        return payload.hasNonNull(name) ? uuid(payload.get(name).asText()) : null;
    }

    private static Instant optionalInstant(JsonNode payload, String name) {
        return payload.hasNonNull(name) ? instant(payload.get(name).asText()) : null;
    }

    private static String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException exception) { reject(); return null; }
    }

    private static Instant instant(String value) {
        try { return Instant.parse(value); }
        catch (DateTimeParseException exception) { reject(); return null; }
    }

    private static void reject() {
        throw new AmqpRejectAndDontRequeueException("invalid adoption lifecycle event contract");
    }
}
