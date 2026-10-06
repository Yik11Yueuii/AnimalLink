package org.animallink.animal.infrastructure;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RabbitTopologyConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(RabbitTopologyConfiguration.class);

    @Test
    void declaresThreeUnambiguousTimelineQueuesAndBindings() {
        context.run(application -> {
            assertEquals(3, application.getBeansOfType(Queue.class).size());
            assertEquals("animal.timeline.case-result", application.getBean("caseResultTimelineQueue", Queue.class).getName());
            assertEquals("animal.timeline.adoption-completed", application.getBean("adoptionCompletedTimelineQueue", Queue.class).getName());
            assertEquals("animal.timeline.adoption-ended", application.getBean("adoptionRelationEndedTimelineQueue", Queue.class).getName());
            Map<String, Binding> bindings = application.getBeansOfType(Binding.class);
            assertEquals(3, bindings.size());
            assertEquals("incident.case.result-finalized", bindings.get("caseResultTimelineBinding").getRoutingKey());
            assertEquals("adoption.handover.completed", bindings.get("adoptionCompletedTimelineBinding").getRoutingKey());
            assertEquals("adoption.relation.ended", bindings.get("adoptionRelationEndedTimelineBinding").getRoutingKey());
            assertNotNull(application.getBean("domainExchange"));
        });
    }
}
