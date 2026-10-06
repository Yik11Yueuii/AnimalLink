package org.animallink.animal.infrastructure;

import org.springframework.amqp.core.Binding; import org.springframework.amqp.core.BindingBuilder; import org.springframework.amqp.core.Queue; import org.springframework.amqp.core.TopicExchange; import org.springframework.beans.factory.annotation.Qualifier; import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration;

@Configuration @ConditionalOnProperty(name="animallink.messaging.enabled",havingValue="true",matchIfMissing=true)
class RabbitTopologyConfiguration {
 @Bean TopicExchange domainExchange(){return new TopicExchange("animallink.domain",true,false);}
 @Bean Queue caseResultTimelineQueue(){return new Queue("animal.timeline.case-result",true,false,false);}
 @Bean Binding caseResultTimelineBinding(@Qualifier("caseResultTimelineQueue") Queue queue,TopicExchange domainExchange){return BindingBuilder.bind(queue).to(domainExchange).with("incident.case.result-finalized");}
 @Bean Queue adoptionCompletedTimelineQueue(){return new Queue("animal.timeline.adoption-completed",true,false,false);}
 @Bean Binding adoptionCompletedTimelineBinding(@Qualifier("adoptionCompletedTimelineQueue") Queue queue,TopicExchange domainExchange){return BindingBuilder.bind(queue).to(domainExchange).with("adoption.handover.completed");}
 @Bean Queue adoptionRelationEndedTimelineQueue(){return new Queue("animal.timeline.adoption-ended",true,false,false);}
 @Bean Binding adoptionRelationEndedTimelineBinding(@Qualifier("adoptionRelationEndedTimelineQueue") Queue queue,TopicExchange domainExchange){return BindingBuilder.bind(queue).to(domainExchange).with("adoption.relation.ended");}
}
