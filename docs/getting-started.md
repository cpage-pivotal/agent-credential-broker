# Getting Started with Agent Credential Broker

## Overview

The Agent Credential Broker is designed to work with AI agent applications deployed using the [Goose Buildpack](https://github.com/cpage-pivotal/goose-buildpack). The Goose Buildpack packages Java applications with an embedded [Goose](https://github.com/block/goose) agent, enabling those applications to use MCP (Model Context Protocol) servers as tools.

Many MCP servers require authentication — OAuth tokens, personal access tokens, or API keys — that must be obtained on behalf of a specific user. Rather than requiring each application to implement its own credential management, the Agent Credential Broker centralizes this concern:

1. **Users pre-authorize access** by logging into the broker's UI and granting it permission to hold credentials for each target MCP server. For OAuth-based systems, users complete a standard consent flow. For token-based systems, users paste in a personal access token.

2. **Applications get a delegation token** — a signed JWT scoped to a specific user, a specific set of target systems, and a time window. Async agents receive a pre-created token via environment variable. Interactive agents obtain one dynamically via RFC 8693 Token Exchange.

3. **At runtime, the agent connects to the broker over mTLS** (port 8443 on the internal C2C route) and calls `POST /api/credentials/request` with its delegation token. The broker validates the delegation, verifies the agent's CF workload identity from its Instance Identity certificate, checks that the user has an active grant, and returns a credential ready for use with the target MCP server.

This model ensures that agents never handle user passwords or long-lived secrets. The mTLS requirement guarantees that only verified Cloud Foundry workloads can request credentials, and users retain full visibility and control over which agents can access which systems.

### Integration with the Goose Buildpack

Agent applications deployed with the [Goose Buildpack](https://github.com/cpage-pivotal/goose-buildpack) connect to the broker over Cloud Foundry's container-to-container (C2C) network using mutual TLS (mTLS). The broker verifies the agent's identity using its CF Instance Identity certificate and only serves credentials to agents whose workload identity matches the delegation token.

#### Agent Environment Variables

Configure these in your agent application's `manifest.yml` or `vars.yaml`:

| Variable | Required | Description |
|---|---|---|
| `BROKER_BASE_URL` | Yes | The broker's internal mTLS endpoint. Must point to the internal route on port 8443: `https://agent-credential-broker.apps.internal:8443` |
| `BROKER_PUBLIC_URL` | No | The broker's public URL (e.g., `https://agent-credential-broker.apps.example.com`). Set this if your agent has a UI that needs to link users to the broker's grants page. If not set, the agent will use `BROKER_BASE_URL` for UI links (which won't work from a browser). |
| `BROKER_DELEGATION_TOKEN` | Depends | A pre-created delegation token for async agents (e.g., email agents, scheduled tasks). Not needed for interactive agents that create delegations at session time via RFC 8693 Token Exchange. |

**`BROKER_BASE_URL`** must always point to the internal mTLS route. The Goose Buildpack's Java wrapper detects `.apps.internal` in the URL and automatically configures mTLS using the app's CF Instance Identity certificate. It also disables hostname verification for the connection, since CF Instance Identity certificates do not include internal route hostnames in their Subject Alternative Names.

**`BROKER_DELEGATION_TOKEN`** is used by async agents that run without an interactive user session. Create this token in the broker UI:

1. Log into the broker UI
2. Navigate to "Delegations" and create a new delegation
3. Select the target systems the agent needs access to
4. Optionally bind the delegation to a specific workload identity (org/space/app) — if bound, only the matching agent can use the token
5. Copy the generated token into your agent's environment

Interactive agents (e.g., chat applications) do not need a static delegation token. Instead, they obtain one dynamically by performing an RFC 8693 Token Exchange on each user session, presenting the user's access token as the `subject_token`. The broker binds the resulting delegation to the agent's workload identity automatically.

#### Network Policies

CF C2C traffic is denied by default. Each agent app needs a network policy to reach the broker's mTLS port:

```bash
cf add-network-policy <agent-app-name> agent-credential-broker --port 8443 --protocol tcp
```

#### Example: Async Agent (e.g., email agent)

`manifest.yml`:
```yaml
applications:
  - name: my-email-agent
    env:
      BROKER_BASE_URL: https://agent-credential-broker.apps.internal:8443
      BROKER_DELEGATION_TOKEN: ((BROKER_DELEGATION_TOKEN))
```

`vars.yaml`:
```yaml
BROKER_DELEGATION_TOKEN: <token-from-broker-ui>
```

```bash
cf add-network-policy my-email-agent agent-credential-broker --port 8443 --protocol tcp
```

#### Example: Interactive Agent (e.g., chat application)

`manifest.yml`:
```yaml
applications:
  - name: my-chat-agent
    env:
      BROKER_BASE_URL: https://agent-credential-broker.apps.internal:8443
      BROKER_PUBLIC_URL: ((BROKER_PUBLIC_URL))
```

`vars.yaml`:
```yaml
BROKER_PUBLIC_URL: https://agent-credential-broker.apps.example.com
```

```bash
cf add-network-policy my-chat-agent agent-credential-broker --port 8443 --protocol tcp
```

No `BROKER_DELEGATION_TOKEN` is needed — the application creates delegations dynamically via Token Exchange when users start a session.

---

## Configuring Target Systems

Target systems are configured in `src/main/resources/application.yml` under `broker.target-systems`. Each entry represents an external MCP server (or API) that agents can request credentials for. Add one entry per system your deployment needs to support.

### Common Fields

| Field | Required | Description |
|---|---|---|
| `name` | Yes | Unique identifier used in API calls and delegation tokens |
| `type` | Yes | One of `OAUTH_AUTHORIZATION_CODE`, `OAUTH_CLIENT_CREDENTIALS`, `USER_PROVIDED_TOKEN`, `STATIC_API_KEY` |
| `description` | No | Human-readable label shown in the UI |
| `mcpServerUrl` | No | URL of the MCP server this system provides credentials for |
| `defaultScopes` | No | Space-separated OAuth scopes to request by default |
| `headerName` | No | Custom header name for credential injection (default: `Authorization`) |
| `headerFormat` | No | Custom header value format with `{token}` placeholder (default: `Bearer {token}`) |

---

## Type 1: `OAUTH_AUTHORIZATION_CODE`

The user completes an interactive OAuth consent flow. The broker stores the resulting tokens and refreshes them automatically. Use this for systems where a human must grant access — GitHub, Google, Cloud Foundry, etc.

### Additional Fields

| Field | Required | Description |
|---|---|---|
| `clientId` | Yes | OAuth client ID registered with the authorization server |
| `clientSecret` | Yes | OAuth client secret |
| `discovery` | Recommended | OAuth endpoint discovery strategy. The only supported value is `rfc9728` (see below) |
| `authorizationServer` | If no discovery | Explicit authorization server base URL (e.g., `https://auth.example.com`) |

### OAuth Endpoint Discovery

The broker needs to know the authorization and token endpoints for each OAuth target system. These are resolved using the following priority:

1. **`authorizationServer`** — If set, the broker fetches metadata directly from the server's `/.well-known/oauth-authorization-server` or `/.well-known/openid-configuration` endpoint. No probing of the MCP server is needed.

2. **`discovery: rfc9728`** — If `authorizationServer` is not set and `discovery` is `rfc9728`, the broker performs [RFC 9728 Protected Resource Metadata](https://www.rfc-editor.org/rfc/rfc9728) discovery against the `mcpServerUrl`. This works by probing the MCP server for a `401` response, parsing the `WWW-Authenticate` header to find the `resource_metadata` URL, fetching the Protected Resource Metadata document to identify the authorization server, and then fetching that server's standard OAuth metadata.

3. **Neither set** — If neither `authorizationServer` nor `discovery` is configured, the broker cannot discover OAuth endpoints and the grant flow will fail.

`rfc9728` is the only supported value for `discovery`. Most MCP servers that require OAuth will support RFC 9728 discovery, making it the recommended approach when you don't know the authorization server URL ahead of time.

### Example: GitHub Copilot MCP Server

In `src/main/resources/application.yml`:

```yaml
broker:
  target-systems:
    - name: github
      type: OAUTH_AUTHORIZATION_CODE
      description: GitHub Copilot MCP Server
      clientId: ${GITHUB_OAUTH_CLIENT_ID}
      clientSecret: ${GITHUB_OAUTH_CLIENT_SECRET}
      mcpServerUrl: "https://api.githubcopilot.com/mcp/"
      discovery: rfc9728
      defaultScopes: "repo read:org user:email"
```

**vars.yaml additions:**
```yaml
GITHUB_OAUTH_CLIENT_ID: <from-github-oauth-app>
GITHUB_OAUTH_CLIENT_SECRET: <from-github-oauth-app>
```

Register an OAuth App at `github.com/settings/developers`. Set the callback URL to:
```
https://<broker-host>/api/grants/github/callback
```

### Example: Cloud Foundry MCP Server (using Tanzu SSO service binding)

When the broker is deployed on Cloud Foundry and bound to a `p-identity` service instance, client credentials are available directly from `VCAP_SERVICES` — no environment variables needed.

In `src/main/resources/application.yml`:

```yaml
broker:
  target-systems:
    - name: cloud-foundry
      type: OAUTH_AUTHORIZATION_CODE
      description: Cloud Foundry MCP Server
      clientId: ${vcap.services.agent-sso.credentials.client_id:${CF_MCP_OAUTH_CLIENT_ID:}}
      clientSecret: ${vcap.services.agent-sso.credentials.client_secret:${CF_MCP_OAUTH_CLIENT_SECRET:}}
      mcpServerUrl: "https://cf-auth-mcp.example.com/mcp"
      discovery: rfc9728
      defaultScopes: "openid cloud_controller.read cloud_controller.write"
```

---

## Type 2: `OAUTH_CLIENT_CREDENTIALS`

The broker authenticates directly to the authorization server using its own client credentials — no user interaction required. Use this for machine-to-machine integrations where the broker acts as a service account.

### Additional Fields

Same as `OAUTH_AUTHORIZATION_CODE`: `clientId`, `clientSecret`, `discovery` / `authorizationServer`, `defaultScopes`.

### Example: Internal Platform API

In `src/main/resources/application.yml`:

```yaml
broker:
  target-systems:
    - name: platform-api
      type: OAUTH_CLIENT_CREDENTIALS
      description: Internal Platform API
      clientId: ${PLATFORM_API_CLIENT_ID}
      clientSecret: ${PLATFORM_API_CLIENT_SECRET}
      authorizationServer: "https://auth.internal.example.com"
      defaultScopes: "platform.read platform.write"
```

**vars.yaml additions:**
```yaml
PLATFORM_API_CLIENT_ID: <service-account-client-id>
PLATFORM_API_CLIENT_SECRET: <service-account-client-secret>
```

No user consent flow is required. The broker fetches tokens autonomously whenever an agent requests credentials for this system.

---

## Type 3: `USER_PROVIDED_TOKEN`

The user manually pastes a token (personal access token, API key, etc.) into the broker UI. The broker stores it and serves it to agents on request. Use this for systems that issue long-lived tokens outside of an OAuth flow — Jira, Linear, internal tools with PATs.

No OAuth fields are needed. The UI presents a text field for the user to enter their token.

### Example: Jira Personal Access Token

In `src/main/resources/application.yml`:

```yaml
broker:
  target-systems:
    - name: jira
      type: USER_PROVIDED_TOKEN
      description: Jira (Personal Access Token)
      mcpServerUrl: "https://jira-mcp.internal.example.com/mcp"
```

The user navigates to their Jira profile, generates a Personal Access Token, and pastes it into the broker UI. Agents then receive it as a `Bearer` token in the `Authorization` header.

### Example: Custom Header Token

Some APIs require a non-standard header. Use `headerName` and `headerFormat` to override the default `Authorization: Bearer {token}` format:

In `src/main/resources/application.yml`:

```yaml
broker:
  target-systems:
    - name: linear
      type: USER_PROVIDED_TOKEN
      description: Linear (API Key)
      mcpServerUrl: "https://linear-mcp.internal.example.com/mcp"
      headerName: "X-Api-Key"
      headerFormat: "{token}"
```

---

## Type 4: `STATIC_API_KEY`

A single API key is embedded in the broker's configuration and served to all authorized agents. No per-user grant is required. Use this for shared team APIs where everyone uses the same key.

### Additional Fields

| Field | Required | Description |
|---|---|---|
| `apiKey` | Yes | The static API key value (use an environment variable reference) |

### Example: Shared Internal API

In `src/main/resources/application.yml`:

```yaml
broker:
  target-systems:
    - name: telemetry-api
      type: STATIC_API_KEY
      description: Telemetry API (Shared Key)
      apiKey: ${TELEMETRY_API_KEY}
      headerName: "X-Api-Key"
      headerFormat: "{token}"
```

**vars.yaml additions:**
```yaml
TELEMETRY_API_KEY: <shared-api-key>
```

Because the key is shared, any user with a valid delegation for this system will receive it. Scope delegation grants appropriately in your delegation policies.
