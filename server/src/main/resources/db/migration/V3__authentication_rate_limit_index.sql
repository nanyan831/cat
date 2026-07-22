CREATE INDEX login_codes_ip_created_idx
    ON login_codes (request_ip_hash, created_at DESC);
