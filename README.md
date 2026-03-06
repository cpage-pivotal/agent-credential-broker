# Agent Credential Broker

A standalone Spring Boot + Angular service that centralizes credential acquisition for AI agents. Users pre-authorize access to target systems (OAuth, user-provided tokens, static API keys) through the broker's UI, and agents authenticate using delegation tokens (signed JWTs) to request credentials at runtime.

## Architecture

- **Spring Boot 3.5** backend with Spring Security OAuth2 (SSO login via Tanzu SSO / `p-identity`)
- **Angular 21** Material UI bundled into the jar via `frontend-maven-plugin`
- **Delegation tokens**: HMAC-SHA256 signed JWTs encoding user, agent, allowed systems, and expiry
- **Two-layer authorization**: Grants (per-user, per-system) + Delegations (per-user, per-agent, time-limited)

## Building

```bash
mvn package
```

This runs the full pipeline: install Node.js, `npm ci`, `ng build`, compile Java, copy Angular output into `static/`, package the fat jar.

## Deployment

```bash
cf push -f manifest.yml
```

The app binds to the `goose-sso` p-identity service instance (same as `goose-agent-chat`).

### Required Environment Variables

| Variable | Description |
|---|---|
| `BROKER_SIGNING_SECRET` | HMAC-SHA256 secret for signing delegation tokens |

### Target System Configuration

Configure target systems in `application.yml` under `broker.target-systems`. See the plan document for examples of OAuth, user-provided token, and static API key configurations.

## API

### Credential Access (called by agents)

- `POST /api/credentials/request` — Bearer delegation token, body: `{targetSystem, scope}`
- `GET /api/credentials/status` — Bearer delegation token, returns grant status

### Grant Management (authenticated UI)

- `GET /api/grants` — list grants for current user
- `POST /api/grants/{system}/authorize` — initiate OAuth flow
- `POST /api/grants/{system}/token` — store user-provided token
- `DELETE /api/grants/{system}` — revoke grant

### Delegation Management (authenticated UI or inter-app)

- `POST /api/delegations` — create delegation token (SSO session)
- `POST /api/delegations/inter-app` — create delegation token (UAA ID token)
- `GET /api/delegations` — list delegations
- `DELETE /api/delegations/{id}` — revoke
- `POST /api/delegations/{id}/refresh` — refresh with new expiry

## Local Development

```bash
# Backend
mvn spring-boot:run

# Frontend (with proxy to backend)
cd src/main/frontend
npm start
```
