# Getting Started with Agent Credential Broker

## Overview

The Agent Credential Broker is designed to work with AI agent applications deployed using the [Goose Buildpack](https://github.com/cpage-pivotal/goose-buildpack). The Goose Buildpack packages Java applications with an embedded [Goose](https://github.com/block/goose) agent, enabling those applications to use MCP (Model Context Protocol) servers as tools.

Many MCP servers require authentication — OAuth tokens, personal access tokens, or API keys — that must be obtained on behalf of a specific user. Rather than requiring each application to implement its own credential management, the Agent Credential Broker centralizes this concern:

1. **Users pre-authorize access** by logging into the broker's UI and granting it permission to hold credentials for each target MCP server. For OAuth-based systems, users complete a standard consent flow. For token-based systems, users paste in a personal access token.

2. **Applications get a delegation token** — a signed JWT scoped to a specific user, a specific set of target systems, and a time window. This token is issued by the broker and distributed to the application (typically as an environment variable injected by the Goose Buildpack).

3. **At runtime, the agent exchanges the delegation token** for a short-lived access token by calling `POST /api/credentials/request`. The broker validates the delegation, checks that the user has an active grant, and returns a credential ready for use with the target MCP server.

This model ensures that agents never handle user passwords or long-lived secrets, and that users retain full visibility and control over which agents can access which systems.

### Integration with the Goose Buildpack

When you deploy an application using the Goose Buildpack, the buildpack expects a `BROKER_DELEGATION_TOKEN` environment variable to be set. Provide this by:

1. Logging into the broker UI
2. Creating a delegation scoped to the target systems your application needs
3. Copying the generated token into your application's environment (via `cf set-env`, `vars.yaml`, or a secrets manager)

The Goose Buildpack passes this token to the embedded Goose agent, which presents it when requesting credentials from the broker at runtime.

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
| `discovery` | Recommended | Set to `rfc9728` to auto-discover endpoints from the MCP server's `/.well-known/oauth-authorization-server` |
| `authorizationServer` | If no discovery | Explicit authorization server base URL |

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
