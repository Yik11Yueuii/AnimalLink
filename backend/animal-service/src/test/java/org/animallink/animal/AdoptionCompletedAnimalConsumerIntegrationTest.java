package org.animallink.animal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.animallink.animal.application.AdoptionLifecycleEvent;
import org.animallink.animal.infrastructure.AdoptionLifecycleEventParser;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdoptionCompletedAnimalConsumerIntegrationTest {
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000004";
    private final AdoptionLifecycleEventParser parser =
            new AdoptionLifecycleEventParser(new ObjectMapper());

    @Test
    void legacyV1RequiresHandoverForAuthorityReconciliation() {
        String eventId = id();
        String relationId = id();
        String handoverId = id();

        AdoptionLifecycleEvent event = parser.parse(
                event(eventId, relationId, handoverId), "ADOPTION_COMPLETED");

        assertEquals(eventId, event.eventId());
        assertEquals(1, event.eventVersion());
        assertEquals(handoverId, event.handoverId());
        assertEquals(relationId, event.relationId());
        assertNull(event.lifecycleStartedAt());
    }

    @Test
    void legacyV1WithoutHandoverFailsClosed() {
        String message = event(id(), id(), id())
                .replaceFirst(",\\\"handoverId\\\":\\\"[^\\\"]+\\\"", "");

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> parser.parse(message, "ADOPTION_COMPLETED"));
    }

    private static String event(String eventId, String relationId, String handoverId) {
        return "{\"eventId\":\"" + eventId
                + "\",\"eventType\":\"ADOPTION_COMPLETED\",\"eventVersion\":1"
                + ",\"occurredAt\":\"2026-10-05T00:00:00Z\",\"relationId\":\"" + relationId
                + "\",\"handoverId\":\"" + handoverId + "\",\"animalId\":\"" + ANIMAL + "\"}";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
