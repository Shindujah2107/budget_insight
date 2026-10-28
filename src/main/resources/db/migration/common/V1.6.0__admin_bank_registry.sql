-- =====================================================================================================
-- V1.6.0 — Bank registry managed from the Admin Dashboard
-- =====================================================================================================
-- Until now the banks Budget Insights reads SMS for came from application config
-- (moneyreserve.insights.admin.banks), so adding a bank needed a redeploy. This table replaces that
-- list as the source of truth for "banks Budget Insights supports": the Admin Dashboard creates,
-- renames, deactivates and (when never used) deletes rows here.
--
-- Codes are the 4-digit CBSL bank codes.
--
-- bank_code is the natural key and is never changed after creation: senders, templates, accounts and
-- (through templates) transactions all carry it as text.
-- =====================================================================================================

CREATE TABLE IF NOT EXISTS budget_insights.bi_bank (
    bank_code   varchar(16)                 NOT NULL,
    bank_name   varchar(64)                 NOT NULL,
    is_active   boolean                     NOT NULL DEFAULT true,
    created_at  timestamp(6) with time zone NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamp(6) with time zone,
    updated_by  uuid,
    CONSTRAINT pk_bi_bank PRIMARY KEY (bank_code),
    CONSTRAINT ck_bi_bank_code CHECK (bank_code ~ '^[0-9]{4}$'),
    CONSTRAINT ck_bi_bank_name CHECK (length(btrim(bank_name)) BETWEEN 2 AND 64)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_bank_name_ci ON budget_insights.bi_bank (lower(bank_name));

COMMENT ON TABLE budget_insights.bi_bank IS
    'Banks Budget Insights supports (SMS senders, parser templates). Managed from the Admin Dashboard. '
    'Codes are the 4-digit CBSL bank codes.';

-- The six banks previously configured in application.yml (created_by NULL = migration)
INSERT INTO budget_insights.bi_bank (bank_code, bank_name, is_active, created_at) VALUES
    ('7010', 'Bank of Ceylon',       true, now()),
    ('7056', 'Commercial Bank',      true, now()),
    ('7083', 'Hatton National Bank', true, now()),
    ('7278', 'Sampath Bank',         true, now()),
    ('7287', 'Seylan Bank',          true, now()),
    ('7463', 'Amana Bank',           true, now())
ON CONFLICT (bank_code) DO NOTHING;
