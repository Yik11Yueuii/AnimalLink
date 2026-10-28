package org.animallink.incident.application;

import org.animallink.incident.interfaces.AdminRescueDtos;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdminRescueReadService {
    private static final String SELECT = """
            SELECT e.id event_id,c.id case_id,e.campus_id,e.animal_id,e.status event_status,
              c.status case_status,c.claim_status,c.owner_user_id,e.abnormality_summary,e.description,
              e.public_location_description,e.occurred_at,e.reported_at,e.updated_at event_updated_at,
              c.updated_at case_updated_at,c.claimed_at,c.result_submitted_at,c.closed_at,
              c.result_code,c.result_summary,
              (SELECT COUNT(*) FROM evidence ev WHERE ev.event_id=e.id) evidence_count,
              (SELECT COUNT(*) FROM case_action ca WHERE ca.case_id=c.id) action_count
            FROM event e LEFT JOIN animal_case c ON c.event_id=e.id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    public AdminRescueReadService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AdminRescueDtos.Page search(String campusId, String status, String keyword,
                                       String sort, int page, int size) {
        try { UUID.fromString(campusId); } catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid campus id"); }
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid pagination");
        StringBuilder where = new StringBuilder(" WHERE e.campus_id=:campusId");
        var parameters = new MapSqlParameterSource("campusId", campusId);
        if (status != null && !status.isBlank()) {
            if (!List.of("WAITING_CLAIM", "IN_PROGRESS", "COMPLETED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid rescue status");
            where.append(switch (status) {
                case "WAITING_CLAIM" -> " AND (c.id IS NULL OR c.claim_status='WAITING_CLAIM')";
                case "IN_PROGRESS" -> " AND c.status IN ('OPEN','ACTIVE') AND c.claim_status='CLAIMED'";
                default -> " AND c.status IN ('RESOLVED','CLOSED','CLOSED_UNRESOLVED','CANCELLED')";
            });
        }
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            if (normalized.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword too long");
            where.append(" AND (LOWER(e.description) LIKE :keyword OR LOWER(COALESCE(e.abnormality_summary,'')) LIKE :keyword OR LOWER(e.public_location_description) LIKE :keyword OR e.id=:exactKeyword OR c.id=:exactKeyword)");
            parameters.addValue("keyword", "%" + normalized.replace("%", "\\%").replace("_", "\\_") + "%").addValue("exactKeyword", normalized);
        }
        String order = switch (sort == null ? "UPDATED_DESC" : sort) {
            case "REPORTED_DESC" -> "e.reported_at DESC,e.id DESC";
            case "OCCURRED_DESC" -> "e.occurred_at DESC,e.id DESC";
            case "UPDATED_DESC" -> "COALESCE(c.updated_at,e.updated_at) DESC,e.id DESC";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid sort");
        };
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM event e LEFT JOIN animal_case c ON c.event_id=e.id" + where,
                parameters, Long.class);
        parameters.addValue("limit", size).addValue("offset", page * size);
        List<AdminRescueDtos.RescueItem> items = jdbc.query(SELECT + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", parameters,
                (rs, row) -> item(rs));
        return new AdminRescueDtos.Page(items, page, size, total == null ? 0 : total);
    }

    public AdminRescueDtos.RescueDetail detail(String eventId) {
        try { UUID.fromString(eventId); } catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid event id"); }
        var rows = jdbc.query(SELECT + " WHERE e.id=:eventId", new MapSqlParameterSource("eventId", eventId), (rs, row) -> new DetailRow(item(rs), instant(rs, "claimed_at"), instant(rs, "result_submitted_at"), instant(rs, "closed_at")));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "rescue not found");
        DetailRow row = rows.getFirst();
        List<AdminRescueDtos.Progress> progress = row.item().caseId() == null ? List.of() : jdbc.query("""
                SELECT id,description,occurred_at,created_at,result_code FROM case_action
                WHERE case_id=:caseId ORDER BY occurred_at ASC,id ASC
                """, new MapSqlParameterSource("caseId", row.item().caseId()), (rs, index) ->
                new AdminRescueDtos.Progress(rs.getString("id"), rs.getString("description"),
                        instant(rs, "occurred_at"), instant(rs, "created_at"), rs.getString("result_code")));
        return new AdminRescueDtos.RescueDetail(row.item(), row.claimedAt(), row.resultSubmittedAt(), row.closedAt(), progress);
    }

    private static AdminRescueDtos.RescueItem item(ResultSet rs) throws SQLException {
        String caseId = rs.getString("case_id"); String caseStatus = rs.getString("case_status"); String claim = rs.getString("claim_status");
        String rescueStatus = caseId == null || "WAITING_CLAIM".equals(claim) ? "WAITING_CLAIM"
                : List.of("OPEN", "ACTIVE").contains(caseStatus) ? "IN_PROGRESS" : "COMPLETED";
        Instant eventUpdated = instant(rs, "event_updated_at"), caseUpdated = instant(rs, "case_updated_at");
        return new AdminRescueDtos.RescueItem(rs.getString("event_id"), caseId, rs.getString("campus_id"), rs.getString("animal_id"),
                rescueStatus, rs.getString("event_status"), caseStatus, claim, rs.getString("owner_user_id") != null,
                rs.getString("abnormality_summary"), rs.getString("description"), rs.getString("public_location_description"),
                instant(rs, "occurred_at"), instant(rs, "reported_at"), rs.getLong("evidence_count"), rs.getLong("action_count"),
                rs.getString("result_code"), rs.getString("result_summary"), caseUpdated == null ? eventUpdated : caseUpdated);
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException { var value = rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private record DetailRow(AdminRescueDtos.RescueItem item, Instant claimedAt, Instant resultSubmittedAt, Instant closedAt) { }
}
