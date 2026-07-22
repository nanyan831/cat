CREATE TABLE users (
    id UUID PRIMARY KEY,
    email_normalized VARCHAR(320) NOT NULL,
    email_display VARCHAR(320) NOT NULL,
    display_name VARCHAR(80),
    time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX users_active_email_unique
    ON users (email_normalized)
    WHERE deleted_at IS NULL;

CREATE TABLE login_codes (
    id UUID PRIMARY KEY,
    email_normalized VARCHAR(320) NOT NULL,
    code_hash BYTEA NOT NULL,
    request_ip_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX login_codes_email_created_idx
    ON login_codes (email_normalized, created_at DESC);
CREATE INDEX login_codes_expiry_idx ON login_codes (expires_at);

CREATE TABLE refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash BYTEA NOT NULL UNIQUE,
    device_label VARCHAR(160),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE refresh_sessions
    ADD CONSTRAINT refresh_sessions_replaced_by_fk
    FOREIGN KEY (replaced_by) REFERENCES refresh_sessions(id) ON DELETE SET NULL;

CREATE INDEX refresh_sessions_user_idx ON refresh_sessions (user_id);
CREATE INDEX refresh_sessions_family_idx ON refresh_sessions (family_id);
CREATE INDEX refresh_sessions_expiry_idx ON refresh_sessions (expires_at);
