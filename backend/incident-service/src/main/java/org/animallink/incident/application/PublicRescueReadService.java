package org.animallink.incident.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Anonymous rescue projection. It deliberately does not reuse the member or
 * governance rescue views: incident narratives, location text, identities,
 * evidence and action history are never selected here.
 */
@Service
public class PublicRescueReadService {
    private static final int MAX_PAGE_SIZE = 50;
    private final JdbcTemplate jdbc;
    private final RestClient animal;

    public PublicRescueReadService(JdbcTemplate jdbc,
                                   @Qualifier("animalRestClient") RestClient animal) {
        this.jdbc = jdbc;
        this.animal = animal;
    }

    public Page search(String campusId, String status, int page, int size) {
        requireUuid(campusId, "campusId");
        validatePage(page, size);
        String normalizedStatus = normalizeStatus(status);
        StringBuilder where = new StringBuilder(" WHERE e.campus_id=? AND e.status='VERIFIED'");
        List<Object> args = new ArrayList<>(List.of(campusId));
        if (normalizedStatus != null) {
            where.append(" AND ").append(statusPredicate(normalizedStatus));
        }
        String select = select() + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + select + ") public_rescue_count",
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Item> items = jdbc.queryForList(select
                        + " ORDER BY COALESCE(c.updated_at,e.updated_at) DESC,e.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray()).stream().map(this::item).toList();
        return new Page(items, page, size, total == null ? 0 : total);
    }

    public Detail detail(String campusId, String eventId) {
        requireUuid(campusId, "campusId");
        requireUuid(eventId, "eventId");
        List<Map<String, Object>> rows = jdbc.queryForList(select()
                + " WHERE e.id=? AND e.campus_id=? AND e.status='VERIFIED'", eventId, campusId);
        if (rows.isEmpty()) {
            // Do not disclose whether an identifier belongs to a rejected or archived Event.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "public rescue not found");
        }
        Item rescue = item(rows.getFirst());
        return new Detail(rescue, progress(rows.getFirst(), rescue.rescueStatus()));
    }

    private Item item(Map<String, Object> row) {
        String campusId = text(row, "campus_id");
        String animalId = text(row, "animal_id");
        String rescueStatus = rescueStatus(text(row, "case_status"), text(row, "claim_status"));
        return new Item(text(row, "event_id"), campusId, rescueStatus,
                instant(row, "occurred_at"), instant(row, "reported_at"),
                instant(row, "case_updated_at") == null ? instant(row, "event_updated_at") : instant(row, "case_updated_at"),
                publicSummary(rescueStatus), publicAnimal(campusId, animalId));
    }

    private List<Progress> progress(Map<String, Object> row, String rescueStatus) {
        List<Progress> progress = new ArrayList<>();
        progress.add(new Progress("VERIFIED", "事件已完成核查", instant(row, "event_updated_at")));
        Instant claimedAt = instant(row, "claimed_at");
        if (claimedAt != null) {
            progress.add(new Progress("CLAIMED", "已由志愿者接手处理", claimedAt));
        }
        Instant resultAt = instant(row, "result_submitted_at");
        String resultCode = text(row, "result_code");
        if (resultAt != null && resultCode != null) {
            progress.add(new Progress(resultCode, outcomeLabel(resultCode), resultAt));
        }
        Instant closedAt = instant(row, "closed_at");
        if (closedAt != null) {
            progress.add(new Progress(rescueStatus, terminalLabel(rescueStatus), closedAt));
        }
        return List.copyOf(progress);
    }

    private Animal publicAnimal(String campusId, String animalId) {
        if (animalId == null) return null;
        try {
            Map<?, ?> response = animal.get().uri("/api/v1/public/animals/{animalId}", animalId)
                    .retrieve().body(Map.class);
            if (response == null || !campusId.equals(String.valueOf(response.get("campusId")))) return null;
            Object id = response.get("id");
            Object displayName = response.get("displayName");
            Object species = response.get("species");
            if (id == null || displayName == null || species == null) return null;
            return new Animal(String.valueOf(id), String.valueOf(displayName), String.valueOf(species));
        } catch (RuntimeException unavailableOrNotPublic) {
            // A linkage is public only when the Animal service independently permits it.
            return null;
        }
    }

    private static String select() {
        return "SELECT e.id event_id,e.campus_id,e.animal_id,e.occurred_at,e.reported_at,e.updated_at event_updated_at,"
                + "c.status case_status,c.claim_status,c.claimed_at,c.result_code,c.result_submitted_at,c.closed_at,c.updated_at case_updated_at "
                + "FROM event e LEFT JOIN animal_case c ON c.event_id=e.id";
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status) {
            case "WAITING_CLAIM", "IN_PROGRESS", "RESOLVED", "CLOSED", "CLOSED_UNRESOLVED", "CANCELLED" -> status;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid public rescue status");
        };
    }

    private static String statusPredicate(String status) {
        return switch (status) {
            case "WAITING_CLAIM" -> "(c.id IS NULL OR c.claim_status='WAITING_CLAIM')";
            case "IN_PROGRESS" -> "c.status IN ('OPEN','ACTIVE') AND c.claim_status='CLAIMED'";
            default -> "c.status='" + status + "'";
        };
    }

    private static String rescueStatus(String caseStatus, String claimStatus) {
        if (caseStatus == null || "WAITING_CLAIM".equals(claimStatus)) return "WAITING_CLAIM";
        if (List.of("OPEN", "ACTIVE").contains(caseStatus) && "CLAIMED".equals(claimStatus)) return "IN_PROGRESS";
        if (List.of("RESOLVED", "CLOSED", "CLOSED_UNRESOLVED", "CANCELLED").contains(caseStatus)) return caseStatus;
        return "WAITING_CLAIM";
    }

    private static String publicSummary(String status) {
        return switch (status) {
            case "WAITING_CLAIM" -> "事件已核查，正在等待志愿者处理。";
            case "IN_PROGRESS" -> "志愿者正在处理该已核查事件。";
            case "RESOLVED" -> "处理结果已登记，等待完成归档。";
            case "CLOSED" -> "该救助已完成归档。";
            case "CLOSED_UNRESOLVED" -> "该救助已结束，未形成已解决结果。";
            case "CANCELLED" -> "该救助已取消。";
            default -> "已核查救助事件。";
        };
    }

    private static String outcomeLabel(String code) {
        return switch (code) {
            case "CONTINUE_OBSERVATION" -> "继续观察";
            case "RESOLVED" -> "处理结果：已解决";
            case "UNRESOLVED" -> "处理结果：未解决";
            case "CANCELLED" -> "处理结果：已取消";
            default -> "处理结果已登记";
        };
    }

    private static String terminalLabel(String status) {
        return switch (status) {
            case "CLOSED" -> "救助已完成归档";
            case "CLOSED_UNRESOLVED" -> "救助已结束，未解决";
            case "CANCELLED" -> "救助已取消";
            default -> "救助状态已更新";
        };
    }

    private static void requireUuid(String value, String field) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + field);
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid pagination");
        }
    }

    private static String text(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    public record Animal(String id, String displayName, String species) { }
    public record Item(String eventId, String campusId, String rescueStatus, Instant occurredAt,
                       Instant reportedAt, Instant updatedAt, String publicSummary, Animal animal) { }
    public record Progress(String stage, String label, Instant occurredAt) { }
    public record Detail(Item rescue, List<Progress> progress) { }
    public record Page(List<Item> items, int page, int size, long total) { }
}
