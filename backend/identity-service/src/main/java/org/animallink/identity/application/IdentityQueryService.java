package org.animallink.identity.application;

import org.animallink.identity.domain.Campus;
import org.animallink.identity.domain.CampusMembershipRepository;
import org.animallink.identity.domain.CampusMembership;
import org.animallink.identity.domain.CampusMembershipView;
import org.animallink.identity.domain.CampusRepository;
import org.animallink.identity.domain.NotFoundException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class IdentityQueryService {
    private final CurrentUserProvider currentUserProvider;
    private final CampusRepository campusRepository;
    private final CampusMembershipRepository membershipRepository;
    private final UserRepository userRepository;

    public IdentityQueryService(CurrentUserProvider currentUserProvider,
                                CampusRepository campusRepository,
                                CampusMembershipRepository membershipRepository,
                                UserRepository userRepository) {
        this.currentUserProvider = currentUserProvider;
        this.campusRepository = campusRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
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

    public Campus getCampusForServiceValidation(String campusId) {
        IdRules.requireUuid(campusId, "campusId");
        return campusRepository.findById(campusId)
                .orElseThrow(() -> new NotFoundException("Campus 不存在"));
    }

    public List<CampusMembershipView> currentUserMemberships() {
        return membershipRepository.findMembershipsByUserId(currentUser().id());
    }

    public Optional<CampusMembership> membershipFact(String userId, String campusId) {
        IdRules.requireUuid(userId, "userId");
        IdRules.requireUuid(campusId, "campusId");
        return membershipRepository.findByUserAndCampus(userId, campusId);
    }

    public List<UserAccount> userSummaries(List<String> userIds) {
        List<String> validIds = userIds.stream()
                .map(id -> IdRules.requireUuid(id, "userId"))
                .distinct()
                .toList();
        return userRepository.findUsersByIds(validIds);
    }
}
