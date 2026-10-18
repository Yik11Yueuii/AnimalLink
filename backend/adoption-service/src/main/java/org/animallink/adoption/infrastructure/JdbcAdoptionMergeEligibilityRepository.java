package org.animallink.adoption.infrastructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.animallink.adoption.application.AdoptionMergeEligibilityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdoptionMergeEligibilityRepository implements AdoptionMergeEligibilityRepository {
    private final JdbcTemplate jdbc;

    public JdbcAdoptionMergeEligibilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Blocker> findBlockers(String sourceAnimalId, String targetAnimalId) {
        return findBlockersFor(List.of(sourceAnimalId, targetAnimalId));
    }

    @Override
    public List<Blocker> findBlockers(String animalId) {
        return findBlockersFor(List.of(animalId));
    }

    private List<Blocker> findBlockersFor(List<String> animalIds) {
        String placeholders = String.join(",", Collections.nCopies(animalIds.size(), "?"));
        String sql = """
                SELECT DISTINCT animal_id, blocker_code
                FROM (
                    SELECT l.animal_id, 'ACTIVE_LISTING' AS blocker_code
                    FROM adoption_listing l
                    WHERE l.animal_id IN (%1$s) AND l.status IN ('DRAFT', 'PUBLISHED')
                    UNION ALL
                    SELECT l.animal_id, 'ACTIVE_APPLICATION' AS blocker_code
                    FROM adoption_application a
                    JOIN adoption_listing l ON l.id = a.listing_id
                    WHERE l.animal_id IN (%1$s) AND l.status = 'PUBLISHED'
                      AND a.status IN ('SUBMITTED', 'APPROVED')
                      AND NOT EXISTS (
                          SELECT 1 FROM adoption_selection s
                          WHERE s.application_id = a.id AND s.status = 'ACTIVE'
                      )
                    UNION ALL
                    SELECT l.animal_id, 'ACTIVE_SELECTION' AS blocker_code
                    FROM adoption_selection s
                    JOIN adoption_listing l ON l.id = s.listing_id
                    WHERE l.animal_id IN (%1$s) AND s.status = 'ACTIVE'
                      AND NOT EXISTS (
                          SELECT 1 FROM adoption_handover h WHERE h.selection_id = s.id
                      )
                    UNION ALL
                    SELECT l.animal_id, 'ACTIVE_SELECTION' AS blocker_code
                    FROM adoption_selection s
                    JOIN adoption_listing l ON l.id = s.listing_id
                    JOIN adoption_handover h ON h.selection_id = s.id
                    WHERE l.animal_id IN (%1$s) AND s.status = 'ACTIVE' AND h.status = 'COMPLETED'
                      AND NOT EXISTS (
                          SELECT 1 FROM adoption_relation r
                          WHERE r.handover_id = h.id
                            AND r.animal_id = l.animal_id
                            AND r.status IN ('ACTIVE', 'ENDED')
                      )
                    UNION ALL
                    SELECT l.animal_id, 'PENDING_HANDOVER' AS blocker_code
                    FROM adoption_handover h
                    JOIN adoption_selection s ON s.id = h.selection_id
                    JOIN adoption_listing l ON l.id = s.listing_id
                    WHERE l.animal_id IN (%1$s) AND h.status = 'PENDING'
                    UNION ALL
                    SELECT r.animal_id, 'ACTIVE_RELATION' AS blocker_code
                    FROM adoption_relation r
                    WHERE r.animal_id IN (%1$s) AND r.status = 'ACTIVE'
                ) active_blockers
                ORDER BY animal_id, blocker_code
                """.formatted(placeholders);
        List<Object> arguments = new ArrayList<>(animalIds.size() * 6);
        for (int occurrence = 0; occurrence < 6; occurrence++) {
            arguments.addAll(animalIds);
        }
        return jdbc.query(sql, (rs, row) -> new Blocker(rs.getString("animal_id"),
                BlockerCode.valueOf(rs.getString("blocker_code"))), arguments.toArray());
    }
}
