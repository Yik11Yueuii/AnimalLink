package org.animallink.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCommunityReviewReadPolicyTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void permitsOnlyFourExactGetShapes() {
        assertThat(AdminCommunityReviewReadPolicy.matches(HttpMethod.GET,
                "/animal/api/v1/admin/community-review/posts")).isTrue();
        assertThat(AdminCommunityReviewReadPolicy.matches(HttpMethod.GET,
                "/animal/api/v1/admin/community-review/posts/" + ID)).isTrue();
        assertThat(AdminCommunityReviewReadPolicy.matches(HttpMethod.GET,
                "/animal/api/v1/admin/community-review/comments")).isTrue();
        assertThat(AdminCommunityReviewReadPolicy.matches(HttpMethod.GET,
                "/animal/api/v1/admin/community-review/comments/" + ID)).isTrue();

        for (String path : new String[] {
                "/animal/api/v1/admin/community-review/posts/",
                "/animal/api/v1/admin/community-review/posts/not-a-uuid",
                "/animal/api/v1/admin/community-review/posts/" + ID + "/extra",
                "/animal/api/v1/admin/posts",
                "/animal/api/v1/admin/community-review",
                "/animal//api/v1/admin/community-review/posts"}) {
            assertThat(AdminCommunityReviewReadPolicy.matches(HttpMethod.GET, path)).isFalse();
        }
        assertThat(AdminCommunityReviewReadPolicy.matches(HttpMethod.HEAD,
                "/animal/api/v1/admin/community-review/posts")).isFalse();
        assertThat(AdminCommunityReviewReadPolicy.matches(HttpMethod.POST,
                "/animal/api/v1/admin/community-review/posts")).isFalse();
    }
}
