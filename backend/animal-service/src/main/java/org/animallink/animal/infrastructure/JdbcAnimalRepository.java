package org.animallink.animal.infrastructure;

import org.animallink.animal.domain.AdoptionStatus;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalMedia;
import org.animallink.animal.domain.AnimalCandidateSnapshot;
import org.animallink.animal.domain.AnimalRepository;
import org.animallink.animal.domain.AnimalSex;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.CurrentContext;
import org.animallink.animal.domain.IdentityStatus;
import org.animallink.animal.domain.MediaType;
import org.animallink.animal.domain.StateConflictException;
import org.animallink.animal.domain.SterilizationStatus;
import org.animallink.animal.domain.TimelineEntry;
import org.animallink.animal.domain.Visibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

@Repository
public class JdbcAnimalRepository implements AnimalRepository {
    private static final String ANIMAL_COLUMNS = """
            SELECT id, campus_id, display_name, species, sex, coat_color,
                   distinctive_features, description, sterilization_status, typical_area,
                   identity_status, adoption_status, current_context, version, created_at, updated_at
            FROM animal
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAnimalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Animal animal) {
        jdbcTemplate.update("""
                INSERT INTO animal
                    (id, campus_id, display_name, species, sex, coat_color,
                     distinctive_features, description, sterilization_status, typical_area,
                     identity_status, adoption_status, current_context, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, animal.id(), animal.campusId(), animal.displayName(), animal.species().name(),
                animal.sex().name(), animal.coatColor(), animal.distinctiveFeatures(),
                animal.description(), animal.sterilizationStatus().name(), animal.typicalArea(),
                animal.identityStatus().name(), animal.adoptionStatus().name(),
                animal.currentContext().name(), animal.version());
    }

    @Override
    public void update(Animal animal) {
        int updated = jdbcTemplate.update("""
                UPDATE animal
                SET display_name = ?, species = ?, sex = ?, coat_color = ?,
                    distinctive_features = ?, description = ?, sterilization_status = ?,
                    typical_area = ?, identity_status = ?, adoption_status = ?, current_context = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND version = ?
                """, animal.displayName(), animal.species().name(), animal.sex().name(),
                animal.coatColor(), animal.distinctiveFeatures(), animal.description(),
                animal.sterilizationStatus().name(), animal.typicalArea(),
                animal.identityStatus().name(), animal.adoptionStatus().name(),
                animal.currentContext().name(), animal.id(), animal.version());
        if (updated != 1) {
            throw new StateConflictException("Animal 已被其他操作修改，请刷新后重试");
        }
    }

    @Override
    public Optional<Animal> findById(String animalId) {
        return optional(ANIMAL_COLUMNS + " WHERE id = ?", ANIMAL_ROW_MAPPER, animalId);
    }

    @Override
    public Optional<Animal> findActiveById(String animalId) {
        return optional(ANIMAL_COLUMNS + " WHERE id = ? AND identity_status = 'ACTIVE'",
                ANIMAL_ROW_MAPPER, animalId);
    }

    @Override
    public List<Animal> searchActive(String campusId, String query, AnimalSpecies species,
                                     int limit, int offset) {
        QueryParts parts = filters(campusId, query, species);
        return jdbcTemplate.query(ANIMAL_COLUMNS + parts.where()
                        + " ORDER BY display_name ASC, id ASC LIMIT ? OFFSET ?",
                ANIMAL_ROW_MAPPER, append(parts.args(), limit, offset));
    }

    @Override
    public long countActive(String campusId, String query, AnimalSpecies species) {
        QueryParts parts = filters(campusId, query, species);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM animal" + parts.where(),
                Long.class, parts.args().toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<AnimalMedia> findPublicMedia(String animalId) {
        return jdbcTemplate.query("""
                SELECT id, animal_id, object_key, content_type, media_type, size_bytes,
                       sort_order, visibility, created_at
                FROM animal_media
                WHERE animal_id = ? AND visibility = 'PUBLIC'
                ORDER BY sort_order ASC, id ASC
                """, MEDIA_ROW_MAPPER, animalId);
    }

    @Override
    public List<TimelineEntry> findPublicTimeline(String animalId, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT id, animal_id, source_type, source_id, entry_type, title, summary,
                       occurred_at, visibility, created_at
                FROM timeline_entry
                WHERE animal_id = ? AND visibility = 'PUBLIC'
                ORDER BY occurred_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, TIMELINE_ROW_MAPPER, animalId, limit, offset);
    }

    @Override
    public long countPublicTimeline(String animalId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM timeline_entry
                WHERE animal_id = ? AND visibility = 'PUBLIC'
                """, Long.class, animalId);
        return count == null ? 0 : count;
    }

    @Override
    public List<AnimalCandidateSnapshot> recallCandidates(String campusId, AnimalSpecies species, int limit) {
        String speciesClause = species == null ? "" : " AND a.species = ?";
        List<Object> args = new ArrayList<>();
        args.add(campusId);
        if (species != null) args.add(species.name());
        args.add(limit);
        List<CandidateBase> bases = jdbcTemplate.query("""
                SELECT a.id, a.campus_id, a.display_name, a.species, a.sex, a.coat_color,
                       a.distinctive_features, a.typical_area,
                       latest.occurred_at AS last_seen_at, latest.summary AS recent_summary
                FROM animal a
                LEFT JOIN (
                    SELECT animal_id, occurred_at, summary
                    FROM (
                        SELECT animal_id, occurred_at, summary,
                               ROW_NUMBER() OVER (PARTITION BY animal_id ORDER BY occurred_at DESC, id DESC) AS rn
                        FROM timeline_entry WHERE visibility = 'PUBLIC'
                    ) ranked WHERE rn = 1
                ) latest ON latest.animal_id = a.id
                WHERE a.campus_id = ? AND a.identity_status = 'ACTIVE'
                """ + speciesClause + " ORDER BY a.updated_at DESC, a.id ASC LIMIT ?",
                (rs, rowNum) -> new CandidateBase(rs.getString("id"), rs.getString("campus_id"),
                        rs.getString("display_name"), AnimalSpecies.valueOf(rs.getString("species")),
                        AnimalSex.valueOf(rs.getString("sex")), rs.getString("coat_color"),
                        rs.getString("distinctive_features"), rs.getString("typical_area"),
                        instant(rs, "last_seen_at"), rs.getString("recent_summary")), args.toArray());
        if (bases.isEmpty()) return List.of();

        Map<String, List<AnimalCandidateSnapshot.PublicMedia>> mediaByAnimal = new LinkedHashMap<>();
        bases.forEach(base -> mediaByAnimal.put(base.id(), new ArrayList<>()));
        String placeholders = String.join(",", Collections.nCopies(bases.size(), "?"));
        jdbcTemplate.query("""
                SELECT id, animal_id, object_key, content_type
                FROM animal_media
                WHERE visibility = 'PUBLIC' AND media_type = 'IMAGE' AND animal_id IN (
                """ + placeholders + ") ORDER BY animal_id, sort_order, id", rs -> {
            mediaByAnimal.get(rs.getString("animal_id")).add(new AnimalCandidateSnapshot.PublicMedia(
                    rs.getString("id"), rs.getString("object_key"), rs.getString("content_type")));
        }, bases.stream().map(CandidateBase::id).toArray());
        return bases.stream().map(base -> new AnimalCandidateSnapshot(base.id(), base.campusId(),
                base.displayName(), base.species(), base.sex(), base.coatColor(),
                base.distinctiveFeatures(), base.typicalArea(),
                List.copyOf(mediaByAnimal.get(base.id())), base.lastSeenAt(), base.recentSummary())).toList();
    }

    private QueryParts filters(String campusId, String query, AnimalSpecies species) {
        StringBuilder where = new StringBuilder(" WHERE campus_id = ? AND identity_status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        args.add(campusId);
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(display_name) LIKE ? OR LOWER(coat_color) LIKE ?"
                    + " OR LOWER(distinctive_features) LIKE ?)");
            String pattern = "%" + query.toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (species != null) {
            where.append(" AND species = ?");
            args.add(species.name());
        }
        return new QueryParts(where.toString(), args);
    }

    private Object[] append(List<Object> values, Object... tail) {
        List<Object> result = new ArrayList<>(values);
        result.addAll(List.of(tail));
        return result.toArray();
    }

    private <T> Optional<T> optional(String sql, RowMapper<T> mapper, Object... args) {
        return jdbcTemplate.query(sql, mapper, args).stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Animal> ANIMAL_ROW_MAPPER = (rs, rowNum) -> new Animal(
            rs.getString("id"), rs.getString("campus_id"), rs.getString("display_name"),
            AnimalSpecies.valueOf(rs.getString("species")), AnimalSex.valueOf(rs.getString("sex")),
            rs.getString("coat_color"), rs.getString("distinctive_features"),
            rs.getString("description"), SterilizationStatus.valueOf(rs.getString("sterilization_status")),
            rs.getString("typical_area"), IdentityStatus.valueOf(rs.getString("identity_status")),
            AdoptionStatus.valueOf(rs.getString("adoption_status")),
            CurrentContext.valueOf(rs.getString("current_context")), rs.getInt("version"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<AnimalMedia> MEDIA_ROW_MAPPER = (rs, rowNum) -> {
        long size = rs.getLong("size_bytes");
        Long sizeBytes = rs.wasNull() ? null : size;
        return new AnimalMedia(rs.getString("id"), rs.getString("animal_id"),
                rs.getString("object_key"), rs.getString("content_type"),
                MediaType.valueOf(rs.getString("media_type")), sizeBytes, rs.getInt("sort_order"),
                Visibility.valueOf(rs.getString("visibility")), instant(rs, "created_at"));
    };

    private static final RowMapper<TimelineEntry> TIMELINE_ROW_MAPPER = (rs, rowNum) ->
            new TimelineEntry(rs.getString("id"), rs.getString("animal_id"),
                    rs.getString("source_type"), rs.getString("source_id"),
                    rs.getString("entry_type"), rs.getString("title"), rs.getString("summary"),
                    instant(rs, "occurred_at"), Visibility.valueOf(rs.getString("visibility")),
                    instant(rs, "created_at"));

    private record QueryParts(String where, List<Object> args) {
    }

    private record CandidateBase(String id, String campusId, String displayName,
                                 AnimalSpecies species, AnimalSex sex, String coatColor,
                                 String distinctiveFeatures, String typicalArea,
                                 Instant lastSeenAt, String recentSummary) {
    }
}
