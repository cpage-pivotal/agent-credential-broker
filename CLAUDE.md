# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Does

Agent Credential Broker is a standalone OAuth2 credential proxy for AI agents. It implements a two-layer authorization model:

1. **Grants** — A human user pre-authorizes the broker to hold OAuth tokens on their behalf for specific target systems (GitHub, Cloud Foundry, etc.)
2. **Delegations** — The user issues a signed JWT to a specific agent, allowing it to request credentials for a subset of granted systems within a time window

Agents never handle user credentials directly; they present delegation tokens and receive short-lived access tokens.

## Build & Run

```bash
# Full build (frontend embedded in backend JAR)
mvn package

# Run backend in dev mode (listens on :8080)
mvn spring-boot:run

# Run frontend dev server with proxy to backend (:4200 → :8080)
cd src/main/frontend && npm start

# Run frontend tests
cd src/main/frontend && npm test

# Run backend tests
mvn test
```

**Deploy to Cloud Foundry:**
```bash
cf push --vars-file=vars.yaml
```
Requires a `vars.yaml` with `BROKER_SIGNING_SECRET`, `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET` and a bound `agent-sso` p-identity service instance.

## Architecture

### Backend (Spring Boot 3.5 / Java 21)

Package-by-feature under `org.tanzu.broker`:

| Package | Responsibility |
|---|---|
| `credential` | `/api/credentials/**` — agents present delegation tokens here to receive OAuth access tokens |
| `delegation` | `/api/delegations/**` — users create/revoke HMAC-SHA256 JWTs for agents |
| `grant` | `/api/grants/**` — users authorize the broker to hold tokens for target systems |
| `oauth` | Orchestrates RFC 9728 discovery + PKCE authorization code flows to target systems |
| `token` | In-memory `TokenStore` (ConcurrentHashMap); `TokenLifecycleService` runs cleanup every 5 min |
| `targetsystem` | Registry of configured target systems (type-safe records from `application.yml`) |
| `security` | Three layered `SecurityFilterChain` beans — stateless for agents, JWT resource server for inter-app, session-based SSO for humans |

**Important:** All storage is in-memory. Running multiple instances requires replacing `TokenStore` and delegation records with a shared store (Redis, DB).

### Security Layers (SecurityConfig)

1. `/api/credentials/**` — no authentication (agents use delegation tokens inline)
2. `/api/delegations/inter-app` — OAuth2 JWT resource server
3. Everything else — OAuth2 SSO session (via Tanzu p-identity / `agent-sso` service)

### Frontend (Angular 21)

Located in `src/main/frontend/`. Key conventions:
- **Zoneless** (`provideZonelessChangeDetection()`) — do not use `NgZone` or `async` pipe with observables where signals work
- **Signals** for all component state (`signal()`, `computed()`, `effect()`)
- **Standalone components** — no NgModules
- **Template control flow** (`@if`, `@for`, `@switch`) not structural directives
- **Material Design 3** tokens in SCSS; avoid hard-coded colors/spacing

Services in `src/main/frontend/src/app/services/` map 1:1 to backend feature packages.

### Configuration

Target systems are declared in `application.yml` under `broker.target-systems`. Supported types: `OAUTH_AUTHORIZATION_CODE`, `STATIC_API_KEY`, `USER_PROVIDED_TOKEN`. Discovery strategy `rfc9728` triggers well-known endpoint lookup.

Delegation token signing uses `broker.delegation.signing-secret` (HMAC-SHA256). `broker.delegation.previous-signing-secret` enables zero-downtime key rotation.
