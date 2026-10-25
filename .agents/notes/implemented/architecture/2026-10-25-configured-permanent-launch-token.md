# Agent Note: Configured permanent launch token

Status: implemented

English | [中文](2026-10-25-configured-permanent-launch-token.zh.md)

## Problem

The per-process launch token authenticates the root index exchange and `/connect`, and it rotates whenever the process restarts. A signed browser cookie survives that restart, but a non-browser API client that uses `/connect` to obtain an operator must read the newly printed token after each restart and re-authenticate its user. The Android background listener in this workspace is such a client: its event stream stops after a DSH restart even though the WebView cookie is still valid, because the API operator peer is process-scoped and the old launch token no longer matches.

## Decision

`dsh-client-connection` accepts an optional `permanentToken` Connection config at the same two launch-token exchange points: the root `GET /?token=...` exchange and `GET /connect?token=...`. The configured token is accepted alongside the per-process token; the printed startup URL still contains only the per-process token. An unset `permanentToken` preserves the process-token-only behavior.

Connection reads the configured token at activation and keeps it in memory. Changing or removing it takes effect when Connection reloads or the process restarts. The token is never persisted by DSH, never printed, and never included in `authenticatedUrl`, cookies, or diagnostics. The config schema requires at least 32 printable ASCII characters and rejects whitespace. Both launch tokens are compared with the existing timing-safe helper. The Host/Origin trust checks stay in front of both exchanges; the change adds no Authorization-header path, endpoint, or cookie format. Exchanges still mint cookies with the configured `cookieMaxAgeDays`, so a permanent launch token does not extend a browser cookie's absolute lifetime.

The token is a bearer with owner authority. Deployment must treat it like the cookie signing secret: store it in secret configuration, serve the listener over TLS, and rotate it by changing the config and reloading Connection. It does not bypass `trustedHosts`, Fetch Metadata, or TLS policy.

A configured permanent token partially supersedes the "Persist or accept the launch token as an API bearer" alternative in [browser launch-token authentication](2026-08-24-browser-token-authentication.md). The older note remains active authority for the default process-token and cookie model.

## Verification

Unit coverage proves that the root exchange and `/connect` accept the configured token, that the per-process token remains accepted, that an unrelated token receives 401 without publishing an operator, and that `authenticatedUrl` still uses the per-process token. Integration coverage mounts two Connections against one credential provider, proves the process token changes while the configured token still exchanges at `/connect` after the restart, and proves the old process token receives 401. The config catalog is generated from the new field and JSDoc. Focused connection tests, package typecheck, lint, and the documentation gates are run locally.

## Alternatives considered

**Poll the browser cookie's `/api/session/list` from the client.** This needs no new credential and survives restarts, but loses event-driven delivery and can miss a turn that finishes between polls. It remains a fallback for deployments that do not configure a permanent token.

**Persist a random launch token automatically.** This removes the configuration step but silently creates a durable owner credential with no explicit rotation surface. A configured value makes the security tradeoff visible and gives the operator a deliberate revocation action.

**Add a server-minted long-lived API token endpoint.** This would add a second credential protocol and require peer and cookie lifecycle changes. The configured launch token reuses the existing two exchanges and operator minting without a new wire contract.

**Keep re-entering the process token after every restart.** This preserves the current security default but prevents unattended reconnection for a background client, which is the reported need.

## Consequences

A leaked permanent token grants full owner access to every trusted authority the listener serves until the operator changes or removes the config and Connection reloads. The token has no per-client identity, expiry, or audit trail; it is one shared owner credential, like the printed process token and the signed cookie. The default deployment remains process-token-only, so deployments that do not opt in keep the previous behavior and risk profile. Operators obtain the token from their secret configuration rather than from DSH output.
