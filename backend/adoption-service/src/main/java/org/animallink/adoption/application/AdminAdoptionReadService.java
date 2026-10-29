package org.animallink.adoption.application;

import org.animallink.adoption.interfaces.AdminAdoptionDtos;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminAdoptionReadService {
    private static final String SELECT = """
            SELECT a.id application_id,a.listing_id,a.applicant_user_id,a.status application_status,
              a.message,a.created_at application_created_at,a.updated_at application_updated_at,
              a.reviewer_user_id,a.reviewed_at,a.review_comment,
              l.animal_id,l.status listing_status,l.title,l.description listing_description,
              l.created_at listing_created_at,l.updated_at listing_updated_at,l.published_at,l.closed_at
            FROM adoption_application a JOIN adoption_listing l ON l.id=a.listing_id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    public AdminAdoptionReadService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AdminAdoptionDtos.Page search(String status, String keyword, String sort, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid pagination");
        StringBuilder base = new StringBuilder(" WHERE 1=1"); var parameters = new MapSqlParameterSource();
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            if (normalized.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword too long");
            base.append(" AND (LOWER(l.title) LIKE :keyword OR LOWER(a.message) LIKE :keyword OR a.id=:exact OR l.id=:exact OR l.animal_id=:exact)");
            parameters.addValue("keyword", "%" + normalized.replace("%", "\\%").replace("_", "\\_") + "%").addValue("exact", normalized);
        }
        StringBuilder filtered = new StringBuilder(base);
        if (status != null && !status.isBlank()) {
            if (!List.of("SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
            filtered.append(" AND a.status=:status"); parameters.addValue("status", status);
        }
        String order = switch (sort == null ? "UPDATED_DESC" : sort) {
            case "UPDATED_DESC" -> "a.updated_at DESC,a.id DESC";
            case "CREATED_DESC" -> "a.created_at DESC,a.id DESC";
            case "CREATED_ASC" -> "a.created_at ASC,a.id ASC";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid sort");
        };
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM adoption_application a JOIN adoption_listing l ON l.id=a.listing_id" + filtered, parameters, Long.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String value : List.of("SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN")) counts.put(value, 0L);
        jdbc.query("SELECT a.status,COUNT(*) amount FROM adoption_application a JOIN adoption_listing l ON l.id=a.listing_id" + base + " GROUP BY a.status",
                parameters, rs -> { counts.put(rs.getString("status"), rs.getLong("amount")); });
        parameters.addValue("limit", size).addValue("offset", page * size);
        var items = jdbc.query(SELECT + filtered + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", parameters, (rs, row) -> map(rs));
        return new AdminAdoptionDtos.Page(items, page, size, total == null ? 0 : total, Map.copyOf(counts));
    }

    public AdminAdoptionDtos.Application detail(String applicationId) {
        try { UUID.fromString(applicationId); } catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid application id"); }
        var rows = jdbc.query(SELECT + " WHERE a.id=:id", new MapSqlParameterSource("id", applicationId), (rs, row) -> map(rs));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "application not found");
        return rows.getFirst();
    }

    private static AdminAdoptionDtos.Application map(ResultSet rs) throws SQLException {
        String listingId = rs.getString("listing_id");
        var listing = new AdminAdoptionDtos.Listing(listingId, rs.getString("animal_id"), rs.getString("listing_status"),
                rs.getString("title"), rs.getString("listing_description"), instant(rs, "listing_created_at"),
                instant(rs, "listing_updated_at"), instant(rs, "published_at"), instant(rs, "closed_at"));
        return new AdminAdoptionDtos.Application(rs.getString("application_id"), listingId,
                reference(rs.getString("applicant_user_id")), rs.getString("application_status"), rs.getString("message"),
                instant(rs, "application_created_at"), instant(rs, "application_updated_at"), reference(rs.getString("reviewer_user_id")),
                instant(rs, "reviewed_at"), rs.getString("review_comment"), listing);
    }
    private static String reference(String value) { return value == null ? null : "user-…" + value.substring(Math.max(0, value.length() - 8)); }
    private static Instant instant(ResultSet rs, String name) throws SQLException { var value = rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
}
