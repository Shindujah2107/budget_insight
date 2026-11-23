-- Sign-in code requests (V1.32.0): one row per "send me a code", kept two days, so the limits
-- count every request (also the ones whose SMS failed) per number, per network address and in
-- total per day. No phone number or address is stored, only salted hashes. Additive only.

CREATE TABLE IF NOT EXISTS budget_insights.bi_auth_code_request (
    id          uuid                        NOT NULL,
    phone_hash  varchar(64)                 NOT NULL,
    ip_hash     varchar(64),
    created_at  timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_auth_code_request PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS ix_bi_auth_code_request_phone ON budget_insights.bi_auth_code_request (phone_hash, created_at);
CREATE INDEX IF NOT EXISTS ix_bi_auth_code_request_ip ON budget_insights.bi_auth_code_request (ip_hash, created_at);
CREATE INDEX IF NOT EXISTS ix_bi_auth_code_request_created ON budget_insights.bi_auth_code_request (created_at);
