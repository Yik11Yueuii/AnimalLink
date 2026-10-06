package org.animallink.adoption.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "animallink.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class AdoptionCompletedOutboxPublisher {
    private static final String EXCHANGE = "animallink.domain";
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;

    public AdoptionCompletedOutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() { publishBatch(); }

    public int publishBatch() {
        int count = 0;
        for (String id : jdbc.queryForList("SELECT id FROM outbox_event WHERE status='PENDING' AND available_at<=CURRENT_TIMESTAMP(6) ORDER BY created_at,id LIMIT 20", String.class)) {
            if (Boolean.TRUE.equals(transactions.execute(status -> send(id)))) count++;
        }
        return count;
    }

    private boolean send(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT event_type,payload_json FROM outbox_event WHERE id=? AND status='PENDING' AND available_at<=CURRENT_TIMESTAMP(6) FOR UPDATE SKIP LOCKED", id);
        if (rows.isEmpty()) return false;
        try {
            Map<String, Object> row = rows.getFirst();
            String routingKey = routingKey(String.valueOf(row.get("event_type")));
            CorrelationData correlation = new CorrelationData(id);
            rabbit.convertAndSend(EXCHANGE, routingKey, row.get("payload_json").toString(), message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setContentType("application/json");
                return message;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new IllegalStateException(confirm.getReason());
            jdbc.update("UPDATE outbox_event SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(6),last_error=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PENDING'", id);
            return true;
        } catch (Exception exception) {
            String message = String.valueOf(exception.getMessage());
            jdbc.update("UPDATE outbox_event SET attempt_count=attempt_count+1,last_error=?,available_at=CURRENT_TIMESTAMP(6)+INTERVAL 5 SECOND,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PENDING'", message.length() > 1000 ? message.substring(0, 1000) : message, id);
            return false;
        }
    }

    private static String routingKey(String eventType) {
        return switch (eventType) {
            case "ADOPTION_COMPLETED" -> "adoption.handover.completed";
            case "ADOPTION_RELATION_ENDED" -> "adoption.relation.ended";
            default -> throw new IllegalArgumentException("unsupported outbox event type: " + eventType);
        };
    }
}
