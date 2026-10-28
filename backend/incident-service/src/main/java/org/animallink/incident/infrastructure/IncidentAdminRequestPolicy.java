package org.animallink.incident.infrastructure;

import java.util.Optional;
import java.util.regex.Pattern;

final class IncidentAdminRequestPolicy {
    static final String SCOPE = "incident.rescue.read";
    private static final String UUID =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern LIST = Pattern.compile("^/api/v1/admin/rescues$");
    private static final Pattern DETAIL = Pattern.compile("^/api/v1/admin/rescues/" + UUID + "$");

    private IncidentAdminRequestPolicy() { }
    static Optional<String> requiredScope(String method, String path) {
        return "GET".equals(method) && path != null
                && (LIST.matcher(path).matches() || DETAIL.matcher(path).matches())
                ? Optional.of(SCOPE) : Optional.empty();
    }
    static boolean isProtected(String path) {
        return path != null && (LIST.matcher(path).matches() || DETAIL.matcher(path).matches());
    }
}
