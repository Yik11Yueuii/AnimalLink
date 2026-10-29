package org.animallink.adoption.infrastructure;

import java.util.Optional;
import java.util.regex.Pattern;

final class AdoptionAdminRequestPolicy {
    static final String SCOPE = "adoption.application.read";
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern LIST = Pattern.compile("^/api/v1/admin/applications$");
    private static final Pattern DETAIL = Pattern.compile("^/api/v1/admin/applications/" + UUID + "$");
    private AdoptionAdminRequestPolicy() { }
    static Optional<String> requiredScope(String method, String path) {
        return "GET".equals(method) && path != null && (LIST.matcher(path).matches() || DETAIL.matcher(path).matches())
                ? Optional.of(SCOPE) : Optional.empty();
    }
    static boolean isProtected(String path) { return path != null && (LIST.matcher(path).matches() || DETAIL.matcher(path).matches()); }
}
