package org.animallink.adoption.application;

import java.util.UUID;
import org.animallink.adoption.domain.AdoptionApplication;
import org.animallink.adoption.domain.ApplicantNotEligibleException;
import org.animallink.adoption.domain.ApplicationNotFoundException;
import org.animallink.adoption.domain.ApplicationNotOwnerException;
import org.animallink.adoption.domain.ListingNotFoundException;
import org.animallink.adoption.domain.ListingNotOpenForApplicationException;
import org.animallink.adoption.domain.AdoptionListing;
import org.animallink.adoption.domain.InvalidApplicationTransitionException;
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
    private AdoptionApplication owned(String applicationId, String userId) {
        validUuid(applicationId, "applicationId");
        AdoptionApplication application = applications.findById(applicationId).orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
        if (!userId.equals(application.applicantUserId())) throw new ApplicationNotOwnerException("无权访问其他用户的领养申请");
        return application;
    }
    private static void validUuid(String value, String field) { try { UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException(field + " 必须是 UUID"); } }
}
