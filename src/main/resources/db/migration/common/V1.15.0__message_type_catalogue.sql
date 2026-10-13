-- =====================================================================================
-- V1.15.0 — the message type list (Configuration > Message types)
-- =====================================================================================
-- Until now an admin typed any message type when adding an SMS template, and the logical
-- type (the cross-bank group used for reports) was guessed from that name. The same kind of
-- SMS ended up under different names and groups (CARD_PURCHASE / CARD_DEBIT / POS_TXN).
--
-- This table is the one list templates pick from:
--   code                 the message type a template, a phone and a transaction carry
--   display_name         what the dashboard shows
--   logical_type         the group, from a fixed list; new templates take it from here
--   default_direction    the wizard's default "money in / out" (FROM_SMS = read it)
--   default_instrument   the wizard's default "paid with" (BANK / CARD)
--   fixed_category_code  categorise every SMS of this type as this category without merchant
--                        rules (ATM receipts, CRM deposits, bank charges: their "merchant" is a
--                        location or fee text). NULL = merchant rules decide.
--
-- Nothing that exists changes:
--   * templates keep their message_type and field map, published or drafted;
--   * every message type a template already uses is added below, so all of them stay valid;
--   * fixed categories are seeded with exactly the three types the service already decides
--     by type alone (MessageTypeCategories.defaults()), so categorisation is unchanged;
--   * phones are not involved: ingest still accepts any well-formed message type.
--
-- The predefined rows are the SMS kinds Sri Lankan banks send today: account debit/credit
-- alerts, card POS/online/ATM alerts, CRM and branch cash, CEFTS/SLIPS/online transfers,
-- JustPay/LankaQR/bill payments, standing orders, cheques, loans and bank charges.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS budget_insights.bi_message_type (
    code                 varchar(32)                 NOT NULL,
    display_name         varchar(64)                 NOT NULL,
    logical_type         varchar(48)                 NOT NULL,
    default_direction    varchar(8)                  NOT NULL DEFAULT 'FROM_SMS',
    default_instrument   varchar(8)                  NOT NULL DEFAULT 'BANK',
    fixed_category_code  varchar(32),
    is_active            boolean                     NOT NULL DEFAULT true,
    created_at           timestamp(6) with time zone NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_at           timestamp(6) with time zone,
    updated_by           uuid,
    CONSTRAINT pk_bi_message_type PRIMARY KEY (code),
    CONSTRAINT ck_bi_mt_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_bi_mt_name CHECK (length(btrim(display_name)) BETWEEN 2 AND 64),
    CONSTRAINT ck_bi_mt_logical CHECK (logical_type IN (
        'ACCOUNT_TRANSACTION', 'CARD_PURCHASE', 'ATM_WITHDRAWAL', 'CASH_DEPOSIT', 'TRANSFER',
        'PAYMENT', 'CHEQUE', 'LOAN', 'BANK_CHARGE_OR_REFUND', 'REFUND_OR_REVERSAL')),
    CONSTRAINT ck_bi_mt_direction CHECK (default_direction IN ('FROM_SMS', 'DEBIT', 'CREDIT')),
    CONSTRAINT ck_bi_mt_instrument CHECK (default_instrument IN ('BANK', 'CARD'))
);

COMMENT ON TABLE budget_insights.bi_message_type IS
    'Message types SMS templates pick from, with their group (logical type), wizard defaults and an '
    'optional fixed category. Managed from the Admin Dashboard. Templates and transactions carry the '
    'code as text, not as a foreign key.';

