-- =====================================================================================
-- V1.19.0  Bills radar
-- =====================================================================================
-- Recurring bills found in a customer's own payments (electricity, water, phone, streaming,
-- insurance, loan instalments…), with "due soon", "bill jumped" and "missed" alerts.
--
--   bi_biller     shared reference data: known billers and the name terms that identify them,
--                 managed in the Admin Dashboard through the same draft -> publish flow as
--                 categories. Holds nothing about any customer.
--   bi_user_bill  one customer's bills, detected or added by hand. Owner-scoped; exported and
--                 erased with the rest of the customer's data.
--   bi_budget_alert gains bill_id and three alert types, with one alert per bill per due date.
-- No SMS text is read: detection works on transactions already stored.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS budget_insights.bi_biller (
    id                   uuid                        NOT NULL,
    code                 varchar(32)                 NOT NULL,
    name_en              varchar(64)                 NOT NULL,
    name_si              varchar(64),
    name_ta              varchar(64),
    bill_type            varchar(16)                 NOT NULL,
    -- Name terms, as merchant rules write them: DIALOG|DIALOG AXIATA|-REFUND
    match_terms          varchar(1000)               NOT NULL,
    icon_key             varchar(48)                 NOT NULL,
    colour_hex           varchar(7),
    -- ±% one payment may differ from the usual; NULL = the bill type's default.
    amount_tolerance_pct smallint,
    sort_order           smallint                    NOT NULL DEFAULT 100,
    is_active            boolean                     NOT NULL DEFAULT true,
    created_at           timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at           timestamp(6) with time zone,
    CONSTRAINT pk_bi_biller PRIMARY KEY (id),
    CONSTRAINT ux_bi_biller_code UNIQUE (code),
    CONSTRAINT ck_bi_biller_type CHECK (bill_type IN
        ('ELECTRICITY', 'WATER', 'TELECOM', 'STREAMING', 'INSURANCE', 'LOAN', 'CARD', 'RENT', 'OTHER')),
    CONSTRAINT ck_bi_biller_colour CHECK (colour_hex IS NULL OR colour_hex ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_bi_biller_tolerance CHECK (amount_tolerance_pct IS NULL
        OR amount_tolerance_pct BETWEEN 0 AND 200)
);

CREATE TABLE IF NOT EXISTS budget_insights.bi_user_bill (
    id                 uuid                        NOT NULL,
    user_id            uuid                        NOT NULL,
    -- B:<biller code>, M:<normalised merchant>, or X:<id> for one added by hand.
    payee_key          varchar(160)                NOT NULL,
    biller_id          uuid,
    detected_name      varchar(128)                NOT NULL,
    custom_name        varchar(64),
    status             varchar(16)                 NOT NULL,
    cadence            varchar(16)                 NOT NULL,
    expected_amount    numeric(19, 2)              NOT NULL,
    last_amount        numeric(19, 2),
    last_paid_on       date,
    next_due_on        date                        NOT NULL,
    anchor_day         smallint,
    remind_days_before smallint                    NOT NULL DEFAULT 3,
    payment_count      smallint                    NOT NULL DEFAULT 0,
    created_at         timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at         timestamp(6) with time zone,
    CONSTRAINT pk_bi_user_bill PRIMARY KEY (id),
    CONSTRAINT ux_bi_user_bill_payee UNIQUE (user_id, payee_key),
    CONSTRAINT fk_bi_user_bill_biller FOREIGN KEY (biller_id)
        REFERENCES budget_insights.bi_biller (id),
    CONSTRAINT ck_bi_user_bill_status CHECK (status IN ('DETECTED', 'CONFIRMED', 'DISMISSED', 'MANUAL')),
    CONSTRAINT ck_bi_user_bill_cadence CHECK (cadence IN ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    CONSTRAINT ck_bi_user_bill_amounts CHECK (expected_amount >= 0 AND (last_amount IS NULL OR last_amount >= 0)),
    CONSTRAINT ck_bi_user_bill_remind CHECK (remind_days_before BETWEEN 0 AND 14),
    CONSTRAINT ck_bi_user_bill_anchor CHECK (anchor_day IS NULL OR anchor_day BETWEEN 1 AND 31)
);

-- The read path: this customer's live bills by due date.
CREATE INDEX IF NOT EXISTS ix_bi_user_bill_user_due
    ON budget_insights.bi_user_bill (user_id, next_due_on);

-- -------------------------------------------------------------------------------------
-- Bill alerts share the alerts list. For them period_start is the bill's due date, and
-- there is one alert per bill per due date per type; budget alerts keep their own rules.
-- -------------------------------------------------------------------------------------
ALTER TABLE budget_insights.bi_budget_alert ADD COLUMN IF NOT EXISTS bill_id uuid;
ALTER TABLE budget_insights.bi_budget_alert DROP CONSTRAINT IF EXISTS fk_bi_alert_bill;
ALTER TABLE budget_insights.bi_budget_alert
    ADD CONSTRAINT fk_bi_alert_bill FOREIGN KEY (bill_id)
    REFERENCES budget_insights.bi_user_bill (id) ON DELETE CASCADE;

ALTER TABLE budget_insights.bi_budget_alert DROP CONSTRAINT IF EXISTS ck_bi_alert_type;
ALTER TABLE budget_insights.bi_budget_alert
    ADD CONSTRAINT ck_bi_alert_type CHECK (alert_type IN (
        'CATEGORY_APPROACHING',
        'CATEGORY_EXCEEDED',
        'BUDGET_EXCEEDED',
        'BILL_DUE',
        'BILL_JUMPED',
        'BILL_MISSED'
    ));

DROP INDEX IF EXISTS budget_insights.ux_bi_alert_user_period_overall_type;
CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_alert_user_period_overall_type
    ON budget_insights.bi_budget_alert (user_id, period_start, alert_type)
    WHERE category_id IS NULL AND bill_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_alert_bill_due_type
    ON budget_insights.bi_budget_alert (bill_id, period_start, alert_type)
    WHERE bill_id IS NOT NULL;

-- Billers go through draft -> publish like the rest of the reference data.
ALTER TABLE budget_insights.bi_config_change DROP CONSTRAINT IF EXISTS ck_bi_cc_entity;
ALTER TABLE budget_insights.bi_config_change
    ADD CONSTRAINT ck_bi_cc_entity
    CHECK (entity_type IN ('CATEGORY', 'MERCHANT_RULE', 'SENDER', 'TEMPLATE', 'BANK', 'BUDGET_POLICY', 'BILLER'));

-- -------------------------------------------------------------------------------------
-- Sri Lankan billers. Ids are fixed (name-based on the code) so every environment agrees.
-- -------------------------------------------------------------------------------------
INSERT INTO budget_insights.bi_biller
    (id, code, name_en, bill_type, match_terms, icon_key, sort_order)
VALUES
    ('b1b00000-0000-4000-a000-000000000001', 'CEB',              'CEB electricity',      'ELECTRICITY', 'CEB|CEYLON ELECTRICITY|ELECTRICITY BOARD',      'ic_utilities',       10),
    ('b1b00000-0000-4000-a000-000000000002', 'LECO',             'LECO electricity',     'ELECTRICITY', 'LECO|LANKA ELECTRICITY',                        'ic_utilities',       11),
    ('b1b00000-0000-4000-a000-000000000003', 'NWSDB',            'Water board',          'WATER',       'NWSDB|WATER BOARD|NATIONAL WATER',              'ic_utilities',       12),
    ('b1b00000-0000-4000-a000-000000000004', 'DIALOG',           'Dialog',               'TELECOM',     'DIALOG',                                        'ic_bills_utilities', 20),
    ('b1b00000-0000-4000-a000-000000000005', 'MOBITEL',          'Mobitel',              'TELECOM',     'MOBITEL',                                       'ic_bills_utilities', 21),
    ('b1b00000-0000-4000-a000-000000000006', 'SLT',              'SLT',                  'TELECOM',     'SLT|SRI LANKA TELECOM|SLTMOBITEL',              'ic_bills_utilities', 22),
    ('b1b00000-0000-4000-a000-000000000007', 'HUTCH',            'Hutch',                'TELECOM',     'HUTCH',                                         'ic_bills_utilities', 23),
    ('b1b00000-0000-4000-a000-000000000008', 'AIRTEL',           'Airtel',               'TELECOM',     'AIRTEL',                                        'ic_bills_utilities', 24),
    ('b1b00000-0000-4000-a000-000000000009', 'PEO_TV',           'PEO TV',               'TELECOM',     'PEO TV|PEOTV',                                  'ic_entertainment',   25),
    ('b1b00000-0000-4000-a000-000000000010', 'NETFLIX',          'Netflix',              'STREAMING',   'NETFLIX',                                       'ic_subscriptions',   30),
    ('b1b00000-0000-4000-a000-000000000011', 'SPOTIFY',          'Spotify',              'STREAMING',   'SPOTIFY',                                       'ic_subscriptions',   31),
    ('b1b00000-0000-4000-a000-000000000012', 'YOUTUBE',          'YouTube Premium',      'STREAMING',   'YOUTUBE',                                       'ic_subscriptions',   32),
    ('b1b00000-0000-4000-a000-000000000013', 'APPLE',            'Apple',                'STREAMING',   'APPLE COM|ITUNES',                              'ic_subscriptions',   33),
    ('b1b00000-0000-4000-a000-000000000014', 'GOOGLE',           'Google',               'STREAMING',   'GOOGLE',                                        'ic_subscriptions',   34),
    ('b1b00000-0000-4000-a000-000000000015', 'CEYLINCO',         'Ceylinco Insurance',   'INSURANCE',   'CEYLINCO',                                      'ic_health_care',     40),
    ('b1b00000-0000-4000-a000-000000000016', 'AIA',              'AIA Insurance',        'INSURANCE',   'AIA',                                           'ic_health_care',     41),
    ('b1b00000-0000-4000-a000-000000000017', 'UNION_ASSURANCE',  'Union Assurance',      'INSURANCE',   'UNION ASSURANCE',                               'ic_health_care',     42),
    ('b1b00000-0000-4000-a000-000000000018', 'SOFTLOGIC_LIFE',   'Softlogic Life',       'INSURANCE',   'SOFTLOGIC LIFE',                                'ic_health_care',     43),
    ('b1b00000-0000-4000-a000-000000000019', 'SLIC',             'Sri Lanka Insurance',  'INSURANCE',   'SRI LANKA INSURANCE|SLIC',                      'ic_health_care',     44),
    ('b1b00000-0000-4000-a000-000000000020', 'LB_FINANCE',       'LB Finance',           'LOAN',        'LB FINANCE',                                    'ic_debt_payments',   50),
    ('b1b00000-0000-4000-a000-000000000021', 'PEOPLES_LEASING',  'People''s Leasing',    'LOAN',        'PEOPLES LEASING',                               'ic_debt_payments',   51),
    ('b1b00000-0000-4000-a000-000000000022', 'CENTRAL_FINANCE',  'Central Finance',      'LOAN',        'CENTRAL FINANCE',                               'ic_debt_payments',   52),
    ('b1b00000-0000-4000-a000-000000000023', 'LOLC',             'LOLC',                 'LOAN',        'LOLC',                                          'ic_debt_payments',   53),
    ('b1b00000-0000-4000-a000-000000000024', 'COMMERCIAL_CREDIT','Commercial Credit',    'LOAN',        'COMMERCIAL CREDIT',                             'ic_debt_payments',   54)
ON CONFLICT (code) DO NOTHING;
