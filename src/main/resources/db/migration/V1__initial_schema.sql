CREATE TABLE stored_tokens (
    user_id        VARCHAR(255) NOT NULL,
    target_system  VARCHAR(255) NOT NULL,
    access_token   TEXT         NOT NULL,
    refresh_token  TEXT,
    expires_at     TIMESTAMPTZ,
    header_name    VARCHAR(255),
    header_format  VARCHAR(512),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, target_system)
);

CREATE TABLE delegations (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL,
    agent_id         VARCHAR(255) NOT NULL,
    allowed_systems  TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    revoked          BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_delegations_user_id ON delegations (user_id);
CREATE INDEX idx_delegations_expires_at ON delegations (expires_at);

CREATE TABLE revoked_jtis (
    jti        VARCHAR(64)  NOT NULL PRIMARY KEY,
    revoked_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_revoked_jtis_expires_at ON revoked_jtis (expires_at);
