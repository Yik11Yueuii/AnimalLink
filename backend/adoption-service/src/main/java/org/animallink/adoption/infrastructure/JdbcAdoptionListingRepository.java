package org.animallink.adoption.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.animallink.adoption.application.AdoptionListingRepository;
import org.animallink.adoption.domain.AdoptionListing;
import org.animallink.adoption.domain.ListingAlreadyExistsException;
import org.animallink.adoption.domain.ListingStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdoptionListingRepository implements AdoptionListingRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<AdoptionListing> ROW = (rs, row) -> new AdoptionListing(rs.getString("id"),
            rs.getString("animal_id"), ListingStatus.valueOf(rs.getString("status")), rs.getString("title"),
            rs.getString("description"), rs.getString("publisher_user_id"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());
    public JdbcAdoptionListingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void insert(AdoptionListing listing) {
        try {
            jdbc.update("INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,published_at,closed_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    listing.id(), listing.animalId(), listing.status().name(), listing.title(), listing.description(), listing.publisherUserId(),
                    Timestamp.from(listing.createdAt()), Timestamp.from(listing.updatedAt()), null, null);
        } catch (DuplicateKeyException e) { throw new ListingAlreadyExistsException("该动物已存在未关闭的领养信息"); }
    }
    public Optional<AdoptionListing> findById(String id) { return jdbc.query("SELECT * FROM adoption_listing WHERE id=?", ROW, id).stream().findFirst(); }
    public Optional<AdoptionListing> findByIdForUpdate(String id) { return jdbc.query("SELECT * FROM adoption_listing WHERE id=? FOR UPDATE", ROW, id).stream().findFirst(); }
    public List<AdoptionListing> findPublished(int limit, int offset) { return jdbc.query("SELECT * FROM adoption_listing WHERE status='PUBLISHED' ORDER BY published_at DESC,id DESC LIMIT ? OFFSET ?", ROW, limit, offset); }
    public long countPublished() { Long value = jdbc.queryForObject("SELECT COUNT(*) FROM adoption_listing WHERE status='PUBLISHED'", Long.class); return value == null ? 0L : value; }
    public boolean publish(String id, java.time.Instant updatedAt, java.time.Instant publishedAt) { return jdbc.update("UPDATE adoption_listing SET status='PUBLISHED',updated_at=?,published_at=? WHERE id=? AND status='DRAFT'", Timestamp.from(updatedAt), Timestamp.from(publishedAt), id) == 1; }
    public boolean close(String id, java.time.Instant updatedAt, java.time.Instant closedAt) { return jdbc.update("UPDATE adoption_listing SET status='CLOSED',updated_at=?,closed_at=? WHERE id=? AND status IN ('DRAFT','PUBLISHED')", Timestamp.from(updatedAt), Timestamp.from(closedAt), id) == 1; }
}
