package org.animallink.incident.infrastructure;

import java.util.Optional;
import java.util.regex.Pattern;

final class IncidentAdminRequestPolicy {
    static final String SCOPE = "incident.rescue.read";
    static final String VERIFY_SCOPE = "incident.event.verify";
    private static final String UUID =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern LIST = Pattern.compile("^/api/v1/admin/rescues$");
    private static final Pattern DETAIL = Pattern.compile("^/api/v1/admin/rescues/" + UUID + "$");
    private static final Pattern VERIFY = Pattern.compile("^/api/v1/admin/rescues/" + UUID + "/verify$");

    private IncidentAdminRequestPolicy() { }
    static Optional<String> requiredScope(String method, String path) {
        if ("POST".equals(method) && path != null && VERIFY.matcher(path).matches()) return Optional.of(VERIFY_SCOPE);
        return "GET".equals(method) && path != null
                && (LIST.matcher(path).matches() || DETAIL.matcher(path).matches())
                ? Optional.of(SCOPE) : Optional.empty();
    }
    static boolean isProtected(String path) {
        return path != null && (LIST.matcher(path).matches() || DETAIL.matcher(path).matches() || VERIFY.matcher(path).matches());
    }
}
