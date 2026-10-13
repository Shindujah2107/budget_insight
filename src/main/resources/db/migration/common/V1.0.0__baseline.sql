-- ============================================================================
-- V1.0.0  BASELINE  (budget-insights)
--
-- Migrated from MoiPay-Backend V2.19.0__budget_insights_schema.sql (commit bdd15510).
-- Same fourteen tables, same columns, same check constraints.
--
-- WHAT CHANGED, AND WHY
--   * Schema is budget_insights, not moipay_core.
--   * The twelve foreign keys into moipay_core."user"(uid) and moipay_core.tenant
--     (tenant_id) are GONE. Those tables live in the backend's database; this service
--     has its own. user_id and tenant_id are still NOT NULL and still indexed, but the
--     database can no longer prove they point at a real user — the gateway does, by
--     only ever issuing a request with claims it validated.
--   * The nine foreign keys BETWEEN Budget Insights tables are unchanged. Those are
--     inside one database and still enforced.
--
-- Flyway adoption: a fresh database runs this file. Anywhere baselined at 1.0.0 skips
-- it. Future schema changes go in V1.0.3+.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS budget_insights;
-- Budget Insights (MIP-8 / CR-BUDGET-INSIGHTS-01) — schema.
--
-- Fourteen additive tables, all prefixed bi_ so the slice is identifiable in the
-- database. Nothing here alters an existing table and no existing table gains a
-- foreign key to these, which means the whole feature can be left in place and
-- disabled by flag rather than rolled back.
--
-- Two things in here are compliance controls rather than convenience:
--
--   1. bi_account stores a four-digit masked tail and nothing else. There is
--      deliberately no column capable of holding a full PAN or account number, so
--      widening that would require a schema change and therefore a documented
--      Compliance approval.
--   2. bi_compliance_event has four payload columns and no user foreign key. It
--      cannot carry message content or a financial value even by accident.
--
-- Reference data (categories, sender allowlist, parser templates) is seeded in
-- V2.19.1 so it can be amended and re-run without touching this migration.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. Reference data — categories and the merchant map that drives categorisation.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_category (
    id              uuid                        NOT NULL,
    code            varchar(32)                 NOT NULL,
    name_en         varchar(64)                 NOT NULL,
    name_si         varchar(64),
    name_ta         varchar(64),
    icon_key        varchar(48)                 NOT NULL,
    colour_hex      varchar(9),
    sort_order      smallint                    NOT NULL DEFAULT 0,
    is_active       boolean                     NOT NULL DEFAULT true,
    created_at      timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp(6) with time zone,
    CONSTRAINT pk_bi_category PRIMARY KEY (id),
    CONSTRAINT ux_bi_category_code UNIQUE (code)
);

COMMENT ON COLUMN budget_insights.bi_category.colour_hex IS
    'Nullable and unused in v1. The final design draws every share bar in one accent colour; '
    'retained so a future design can reintroduce per-category colour without a migration.';

CREATE INDEX IF NOT EXISTS ix_bi_category_active
    ON budget_insights.bi_category (is_active, sort_order);

CREATE TABLE IF NOT EXISTS budget_insights.bi_merchant_category_map (
    id                  uuid                        NOT NULL,
    merchant_pattern    varchar(256)                NOT NULL,
    match_type          varchar(16)                 NOT NULL,
    category_id         uuid                        NOT NULL,
    priority            smallint                    NOT NULL DEFAULT 100,
    is_active           boolean                     NOT NULL DEFAULT true,
    created_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_merchant_category_map PRIMARY KEY (id),
    CONSTRAINT ux_bi_mcm_pattern_type UNIQUE (merchant_pattern, match_type),
    CONSTRAINT fk_bi_mcm_category FOREIGN KEY (category_id)
        REFERENCES budget_insights.bi_category (id),
    CONSTRAINT ck_bi_mcm_match_type CHECK (match_type IN ('EXACT', 'PREFIX', 'REGEX'))
);

CREATE INDEX IF NOT EXISTS ix_bi_mcm_lookup
    ON budget_insights.bi_merchant_category_map (is_active, priority, match_type);

