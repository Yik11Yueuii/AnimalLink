package org.animallink.adoption.application;

import java.util.List;
import org.animallink.adoption.domain.AdoptionFollowUp;

public interface AdoptionFollowUpRepository {
    void insert(AdoptionFollowUp followUp);
    List<AdoptionFollowUp> findByRelationId(String relationId, int limit, int offset);
    long countByRelationId(String relationId);
}
