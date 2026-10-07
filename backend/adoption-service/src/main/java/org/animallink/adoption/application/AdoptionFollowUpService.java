package org.animallink.adoption.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.animallink.adoption.domain.AdoptionFollowUp;
import org.animallink.adoption.domain.AdoptionRelation;
import org.animallink.adoption.domain.AdoptionRelationNotActiveException;
import org.animallink.adoption.domain.AdoptionRelationNotFoundException;
import org.animallink.adoption.domain.FollowUpAccessDeniedException;
import org.animallink.adoption.domain.ListingAccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionFollowUpService {
    private static final int MAX_PAGE_SIZE = 100;
    private final AdoptionHandoverRepository relations;
    private final AdoptionFollowUpRepository followUps;
    private final IdentityGateway identity;

    public AdoptionFollowUpService(AdoptionHandoverRepository relations, AdoptionFollowUpRepository followUps, IdentityGateway identity) {
        this.relations = relations;
        this.followUps = followUps;
        this.identity = identity;
    }

    @Transactional
    public AdoptionFollowUp create(String relationId, String content, OffsetDateTime followedUpAt) {
        uuid(relationId);
        IdentityGateway.CurrentUser caller = activeCaller();
        AdoptionRelation relation = relation(relationId);
        requireOwner(caller, relation);
        if (!relation.isActive()) throw new AdoptionRelationNotActiveException("领养关系当前不能新增回访记录");
        Instant occurredAt = requiredPast(followedUpAt);
        Instant now = Instant.now();
        AdoptionFollowUp followUp = AdoptionFollowUp.create(relationId, requiredContent(content), occurredAt, now);
        followUps.insert(followUp);
        return followUp;
    }

    public PageResult<AdoptionFollowUp> mine(String relationId, int page, int size) {
        uuid(relationId);
        IdentityGateway.CurrentUser caller = activeCaller();
        requireOwner(caller, relation(relationId));
        return page(relationId, page, size);
    }

    public PageResult<AdoptionFollowUp> governance(String relationId, int page, int size) {
        uuid(relationId);
        if (!identity.requireCurrentUser().isActiveGovernanceAdmin()) throw new ListingAccessDeniedException("需要治理管理员权限");
        relation(relationId);
        return page(relationId, page, size);
    }

    private PageResult<AdoptionFollowUp> page(String relationId, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page 不能小于 0");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        int offset;
        try { offset = Math.multiplyExact(page, size); } catch (ArithmeticException exception) { throw new IllegalArgumentException("page 过大"); }
        return new PageResult<>(followUps.findByRelationId(relationId, size, offset), page, size, followUps.countByRelationId(relationId));
    }

    private IdentityGateway.CurrentUser activeCaller() {
        IdentityGateway.CurrentUser caller = identity.requireCurrentUser();
        if (!"ACTIVE".equals(caller.accountStatus())) throw new FollowUpAccessDeniedException("账户未激活");
        return caller;
    }
    private AdoptionRelation relation(String relationId) { return relations.findRelationById(relationId).orElseThrow(() -> new AdoptionRelationNotFoundException("领养关系不存在")); }
    private static void requireOwner(IdentityGateway.CurrentUser caller, AdoptionRelation relation) {
        if (!caller.id().equals(relation.adopterUserId())) throw new FollowUpAccessDeniedException("无权访问该领养关系的回访记录");
    }
    private static String requiredContent(String content) {
        if (content == null || content.trim().isEmpty()) throw new IllegalArgumentException("content 不能为空");
        String normalized = content.trim();
        if (normalized.length() > 2000) throw new IllegalArgumentException("content 最大长度为 2000");
        return normalized;
    }
    private static Instant requiredPast(OffsetDateTime followedUpAt) {
        if (followedUpAt == null) throw new IllegalArgumentException("followedUpAt 不能为空");
        Instant instant = followedUpAt.toInstant();
        if (instant.isAfter(Instant.now())) throw new IllegalArgumentException("followedUpAt 不能晚于当前时间");
        return instant;
    }
    private static void uuid(String relationId) { try { UUID.fromString(relationId); } catch (Exception exception) { throw new IllegalArgumentException("relationId 必须是 UUID"); } }
}
