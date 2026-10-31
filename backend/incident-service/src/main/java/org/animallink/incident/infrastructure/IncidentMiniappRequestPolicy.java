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
    private IncidentMiniappRequestPolicy() { }
    static Optional<String> requiredScope(String method, String path) {
        if (path == null) return Optional.empty();
        boolean allowed = ("POST".equals(method) && (COLLECTION.matcher(path).matches() || SUBMIT.matcher(path).matches()))
                || (("GET".equals(method) || "PATCH".equals(method)) && ITEM.matcher(path).matches());
        return allowed ? Optional.of(SCOPE) : Optional.empty();
    }
    static boolean isProtected(String path) { return path != null && (COLLECTION.matcher(path).matches() || ITEM.matcher(path).matches() || SUBMIT.matcher(path).matches()); }
}
