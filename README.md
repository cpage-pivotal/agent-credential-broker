# Agent Credential Broker

A standalone Spring Boot + Angular service that centralizes credential acquisition for AI agents. Users pre-authorize access to target systems (OAuth, user-provided tokens, static API keys) through the broker's UI, and agents authenticate using delegation tokens (signed JWTs) to request credentials at runtime.

**New here?** See the [Getting Started Guide](docs/getting-started.md) for an overview of how the broker integrates with the Goose Buildpack and step-by-step examples for all four target system types.

## Architecture

- **Spring Boot 3.5** backend with Spring Security OAuth2 (SSO login via Tanzu SSO / `p-identity`)
- **Angular 21** Material UI bundled into the jar via `frontend-maven-plugin`
- **Delegation tokens**: HMAC-SHA256 signed JWTs encoding user, agent, allowed systems, and expiry
- **Two-layer authorization**: Grants (per-user, per-system) + Delegations (per-user, per-agent, time-limited)
- **PostgreSQL** for persistent storage of grants and delegations

## Building

```bash
mvn package
```

This runs the full pipeline: install Node.js, `npm ci`, `ng build`, compile Java, copy Angular output into `static/`, package the fat jar.

## Deployment

### Prerequisites

1. A `p-identity` service instance with the `uaa` plan (e.g., `agent-sso`)
2. A PostgreSQL service instance (e.g., `agent-db`) — used for persistent storage of grants, delegations, and token metadata
3. A `vars.yaml` file with secrets (not checked into source control)

### SSO Tile Configuration

The broker uses Tanzu SSO (`p-identity`) for two purposes:

1. **UI login** — Spring Security OAuth2 Login, using credentials from the
   service binding (`VCAP_SERVICES`)
2. **MCP target system OAuth** — The broker acts as an OAuth client to obtain
   tokens for protected MCP servers like cf-auth-mcp

Both use the same SSO client (the broker's own App ID). The Cloud Foundry
target system reads `client_id` and `client_secret` directly from
`VCAP_SERVICES` via `${vcap.services.agent-sso.credentials.client_id}`, so
there are no separate environment variables to keep in sync. The `uaa` plan
registers clients directly in the system UAA — the same authorization server
that cf-auth-mcp advertises via RFC 9728.

#### Initial Setup

1. Create the service instances (if they don't already exist):

   ```bash
   cf create-service p-identity uaa agent-sso
   cf create-service postgres on-demand-postgres-db agent-db
   ```

2. Push the app to create the service bindings:

   ```bash
   mvn package
   cf push --vars-file=vars.yaml
   ```

3. In the SSO tile, find **agent-credential-broker** and configure:

   - **Identity Providers**: Check "Internal User Store" (or whichever
     providers your users authenticate with)
   - **Redirect URI Allowlist**: Add the app's base URL. The SSO tile supports
     partial URI matching, so the base URL covers all callback paths:
     ```
     https://agent-credential-broker.apps.example.com
     ```
   - **Scopes**: Add any scopes the broker needs to request on behalf of users.
     For the Cloud Foundry MCP server, add:
     - `cloud_controller.read`
     - `cloud_controller.write`

#### Credential Sync

The Cloud Foundry target system credentials are read from `VCAP_SERVICES`
automatically — no manual sync required. If you regenerate the App Secret in
the SSO tile, just run `cf push` and the updated credentials flow through.

Do **not** unbind and rebind the service — this deletes the existing client and
creates a new one with a new App ID, invalidating the SSO tile configuration.

### Required Environment Variables

| Variable | Description |
|---|---|
| `BROKER_SIGNING_SECRET` | HMAC-SHA256 secret for signing delegation tokens |
| `DATABASE_URL` | PostgreSQL JDBC URL (default: `jdbc:postgresql://localhost:5432/broker`) |
| `DATABASE_USERNAME` | Database username (default: `broker`) |
| `DATABASE_PASSWORD` | Database password (default: `broker`) |

When deploying to Cloud Foundry with a bound PostgreSQL service, `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` are typically injected automatically via `VCAP_SERVICES` bindings or Spring Cloud Connectors — verify with your platform team.

Additional environment variables depend on which target systems are configured
in `application.yml`. Each target system that uses OAuth references its client
credentials via `${...}` placeholders. Target systems that share the SSO
service binding (like Cloud Foundry) can read credentials from `VCAP_SERVICES`
directly, requiring no environment variables.

### vars.yaml

Create a `vars.yaml` file (git-ignored) with the signing secret, database credentials (if not injected by the platform), and any target-system credentials not sourced from service bindings:

```yaml
BROKER_SIGNING_SECRET: <random-base64-string>

# Database (if not injected via VCAP_SERVICES)
DATABASE_URL: jdbc:postgresql://<host>:5432/broker
DATABASE_USERNAME: broker
DATABASE_PASSWORD: <db-password>

# Target system credentials (add as needed)
GITHUB_OAUTH_CLIENT_ID: <from-github-oauth-app>
GITHUB_OAUTH_CLIENT_SECRET: <from-github-oauth-app>
```

### Target System Configuration

Target systems are declared in `src/main/resources/application.yml` under `broker.target-systems`. See the [Getting Started Guide](docs/getting-started.md) for the full field reference and examples of all four supported types (`OAUTH_AUTHORIZATION_CODE`, `OAUTH_CLIENT_CREDENTIALS`, `USER_PROVIDED_TOKEN`, `STATIC_API_KEY`).

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

A local PostgreSQL instance is required. The defaults expect a database named `broker` with username `broker` and password `broker` on `localhost:5432`. You can override these with environment variables:

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/mydb
export DATABASE_USERNAME=myuser
export DATABASE_PASSWORD=mypassword
mvn spring-boot:run
```
