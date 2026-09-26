package org.animallink.identity.application;

import org.animallink.identity.domain.CampusMembershipRepository;
import org.animallink.identity.domain.CampusVerification;
import org.animallink.identity.domain.CampusVerificationRepository;
import org.animallink.identity.domain.ConflictException;
import org.animallink.identity.domain.ForbiddenException;
import org.animallink.identity.domain.NotFoundException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.VerificationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class CampusVerificationReviewService {
    private final CampusVerificationRepository verificationRepository;
    private final CampusMembershipRepository membershipRepository;

    public CampusVerificationReviewService(CampusVerificationRepository verificationRepository,
                                           CampusMembershipRepository membershipRepository) {
        this.verificationRepository = verificationRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional
    public CampusVerification approve(String verificationId, UserAccount reviewer, String reason) {
        requireAdmin(reviewer);
        CampusVerification verification = lockPending(verificationId);
        Instant reviewedAt = Instant.now();
        int updated = verificationRepository.updateReview(
                verification.id(), verification.version(), VerificationStatus.APPROVED,
                reason, reviewer.id(), reviewedAt);
        if (updated != 1) {
            throw new ConflictException("申请状态已发生变化，请刷新后重试");
        }
        membershipRepository.upsertApproved(UUID.randomUUID().toString(), verification, reviewedAt);
        return verificationRepository.findVerificationById(verification.id())
                .orElseThrow(() -> new NotFoundException("CampusVerification 不存在"));
    }

    @Transactional
    public CampusVerification reject(String verificationId, UserAccount reviewer, String reason) {
        requireAdmin(reviewer);
        CampusVerification verification = lockPending(verificationId);
        Instant reviewedAt = Instant.now();
        int updated = verificationRepository.updateReview(
                verification.id(), verification.version(), VerificationStatus.REJECTED,
                reason, reviewer.id(), reviewedAt);
        if (updated != 1) {
            throw new ConflictException("申请状态已发生变化，请刷新后重试");
        }
        return verificationRepository.findVerificationById(verification.id())
                .orElseThrow(() -> new NotFoundException("CampusVerification 不存在"));
    }

    private CampusVerification lockPending(String verificationId) {
        IdRules.requireUuid(verificationId, "verificationId");
        CampusVerification verification = verificationRepository.findByIdForUpdate(verificationId)
                .orElseThrow(() -> new NotFoundException("CampusVerification 不存在"));
        if (verification.status() != VerificationStatus.PENDING_REVIEW) {
            throw new ConflictException("CampusVerification 已审核，不能再次变更状态");
        }
        return verification;
    }

    private static void requireAdmin(UserAccount reviewer) {
        if (!reviewer.isActive() || !reviewer.isGovernanceAdmin()) {
            throw new ForbiddenException("仅有效的治理管理员可以执行校园身份审核");
        }
    }
}