-- -------------------------------------------------------------------------------------
-- 2. Reference data served to the device — sender allowlist and parser templates.
--    Only messages from an allowlisted sender are eligible for processing at all.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_sms_sender_allowlist (
    id              uuid                        NOT NULL,
    bank_code       varchar(16)                 NOT NULL,
    sender_id       varchar(32)                 NOT NULL,
    country_code    varchar(2)                  NOT NULL DEFAULT 'LK',
    is_active       boolean                     NOT NULL DEFAULT true,
    created_at      timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_sms_sender_allowlist PRIMARY KEY (id),
    CONSTRAINT ux_bi_sender_bank_sender UNIQUE (bank_code, sender_id)
);

CREATE INDEX IF NOT EXISTS ix_bi_sender_active
    ON budget_insights.bi_sms_sender_allowlist (is_active, bank_code);

CREATE TABLE IF NOT EXISTS budget_insights.bi_bank_parser_template (
    id                  uuid                        NOT NULL,
    bank_code           varchar(16)                 NOT NULL,
    message_type        varchar(32)                 NOT NULL,
    pattern             text                        NOT NULL,
    field_map           jsonb                       NOT NULL,
    template_version    integer                     NOT NULL DEFAULT 1,
    is_active           boolean                     NOT NULL DEFAULT true,
    created_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp(6) with time zone,
    CONSTRAINT pk_bi_bank_parser_template PRIMARY KEY (id),
    CONSTRAINT ux_bi_tpl_bank_type_version UNIQUE (bank_code, message_type, template_version)
);

CREATE INDEX IF NOT EXISTS ix_bi_tpl_active
    ON budget_insights.bi_bank_parser_template (is_active, bank_code, message_type);

-- -------------------------------------------------------------------------------------
-- 3. Accounts observed in bank SMS. Masked tail only — see the header note.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_account (
    id                  uuid                        NOT NULL,
    user_id             uuid                        NOT NULL,
    tenant_id           uuid                        NOT NULL,
    bank_code           varchar(16),
    instrument_type     varchar(8)                  NOT NULL,
    masked_tail         varchar(8)                  NOT NULL,
    display_name        varchar(64),
    is_active           boolean                     NOT NULL DEFAULT true,
    created_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp(6) with time zone,
    deleted_at          timestamp(6) with time zone,
    CONSTRAINT pk_bi_account PRIMARY KEY (id),
    CONSTRAINT ux_bi_account_user_bank_tail
        UNIQUE (user_id, bank_code, instrument_type, masked_tail),
    CONSTRAINT ck_bi_account_instrument CHECK (instrument_type IN ('CARD', 'BANK'))
);

CREATE INDEX IF NOT EXISTS ix_bi_account_user
    ON budget_insights.bi_account (user_id, is_active)
    WHERE deleted_at IS NULL;

-- -------------------------------------------------------------------------------------
-- 4. The spending ledger. Every figure on the Insights screen is derived from this
--    table at read time; nothing is stored as a displayed value.
--
--    idempotency_key is computed on the device and is what makes repeated ingest of
--    the same message harmless.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_transaction (
    id                  uuid                        NOT NULL,
    user_id             uuid                        NOT NULL,
    tenant_id           uuid                        NOT NULL,
    account_id          uuid,
    amount              numeric(19, 2)              NOT NULL,
    currency            varchar(3)                  NOT NULL DEFAULT 'LKR',
    direction           varchar(8)                  NOT NULL,
    merchant            varchar(128),
    description         varchar(256),
    txn_date            date                        NOT NULL,
    txn_timestamp       timestamp(6) with time zone,
    category_id         uuid                        NOT NULL,
    auto_category_id    uuid,
    is_user_categorised boolean                     NOT NULL DEFAULT false,
    source              varchar(16)                 NOT NULL,
    parser_template_id  uuid,
    idempotency_key     varchar(128)                NOT NULL,
    created_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp(6) with time zone,
    deleted_at          timestamp(6) with time zone,
    CONSTRAINT pk_bi_transaction PRIMARY KEY (id),
    CONSTRAINT ux_bi_txn_user_idem UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_bi_txn_account FOREIGN KEY (account_id)
        REFERENCES budget_insights.bi_account (id),
    CONSTRAINT fk_bi_txn_category FOREIGN KEY (category_id)
        REFERENCES budget_insights.bi_category (id),
    CONSTRAINT fk_bi_txn_auto_category FOREIGN KEY (auto_category_id)
        REFERENCES budget_insights.bi_category (id),
    CONSTRAINT fk_bi_txn_template FOREIGN KEY (parser_template_id)
        REFERENCES budget_insights.bi_bank_parser_template (id),
    CONSTRAINT ck_bi_txn_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_bi_txn_source CHECK (source IN ('SMS_ANDROID', 'SMS_IOS', 'MANUAL')),
    CONSTRAINT ck_bi_txn_amount CHECK (amount >= 0)
);

