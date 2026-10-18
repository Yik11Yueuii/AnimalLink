package org.animallink.animal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.animallink.animal.application.AdoptionLifecycleEvent;
import org.animallink.animal.infrastructure.AdoptionLifecycleEventParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdoptionRelationEndedAnimalConsumerIntegrationTest {
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000004";
    private final AdoptionLifecycleEventParser parser =
            new AdoptionLifecycleEventParser(new ObjectMapper());

    @Test
    void legacyV1RelationOnlyContractIsPreservedForAuthorityReconciliation() {
        String eventId = id();
        String relationId = id();

        AdoptionLifecycleEvent event = parser.parse(
                event(eventId, relationId), "ADOPTION_RELATION_ENDED");

        assertEquals(eventId, event.eventId());
        assertEquals(1, event.eventVersion());
        assertEquals(relationId, event.relationId());
        assertNull(event.handoverId());
        assertNull(event.lifecycleStartedAt());
    }

    private static String event(String eventId, String relationId) {
        return "{\"eventId\":\"" + eventId
                + "\",\"eventType\":\"ADOPTION_RELATION_ENDED\",\"eventVersion\":1"
                + ",\"occurredAt\":\"2026-10-05T00:00:00Z\",\"relationId\":\"" + relationId
                + "\",\"animalId\":\"" + ANIMAL + "\"}";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
