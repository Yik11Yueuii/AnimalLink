package org.animallink.adoption.application;

import java.util.UUID;
import org.animallink.adoption.domain.AdoptionApplication;
import org.animallink.adoption.domain.ApplicantNotEligibleException;
import org.animallink.adoption.domain.ApplicationNotFoundException;
import org.animallink.adoption.domain.ApplicationNotOwnerException;
import org.animallink.adoption.domain.ListingNotFoundException;
import org.animallink.adoption.domain.ListingNotOpenForApplicationException;
import org.animallink.adoption.domain.AdoptionListing;
import org.animallink.adoption.domain.ApplicationStatus;
import org.animallink.adoption.domain.InvalidApplicationTransitionException;
import org.animallink.adoption.domain.ListingAccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionApplicationService {
    private static final int MAX_PAGE_SIZE = 100;
    private final AdoptionApplicationRepository applications;
    private final AdoptionListingRepository listings;
    private final IdentityGateway identity;
    public AdoptionApplicationService(AdoptionApplicationRepository applications, AdoptionListingRepository listings, IdentityGateway identity) { this.applications = applications; this.listings = listings; this.identity = identity; }
    @Transactional
    public AdoptionApplication submit(String listingId, String message) {
        validUuid(listingId, "listingId");
        IdentityGateway.CurrentUser applicant = identity.requireEligibleApplicant();
        AdoptionListing listing = listings.findById(listingId).orElseThrow(() -> new ListingNotFoundException("领养信息不存在"));
        if (!"PUBLISHED".equals(listing.status().name())) throw new ListingNotOpenForApplicationException("领养信息当前不接受申请");
        if (applicant.id().equals(listing.publisherUserId())) throw new ApplicantNotEligibleException("发布者不能申请自己的领养信息");
        AdoptionApplication application = AdoptionApplication.submitted(listingId, applicant.id(), message.trim());
        if (!applications.insertIfListingPublished(application)) throw new ListingNotOpenForApplicationException("领养信息当前不接受申请");
        return application;
    }
    public AdoptionApplication detail(String applicationId) { return owned(applicationId, identity.requireCurrentUser().id()); }
    public PageResult<AdoptionApplication> myApplications(int page, int size) {
        String userId = identity.requireCurrentUser().id(); int safePage = Math.max(0, page); int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return new PageResult<>(applications.findByApplicant(userId, safeSize, safePage * safeSize), safePage, safeSize, applications.countByApplicant(userId));
    }
    @Transactional
    public AdoptionApplication withdraw(String applicationId) {
        AdoptionApplication withdrawn = owned(applicationId, identity.requireCurrentUser().id()).withdraw();
        if (!applications.withdraw(withdrawn.id(), withdrawn.applicantUserId(), withdrawn.updatedAt(), withdrawn.withdrawnAt())) throw new InvalidApplicationTransitionException("领养申请已不处于可撤回状态");
        return withdrawn;
    }
    public PageResult<AdoptionApplication> governanceApplications(String listingId, String status, int page, int size) {
        requireGovernance();
        if (listingId != null && !listingId.isBlank()) validUuid(listingId, "listingId"); else listingId = null;
        ApplicationStatus filter = status == null || status.isBlank() ? ApplicationStatus.SUBMITTED : parseStatus(status);
        Page pageRequest = page(page, size);
        return new PageResult<>(applications.findForGovernance(listingId, filter, pageRequest.size(), pageRequest.offset()), pageRequest.page(), pageRequest.size(), applications.countForGovernance(listingId, filter));
    }
    public AdoptionApplication governanceDetail(String applicationId) {
        requireGovernance();
        return existing(applicationId);
    }
    @Transactional
    public AdoptionApplication approve(String applicationId, String comment) { return review(applicationId, ApplicationStatus.APPROVED, normalizeOptionalComment(comment)); }
    @Transactional
    public AdoptionApplication reject(String applicationId, String reason) {
        if (reason == null || reason.trim().isEmpty()) throw new IllegalArgumentException("reason 不能为空");
        String normalized = reason.trim();
        if (normalized.length() > 500) throw new IllegalArgumentException("reason 最大长度为 500");
        return review(applicationId, ApplicationStatus.REJECTED, normalized);
    }
    private AdoptionApplication review(String applicationId, ApplicationStatus decision, String comment) {
        IdentityGateway.CurrentUser reviewer = requireGovernance();
        AdoptionApplication reviewed = existing(applicationId).review(decision, reviewer.id(), comment);
        if (applications.review(reviewed.id(), decision, reviewer.id(), reviewed.reviewedAt(), reviewed.reviewComment(), reviewed.updatedAt())) return reviewed;
        AdoptionApplication latest = applications.findById(applicationId).orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
        throw new InvalidApplicationTransitionException("领养申请已不处于可审核状态");
    }
    private IdentityGateway.CurrentUser requireGovernance() {
        IdentityGateway.CurrentUser user = identity.requireCurrentUser();
        if (!user.isActiveGovernanceAdmin()) throw new ListingAccessDeniedException("需要治理管理员权限");
        return user;
    }
    private AdoptionApplication existing(String applicationId) {
        validUuid(applicationId, "applicationId");
        return applications.findById(applicationId).orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
    }
    private static ApplicationStatus parseStatus(String value) { try { return ApplicationStatus.valueOf(value); } catch (RuntimeException e) { throw new IllegalArgumentException("status 无效"); } }
    private static String normalizeOptionalComment(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim(); if (normalized.length() > 500) throw new IllegalArgumentException("comment 最大长度为 500"); return normalized;
    }
    private static Page page(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page 不能小于 0");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        try { return new Page(page, size, Math.multiplyExact(page, size)); } catch (ArithmeticException e) { throw new IllegalArgumentException("page 过大"); }
    }
    private record Page(int page, int size, int offset) { }
    private AdoptionApplication owned(String applicationId, String userId) {
        validUuid(applicationId, "applicationId");
        AdoptionApplication application = applications.findById(applicationId).orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
        if (!userId.equals(application.applicantUserId())) throw new ApplicationNotOwnerException("无权访问其他用户的领养申请");
        return application;
    }
    private static void validUuid(String value, String field) { try { UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException(field + " 必须是 UUID"); } }
}
