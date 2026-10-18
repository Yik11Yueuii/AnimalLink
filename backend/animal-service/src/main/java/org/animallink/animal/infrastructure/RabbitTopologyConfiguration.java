package org.animallink.animal.infrastructure;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "animallink.messaging.enabled", havingValue = "true", matchIfMissing = true)
class RabbitTopologyConfiguration {
    static final String LIFECYCLE_QUARANTINE_EXCHANGE = "animallink.adoption-lifecycle.quarantine";
    static final String LIFECYCLE_QUARANTINE_QUEUE = "animal.adoption-lifecycle.quarantine";
    static final String LIFECYCLE_QUARANTINE_ROUTING_KEY = "adoption.lifecycle.unverified";

    @Bean
    TopicExchange domainExchange() {
        return new TopicExchange("animallink.domain", true, false);
    }

    @Bean
    Queue caseResultTimelineQueue() {
        return new Queue("animal.timeline.case-result", true, false, false);
    }

    @Bean
    Binding caseResultTimelineBinding(@Qualifier("caseResultTimelineQueue") Queue queue,
                                      TopicExchange domainExchange) {
        return BindingBuilder.bind(queue).to(domainExchange).with("incident.case.result-finalized");
    }

    @Bean
    Queue adoptionCompletedTimelineQueue() {
        return new Queue("animal.timeline.adoption-completed", true, false, false);
    }

    @Bean
    Binding adoptionCompletedTimelineBinding(
            @Qualifier("adoptionCompletedTimelineQueue") Queue queue,
            TopicExchange domainExchange) {
        return BindingBuilder.bind(queue).to(domainExchange).with("adoption.handover.completed");
    }

    @Bean
    Queue adoptionHandoverInitiatedQueue() {
        return new Queue("animal.adoption-lifecycle.handover-initiated", true, false, false);
    }

    @Bean
    Binding adoptionHandoverInitiatedBinding(
            @Qualifier("adoptionHandoverInitiatedQueue") Queue queue,
            TopicExchange domainExchange) {
        return BindingBuilder.bind(queue).to(domainExchange).with("adoption.handover.initiated");
    }

    @Bean
    Queue adoptionHandoverCancelledQueue() {
        return new Queue("animal.adoption-lifecycle.handover-cancelled", true, false, false);
    }

    @Bean
    Binding adoptionHandoverCancelledBinding(
            @Qualifier("adoptionHandoverCancelledQueue") Queue queue,
            TopicExchange domainExchange) {
        return BindingBuilder.bind(queue).to(domainExchange).with("adoption.handover.cancelled");
    }

    @Bean
    Queue adoptionRelationEndedTimelineQueue() {
        return new Queue("animal.timeline.adoption-ended", true, false, false);
    }

    @Bean
    Binding adoptionRelationEndedTimelineBinding(
            @Qualifier("adoptionRelationEndedTimelineQueue") Queue queue,
            TopicExchange domainExchange) {
        return BindingBuilder.bind(queue).to(domainExchange).with("adoption.relation.ended");
    }

    @Bean
    DirectExchange adoptionLifecycleQuarantineExchange() {
        return new DirectExchange(LIFECYCLE_QUARANTINE_EXCHANGE, true, false);
    }

    @Bean
    Queue adoptionLifecycleQuarantineQueue() {
        return new Queue(LIFECYCLE_QUARANTINE_QUEUE, true, false, false);
    }

    @Bean
    Binding adoptionLifecycleQuarantineBinding(
            @Qualifier("adoptionLifecycleQuarantineQueue") Queue queue,
            DirectExchange adoptionLifecycleQuarantineExchange) {
        return BindingBuilder.bind(queue).to(adoptionLifecycleQuarantineExchange)
                .with(LIFECYCLE_QUARANTINE_ROUTING_KEY);
    }

    @Bean("adoptionLifecycleRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory adoptionLifecycleRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        RepublishMessageRecoverer recoverer = new RepublishMessageRecoverer(
                rabbitTemplate, LIFECYCLE_QUARANTINE_EXCHANGE, LIFECYCLE_QUARANTINE_ROUTING_KEY);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(5)
                .backOffOptions(500, 2.0, 5_000)
                .recoverer(recoverer)
                .build());
        return factory;
    }
}
