package org.animallink.gateway;

import org.springframework.http.HttpMethod;

import java.util.Optional;
import java.util.regex.Pattern;

final class AdminGovernanceReadPolicy {
    static final String INCIDENT_SCOPE = "incident.rescue.read";
    static final String INCIDENT_VERIFY_SCOPE = "incident.event.verify";
    static final String ADOPTION_SCOPE = "adoption.application.read";
    static final String ADOPTION_REVIEW_SCOPE = "adoption.application.review";
    static final String ADOPTION_SELECTION_SCOPE = "adoption.selection.manage";
    static final String ADOPTION_HANDOVER_SCOPE = "adoption.handover.manage";
    static final String ADOPTION_LISTING_READ_SCOPE = "adoption.listing.read";
    static final String ADOPTION_LISTING_MANAGE_SCOPE = "adoption.listing.manage";
    private static final String UUID =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern INCIDENT_LIST = Pattern.compile("^/incident/api/v1/admin/rescues$");
    private static final Pattern INCIDENT_DETAIL = Pattern.compile("^/incident/api/v1/admin/rescues/" + UUID + "$");
    private static final Pattern INCIDENT_VERIFY = Pattern.compile("^/incident/api/v1/admin/rescues/" + UUID + "/verify$");
    private static final Pattern ADOPTION_LIST = Pattern.compile("^/adoption/api/v1/admin/applications$");
    private static final Pattern ADOPTION_DETAIL = Pattern.compile("^/adoption/api/v1/admin/applications/" + UUID + "$");
    private static final Pattern ADOPTION_REVIEW = Pattern.compile("^/adoption/api/v1/admin/applications/" + UUID + "/(approve|reject)$");
    private static final Pattern ADOPTION_LISTINGS = Pattern.compile("^/adoption/api/v1/admin/listings$");
    private static final Pattern ADOPTION_LISTING_DETAIL = Pattern.compile("^/adoption/api/v1/admin/listings/" + UUID + "$");
    private static final Pattern ADOPTION_LISTING_PUBLISH = Pattern.compile("^/adoption/api/v1/admin/listings/" + UUID + "/publish$");
    private static final Pattern ADOPTION_SELECTION = Pattern.compile("^/adoption/api/v1/admin/listings/" + UUID + "/selection$");
    private static final Pattern ADOPTION_HANDOVER = Pattern.compile("^/adoption/api/v1/admin/selections/" + UUID + "/handover$");
    private static final Pattern ADOPTION_HANDOVER_ACTION = Pattern.compile("^/adoption/api/v1/admin/handovers/" + UUID + "/(complete|cancel)$");

    private AdminGovernanceReadPolicy() {
    }

    static Optional<Binding> binding(HttpMethod method, String externalPath) {
        if (externalPath == null) return Optional.empty();
        if (HttpMethod.POST.equals(method) && INCIDENT_VERIFY.matcher(externalPath).matches()) {
            return Optional.of(new Binding("incident-service", INCIDENT_VERIFY_SCOPE,
                    externalPath.substring("/incident".length()), method));
        }
        String adoptionScope = null;
        if (HttpMethod.POST.equals(method) && (ADOPTION_LISTINGS.matcher(externalPath).matches() || ADOPTION_LISTING_PUBLISH.matcher(externalPath).matches())) adoptionScope = ADOPTION_LISTING_MANAGE_SCOPE;
        else if (HttpMethod.GET.equals(method) && (ADOPTION_LISTINGS.matcher(externalPath).matches() || ADOPTION_LISTING_DETAIL.matcher(externalPath).matches())) adoptionScope = ADOPTION_LISTING_READ_SCOPE;
        else if (HttpMethod.POST.equals(method) && ADOPTION_REVIEW.matcher(externalPath).matches()) adoptionScope = ADOPTION_REVIEW_SCOPE;
        else if ((HttpMethod.GET.equals(method) || HttpMethod.POST.equals(method)) && ADOPTION_SELECTION.matcher(externalPath).matches()) adoptionScope = ADOPTION_SELECTION_SCOPE;
        else if ((HttpMethod.GET.equals(method) || HttpMethod.POST.equals(method)) && ADOPTION_HANDOVER.matcher(externalPath).matches()) adoptionScope = ADOPTION_HANDOVER_SCOPE;
        else if (HttpMethod.POST.equals(method) && ADOPTION_HANDOVER_ACTION.matcher(externalPath).matches()) adoptionScope = ADOPTION_HANDOVER_SCOPE;
        if (adoptionScope != null) return Optional.of(new Binding("adoption-service", adoptionScope,
                externalPath.substring("/adoption".length()), method));
        if (!HttpMethod.GET.equals(method)) return Optional.empty();
        if (INCIDENT_LIST.matcher(externalPath).matches() || INCIDENT_DETAIL.matcher(externalPath).matches()) {
            return Optional.of(new Binding("incident-service", INCIDENT_SCOPE,
                    externalPath.substring("/incident".length()), method));
        }
        if (ADOPTION_LIST.matcher(externalPath).matches() || ADOPTION_DETAIL.matcher(externalPath).matches()) {
            return Optional.of(new Binding("adoption-service", ADOPTION_SCOPE,
                    externalPath.substring("/adoption".length()), method));
        }
        return Optional.empty();
    }

    static boolean matches(HttpMethod method, String externalPath) {
        return binding(method, externalPath).isPresent();
    }

    record Binding(String target, String scope, String downstreamPath, HttpMethod method) {
    }
}
