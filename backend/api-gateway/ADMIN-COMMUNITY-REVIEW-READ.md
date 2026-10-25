# Administrator community review read channel

This stage adds a narrowly scoped, read-only authorization channel from an
authenticated governance browser session through Gateway to Animal Service. It
does not open community review writes and is not production security acceptance.

## Exact external contract

Gateway accepts only these authenticated `GET` routes:

| Method | Path |
|---|---|
| GET | `/animal/api/v1/admin/community-review/posts` |
| GET | `/animal/api/v1/admin/community-review/posts/{postId}` |
| GET | `/animal/api/v1/admin/community-review/comments` |
| GET | `/animal/api/v1/admin/community-review/comments/{commentId}` |

The identifiers must be canonical UUID path segments. Adjacent paths, trailing
slashes, ambiguous encodings, `HEAD`, `OPTIONS`, and all write methods remain
denied. The existing anonymous Animal and Community policies are unchanged.

Gateway first validates the opaque administrator cookie through Identity
Service. It never forwards that cookie or its raw token to Animal Service. A
verified `GOVERNANCE_ADMIN` principal is converted into a short-lived HS256 JWS
with a dedicated Gateway-to-Animal key. Client-supplied `Authorization`,
`X-User-Id`, `X-Internal-Service`, and related identity headers are removed
before the trusted assertion is added.

## Assertion contract

Animal Service independently verifies the assertion signature and requires the
configured `kid`, issuer, subject, audience, target service, actor UUID,
`GOVERNANCE_ADMIN` role, `community.review.read` scope, request method, exact
request path, issue time, expiry, maximum lifetime, and unique `jti`. Missing or
invalid credentials fail with 401, insufficient role or scope fails with 403,
and unavailable verification configuration fails with 503. Direct access to
the Animal Service port has the same checks and cannot be authorized by plain
identity headers.

Gateway-to-Identity and Gateway-to-Animal keys are intentionally separate. The
Animal key is configured with `AUTH_ANIMAL_ASSERTION_KEYS` using
`kid=base64-secret` entries and selected with `AUTH_ANIMAL_ASSERTION_ACTIVE_KID`.
The secret must contain at least 256 bits of decoded key material and must be
provided by deployment secret management; it must not be committed or logged.

## Deployment boundary

Formal deployment requires authenticated TLS between Gateway and Animal
Service, controlled key distribution and rotation, and monitoring of assertion
verification failures. The current replay cache is bounded and process-local;
a multi-instance Animal deployment needs an approved shared replay mechanism or
another architecture that provides equivalent one-time assertion enforcement.

This stage exposes no approve, reject, hide, restore, or remove operation. It
does not authorize any other Animal governance endpoint and does not establish
a general-purpose identity-header trust model for other services.
