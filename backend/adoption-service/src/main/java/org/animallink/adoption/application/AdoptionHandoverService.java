package org.animallink.adoption.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.animallink.adoption.domain.AdoptionApplication;
import org.animallink.adoption.domain.AdoptionHandover;
import org.animallink.adoption.domain.AdoptionRelation;
import org.animallink.adoption.domain.AdoptionSelection;
import org.animallink.adoption.domain.ApplicationNotFoundException;
import org.animallink.adoption.domain.HandoverAlreadyExistsException;
import org.animallink.adoption.domain.HandoverNotFoundException;
import org.animallink.adoption.domain.HandoverStatus;
import org.animallink.adoption.domain.InvalidHandoverTransitionException;
import org.animallink.adoption.domain.ListingAccessDeniedException;
import org.animallink.adoption.domain.ListingNotFoundException;
import org.animallink.adoption.domain.SelectionNotActiveException;
import org.animallink.adoption.domain.SelectionNotFoundException;
import org.animallink.adoption.domain.SelectionStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionHandoverService {
    private final AdoptionHandoverRepository handovers;
    private final AdoptionSelectionRepository selections;
    private final AdoptionApplicationRepository applications;
    private final AdoptionListingRepository listings;
    private final IdentityGateway identity;
    private final ObjectMapper json;

    public AdoptionHandoverService(AdoptionHandoverRepository handovers, AdoptionSelectionRepository selections, AdoptionApplicationRepository applications, AdoptionListingRepository listings, IdentityGateway identity, ObjectMapper json) {
        this.handovers = handovers;
        this.selections = selections;
        this.applications = applications;
        this.listings = listings;
        this.identity = identity;
        this.json = json;
    }

    @Transactional
    public Result initiate(String selectionId, OffsetDateTime scheduled, String note) {
        IdentityGateway.CurrentUser user = admin();
        uuid(selectionId, "selectionId");
        if (scheduled == null) throw new IllegalArgumentException("scheduledAt 不能为空");
        Instant when = scheduled.toInstant();
        if (!when.isAfter(Instant.now())) throw new IllegalArgumentException("scheduledAt 必须是未来时间");
        AdoptionSelection selection = selections.findByIdForUpdate(selectionId).orElseThrow(() -> new SelectionNotFoundException("最终候选不存在"));
        if (selection.status() != SelectionStatus.ACTIVE) throw new SelectionNotActiveException("最终候选已失效");
        var existing = handovers.findBySelectionId(selectionId);
        if (existing.isPresent()) return existing(existing.get());
        AdoptionHandover handover = AdoptionHandover.pending(selectionId, when, user.id(), optional(note));
        try {
            handovers.insert(handover);
            return new Result(view(handover), true);
        } catch (DuplicateKeyException exception) {
            return existing(handovers.findBySelectionId(selectionId).orElseThrow(() -> exception));
        }
    }

    public View governance(String selectionId) {
        admin();
        uuid(selectionId, "selectionId");
        selection(selectionId);
        return view(handovers.findBySelectionId(selectionId).orElseThrow(() -> new HandoverNotFoundException("交接不存在")));
    }

    public List<ApplicantView> mine() {
        String userId = identity.requireCurrentUser().id();
        return handovers.findByApplicant(userId).stream().map(value -> new ApplicantView(value.handover().id(), value.listingId(), value.applicationId(), value.handover().status().name(), value.handover().scheduledAt(), value.handover().initiatedAt(), value.handover().completedAt())).toList();
    }

    @Transactional
    public Result complete(String handoverId) {
        IdentityGateway.CurrentUser user = admin();
        uuid(handoverId, "handoverId");
        AdoptionHandover handover = required(handoverId);
        if (handover.status() == HandoverStatus.COMPLETED) return new Result(view(handover), false);
        if (handover.status() != HandoverStatus.PENDING) throw new InvalidHandoverTransitionException("已取消的交接不能完成");
        Instant at = Instant.now();
        if (!handovers.complete(handoverId, user.id(), at)) return terminal(handoverId, HandoverStatus.COMPLETED);
        AdoptionSelection selection = selection(handover.selectionId());
        AdoptionApplication application = applications.findById(selection.applicationId()).orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
        var listing = listings.findById(selection.listingId()).orElseThrow(() -> new ListingNotFoundException("领养信息不存在"));
        AdoptionRelation relation = AdoptionRelation.active(listing.animalId(), application.applicantUserId(), handoverId, at);
        handovers.insertRelation(relation);
        String eventId = UUID.randomUUID().toString();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", eventId);
            payload.put("eventType", "ADOPTION_COMPLETED");
            payload.put("eventVersion", 1);
            payload.put("occurredAt", at.toString());
            payload.put("relationId", relation.id());
            payload.put("handoverId", handoverId);
            payload.put("animalId", listing.animalId());
            handovers.insertOutbox(eventId, relation.id(), "ADOPTION_COMPLETED", json.writeValueAsString(payload), at);
        } catch (Exception exception) {
            throw new IllegalStateException("outbox write failed", exception);
        }
        return new Result(view(required(handoverId)), true);
    }

    @Transactional
    public Result cancel(String handoverId, String reason) {
        IdentityGateway.CurrentUser user = admin();
        uuid(handoverId, "handoverId");
        String normalizedReason = requiredReason(reason);
        AdoptionHandover handover = required(handoverId);
        if (handover.status() == HandoverStatus.CANCELLED) return new Result(view(handover), false);
        if (handover.status() != HandoverStatus.PENDING) throw new InvalidHandoverTransitionException("已完成的交接不能取消");
        Instant at = Instant.now();
        AdoptionSelection selection = selection(handover.selectionId());
        if (selection.status() != SelectionStatus.ACTIVE) throw new SelectionNotActiveException("最终候选已失效");
        if (!handovers.cancel(handoverId, user.id(), at, normalizedReason)) return terminal(handoverId, HandoverStatus.CANCELLED);
        if (!selections.cancel(selection.id(), user.id(), at, normalizedReason)) throw new SelectionNotActiveException("最终候选已失效");
        return new Result(view(required(handoverId)), true);
    }

    private Result terminal(String id, HandoverStatus wanted) {
        AdoptionHandover latest = required(id);
        if (latest.status() == wanted) return new Result(view(latest), false);
        throw new InvalidHandoverTransitionException("交接状态已变化");
    }

    private Result existing(AdoptionHandover handover) {
        if (handover.status() == HandoverStatus.PENDING) return new Result(view(handover), false);
        if (handover.status() == HandoverStatus.COMPLETED) throw new HandoverAlreadyExistsException("该最终候选已有已完成交接");
        throw new SelectionNotActiveException("最终候选已失效");
    }

    private View view(AdoptionHandover handover) {
        AdoptionSelection selection = selection(handover.selectionId());
        AdoptionApplication application = applications.findById(selection.applicationId()).orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
        return new View(handover, selection.listingId(), selection.applicationId(), application.applicantUserId());
    }

    private AdoptionSelection selection(String id) { return selections.findById(id).orElseThrow(() -> new SelectionNotFoundException("最终候选不存在")); }
    private AdoptionHandover required(String id) { return handovers.findById(id).orElseThrow(() -> new HandoverNotFoundException("交接不存在")); }
    private IdentityGateway.CurrentUser admin() { var user = identity.requireCurrentUser(); if (!user.isActiveGovernanceAdmin()) throw new ListingAccessDeniedException("需要治理管理员权限"); return user; }
    private static void uuid(String value, String name) { try { UUID.fromString(value); } catch (Exception exception) { throw new IllegalArgumentException(name + " 必须是 UUID"); } }
    private static String optional(String value) { if (value == null || value.trim().isEmpty()) return null; value = value.trim(); if (value.length() > 500) throw new IllegalArgumentException("note 最大长度为 500"); return value; }
    private static String requiredReason(String value) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("reason 不能为空"); value = value.trim(); if (value.length() > 500) throw new IllegalArgumentException("reason 最大长度为 500"); return value; }

    public record View(AdoptionHandover handover, String listingId, String applicationId, String applicantUserId) { }
    public record Result(View view, boolean created) { }
    public record ApplicantView(String handoverId, String listingId, String applicationId, String status, Instant scheduledAt, Instant initiatedAt, Instant completedAt) { }
}
