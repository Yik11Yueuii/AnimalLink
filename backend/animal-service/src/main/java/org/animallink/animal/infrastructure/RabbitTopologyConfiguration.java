package org.animallink.animal.infrastructure;

import org.springframework.amqp.core.Binding; import org.springframework.amqp.core.BindingBuilder; import org.springframework.amqp.core.Queue; import org.springframework.amqp.core.TopicExchange; import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration;

@Configuration @ConditionalOnProperty(name="animallink.messaging.enabled",havingValue="true",matchIfMissing=true)
class RabbitTopologyConfiguration {
 @Bean TopicExchange domainExchange(){return new TopicExchange("animallink.domain",true,false);}
 @Bean Queue caseResultTimelineQueue(){return new Queue("animal.timeline.case-result",true,false,false);}
 @Bean Binding caseResultTimelineBinding(TopicExchange domainExchange,Queue caseResultTimelineQueue){return BindingBuilder.bind(caseResultTimelineQueue).to(domainExchange).with("incident.case.result-finalized");}
}
