package org.animallink.animal.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.animallink.animal.domain.DependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdoptionLifecycleProjectionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdoptionLifecycleProjectionService.class);
    private final JdbcTemplate jdbc;
    private final AdoptionLifecycleAuthorityGateway authority;
    private final TransactionTemplate transactions;

    public AdoptionLifecycleProjectionService(JdbcTemplate jdbc,
                                              AdoptionLifecycleAuthorityGateway authority,
                                              TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.authority = authority;
        this.transactions = transactions;
    }

    public void reconcile(AdoptionLifecycleEvent event) {
        transactions.executeWithoutResult(status -> reconcileInTransaction(event));
    }

    private void reconcileInTransaction(AdoptionLifecycleEvent event) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO processed_domain_event(event_id,event_type,processed_at)
                VALUES(?,?,?)
                """, event.eventId(), event.eventType(), Timestamp.from(Instant.now()));
        if (inserted == 0) {
            String existingType = jdbc.queryForObject(
                    "SELECT event_type FROM processed_domain_event WHERE event_id=?",
                    String.class, event.eventId());
            if (!event.eventType().equals(existingType)) reject("eventId 已用于其他事件类型");
            return;
        }

        Map<String, Object> animal = jdbc.queryForList("""
                SELECT identity_status,adoption_status,current_context,version
                FROM animal WHERE id=? FOR UPDATE
                """, event.animalId()).stream().findFirst()
                .orElseThrow(() -> rejectException("Animal 不存在"));

        AdoptionLifecycleAuthorityGateway.Authority latest = authority.latest(event.animalId());
        if (!latest.exists()) {
            throw new DependencyUnavailableException("领养生命周期权威记录尚不可见");
        }
        if (!event.animalId().equals(latest.animalId())) reject("权威生命周期 Animal 不匹配");

        String eventHandover = event.handoverId();
        if (eventHandover == null && event.relationId() != null
                && event.relationId().equals(latest.relationId())) {
            eventHandover = latest.handoverId();
        }
        if (!latest.handoverId().equals(eventHandover)) {
            isolate(event, latest, "handoverId 与当前权威生命周期不匹配");
            return;
        }
        if (event.relationId() != null
                && !event.relationId().equals(latest.relationId())) {
            if (latest.relationId() == null) {
                isolate(event, latest, "当前权威生命周期尚无对应 AdoptionRelation");
                return;
            }
            isolate(event, latest, "relationId 与当前权威生命周期不匹配");
            return;
        }

        Projection previous = projectionForUpdate(event.animalId());
        if (previous != null && isNewerOrConflicting(previous, latest)) {
            return;
        }

        Desired desired = desired(latest);
        applyAnimal(event.animalId(), animal, desired, latest);
        upsertProjection(latest);
        reconcileTimeline(latest);
    }

    private Projection projectionForUpdate(String animalId) {
        List<Projection> rows = jdbc.query("""
                SELECT handover_id,lifecycle_started_at
                FROM animal_adoption_lifecycle_projection
                WHERE animal_id=? FOR UPDATE
                """, (rs, row) -> new Projection(rs.getString("handover_id"),
                rs.getTimestamp("lifecycle_started_at").toInstant()), animalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean isNewerOrConflicting(Projection previous,
                                         AdoptionLifecycleAuthorityGateway.Authority latest) {
        int order = previous.startedAt().compareTo(latest.lifecycleStartedAt());
        return order > 0 || (order == 0 && !previous.handoverId().equals(latest.handoverId()));
    }

    private Desired desired(AdoptionLifecycleAuthorityGateway.Authority latest) {
        if ("ENDED".equals(latest.relationStatus())) return Desired.NOT_OPEN;
        return switch (latest.handoverStatus()) {
            case "PENDING" -> Desired.HANDOVER_PENDING;
            case "CANCELLED" -> Desired.NOT_OPEN;
            case "COMPLETED" -> {
                if (!"ACTIVE".equals(latest.relationStatus()) || latest.relationId() == null) {
                    throw new DependencyUnavailableException(
                            "COMPLETED Handover 尚无 ACTIVE AdoptionRelation 权威记录");
                }
                yield Desired.ADOPTED;
            }
            default -> throw rejectException("不支持的权威 Handover 状态");
        };
    }

    private void applyAnimal(String animalId, Map<String, Object> animal, Desired desired,
                             AdoptionLifecycleAuthorityGateway.Authority latest) {
        String identity = String.valueOf(animal.get("identity_status"));
        String adoption = String.valueOf(animal.get("adoption_status"));
        String context = String.valueOf(animal.get("current_context"));
        if (!"ACTIVE".equals(identity)) reject("非 ACTIVE Animal 不能应用领养生命周期事件");

        String targetContext = desired == Desired.ADOPTED ? "ADOPTED_HOME" : "CAMPUS";
        if (desired == Desired.HANDOVER_PENDING) {
            if (!("OPEN".equals(adoption) || "HANDOVER_PENDING".equals(adoption))
                    || !"CAMPUS".equals(context)) {
                reject("只有 OPEN + CAMPUS Animal 可进入 HANDOVER_PENDING");
            }
        } else if (desired == Desired.ADOPTED) {
            if (!("OPEN".equals(adoption) || "HANDOVER_PENDING".equals(adoption)
                    || "ADOPTED".equals(adoption))) {
                reject("完成交接与 Animal 领养状态不兼容");
            }
        } else if (!("OPEN".equals(adoption) || "HANDOVER_PENDING".equals(adoption)
                || "ADOPTED".equals(adoption) || "NOT_OPEN".equals(adoption))) {
            reject("终止领养生命周期与 Animal 状态不兼容");
        }

        if (!desired.name().equals(adoption) || !targetContext.equals(context)) {
            int changed = jdbc.update("""
                    UPDATE animal SET adoption_status=?,current_context=?,version=version+1
                    WHERE id=? AND version=? AND identity_status='ACTIVE'
                    """, desired.name(), targetContext, animalId,
                    ((Number) animal.get("version")).intValue());
            if (changed != 1) reject("领养生命周期状态已并发变化");
        }
    }

    private void upsertProjection(AdoptionLifecycleAuthorityGateway.Authority latest) {
        jdbc.update("""
                INSERT INTO animal_adoption_lifecycle_projection
                  (animal_id,handover_id,lifecycle_started_at,handover_status,
                   relation_id,relation_status,reconciled_at)
                VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                  handover_id=VALUES(handover_id),
                  lifecycle_started_at=VALUES(lifecycle_started_at),
                  handover_status=VALUES(handover_status),
                  relation_id=VALUES(relation_id),
                  relation_status=VALUES(relation_status),
                  reconciled_at=CURRENT_TIMESTAMP(6)
                """, latest.animalId(), latest.handoverId(),
                Timestamp.from(latest.lifecycleStartedAt()), latest.handoverStatus(),
                latest.relationId(), latest.relationStatus());
    }

    private void reconcileTimeline(AdoptionLifecycleAuthorityGateway.Authority latest) {
        if (latest.relationId() == null) return;
        if (latest.relationActivatedAt() != null) {
            insertTimeline(latest.animalId(), latest.relationId(), "ADOPTION_COMPLETED",
                    "已完成领养交接", latest.relationActivatedAt());
        }
        if ("ENDED".equals(latest.relationStatus()) && latest.relationEndedAt() != null) {
            insertTimeline(latest.animalId(), latest.relationId(), "ADOPTION_ENDED",
                    "领养关系已结束", latest.relationEndedAt());
        }
    }

    private void insertTimeline(String animalId, String relationId, String entryType,
                                String title, Instant occurredAt) {
        jdbc.update("""
                INSERT IGNORE INTO timeline_entry
                  (id,animal_id,source_type,source_id,entry_type,title,summary,
                   occurred_at,visibility,created_at)
                VALUES(?,?,'ADOPTION',?,?,?,NULL,?,'PUBLIC',?)
                """, UUID.randomUUID().toString(), animalId, relationId,
                entryType, title, Timestamp.from(occurredAt), Timestamp.from(Instant.now()));
    }

    private void isolate(AdoptionLifecycleEvent event,
                         AdoptionLifecycleAuthorityGateway.Authority latest,
                         String reason) {
        LOGGER.warn("Isolated adoption lifecycle event: eventId={}, eventType={}, eventVersion={}, "
                        + "animalId={}, eventHandoverId={}, eventRelationId={}, "
                        + "authorityHandoverId={}, authorityRelationId={}, reason={}",
                event.eventId(), event.eventType(), event.eventVersion(), event.animalId(),
                event.handoverId(), event.relationId(), latest.handoverId(),
                latest.relationId(), reason);
    }

    private static void reject(String message) {
        throw rejectException(message);
    }

    private static AmqpRejectAndDontRequeueException rejectException(String message) {
        return new AmqpRejectAndDontRequeueException(message);
    }

    private enum Desired { HANDOVER_PENDING, ADOPTED, NOT_OPEN }
    private record Projection(String handoverId, Instant startedAt) { }
}