-- ---------------------------------------------------------------- predefined (created_by NULL = migration)
INSERT INTO budget_insights.bi_message_type
    (code, display_name, logical_type, default_direction, default_instrument, fixed_category_code) VALUES
    -- account alerts
    ('ACCOUNT_TXN',          'Account debit or credit',             'ACCOUNT_TRANSACTION',   'FROM_SMS', 'BANK', NULL),
    ('ACCOUNT_DEBIT',        'Account debit',                       'ACCOUNT_TRANSACTION',   'DEBIT',    'BANK', NULL),
    ('ACCOUNT_CREDIT',       'Account credit',                      'ACCOUNT_TRANSACTION',   'CREDIT',   'BANK', NULL),
    ('ACCOUNT_PURCHASE',     'Purchase debited from account',       'CARD_PURCHASE',         'DEBIT',    'BANK', NULL),
    ('SALARY_CREDIT',        'Salary credited',                     'ACCOUNT_TRANSACTION',   'CREDIT',   'BANK', NULL),
    ('INTEREST_CREDIT',      'Interest credited',                   'ACCOUNT_TRANSACTION',   'CREDIT',   'BANK', NULL),
    ('ACCOUNT_CHARGE',       'Bank charge or fee',                  'BANK_CHARGE_OR_REFUND', 'FROM_SMS', 'BANK', 'OTHER'),
    -- cards
    ('CARD_PURCHASE',        'Card purchase - POS or online',       'CARD_PURCHASE',         'DEBIT',    'CARD', NULL),
    ('CARD_ONLINE_PURCHASE', 'Card purchase - online only',         'CARD_PURCHASE',         'DEBIT',    'CARD', NULL),
    ('CARD_ALERT',           'Card alert - purchase or internet',   'CARD_PURCHASE',         'DEBIT',    'CARD', NULL),
    ('CARD_DEBIT',           'Card debit transaction',              'CARD_PURCHASE',         'DEBIT',    'CARD', NULL),
    ('POS_TXN',              'POS transaction',                     'CARD_PURCHASE',         'DEBIT',    'BANK', NULL),
    ('CREDIT_CARD_PURCHASE', 'Credit card purchase',                'CARD_PURCHASE',         'DEBIT',    'CARD', NULL),
    ('CARD_REFUND',          'Card refund or reversal',             'REFUND_OR_REVERSAL',    'CREDIT',   'CARD', NULL),
    ('CREDIT_CARD_PAYMENT',  'Credit card bill payment',            'PAYMENT',               'DEBIT',    'BANK', NULL),
    -- cash
    ('CARD_ATM_WITHDRAWAL',  'ATM cash withdrawal with a card',     'ATM_WITHDRAWAL',        'DEBIT',    'CARD', NULL),
    ('ATM_RECEIPT',          'ATM withdrawal e-receipt',            'ATM_WITHDRAWAL',        'DEBIT',    'CARD', 'OTHER'),
    ('CASH_WITHDRAWAL',      'Cash withdrawal at a branch',         'ATM_WITHDRAWAL',        'DEBIT',    'BANK', NULL),
    ('CRM_DEPOSIT',          'Cash deposit machine (CRM/CDM)',      'CASH_DEPOSIT',          'CREDIT',   'BANK', 'OTHER'),
    ('CASH_DEPOSIT',         'Cash deposit at a branch',            'CASH_DEPOSIT',          'CREDIT',   'BANK', NULL),
    -- transfers
    ('CEFTS_INWARD',         'CEFTS transfer received',             'TRANSFER',              'CREDIT',   'BANK', NULL),
    ('CEFTS_OUTWARD',        'CEFTS transfer sent',                 'TRANSFER',              'DEBIT',    'BANK', NULL),
    ('ONLINE_TRANSFER',      'Online or mobile banking transfer',   'TRANSFER',              'FROM_SMS', 'BANK', NULL),
    ('SLIPS_CREDIT',         'SLIPS credit',                        'TRANSFER',              'CREDIT',   'BANK', NULL),
    ('REMITTANCE_INWARD',    'Foreign remittance received',         'TRANSFER',              'CREDIT',   'BANK', NULL),
    -- payments
    ('BILL_PAYMENT',         'Bill payment',                        'PAYMENT',               'DEBIT',    'BANK', NULL),
    ('JUSTPAY_PAYMENT',      'JustPay payment',                     'PAYMENT',               'DEBIT',    'BANK', NULL),
    ('LANKAQR_PAYMENT',      'LankaQR payment',                     'PAYMENT',               'DEBIT',    'BANK', NULL),
    ('WALLET_PAYMENT',       'Wallet or QR payment',                'PAYMENT',               'DEBIT',    'BANK', NULL),
    ('STANDING_ORDER',       'Standing order',                      'PAYMENT',               'DEBIT',    'BANK', NULL),
    -- cheques
    ('CHEQUE_DEBIT',         'Cheque paid',                         'CHEQUE',                'DEBIT',    'BANK', NULL),
    ('CASH_CHEQUE_DEBIT',    'Cash cheque debit',                   'CHEQUE',                'DEBIT',    'BANK', NULL),
    ('CHEQUE_DEPOSIT',       'Cheque deposited or realised',        'CHEQUE',                'CREDIT',   'BANK', NULL),
    ('CHEQUE_RETURN',        'Cheque returned',                     'CHEQUE',                'FROM_SMS', 'BANK', NULL),
    -- loans
    ('LOAN_INSTALMENT',      'Loan or leasing instalment',          'LOAN',                  'DEBIT',    'BANK', NULL),
    ('LOAN_DISBURSEMENT',    'Loan disbursed',                      'LOAN',                  'CREDIT',   'BANK', NULL)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------- every type a template already uses
