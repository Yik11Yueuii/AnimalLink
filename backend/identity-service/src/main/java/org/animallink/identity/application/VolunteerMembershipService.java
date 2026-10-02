package org.animallink.identity.application;

import org.animallink.identity.domain.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class VolunteerMembershipService {
    private final CurrentUserProvider currentUserProvider;
    private final CampusMembershipRepository campusMembershipRepository;
    private final CampusRepository campusRepository;
    private final VolunteerMembershipRepository repository;

    public VolunteerMembershipService(CurrentUserProvider currentUserProvider,
                                      CampusMembershipRepository campusMembershipRepository,
                                      CampusRepository campusRepository,
                                      VolunteerMembershipRepository repository) {
        this.currentUserProvider = currentUserProvider;
        this.campusMembershipRepository = campusMembershipRepository;
        this.campusRepository = campusRepository;
        this.repository = repository;
    }

    @Transactional
    public VolunteerMembership apply(String campusId, String applicationNote) {
        UserAccount user = currentUserProvider.requireCurrentUser();
        campusId = IdRules.requireUuid(campusId, "campusId");
        campusRepository.findActiveById(campusId).orElseThrow(() -> new NotFoundException("Campus 不存在或不可用"));
        CampusMembership membership = campusMembershipRepository.findByUserAndCampus(user.id(), campusId)
                .orElseThrow(() -> new VolunteerMembershipException("CAMPUS_MEMBERSHIP_REQUIRED", "申请志愿者必须具备有效 CampusMembership"));
        if (membership.status() != MembershipStatus.ACTIVE) {
            throw new VolunteerMembershipException("CAMPUS_MEMBERSHIP_REQUIRED", "申请志愿者必须具备有效 CampusMembership");
        }
        if (repository.findByUserAndCampus(user.id(), campusId).isPresent()) {
            throw new VolunteerMembershipException("VOLUNTEER_APPLICATION_EXISTS", "该 Campus 已存在志愿者身份生命周期记录");
        }
        Instant now = Instant.now();
        VolunteerMembership created = new VolunteerMembership(UUID.randomUUID().toString(), user.id(), campusId,
                VolunteerMembershipStatus.PENDING_REVIEW, normalize(applicationNote), null, null, null,
                null, null, null, 0, now, now);
        try { repository.insert(created); }
        catch (DataIntegrityViolationException e) {
            throw new VolunteerMembershipException("VOLUNTEER_APPLICATION_EXISTS", "该 Campus 已存在志愿者身份生命周期记录");
        }
        return created;
    }

    public List<VolunteerMembership> mine(String campusId, int page, int size) {
        UserAccount user = currentUserProvider.requireCurrentUser();
        int limit = Math.min(Math.max(size, 1), 100), offset = Math.max(page, 0) * limit;
        if (campusId == null || campusId.isBlank()) return repository.findByUserId(user.id(), limit, offset);
        String validCampusId = IdRules.requireUuid(campusId, "campusId");
        return repository.findByUserAndCampus(user.id(), validCampusId).stream().toList();
    }

    @Transactional
    public VolunteerMembership pause(String id) { return selfTransition(id, VolunteerMembershipStatus.ACTIVE, VolunteerMembershipStatus.PAUSED); }
    @Transactional
    public VolunteerMembership resume(String id) { return selfTransition(id, VolunteerMembershipStatus.PAUSED, VolunteerMembershipStatus.ACTIVE); }
    @Transactional
    public VolunteerMembership exit(String id) {
        UserAccount user = currentUserProvider.requireCurrentUser();
        VolunteerMembership value = ownedForUpdate(id, user.id());
        if (value.status() != VolunteerMembershipStatus.ACTIVE && value.status() != VolunteerMembershipStatus.PAUSED) invalidTransition();
        update(value, value.status(), VolunteerMembershipStatus.EXITED, null, null, null, null, null, Instant.now());
        return require(id);
    }

    public List<VolunteerMembership> listForAdmin(String campusId, VolunteerMembershipStatus status, int page, int size) {
        requireAdmin();
        if (campusId != null && !campusId.isBlank()) IdRules.requireUuid(campusId, "campusId");
        int limit = Math.min(Math.max(size, 1), 100);
        return repository.findForAdmin(campusId, status, limit, Math.max(page, 0) * limit);
    }

    @Transactional
    public VolunteerMembership approve(String id, String reason) {
        UserAccount admin = requireAdmin();
        VolunteerMembership value = locked(id);
        if (value.status() != VolunteerMembershipStatus.PENDING_REVIEW) invalidTransition();
        Instant now = Instant.now();
        update(value, VolunteerMembershipStatus.PENDING_REVIEW, VolunteerMembershipStatus.ACTIVE, normalize(reason), admin.id(), now, now, null, null);
        return require(id);
    }
    @Transactional
    public VolunteerMembership reject(String id, String reason) {
        UserAccount admin = requireAdmin();
        requireReason(reason);
        VolunteerMembership value = locked(id);
        if (value.status() != VolunteerMembershipStatus.PENDING_REVIEW) invalidTransition();
        update(value, VolunteerMembershipStatus.PENDING_REVIEW, VolunteerMembershipStatus.REJECTED, reason.trim(), admin.id(), Instant.now(), null, null, Instant.now());
        return require(id);
    }
    @Transactional
    public VolunteerMembership revoke(String id, String reason) {
        UserAccount admin = requireAdmin();
        requireReason(reason);
        VolunteerMembership value = locked(id);
        if (value.status() != VolunteerMembershipStatus.ACTIVE && value.status() != VolunteerMembershipStatus.PAUSED) invalidTransition();
        update(value, value.status(), VolunteerMembershipStatus.REVOKED, reason.trim(), admin.id(), Instant.now(), null, null, Instant.now());
        return require(id);
    }

    public Optional<VolunteerMembership> fact(String userId, String campusId) {
        return repository.findByUserAndCampus(IdRules.requireUuid(userId, "userId"), IdRules.requireUuid(campusId, "campusId"));
    }
    private VolunteerMembership selfTransition(String id, VolunteerMembershipStatus expected, VolunteerMembershipStatus next) {
        UserAccount user = currentUserProvider.requireCurrentUser();
        VolunteerMembership value = ownedForUpdate(id, user.id());
        if (value.status() != expected) invalidTransition();
        Instant now = Instant.now();
        update(value, expected, next, null, null, null,
                next == VolunteerMembershipStatus.PAUSED ? now : null, null, null);
        return require(id);
    }
    private VolunteerMembership ownedForUpdate(String id, String userId) {
        VolunteerMembership value = locked(id);
        if (!value.userId().equals(userId)) throw new VolunteerMembershipNotFoundException();
        return value;
    }
    private VolunteerMembership locked(String id) { IdRules.requireUuid(id, "volunteerMembershipId"); return repository.findByIdForUpdate(id).orElseThrow(VolunteerMembershipNotFoundException::new); }
    private VolunteerMembership require(String id) { return repository.findById(id).orElseThrow(VolunteerMembershipNotFoundException::new); }
    private void update(VolunteerMembership v, VolunteerMembershipStatus expected, VolunteerMembershipStatus next, String reason, String reviewer, Instant reviewed, Instant active, Instant paused, Instant ended) {
        if (repository.transition(v.id(), v.version(), expected, next, reason, reviewer, reviewed, active, paused, ended) != 1) invalidTransition();
    }
    private UserAccount requireAdmin() { UserAccount u = currentUserProvider.requireCurrentUser(); if (!u.isGovernanceAdmin()) throw new VolunteerMembershipException("GOVERNANCE_REQUIRED", "仅治理管理员可以管理志愿者身份"); return u; }
    private static void invalidTransition() { throw new VolunteerMembershipException("INVALID_VOLUNTEER_TRANSITION", "不允许的 VolunteerMembership 状态迁移"); }
    private static void requireReason(String value) { if (value == null || value.isBlank()) throw new jakarta.validation.ValidationException("reviewReason 不能为空"); }
    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
