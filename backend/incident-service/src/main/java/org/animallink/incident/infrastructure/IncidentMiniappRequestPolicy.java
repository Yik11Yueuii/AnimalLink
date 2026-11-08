package org.animallink.incident.infrastructure;

import java.util.Optional;
import java.util.regex.Pattern;

/** Exact internal-only paths which accept a Gateway-authenticated Miniapp user. */
final class IncidentMiniappRequestPolicy {
    static final String SCOPE = "incident.rescue-draft.write";
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern COLLECTION = Pattern.compile("^/api/v1/internal/miniapp/rescue-drafts$");
    private static final Pattern ITEM = Pattern.compile("^/api/v1/internal/miniapp/rescue-drafts/" + UUID + "$");
    private static final Pattern SUBMIT = Pattern.compile("^/api/v1/internal/miniapp/rescue-drafts/" + UUID + "/submit$");
    private static final Pattern CLAIM = Pattern.compile("^/api/v1/internal/miniapp/rescues/" + UUID + "/claim$");
    private static final Pattern ACTIONS = Pattern.compile("^/api/v1/internal/miniapp/cases/" + UUID + "/actions$");
    private static final Pattern CASE_BY_EVENT = Pattern.compile("^/api/v1/internal/miniapp/rescues/" + UUID + "/case$");
    private IncidentMiniappRequestPolicy() { }
    static Optional<String> requiredScope(String method, String path) {
        if (path == null) return Optional.empty();
        if (CASE_BY_EVENT.matcher(path).matches() && "GET".equals(method)) return Optional.of("incident.case-action.read");
        if (ACTIONS.matcher(path).matches() && "GET".equals(method)) return Optional.of("incident.case-action.read");
        if (ACTIONS.matcher(path).matches() && "POST".equals(method)) return Optional.of("incident.case-action.write");
        if ("POST".equals(method) && CLAIM.matcher(path).matches()) return Optional.of("incident.case.claim");
        boolean allowed = ("POST".equals(method) && (COLLECTION.matcher(path).matches() || SUBMIT.matcher(path).matches()))
                || (("GET".equals(method) || "PATCH".equals(method)) && ITEM.matcher(path).matches());
        return allowed ? Optional.of(SCOPE) : Optional.empty();
    }
    static boolean isProtected(String path) { return path != null && (COLLECTION.matcher(path).matches() || ITEM.matcher(path).matches() || SUBMIT.matcher(path).matches() || CLAIM.matcher(path).matches() || ACTIONS.matcher(path).matches() || CASE_BY_EVENT.matcher(path).matches()); }
}
