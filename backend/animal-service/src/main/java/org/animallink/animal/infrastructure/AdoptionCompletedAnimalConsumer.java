package org.animallink.animal.infrastructure;

import org.animallink.animal.application.AdoptionLifecycleProjectionService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "animallink.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class AdoptionCompletedAnimalConsumer {
    private final AdoptionLifecycleEventParser parser;
    private final AdoptionLifecycleProjectionService projection;

    public AdoptionCompletedAnimalConsumer(AdoptionLifecycleEventParser parser,
                                           AdoptionLifecycleProjectionService projection) {
        this.parser = parser;
        this.projection = projection;
    }

    @RabbitListener(
            queues = "animal.timeline.adoption-completed",
            containerFactory = "adoptionLifecycleRabbitListenerContainerFactory")
    public void consume(String message) {
        projection.reconcile(parser.parse(message, "ADOPTION_COMPLETED"));
    }
}
