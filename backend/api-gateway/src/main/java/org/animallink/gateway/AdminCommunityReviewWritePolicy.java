package org.animallink.gateway;

import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.regex.Pattern;

final class AdminCommunityReviewWritePolicy {
    static final String SCOPE = "community.review.write";
    private static final String UUID_SEGMENT =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final List<Pattern> PATHS = List.of(
            Pattern.compile("^/animal/api/v1/admin/community-review/posts/" + UUID_SEGMENT + "/approve$"),
            Pattern.compile("^/animal/api/v1/admin/community-review/posts/" + UUID_SEGMENT + "/reject$"),
            Pattern.compile("^/animal/api/v1/admin/community-review/comments/" + UUID_SEGMENT + "/approve$"),
            Pattern.compile("^/animal/api/v1/admin/community-review/comments/" + UUID_SEGMENT + "/reject$")
    );

    private AdminCommunityReviewWritePolicy() {
    }

    static boolean matches(HttpMethod method, String path) {
        return HttpMethod.POST.equals(method) && path != null
                && PATHS.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    static String downstreamPath(String externalPath) {
        if (externalPath == null || !externalPath.startsWith("/animal/")) {
            throw new IllegalArgumentException("Unexpected Animal route");
        }
        return externalPath.substring("/animal".length());
    }
}
