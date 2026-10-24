# WEB-AUTH-1A.1 ingress boundary

This is an interim ingress safeguard, not authentication. Formal login, session
validation, authenticated service identities and downstream authorization do not
exist yet. This stage is not production security acceptance; production deployment
and governance writes must not be opened on its basis. Do not assume protected reads
are safe through direct backend ports. Network isolation remains mandatory.

By default only GET and HEAD for the exact reviewed public campus, Animal and
published support expense paths in ExternalIdentityBoundaryFilter pass:

- /identity/api/v1/campuses
- /identity/api/v1/campuses/{campusId}
- /animal/api/v1/public/animals
- /animal/api/v1/public/animals/{animalId}
- /incident/api/v1/cases/{caseId}/support/expenses

Actuator health/info GET/HEAD are also retained. The list requires a canonical
UUID-shaped segment; Animal detail also requires a canonical UUID-shaped segment.
The Animal projection contains only identity-neutral profile fields and excludes
free text, precise/typical location, internal identity state, merge mappings and
media object keys. Legacy Animal list/detail and Timeline routes remain protected.
An adjacent child path is not public. All other requests,
including unknown routes, protected and unconfirmed reads, are 401. OPTIONS,
including CORS preflight, is denied in formal mode: there is no configured CORS policy. A future
CORS policy must explicitly validate origin, requested method and headers and
must not authorize the actual request.

See API-VISIBILITY-INVENTORY.md for source-based mapping, field concerns and
unconfirmed routes. Restricting public animal, rescue and adoption endpoints is
a temporary availability tradeoff until their public fields and visibility are
confirmed. CaseController needs its own service-side authorization and response
policy before sensitive access can be opened.

The earlier path filter blocks external internal routes and ambiguous paths,
including all percent-encoded paths, repeated slashes, semicolons and dot
segments. Ingress claims including Authorization, X-User-* and X-Internal-*
are stripped. Internal endpoints do not become trustworthy by checking the
X-Internal-Service string. Direct service access is outside the Gateway boundary.

Gateway development identity simulation remains off by default and requires
explicit opt-in, local/dev/test profiles, a loopback listener and a loopback peer.
The identity-service development profile enables its header provider and also
requires a loopback listener. Neither is authentication; test/dev services must
never be exposed through a proxy or tunnel.

WEB-AUTH-1B-1B-B1 adds the first part of that future path: the Gateway can validate
an opaque administrator session through the service-authenticated Identity endpoint
described in TRUSTED-SESSION-CHANNEL.md. A successful result remains an internal
Gateway attribute only. This stage still does not open protected routes, add login,
or produce a downstream-trusted user assertion. Each business service must later
validate service/user assertions and enforce resource/relationship authorization.

WEB-AUTH-1B-1B-B2 adds four exact administrator HTTP endpoints for CSRF bootstrap,
login, session recovery and logout, documented in ADMIN-HTTP-AUTH.md. Credentials
and raw session tokens cross only the authenticated Gateway-to-Identity channel;
the browser receives the token only as a hardened HttpOnly cookie. This addition
does not change public business reads, does not open any protected governance route,
and does not create a downstream-trusted user assertion. Production HTTPS, shared
multi-instance replay protection, key operations and business-service authorization
remain separate deployment and later-stage requirements.
