package org.animallink.adoption.application;

import java.util.UUID;
import org.animallink.adoption.domain.AdoptionApplication;
import org.animallink.adoption.domain.AdoptionListing;
import org.animallink.adoption.domain.AdoptionSelection;
import org.animallink.adoption.domain.ApplicationNotApprovedException;
import org.animallink.adoption.domain.ApplicationNotFoundException;
import org.animallink.adoption.domain.ApplicationStatus;
import org.animallink.adoption.domain.InvalidListingTransitionException;
import org.animallink.adoption.domain.ListingAccessDeniedException;
import org.animallink.adoption.domain.ListingAlreadySelectedException;
import org.animallink.adoption.domain.ListingNotFoundException;
import org.animallink.adoption.domain.ListingNotSelectableException;
import org.animallink.adoption.domain.ListingStatus;
import org.animallink.adoption.domain.SelectionNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionSelectionService {
    private final AdoptionSelectionRepository selections;
    private final AdoptionApplicationRepository applications;
    private final AdoptionListingRepository listings;
    private final IdentityGateway identity;

    public AdoptionSelectionService(AdoptionSelectionRepository selections, AdoptionApplicationRepository applications,
                                    AdoptionListingRepository listings, IdentityGateway identity) {
        this.selections = selections; this.applications = applications; this.listings = listings; this.identity = identity;
    }

    @Transactional
    public SelectionCommandResult select(String listingId, String applicationId, String note) {
        validUuid(listingId, "listingId"); validUuid(applicationId, "applicationId");
        IdentityGateway.CurrentUser admin = requireGovernance();
        AdoptionListing listing = listings.findByIdForUpdate(listingId)
                .orElseThrow(() -> new ListingNotFoundException("领养信息不存在"));
        if (listing.status() == ListingStatus.DRAFT) throw new ListingNotSelectableException("草稿领养信息不能选择最终候选人");
        AdoptionApplication application = applications.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
        if (!listingId.equals(application.listingId())) throw new IllegalArgumentException("applicationId 不属于 listingId");
        if (application.status() != ApplicationStatus.APPROVED) throw new ApplicationNotApprovedException("只有已批准的领养申请可以被最终选择");

        var existing = selections.findActiveByListingId(listingId);
        if (existing.isPresent()) return existingResult(existing.get(), applicationId);

        AdoptionSelection selection = AdoptionSelection.active(listingId, applicationId, admin.id(), normalizeNote(note));
        try {
            selections.insert(selection);
        } catch (DuplicateKeyException e) {
            return existingResult(selections.findActiveByListingId(listingId)
                    .orElseThrow(() -> e), applicationId);
        }
        if (listing.status() == ListingStatus.PUBLISHED && !listings.close(selection.listingId(), selection.selectedAt(), selection.selectedAt())) {
            throw new InvalidListingTransitionException("领养信息状态已变化，请刷新后重试");
        }
        return new SelectionCommandResult(new SelectionView(selection, application.applicantUserId()), true);
    }

    public SelectionView activeSelection(String listingId) {
        validUuid(listingId, "listingId"); requireGovernance();
        listings.findById(listingId).orElseThrow(() -> new ListingNotFoundException("领养信息不存在"));
        AdoptionSelection selection = selections.findActiveByListingId(listingId)
                .orElseThrow(() -> new SelectionNotFoundException("当前领养信息尚无最终候选人"));
        AdoptionApplication application = applications.findById(selection.applicationId())
                .orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
        return new SelectionView(selection, application.applicantUserId());
    }

    private SelectionCommandResult existingResult(AdoptionSelection existing, String applicationId) {
        if (!existing.applicationId().equals(applicationId)) throw new ListingAlreadySelectedException("该领养信息已有最终候选人");
        AdoptionApplication application = applications.findById(existing.applicationId())
                .orElseThrow(() -> new ApplicationNotFoundException("领养申请不存在"));
        return new SelectionCommandResult(new SelectionView(existing, application.applicantUserId()), false);
    }
    private IdentityGateway.CurrentUser requireGovernance() {
        IdentityGateway.CurrentUser user = identity.requireCurrentUser();
        if (!user.isActiveGovernanceAdmin()) throw new ListingAccessDeniedException("需要治理管理员权限");
        return user;
    }
    private static String normalizeNote(String note) {
        if (note == null || note.trim().isEmpty()) return null;
        String normalized = note.trim();
        if (normalized.length() > 500) throw new IllegalArgumentException("note 最大长度为 500");
        return normalized;
    }
    private static void validUuid(String value, String field) { try { UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException(field + " 必须是 UUID"); } }
    public record SelectionCommandResult(SelectionView view, boolean created) { }
}