-- Published templates and template drafts (bi_config_change payload). The group, direction and
-- "paid with" come from the newest template of that type; an unknown group becomes
-- ACCOUNT_TRANSACTION (CARD_TRANSACTION, the wizard's old guess, becomes CARD_PURCHASE).
WITH used AS (
    SELECT t.message_type                              AS code,
           t.field_map ->> 'logicalType'               AS logical_type,
           t.field_map -> 'direction' ->> 'value'      AS direction,
           t.field_map ->> 'instrumentType'            AS instrument,
           t.is_active                                 AS active,
           t.template_version                          AS version
    FROM budget_insights.bi_bank_parser_template t
    UNION ALL
    SELECT c.payload ->> 'messageType',
           c.payload -> 'fieldMap' ->> 'logicalType',
           c.payload -> 'fieldMap' -> 'direction' ->> 'value',
           c.payload -> 'fieldMap' ->> 'instrumentType',
           true,
           2147483647
    FROM budget_insights.bi_config_change c
    WHERE c.entity_type = 'TEMPLATE'
      AND c.payload IS NOT NULL
      AND c.payload ->> 'messageType' IS NOT NULL
),
newest AS (
    SELECT DISTINCT ON (code) code, logical_type, direction, instrument
    FROM used
    WHERE code ~ '^[A-Z][A-Z0-9_]{1,31}$'
    ORDER BY code, active DESC, version DESC
)
INSERT INTO budget_insights.bi_message_type
    (code, display_name, logical_type, default_direction, default_instrument, fixed_category_code)
SELECT n.code,
       upper(left(replace(lower(n.code), '_', ' '), 1)) || substr(replace(lower(n.code), '_', ' '), 2),
       CASE
           WHEN n.logical_type IN ('ACCOUNT_TRANSACTION', 'CARD_PURCHASE', 'ATM_WITHDRAWAL', 'CASH_DEPOSIT',
                                   'TRANSFER', 'PAYMENT', 'CHEQUE', 'LOAN', 'BANK_CHARGE_OR_REFUND',
                                   'REFUND_OR_REVERSAL') THEN n.logical_type
           WHEN n.logical_type = 'CARD_TRANSACTION' THEN 'CARD_PURCHASE'
           ELSE 'ACCOUNT_TRANSACTION'
       END,
       CASE WHEN n.direction IN ('DEBIT', 'CREDIT') THEN n.direction ELSE 'FROM_SMS' END,
       CASE WHEN n.instrument IN ('BANK', 'CARD') THEN n.instrument ELSE 'BANK' END,
       NULL
FROM newest n
ON CONFLICT (code) DO NOTHING;
