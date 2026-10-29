package org.animallink.gateway;

import org.springframework.http.HttpMethod;

import java.util.Optional;
import java.util.regex.Pattern;

final class AdminGovernanceReadPolicy {
    static final String INCIDENT_SCOPE = "incident.rescue.read";
    static final String ADOPTION_SCOPE = "adoption.application.read";
    private static final String UUID =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern INCIDENT_LIST = Pattern.compile("^/incident/api/v1/admin/rescues$");
    private static final Pattern INCIDENT_DETAIL = Pattern.compile("^/incident/api/v1/admin/rescues/" + UUID + "$");
    private static final Pattern ADOPTION_LIST = Pattern.compile("^/adoption/api/v1/admin/applications$");
    private static final Pattern ADOPTION_DETAIL = Pattern.compile("^/adoption/api/v1/admin/applications/" + UUID + "$");

    private AdminGovernanceReadPolicy() {
    }

    static Optional<Binding> binding(HttpMethod method, String externalPath) {
        if (!HttpMethod.GET.equals(method) || externalPath == null) return Optional.empty();
        if (INCIDENT_LIST.matcher(externalPath).matches() || INCIDENT_DETAIL.matcher(externalPath).matches()) {
            return Optional.of(new Binding("incident-service", INCIDENT_SCOPE,
                    externalPath.substring("/incident".length())));
        }
        if (ADOPTION_LIST.matcher(externalPath).matches() || ADOPTION_DETAIL.matcher(externalPath).matches()) {
            return Optional.of(new Binding("adoption-service", ADOPTION_SCOPE,
                    externalPath.substring("/adoption".length())));
        }
        return Optional.empty();
    }

    static boolean matches(HttpMethod method, String externalPath) {
        return binding(method, externalPath).isPresent();
    }

    record Binding(String target, String scope, String downstreamPath) {
    }
}
