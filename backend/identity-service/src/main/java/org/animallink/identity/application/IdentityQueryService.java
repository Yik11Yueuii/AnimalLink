package org.animallink.identity.application;

import org.animallink.identity.domain.Campus;
import org.animallink.identity.domain.CampusMembershipRepository;
import org.animallink.identity.domain.CampusMembershipView;
import org.animallink.identity.domain.CampusRepository;
import org.animallink.identity.domain.NotFoundException;
import org.animallink.identity.domain.UserAccount;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IdentityQueryService {
    private final CurrentUserProvider currentUserProvider;
    private final CampusRepository campusRepository;
    private final CampusMembershipRepository membershipRepository;

    public IdentityQueryService(CurrentUserProvider currentUserProvider,
                                CampusRepository campusRepository,
                                CampusMembershipRepository membershipRepository) {
        this.currentUserProvider = currentUserProvider;
        this.campusRepository = campusRepository;
        this.membershipRepository = membershipRepository;
    }

    public UserAccount currentUser() {
        return currentUserProvider.requireCurrentUser();
    }

    public List<Campus> searchCampuses(String query, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return campusRepository.searchActive(query, safeSize, safePage * safeSize);
    }

    public Campus getCampus(String campusId) {
        IdRules.requireUuid(campusId, "campusId");
        return campusRepository.findActiveById(campusId)
                .orElseThrow(() -> new NotFoundException("Campus 不存在或不可用"));
    }

    public List<CampusMembershipView> currentUserMemberships() {
        return membershipRepository.findMembershipsByUserId(currentUser().id());
    }
}
