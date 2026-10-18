package org.animallink.animal.infrastructure;

import org.animallink.animal.application.AdoptionLifecycleProjectionService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "animallink.messaging.enabled", havingValue = "true",
        matchIfMissing = true)
public class AdoptionHandoverCancelledAnimalConsumer {
    private final AdoptionLifecycleEventParser parser;
    private final AdoptionLifecycleProjectionService projection;

    public AdoptionHandoverCancelledAnimalConsumer(AdoptionLifecycleEventParser parser,
                                                   AdoptionLifecycleProjectionService projection) {
        this.parser = parser;
        this.projection = projection;
    }

    @RabbitListener(queues = "animal.adoption-lifecycle.handover-cancelled")
    public void consume(String message) {
        projection.reconcile(parser.parse(message, "ADOPTION_HANDOVER_CANCELLED"));
    }
}