-- The three screen queries are all (user, date range) with an optional account or
-- category narrowing. Partial on deleted_at so soft-deleted rows are never scanned.
CREATE INDEX IF NOT EXISTS ix_bi_txn_user_date
    ON budget_insights.bi_transaction (user_id, txn_date DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_bi_txn_user_cat_date
    ON budget_insights.bi_transaction (user_id, category_id, txn_date)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_bi_txn_user_acct_date
    ON budget_insights.bi_transaction (user_id, account_id, txn_date)
    WHERE deleted_at IS NULL;

-- -------------------------------------------------------------------------------------
-- 5. Append-only audit of everything the customer changed by hand.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_transaction_audit (
    id                  uuid                        NOT NULL,
    transaction_id      uuid                        NOT NULL,
    action              varchar(16)                 NOT NULL,
    old_category_id     uuid,
    new_category_id     uuid,
    actor               varchar(64)                 NOT NULL,
    created_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_transaction_audit PRIMARY KEY (id),
    CONSTRAINT fk_bi_txn_audit_txn FOREIGN KEY (transaction_id)
        REFERENCES budget_insights.bi_transaction (id),
    CONSTRAINT ck_bi_txn_audit_action
        CHECK (action IN ('CREATE', 'EDIT', 'RECATEGORISE', 'DELETE'))
);

CREATE INDEX IF NOT EXISTS ix_bi_txn_audit_txn
    ON budget_insights.bi_transaction_audit (transaction_id, created_at DESC);

-- -------------------------------------------------------------------------------------
-- 6. Budget target and optional per-category limits.
--    period_start / period_end are stored explicitly rather than derived from a month,
--    so a non-calendar budget cycle can be introduced later without a migration.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_budget (
    id              uuid                        NOT NULL,
    user_id         uuid                        NOT NULL,
    tenant_id       uuid                        NOT NULL,
    period_start    date                        NOT NULL,
    period_end      date                        NOT NULL,
    overall_limit   numeric(19, 2)              NOT NULL,
    currency        varchar(3)                  NOT NULL DEFAULT 'LKR',
    rolled_from_id  uuid,
    created_at      timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp(6) with time zone,
    CONSTRAINT pk_bi_budget PRIMARY KEY (id),
    CONSTRAINT ux_bi_budget_user_period UNIQUE (user_id, period_start),
    CONSTRAINT fk_bi_budget_rolled_from FOREIGN KEY (rolled_from_id)
        REFERENCES budget_insights.bi_budget (id),
    CONSTRAINT ck_bi_budget_period CHECK (period_end >= period_start),
    CONSTRAINT ck_bi_budget_limit CHECK (overall_limit > 0)
);

CREATE INDEX IF NOT EXISTS ix_bi_budget_user_period
    ON budget_insights.bi_budget (user_id, period_start DESC);

CREATE TABLE IF NOT EXISTS budget_insights.bi_budget_category_limit (
    id                      uuid                        NOT NULL,
    budget_id               uuid                        NOT NULL,
    category_id             uuid                        NOT NULL,
    limit_amount            numeric(19, 2)              NOT NULL,
    alert_threshold_pct     smallint                    NOT NULL DEFAULT 80,
    created_at              timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at              timestamp(6) with time zone,
    CONSTRAINT pk_bi_budget_category_limit PRIMARY KEY (id),
    CONSTRAINT ux_bi_bcl_budget_cat UNIQUE (budget_id, category_id),
    CONSTRAINT fk_bi_bcl_budget FOREIGN KEY (budget_id)
        REFERENCES budget_insights.bi_budget (id) ON DELETE CASCADE,
    CONSTRAINT fk_bi_bcl_category FOREIGN KEY (category_id)
        REFERENCES budget_insights.bi_category (id),
    CONSTRAINT ck_bi_bcl_limit CHECK (limit_amount > 0),
    CONSTRAINT ck_bi_bcl_threshold CHECK (alert_threshold_pct BETWEEN 1 AND 100)
);

-- -------------------------------------------------------------------------------------
-- 7. Consent. Append-only; the current state is the latest row for the user.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_consent (
    id                  uuid                        NOT NULL,
    user_id             uuid                        NOT NULL,
    tenant_id           uuid                        NOT NULL,
    consent_version     varchar(16)                 NOT NULL,
    policy_hash         varchar(128)                NOT NULL,
    channel             varchar(16)                 NOT NULL,
    granted_at          timestamp(6) with time zone,
    withdrawn_at        timestamp(6) with time zone,
    created_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_consent PRIMARY KEY (id),
    CONSTRAINT ck_bi_consent_channel CHECK (channel IN ('ANDROID', 'IOS', 'WEB'))
);

CREATE INDEX IF NOT EXISTS ix_bi_consent_user_latest
    ON budget_insights.bi_consent (user_id, created_at DESC);

-- -------------------------------------------------------------------------------------
-- 8. Compliance events.
--
--    Four payload columns and no user foreign key, deliberately. An OTP detection must
--    be recordable as a formal compliance incident without the record itself becoming
--    a place where message content or a financial value could end up.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_compliance_event (
    id              uuid                        NOT NULL,
    event_type      varchar(32)                 NOT NULL,
    occurred_at     timestamp(6) with time zone NOT NULL DEFAULT now(),
    source          varchar(32)                 NOT NULL,
    action          varchar(32)                 NOT NULL,
    device_id_hash  varchar(64),
    tenant_id       uuid,
    CONSTRAINT pk_bi_compliance_event PRIMARY KEY (id),
    CONSTRAINT ck_bi_ce_source CHECK (source IN ('SMS_PARSER', 'API_GATEWAY', 'APP'))
);

CREATE INDEX IF NOT EXISTS ix_bi_ce_type_time
    ON budget_insights.bi_compliance_event (event_type, occurred_at DESC);

-- -------------------------------------------------------------------------------------
-- 9. Encrypted raw store. Written only on the consent-gated parser-improvement path,
--    OTP-free by construction, purged on TTL by InsightsRawMessagePurgeScheduler.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_raw_message (
    id              uuid                        NOT NULL,
    user_id         uuid                        NOT NULL,
    tenant_id       uuid                        NOT NULL,
    bank_code       varchar(16),
    ciphertext      text                        NOT NULL,
    parse_status    varchar(16)                 NOT NULL,
    received_at     timestamp(6) with time zone NOT NULL DEFAULT now(),
    ttl_expires_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_bi_raw_message PRIMARY KEY (id),
    CONSTRAINT ck_bi_raw_parse_status CHECK (parse_status IN ('UNPARSED', 'PARTIAL', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS ix_bi_raw_ttl
    ON budget_insights.bi_raw_message (ttl_expires_at);

-- -------------------------------------------------------------------------------------
-- 10. Derived aggregate cache and device sync state. Both reconstructible — the cache
--     from bi_transaction, the sync state from the device's own queue.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_insights.bi_insight_cache (
    id              uuid                        NOT NULL,
    user_id         uuid                        NOT NULL,
    cache_key       varchar(64)                 NOT NULL,
    period_start    date                        NOT NULL,
    period_end      date                        NOT NULL,
    payload         jsonb                       NOT NULL,
    computed_at     timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_insight_cache PRIMARY KEY (id),
    CONSTRAINT ux_bi_cache_user_key_period UNIQUE (user_id, cache_key, period_start, period_end)
);

-- Invalidation is "every cache row whose period overlaps this transaction date".
CREATE INDEX IF NOT EXISTS ix_bi_cache_user_period
    ON budget_insights.bi_insight_cache (user_id, period_start, period_end);

CREATE TABLE IF NOT EXISTS budget_insights.bi_device_sync_state (
    id                  uuid                        NOT NULL,
    user_id             uuid                        NOT NULL,
    device_id           varchar(128)                NOT NULL,
    last_synced_at      timestamp(6) with time zone,
    last_ingest_seq     bigint                      NOT NULL DEFAULT 0,
    template_version    integer                     NOT NULL DEFAULT 0,
    created_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp(6) with time zone,
    CONSTRAINT pk_bi_device_sync_state PRIMARY KEY (id),
    CONSTRAINT ux_bi_sync_user_device UNIQUE (user_id, device_id)
);
