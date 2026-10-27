package org.animallink.identity.domain;

import java.util.List;
import java.util.Optional;

public interface UniversityRepository {
    Optional<University> findActiveById(String universityId);

    List<University> searchActive(String province, String query, int limit, int offset);

    long countActive(String province, String query);
}
