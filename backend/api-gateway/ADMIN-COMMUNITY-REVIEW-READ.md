# Administrator community review channel

This channel carries narrowly scoped community-review reads and explicit review
decisions from an authenticated governance browser session through Gateway to
Animal Service. It does not open general governance writes and is not production
security acceptance.

## Exact external contract

Gateway accepts only these authenticated `GET` routes:

| Method | Path |
|---|---|
| GET | `/animal/api/v1/admin/community-review/posts` |
| GET | `/animal/api/v1/admin/community-review/posts/{postId}` |
| GET | `/animal/api/v1/admin/community-review/comments` |
| GET | `/animal/api/v1/admin/community-review/comments/{commentId}` |

Gateway also accepts only these authenticated, CSRF-protected `POST` routes:

| Method | Path |
|---|---|
| POST | `/animal/api/v1/admin/community-review/posts/{postId}/approve` |
| POST | `/animal/api/v1/admin/community-review/posts/{postId}/reject` |
| POST | `/animal/api/v1/admin/community-review/comments/{commentId}/approve` |
| POST | `/animal/api/v1/admin/community-review/comments/{commentId}/reject` |

The identifiers must be canonical UUID path segments. Adjacent paths, trailing
slashes, ambiguous encodings, `HEAD`, `OPTIONS`, and every other write method or
path remain denied. The existing anonymous Animal and Community policies are
unchanged.

Each review decision requires the existing WebSession CSRF cookie and matching
request header issued by Gateway. Gateway also requires an explicitly allowed
HTTPS `Origin` and `Sec-Fetch-Site: same-origin`. Missing, forged, or cross-site
inputs fail before a downstream assertion is issued.

Gateway first validates the opaque administrator cookie through Identity
Service. It never forwards that cookie or its raw token to Animal Service. A
verified `GOVERNANCE_ADMIN` principal is converted into a short-lived HS256 JWS
with a dedicated Gateway-to-Animal key. Client-supplied `Authorization`,
`X-User-Id`, `X-Internal-Service`, and related identity headers are removed
before the trusted assertion is added.

## Assertion contract

Animal Service independently verifies the assertion signature and requires the
configured `kid`, issuer, subject, audience, target service, actor UUID,
`GOVERNANCE_ADMIN` role, the operation-specific `community.review.read` or
`community.review.write` scope, request method, exact request path, issue time,
expiry, maximum lifetime, and unique `jti`. Read assertions cannot authorize
writes, and write assertions cannot authorize other administration operations.
Missing or invalid credentials fail with 401, insufficient role or scope fails
with 403, and unavailable verification configuration fails with 503. Direct
access to the Animal Service port has the same checks and cannot be authorized
by plain identity headers.

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

Only approve and reject are exposed by this channel. Hide, restore, remove, and
all other Animal governance operations remain unavailable through Gateway. The
write adapter reuses Animal Service's existing transactional review state,
audit, and POST_ADOPTION Timeline synchronization rules; it does not establish
a general-purpose identity-header trust model for other services.

Automated Gateway and isolated MySQL integration tests exercise this contract.
A real browser login through multiple independently running Gateway, Identity,
and Animal Service processes remains unverified and is required before any
production security acceptance.
