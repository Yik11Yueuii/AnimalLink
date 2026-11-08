# Mini Program observation development setup

The mock AI provider is selected server-side. Configure a dedicated Gateway–Intelligence assertion keyring and key ID in both services through deployment environment variables; never place their values in the client or repository. Gateway additionally needs the current key ID. The default service URL uses discovery (`intelligence-service`).

The Intelligence MinIO bucket must exist, and its externally advertised endpoint must be reachable from the Mini Program. Configure the Mini Program request-domain allowlist and HTTPS for both Gateway and object storage; only loopback HTTP is accepted for development. The upload intent is short-lived and object keys are scoped to the authenticated user. The client uploads the selected local JPEG/PNG/WebP bytes; it must not send its session token to object storage.

The local development runtime must enable both public data and user authentication API modes. Trial/release behavior is unchanged. The HTTP smoke uses generated test data, temporary in-memory secrets and mock AI, not real WeChat login or a production image.
