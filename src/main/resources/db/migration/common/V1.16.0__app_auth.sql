-- App sign-in: mobile number + SMS one-time code, then JWTs issued by this service.
--
-- bi_app_user.id is the user id every other Budget Insights table keys on.
-- Nothing here stores a secret in the clear:
-- one-time codes are HMACs bound to their row id, refresh tokens are SHA-256 hashes.

CREATE TABLE IF NOT EXISTS budget_insights.bi_app_user (
    id           uuid          NOT NULL,
    phone        varchar(16)   NOT NULL,
    full_name    varchar(120)  NOT NULL,
    email        varchar(254),
    role         varchar(16)   NOT NULL DEFAULT 'CUSTOMER',
    verified_at  timestamptz,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_app_user PRIMARY KEY (id),
    CONSTRAINT uq_bi_app_user_phone UNIQUE (phone),
    CONSTRAINT ck_bi_app_user_phone CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT ck_bi_app_user_role CHECK (role IN ('CUSTOMER', 'ADMIN', 'SUPER_ADMIN'))
);

CREATE TABLE IF NOT EXISTS budget_insights.bi_auth_otp (
    id           uuid          NOT NULL,
    phone        varchar(16)   NOT NULL,
    purpose      varchar(16)   NOT NULL,
    code_hash    varchar(64)   NOT NULL,
    attempts     integer       NOT NULL DEFAULT 0,
    expires_at   timestamptz   NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_auth_otp PRIMARY KEY (id),
    CONSTRAINT ck_bi_auth_otp_purpose CHECK (purpose IN ('REGISTER', 'LOGIN')),
    CONSTRAINT ck_bi_auth_otp_attempts CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS ix_bi_auth_otp_phone_created
    ON budget_insights.bi_auth_otp (phone, created_at);

CREATE TABLE IF NOT EXISTS budget_insights.bi_auth_refresh_token (
    id           uuid          NOT NULL,
    user_id      uuid          NOT NULL,
    family_id    uuid          NOT NULL,
    token_hash   varchar(64)   NOT NULL,
    expires_at   timestamptz   NOT NULL,
    revoked_at   timestamptz,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_auth_refresh_token PRIMARY KEY (id),
    CONSTRAINT uq_bi_auth_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_bi_auth_refresh_token_user
        FOREIGN KEY (user_id) REFERENCES budget_insights.bi_app_user (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_bi_auth_refresh_token_family
    ON budget_insights.bi_auth_refresh_token (family_id);
CREATE INDEX IF NOT EXISTS ix_bi_auth_refresh_token_user
    ON budget_insights.bi_auth_refresh_token (user_id);
