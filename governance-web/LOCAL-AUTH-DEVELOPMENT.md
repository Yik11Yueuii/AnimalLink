# Local administrator authentication

Administrator authentication must be exercised from an HTTPS browser origin so
the Gateway's Secure cookies and exact Origin checks behave as they will in a
formal deployment.

1. Generate a localhost certificate with a trusted local CA such as `mkcert`.
   Keep the certificate and private key outside this repository.
2. Copy `.env.example` to an ignored `.env.local` and set the absolute certificate
   paths. Keep `VITE_GATEWAY_API_BASE_URL` empty so browser authentication calls
   remain same-origin.
3. Configure the Gateway with
   `AUTH_ADMIN_ALLOWED_ORIGINS=https://localhost:5173` and the B1 service-assertion
   secrets. Start Identity and Gateway normally.
4. Run `npm run dev`, then open `https://localhost:5173`.

Vite proxies only `/auth/admin/**` to the configured Gateway target. The proxy
preserves the browser Origin; it does not replace CSRF, Origin, Fetch Metadata,
service assertion, or session validation. Do not add development certificates,
private keys, administrator passwords, session tokens, or service secrets to Git.

Browsers control `Content-Length`; application JavaScript cannot safely set it.
The B2 Gateway currently requires a known `Content-Length` for login. Vite's
HTTP/1.1 proxy is expected to provide it for the JSON request, but formal browser
verification is still required. If Gateway returns 411, fix the backend with a
bounded streaming body limit in a separate change instead of setting the header
in JavaScript or disabling request-size enforcement.
