# WEB-AUTH-1A.1 ingress boundary

This is an interim ingress safeguard, not authentication. Formal login, session
validation, authenticated service identities and downstream authorization do not
exist yet. This stage is not production security acceptance; production deployment
and governance writes must not be opened on its basis. Do not assume protected reads
are safe through direct backend ports. Network isolation remains mandatory.

By default only GET and HEAD for the exact public campus list/detail and
published support expense list paths in ExternalIdentityBoundaryFilter pass:

- /identity/api/v1/campuses
- /identity/api/v1/campuses/{campusId}
- /incident/api/v1/cases/{caseId}/support/expenses

Actuator health/info GET/HEAD are also retained. The list requires a canonical
UUID-shaped segment; an adjacent child path is not public. All other requests,
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

Future design: add only the precise method/path for a reviewed login endpoint;
after discarding client-supplied assertions validate the identity-service session
at the Gateway before route authorization, then produce a verifiable downstream
assertion. Each service must validate it and enforce resource/relationship
authorization. Unauthenticated requests yield 401; authenticated principals
without permission yield 403. This change implements neither step.
