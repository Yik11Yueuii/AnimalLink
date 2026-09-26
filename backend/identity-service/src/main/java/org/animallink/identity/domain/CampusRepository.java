package org.animallink.identity.domain;

import java.util.List;
import java.util.Optional;

public interface CampusRepository {
    Optional<Campus> findById(String id);

    Optional<Campus> findActiveById(String id);

    List<Campus> searchActive(String query, int limit, int offset);
}
