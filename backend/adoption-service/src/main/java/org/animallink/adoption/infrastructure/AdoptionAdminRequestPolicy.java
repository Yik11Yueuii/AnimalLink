package org.animallink.adoption.infrastructure;

import java.util.Optional;
import java.util.regex.Pattern;

final class AdoptionAdminRequestPolicy {
    static final String SCOPE = "adoption.application.read";
    static final String REVIEW = "adoption.application.review";
    static final String SELECTION = "adoption.selection.manage";
    static final String HANDOVER = "adoption.handover.manage";
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern LIST = Pattern.compile("^/api/v1/admin/applications$");
    private static final Pattern DETAIL = Pattern.compile("^/api/v1/admin/applications/" + UUID + "$");
    private static final Pattern REVIEW_PATH = Pattern.compile("^/api/v1/admin/applications/" + UUID + "/(approve|reject)$");
    private static final Pattern SELECTION_PATH = Pattern.compile("^/api/v1/admin/listings/" + UUID + "/selection$");
    private static final Pattern HANDOVER_PATH = Pattern.compile("^/api/v1/admin/selections/" + UUID + "/handover$");
    private static final Pattern HANDOVER_ACTION = Pattern.compile("^/api/v1/admin/handovers/" + UUID + "/(complete|cancel)$");
    private AdoptionAdminRequestPolicy() { }
    static Optional<String> requiredScope(String method, String path) {
        if (path == null) return Optional.empty();
        if ("POST".equals(method) && REVIEW_PATH.matcher(path).matches()) return Optional.of(REVIEW);
        if (("POST".equals(method) || "GET".equals(method)) && SELECTION_PATH.matcher(path).matches()) return Optional.of(SELECTION);
        if (("POST".equals(method) || "GET".equals(method)) && HANDOVER_PATH.matcher(path).matches()) return Optional.of(HANDOVER);
        if ("POST".equals(method) && HANDOVER_ACTION.matcher(path).matches()) return Optional.of(HANDOVER);
        return "GET".equals(method) && path != null && (LIST.matcher(path).matches() || DETAIL.matcher(path).matches())
                ? Optional.of(SCOPE) : Optional.empty();
    }
    static boolean isProtected(String path) { return path != null && (LIST.matcher(path).matches() || DETAIL.matcher(path).matches()
            || REVIEW_PATH.matcher(path).matches() || SELECTION_PATH.matcher(path).matches()
            || HANDOVER_PATH.matcher(path).matches() || HANDOVER_ACTION.matcher(path).matches()); }
}
