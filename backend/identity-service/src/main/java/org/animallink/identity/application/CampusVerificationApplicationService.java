package org.animallink.identity.application;

import org.animallink.identity.domain.Campus;
import org.animallink.identity.domain.CampusMembershipRepository;
import org.animallink.identity.domain.CampusRepository;
import org.animallink.identity.domain.CampusVerification;
import org.animallink.identity.domain.CampusVerificationRepository;
import org.animallink.identity.domain.CampusVerificationView;
import org.animallink.identity.domain.ConflictException;
import org.animallink.identity.domain.ForbiddenException;
import org.animallink.identity.domain.NotFoundException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.VerificationStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CampusVerificationApplicationService {
    private final CurrentUserProvider currentUserProvider;
    private final CampusRepository campusRepository;
    private final CampusMembershipRepository membershipRepository;
    private final CampusVerificationRepository verificationRepository;
    private final CampusVerificationReviewService reviewService;

    public CampusVerificationApplicationService(CurrentUserProvider currentUserProvider,
                                                CampusRepository campusRepository,
                                                CampusMembershipRepository membershipRepository,
                                                CampusVerificationRepository verificationRepository,
                                                CampusVerificationReviewService reviewService) {
        this.currentUserProvider = currentUserProvider;
        this.campusRepository = campusRepository;
        this.membershipRepository = membershipRepository;
        this.verificationRepository = verificationRepository;
        this.reviewService = reviewService;
    }

    @Transactional
    public CampusVerificationView submit(SubmitVerificationCommand command) {
        UserAccount user = currentUserProvider.requireCurrentUser();
        String campusId = IdRules.requireUuid(command.campusId(), "campusId");
        String materialMediaId = IdRules.optionalUuid(command.materialMediaId(), "materialMediaId");
        Campus campus = campusRepository.findActiveById(campusId)
                .orElseThrow(() -> new NotFoundException("Campus 不存在或不可用"));

        if (membershipRepository.existsActiveByUserAndCampus(user.id(), campusId)) {
            throw new ConflictException("当前用户已拥有该 Campus 的有效身份");
        }
        if (verificationRepository.existsPendingByUserAndCampus(user.id(), campusId)) {
            throw new ConflictException("当前用户在该 Campus 已有待审核申请");
        }

        Instant now = Instant.now();
        CampusVerification verification = new CampusVerification(
                UUID.randomUUID().toString(),
                user.id(),
                campusId,
                command.requestedMembershipType(),
                command.applicantName().trim(),
                normalize(command.affiliationNote()),
                command.expectedGraduationDate(),
                materialMediaId,
                VerificationStatus.PENDING_REVIEW,
                null,
                null,
                null,
                0,
                now,
                now);
        try {
            verificationRepository.insert(verification);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("无法创建申请：可能存在重复的待审核申请或无效关联");
        }
        return new CampusVerificationView(
                verificationRepository.findVerificationById(verification.id()).orElse(verification), campus);
    }

    public List<CampusVerificationView> listMine() {
        UserAccount user = currentUserProvider.requireCurrentUser();
        return verificationRepository.findVerificationsByUserId(user.id()).stream().map(this::withCampus).toList();
    }

    public CampusVerificationView getMine(String verificationId) {
        IdRules.requireUuid(verificationId, "verificationId");
        UserAccount user = currentUserProvider.requireCurrentUser();
        CampusVerification verification = verificationRepository.findByIdAndUserId(verificationId, user.id())
                .orElseThrow(() -> new NotFoundException("CampusVerification 不存在"));
        return withCampus(verification);
    }

    public List<CampusVerificationView> listForAdmin(VerificationStatus status, int page, int size) {
        requireAdmin();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return verificationRepository.findByStatus(status, safeSize, safePage * safeSize)
                .stream().map(this::withCampus).toList();
    }

    public CampusVerificationView getForAdmin(String verificationId) {
        requireAdmin();
        IdRules.requireUuid(verificationId, "verificationId");
        return withCampus(verificationRepository.findVerificationById(verificationId)
                .orElseThrow(() -> new NotFoundException("CampusVerification 不存在")));
    }

    public CampusVerificationView approve(String verificationId, String reason) {
        UserAccount reviewer = requireAdmin();
        return withCampus(reviewService.approve(verificationId, reviewer, normalize(reason)));
    }

    public CampusVerificationView reject(String verificationId, String reason) {
        UserAccount reviewer = requireAdmin();
        return withCampus(reviewService.reject(verificationId, reviewer, reason.trim()));
    }

    private UserAccount requireAdmin() {
        UserAccount user = currentUserProvider.requireCurrentUser();
        if (!user.isGovernanceAdmin()) {
            throw new ForbiddenException("仅治理管理员可以执行校园身份审核");
        }
        return user;
    }

    private CampusVerificationView withCampus(CampusVerification verification) {
        Campus campus = campusRepository.findActiveById(verification.campusId())
                .orElseThrow(() -> new NotFoundException("申请关联的 Campus 不存在或不可用"));
        return new CampusVerificationView(verification, campus);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
