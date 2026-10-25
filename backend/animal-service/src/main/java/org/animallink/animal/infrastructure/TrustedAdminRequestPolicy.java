package org.animallink.animal.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

final class TrustedAdminRequestPolicy {
    static final String READ_SCOPE = "community.review.read";
    static final String WRITE_SCOPE = "community.review.write";
    private static final String UUID_SEGMENT =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final List<Pattern> READ_PATHS = List.of(
            Pattern.compile("^/api/v1/admin/community-review/posts$"),
            Pattern.compile("^/api/v1/admin/community-review/posts/" + UUID_SEGMENT + "$"),
            Pattern.compile("^/api/v1/admin/community-review/comments$"),
            Pattern.compile("^/api/v1/admin/community-review/comments/" + UUID_SEGMENT + "$")
    );
    private static final List<Pattern> WRITE_PATHS = List.of(
            Pattern.compile("^/api/v1/admin/community-review/posts/" + UUID_SEGMENT + "/approve$"),
            Pattern.compile("^/api/v1/admin/community-review/posts/" + UUID_SEGMENT + "/reject$"),
            Pattern.compile("^/api/v1/admin/community-review/comments/" + UUID_SEGMENT + "/approve$"),
            Pattern.compile("^/api/v1/admin/community-review/comments/" + UUID_SEGMENT + "/reject$")
    );

    private TrustedAdminRequestPolicy() {
    }

    static Optional<String> requiredScope(String method, String path) {
        if ("GET".equals(method) && matches(READ_PATHS, path)) {
            return Optional.of(READ_SCOPE);
        }
        if ("POST".equals(method) && matches(WRITE_PATHS, path)) {
            return Optional.of(WRITE_SCOPE);
        }
        return Optional.empty();
    }

    static boolean isProtectedPath(String path) {
        return matches(READ_PATHS, path) || matches(WRITE_PATHS, path);
    }

    private static boolean matches(List<Pattern> patterns, String path) {
        return path != null && patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }
}
