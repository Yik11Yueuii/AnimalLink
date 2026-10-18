package org.animallink.animal.infrastructure;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RabbitTopologyConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RabbitTopologyConfiguration.class)
            .withBean(SimpleRabbitListenerContainerFactoryConfigurer.class,
                    () -> new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()))
            .withBean(ConnectionFactory.class,
                    () -> new CachingConnectionFactory("localhost", 5672))
            .withBean(RabbitTemplate.class,
                    () -> new RabbitTemplate(new CachingConnectionFactory("localhost", 5672)));

    @Test
    void declaresTimelineAndAdoptionLifecycleQueuesAndBindings() {
        context.run(application -> {
            assertEquals(6, application.getBeansOfType(Queue.class).size());
            assertEquals("animal.timeline.case-result", application.getBean("caseResultTimelineQueue", Queue.class).getName());
            assertEquals("animal.timeline.adoption-completed", application.getBean("adoptionCompletedTimelineQueue", Queue.class).getName());
            assertEquals("animal.timeline.adoption-ended", application.getBean("adoptionRelationEndedTimelineQueue", Queue.class).getName());
            assertEquals("animal.adoption-lifecycle.handover-initiated", application.getBean("adoptionHandoverInitiatedQueue", Queue.class).getName());
            assertEquals("animal.adoption-lifecycle.handover-cancelled", application.getBean("adoptionHandoverCancelledQueue", Queue.class).getName());
            assertEquals("animal.adoption-lifecycle.quarantine", application.getBean("adoptionLifecycleQuarantineQueue", Queue.class).getName());
            Map<String, Binding> bindings = application.getBeansOfType(Binding.class);
            assertEquals(6, bindings.size());
            assertEquals("incident.case.result-finalized", bindings.get("caseResultTimelineBinding").getRoutingKey());
            assertEquals("adoption.handover.completed", bindings.get("adoptionCompletedTimelineBinding").getRoutingKey());
            assertEquals("adoption.relation.ended", bindings.get("adoptionRelationEndedTimelineBinding").getRoutingKey());
            assertEquals("adoption.handover.initiated", bindings.get("adoptionHandoverInitiatedBinding").getRoutingKey());
            assertEquals("adoption.handover.cancelled", bindings.get("adoptionHandoverCancelledBinding").getRoutingKey());
            assertEquals("adoption.lifecycle.unverified", bindings.get("adoptionLifecycleQuarantineBinding").getRoutingKey());
            assertNotNull(application.getBean("domainExchange"));
            assertNotNull(application.getBean("adoptionLifecycleRabbitListenerContainerFactory"));
        });
    }
}
