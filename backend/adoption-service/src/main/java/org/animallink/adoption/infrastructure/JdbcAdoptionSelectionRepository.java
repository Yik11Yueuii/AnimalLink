package org.animallink.adoption.infrastructure;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.animallink.adoption.application.AdoptionSelectionRepository;
import org.animallink.adoption.domain.AdoptionSelection;
import org.animallink.adoption.domain.SelectionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdoptionSelectionRepository implements AdoptionSelectionRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<AdoptionSelection> ROW = (rs, row) -> new AdoptionSelection(
            rs.getString("id"), rs.getString("listing_id"), rs.getString("application_id"),
            SelectionStatus.valueOf(rs.getString("status")), rs.getString("selected_by_user_id"),
            rs.getTimestamp("selected_at").toInstant(), rs.getString("note"), rs.getString("cancelled_by_user_id"),
            rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(), rs.getString("cancel_reason"));
    public JdbcAdoptionSelectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void insert(AdoptionSelection selection) {
        jdbc.update("INSERT INTO adoption_selection(id,listing_id,application_id,status,selected_by_user_id,selected_at,note,cancelled_by_user_id,cancelled_at,cancel_reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
                selection.id(), selection.listingId(), selection.applicationId(), selection.status().name(), selection.selectedByUserId(),
                Timestamp.from(selection.selectedAt()), selection.note(), null, null, null);
    }
    public Optional<AdoptionSelection> findActiveByListingId(String listingId) {
        return jdbc.query("SELECT * FROM adoption_selection WHERE listing_id=? AND status='ACTIVE'", ROW, listingId).stream().findFirst();
    }
    public Set<String> findActiveApplicationIds(Collection<String> applicationIds) {
        if (applicationIds.isEmpty()) return Set.of();
        String placeholders = applicationIds.stream().map(value -> "?").collect(Collectors.joining(","));
        List<String> ids = jdbc.queryForList("SELECT application_id FROM adoption_selection WHERE status='ACTIVE' AND application_id IN (" + placeholders + ")", String.class, applicationIds.toArray());
        return Set.copyOf(ids);
    }
}
