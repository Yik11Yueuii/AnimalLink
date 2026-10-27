package org.animallink.identity.application;

import org.animallink.identity.domain.Campus;
import org.animallink.identity.domain.CampusRepository;
import org.animallink.identity.domain.NotFoundException;
import org.animallink.identity.domain.University;
import org.animallink.identity.domain.UniversityRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UniversityQueryService {
    private final UniversityRepository universityRepository;
    private final CampusRepository campusRepository;

    public UniversityQueryService(UniversityRepository universityRepository,
                                  CampusRepository campusRepository) {
        this.universityRepository = universityRepository;
        this.campusRepository = campusRepository;
    }

    public UniversityPage search(String province, String query, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<University> items = universityRepository.searchActive(
                province, query, safeSize, safePage * safeSize);
        long total = universityRepository.countActive(province, query);
        return new UniversityPage(items, safePage, safeSize, total);
    }

    public Campus activeCommunity(String universityId) {
        String validId = IdRules.requireUuid(universityId, "universityId");
        universityRepository.findActiveById(validId)
                .orElseThrow(() -> new NotFoundException("高校不存在或不可用"));
        return campusRepository.findActiveCommunityByUniversityId(validId)
                .orElseThrow(() -> new NotFoundException("该高校社区尚未开放"));
    }

    public record UniversityPage(List<University> items, int page, int size, long total) {
    }
}
