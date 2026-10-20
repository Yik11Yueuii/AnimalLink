# WEB-AUTH-1B-1B-B1 trusted session channel

This stage adds a narrowly scoped Gateway-to-Identity channel for administrator
session validation. It does not add external login, authorize protected business
routes, or create an identity assertion that downstream business services may trust.

## Service identity

The Gateway creates a short-lived HS256 JWS for each validation request using the
maintained Nimbus JOSE + JWT library pinned in the backend parent build. The assertion has a
key id and requires exact issuer, subject, audience and `target` claims, plus issue,
expiry and unique assertion times/identifiers. Identity accepts configured rotation
keys, enforces a maximum 30-second lifetime and rejects replayed identifiers.

Keys are supplied only through `AUTH_SERVICE_ASSERTION_CURRENT_KEY_ID` and
`AUTH_SERVICE_ASSERTION_KEYS`. The key set syntax is `key-id=base64-secret`, with
semicolon-separated entries during rotation. Every secret must contain at least
256 bits. Missing, malformed or unknown-key configuration fails closed. Keys and
assertions must never be logged.

HS256 keeps this two-service stage operationally small, but both services hold the
shared secret. Formal deployment therefore requires TLS, access-controlled secret
injection and rotation. The replay cache is local to one Identity instance; a
multi-replica deployment must add a shared one-time assertion store or replace this
channel with infrastructure-managed mTLS before production security acceptance.

## Session validation contract

Gateway calls `POST /internal/v1/auth/sessions/validate` directly on Identity. The
service assertion is the HTTP Bearer credential; the opaque administrator session
token is sent only in the JSON body. Identity authenticates the service before
reading the session and then reuses `AdminAuthenticationService.validateAdminSession`.
The database check covers expiry, revocation, active account, governance role and
credential version. The response contains only session id, user id, system role and
the two expiry instants.

`SESSION_INVALID` is an unauthenticated result. Service assertion failure and
Identity/configuration availability failures are kept distinct so Gateway can fail
closed with 401 versus 503 semantics. The external Gateway blocks `/identity/internal/**`
before routing, while Identity also authenticates direct calls to this endpoint.

## Gateway behavior

The planned browser credential location is the HttpOnly session cookie named
`ANIMALLINK_ADMIN_SESSION`. Gateway validates its strict opaque-token shape, removes
that cookie before any route forwarding, calls Identity with non-blocking `WebClient`
and bounded connection/response timeouts, and stores a successful result only in a
private exchange attribute. No user or internal-service headers are injected.

WEB-AUTH-1A remains authoritative: public reads remain anonymous and all protected
business requests remain denied even when the session validates. Public reads can
degrade to anonymous access if an attached stale cookie or Identity outage occurs;
protected requests fail with 401, 403 or 503 and never route downstream.

## Trusted source contract for the next login stage

By default Gateway uses only the socket peer address and ignores `Forwarded` and
`X-Forwarded-For`. Exact trusted entry-proxy addresses may be configured through
`TRUSTED_ENTRY_PROXIES`; only then is one bounded `X-Forwarded-For` chain processed
from right to left. Host names, ports, zone identifiers, malformed chains and a
trusted proxy with no valid chain fail closed. The resulting normalized `ip:` value
is intended for the future login limiter and may be sent to Identity only over this
authenticated channel.

Other existing `/internal/**` endpoints have not gained service authentication in
this stage. Network isolation remains required and their legacy identity headers
must not be treated as trusted.
