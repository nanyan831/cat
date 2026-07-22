CREATE TABLE ai_request_audit (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    model VARCHAR(80),
    outcome VARCHAR(32) NOT NULL,
    error_category VARCHAR(64),
    input_tokens INTEGER NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    latency_ms BIGINT NOT NULL CHECK (latency_ms >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ai_request_audit_user_created_idx
    ON ai_request_audit (user_id, created_at DESC);
