package org.animallink.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class AdminGovernanceReadPolicyTest {
    @Test
    void allowsOnlyFourExactGetRoutesAndBindsStripPrefixPath() {
        String id = "00000000-0000-0000-0000-000000000001";
        assertThat(AdminGovernanceReadPolicy.binding(HttpMethod.GET, "/incident/api/v1/admin/rescues").orElseThrow())
                .extracting(AdminGovernanceReadPolicy.Binding::target, AdminGovernanceReadPolicy.Binding::scope,
                        AdminGovernanceReadPolicy.Binding::downstreamPath)
                .containsExactly("incident-service", "incident.rescue.read", "/api/v1/admin/rescues");
        assertThat(AdminGovernanceReadPolicy.binding(HttpMethod.GET, "/incident/api/v1/admin/rescues/" + id)).isPresent();
        assertThat(AdminGovernanceReadPolicy.binding(HttpMethod.GET, "/adoption/api/v1/admin/applications")).isPresent();
        assertThat(AdminGovernanceReadPolicy.binding(HttpMethod.GET, "/adoption/api/v1/admin/applications/" + id)).isPresent();
        for (String path : new String[] { "/incident/api/v1/admin/rescues/", "/incident/api/v1/admin/rescues/" + id + "/extra",
                "/adoption/api/v1/admin/applications/not-a-uuid", "/adoption/api/v1/admin/listings" }) {
            assertThat(AdminGovernanceReadPolicy.binding(HttpMethod.GET, path)).isEmpty();
        }
        assertThat(AdminGovernanceReadPolicy.binding(HttpMethod.POST, "/incident/api/v1/admin/rescues")).isEmpty();
        assertThat(AdminGovernanceReadPolicy.binding(HttpMethod.HEAD, "/adoption/api/v1/admin/applications")).isEmpty();
    }
}
