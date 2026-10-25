package org.animallink.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCommunityReviewWritePolicyTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void permitsOnlyFourExactPostShapes() {
        for (String path : new String[] {
                "/animal/api/v1/admin/community-review/posts/" + ID + "/approve",
                "/animal/api/v1/admin/community-review/posts/" + ID + "/reject",
                "/animal/api/v1/admin/community-review/comments/" + ID + "/approve",
                "/animal/api/v1/admin/community-review/comments/" + ID + "/reject"}) {
            assertThat(AdminCommunityReviewWritePolicy.matches(HttpMethod.POST, path)).isTrue();
        }

        for (String path : new String[] {
                "/animal/api/v1/admin/community-review/posts/" + ID,
                "/animal/api/v1/admin/community-review/posts/" + ID + "/approve/",
                "/animal/api/v1/admin/community-review/posts/not-a-uuid/approve",
                "/animal/api/v1/admin/community-review/posts/" + ID + "/hide",
                "/animal/api/v1/admin/community-review/posts/" + ID + "/approve/extra",
                "/animal//api/v1/admin/community-review/posts/" + ID + "/approve"}) {
            assertThat(AdminCommunityReviewWritePolicy.matches(HttpMethod.POST, path)).isFalse();
        }
        assertThat(AdminCommunityReviewWritePolicy.matches(HttpMethod.GET,
                "/animal/api/v1/admin/community-review/posts/" + ID + "/approve")).isFalse();
        assertThat(AdminCommunityReviewWritePolicy.matches(HttpMethod.PATCH,
                "/animal/api/v1/admin/community-review/posts/" + ID + "/approve")).isFalse();
    }
}
