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

# Run a single backend test class
mvn test -pl . -Dtest=CredentialControllerTest

# Run all backend tests
mvn test

# Run frontend tests
cd src/main/frontend && npm test
```

**Deploy to Cloud Foundry:**
```bash
cf push --vars-file=vars.yaml
```
Requires a `vars.yaml` with `BROKER_SIGNING_SECRET`, and a bound `agent-sso` p-identity service instance plus a `postgres-db` service.

## Architecture

### Backend (Spring Boot 3.5 / Java 21)

Package-by-feature under `org.tanzu.broker`:

| Package | Responsibility |
|---|---|
| `credential` | `/api/credentials/**` — agents present delegation tokens here to receive OAuth access tokens |
| `delegation` | `/api/delegations/**` — users create/revoke HMAC-SHA256 JWTs for agents; includes token exchange endpoint |
| `grant` | `/api/grants/**` — users authorize the broker to hold tokens for target systems |
| `oauth` | Orchestrates RFC 9728 discovery + PKCE authorization code flows to target systems |
| `token` | `TokenStore` persists tokens to PostgreSQL; `TokenLifecycleService` runs cleanup every 5 min |
| `targetsystem` | Registry of configured target systems (type-safe records from `application.yml`) |
| `security` | Three layered `SecurityFilterChain` beans, mTLS connector config, workload identity extraction, SPA forwarding |
| `cf` | Cloud Foundry API integration — org/space/app lookup for agent identity |
| `config` | Flyway migration configuration |

Storage is PostgreSQL with Flyway migrations (`src/main/resources/db/migration/`). Schema has three tables: `stored_tokens`, `delegations`, `revoked_jtis`.

### Security Layers (SecurityConfig)

1. `/api/credentials/**` — stateless, X.509 client cert extraction (mTLS), permits all (agents use delegation tokens inline)
2. `/api/delegations/token` — stateless, X.509 extraction, requires authentication
3. Everything else — OAuth2 SSO session (via Tanzu p-identity / `agent-sso` service)

**mTLS:** A second Tomcat connector listens on port 8443 (configurable via `broker.mtls.port`). On Cloud Foundry it reads `CF_INSTANCE_CERT`/`CF_INSTANCE_KEY` from the environment. `WorkloadIdentityExtractor` parses CF Instance Identity certificates to extract `organization:GUID/space:GUID/app:GUID` from the OU field.

### Frontend (Angular 21)

Located in `src/main/frontend/`. Key conventions:
- **Zoneless** (`provideZonelessChangeDetection()`) — do not use `NgZone` or `async` pipe with observables where signals work
- **Signals** for all component state (`signal()`, `computed()`, `effect()`)
- **Standalone components** — no NgModules
- **Template control flow** (`@if`, `@for`, `@switch`) not structural directives
- **Material Design 3** tokens in SCSS; avoid hard-coded colors/spacing

Services in `src/main/frontend/src/app/services/` map 1:1 to backend feature packages. The Maven build embeds the Angular production output into the Spring Boot JAR via `frontend-maven-plugin`.

### Testing Patterns

Backend tests use JUnit 5 with Mockito (`mock()`, `when()`, `eq()`). Security context is set up with `TestingAuthenticationToken`. Tests are organized by feature package mirroring the main source.

### Configuration

Target systems are declared in `application.yml` under `broker.target-systems`. Supported types: `OAUTH_AUTHORIZATION_CODE`, `OAUTH_CLIENT_CREDENTIALS`, `STATIC_API_KEY`, `USER_PROVIDED_TOKEN`. Discovery strategy `rfc9728` triggers well-known endpoint lookup.

Delegation token signing uses `broker.delegation.signing-secret` (HMAC-SHA256). `broker.delegation.previous-signing-secret` enables zero-downtime key rotation. Default TTL is 72h, max is 720h.
