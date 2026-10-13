SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights — reference data.
--
-- Kept separate from V1.0.0 so the category list can be amended in a later migration
-- without anyone editing an applied schema file and producing a Flyway checksum failure.
--
-- Seeded here:
--   * The 17 primary categories confirmed under CR-BUDGET-INSIGHTS-01 section 06.
--
-- Deliberately NOT seeded here:
--   * bi_sms_sender_allowlist — the real bank sender IDs come with the labelled SMS
--     sample set (dependency D-1). An empty allowlist is the safe default: no sender
--     is eligible, so no message is processed at all until the real values are added.
--   * bi_bank_parser_template — same dependency. Guessed patterns would silently
--     mis-parse rather than fail loudly, which is worse than having none.
--
-- Both are added in a follow-up migration once D-1 lands.
--
-- icon_key values are placeholders pending the Design icon mapping (dependency D-2).
-- Changing them later is a data update, not a schema change.
--
-- name_si / name_ta are left null until the Sinhala and Tamil strings arrive
-- (dependency D-5). The API falls back to name_en, so an untranslated category
-- renders in English rather than blank.
--
-- Category ids are fixed rather than generated, so the same category has the same id
-- in every environment and a transaction's category_id means the same thing everywhere.
-- =====================================================================================

INSERT INTO budget_insights.bi_category
    (id, code, name_en, icon_key, sort_order, is_active)
VALUES
    ('b1c00000-0000-4000-a000-000000000001', 'TRANSPORT',       'Transport',        'ic_transport',        1,  true),
    ('b1c00000-0000-4000-a000-000000000002', 'UTILITIES',       'Utilities',        'ic_utilities',        2,  true),
    ('b1c00000-0000-4000-a000-000000000003', 'HEALTH_CARE',     'Health Care',      'ic_health_care',      3,  true),
    ('b1c00000-0000-4000-a000-000000000004', 'DEBT_PAYMENTS',   'Debt Payments',    'ic_debt_payments',    4,  true),
    ('b1c00000-0000-4000-a000-000000000005', 'DINING_OUT',      'Dining Out',       'ic_dining_out',       5,  true),
    ('b1c00000-0000-4000-a000-000000000006', 'ENTERTAINMENT',   'Entertainment',    'ic_entertainment',    6,  true),
    ('b1c00000-0000-4000-a000-000000000007', 'PERSONAL_CARE',   'Personal Care',    'ic_personal_care',    7,  true),
    ('b1c00000-0000-4000-a000-000000000008', 'SHOPPING',        'Shopping',         'ic_shopping',         8,  true),
    ('b1c00000-0000-4000-a000-000000000009', 'GIFTS_DONATION',  'Gifts / Donation', 'ic_gifts_donation',   9,  true),
    ('b1c00000-0000-4000-a000-000000000010', 'EDUCATION',       'Education',        'ic_education',        10, true),
    ('b1c00000-0000-4000-a000-000000000011', 'WORK',            'Work',             'ic_work',             11, true),
    ('b1c00000-0000-4000-a000-000000000012', 'TRAVEL',          'Travel',           'ic_travel',           12, true),
    ('b1c00000-0000-4000-a000-000000000013', 'GYM',             'Gym',              'ic_gym',              13, true),
    ('b1c00000-0000-4000-a000-000000000014', 'SUBSCRIPTIONS',   'Subscriptions',    'ic_subscriptions',    14, true),
    ('b1c00000-0000-4000-a000-000000000015', 'TRANSFERS',       'Transfers',        'ic_transfers',        15, true),
    ('b1c00000-0000-4000-a000-000000000016', 'BILLS_UTILITIES', 'Bills & Utilities','ic_bills_utilities',  16, true),
    ('b1c00000-0000-4000-a000-000000000017', 'OTHER',           'Other',            'ic_other',            17, true)
ON CONFLICT (code) DO NOTHING;

-- OTHER is the fallback the categorisation engine falls back to and must always exist
-- and always be active. A transaction that matches no rule still has to land somewhere
-- visible and editable rather than disappearing from the breakdown.
COMMENT ON TABLE budget_insights.bi_category IS
    'The 17 primary spending categories confirmed under CR-BUDGET-INSIGHTS-01. '
    'OTHER is the required fallback and must not be deactivated.';
