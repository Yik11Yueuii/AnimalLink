package org.animallink.adoption.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.animallink.adoption.domain.AdoptionRelation;
import org.animallink.adoption.domain.AdoptionRelationNotFoundException;
import org.animallink.adoption.domain.InvalidAdoptionRelationTransitionException;
import org.animallink.adoption.domain.ListingAccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionRelationService {
    private static final String ENDED_EVENT = "ADOPTION_RELATION_ENDED";
    private final AdoptionHandoverRepository relations;
    private final IdentityGateway identity;
    private final ObjectMapper json;

    public AdoptionRelationService(AdoptionHandoverRepository relations, IdentityGateway identity, ObjectMapper json) {
        this.relations = relations;
        this.identity = identity;
        this.json = json;
    }

    @Transactional
    public Result end(String relationId, String reason) {
        requireGovernanceAdmin();
        uuid(relationId);
        String normalizedReason = requiredReason(reason);
        AdoptionRelation relation = required(relationId);
        if (relation.isEnded()) return new Result(relation, false);
        if (!relation.isActive()) throw new InvalidAdoptionRelationTransitionException("领养关系状态不支持终止");

        Instant endedAt = Instant.now();
        if (!relations.endRelation(relationId, endedAt, normalizedReason)) return afterConcurrentEnd(relationId);

        AdoptionRelation ended = required(relationId);
        String eventId = UUID.randomUUID().toString();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", eventId);
            payload.put("eventType", ENDED_EVENT);
            payload.put("eventVersion", 1);
            payload.put("occurredAt", endedAt.toString());
            payload.put("relationId", ended.id());
            payload.put("animalId", ended.animalId());
            relations.insertOutbox(eventId, ended.id(), ENDED_EVENT, json.writeValueAsString(payload), endedAt);
        } catch (Exception exception) {
            throw new IllegalStateException("outbox write failed", exception);
        }
        return new Result(ended, true);
    }

    private Result afterConcurrentEnd(String relationId) {
        AdoptionRelation latest = relations.findRelationByIdForUpdate(relationId).orElseThrow(() -> new AdoptionRelationNotFoundException("领养关系不存在"));
        if (latest.isEnded()) return new Result(latest, false);
        throw new InvalidAdoptionRelationTransitionException("领养关系状态已变化");
    }

    private AdoptionRelation required(String relationId) {
        return relations.findRelationById(relationId).orElseThrow(() -> new AdoptionRelationNotFoundException("领养关系不存在"));
    }

    private void requireGovernanceAdmin() {
        if (!identity.requireCurrentUser().isActiveGovernanceAdmin()) throw new ListingAccessDeniedException("需要治理管理员权限");
    }

    private static void uuid(String value) {
        try { UUID.fromString(value); } catch (Exception exception) { throw new IllegalArgumentException("relationId 必须是 UUID"); }
    }

    private static String requiredReason(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("reason 不能为空");
        String normalized = value.trim();
        if (normalized.length() > 500) throw new IllegalArgumentException("reason 最大长度为 500");
        return normalized;
    }

    public record Result(AdoptionRelation relation, boolean changed) { }
}
