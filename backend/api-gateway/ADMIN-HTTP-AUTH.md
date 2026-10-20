# WEB-AUTH-1B-1B-B2 administrator HTTP authentication

This stage adds the minimum browser-to-Gateway administrator authentication
loop. It does not authorize governance business routes and is not production
security acceptance.

## Exact browser contract

Only these method/path pairs are authentication entry points:

| Method | Path | Result |
|---|---|---|
| GET | `/auth/admin/csrf` | Returns `{token, headerName}` and starts a hardened server-side CSRF WebSession. |
| POST | `/auth/admin/login` | Accepts `{username, password}`, creates a server session through Identity, sets the administrator cookie, and returns minimal identity/session expiry data without the token. |
| GET | `/auth/admin/session` | Validates the administrator cookie through Identity, refreshes its browser expiry, and returns minimal identity/session expiry data. |
| POST | `/auth/admin/logout` | Revokes the current server session through Identity and then expires the administrator cookie. |

All adjacent `/auth/**` routes and wrong HTTP methods remain denied by the
Gateway boundary. Login and logout require the configured exact HTTPS Origin,
`Sec-Fetch-Site: same-origin`, and a valid Spring Security CSRF WebSession/header
pair. The raw CSRF secret is stored in the server WebSession; the browser cookie
contains only the opaque WebSession identifier. Supplying matching attacker-made
Cookie and Header values therefore cannot create a trusted CSRF state. Login
additionally requires JSON, an explicit Content-Length, and the configured
request-size limit.

The governance Web client must first call the CSRF endpoint with credentials
enabled, retain the returned token only in memory, and send it in the returned
header name on login and logout. It must never read or store the administrator
session token; that token is available only in the HttpOnly cookie.

## Cookies and service calls

The administrator cookie uses the B1 cookie name and is `Secure`, `HttpOnly`,
`SameSite=Strict`, host-only, and `Path=/`. Its Max-Age is bounded by the earlier
of Identity's idle and absolute expiry and is refreshed only after Identity
successfully validates the session. The separate CSRF WebSession cookie is also
`Secure`, `HttpOnly`, `SameSite=Strict`, host-only, and `Path=/`, and expires with
the configured server-side inactivity timeout. Successful login does not discard
that CSRF WebSession, so the same page can immediately submit a protected logout;
the CSRF token remains independent from the administrator authentication token.

Gateway removes the administrator cookie before ordinary routing. Login,
validation, and revocation call exact Identity internal endpoints with a fresh
B1 service assertion. Identity alone checks credentials, account state, role,
rate limits, audit persistence, session state, expiry, revocation, and credential
version. Service-authentication failure, timeout, or Identity unavailability
fails closed.

## Deployment boundary

Formal deployment requires end-to-end HTTPS and secret-managed service keys.
The configured browser Origin must be the actual governance Web origin; the
default empty allowlist rejects login and logout. Client-supplied Forwarded and
X-Forwarded-For headers remain untrusted unless the immediate peer is explicitly
configured as a trusted ingress proxy.

The current CSRF WebSession store is process-local. A multi-instance Gateway
deployment must provide verified sticky routing or an approved shared Spring
Session store before production use.

This stage does not provide browser UI, password recovery, multi-factor
authentication, downstream trusted user assertions, business-service
authorization, shared multi-instance JWS replay state, or production TLS/key
rotation operations. Governance writes remain closed.
