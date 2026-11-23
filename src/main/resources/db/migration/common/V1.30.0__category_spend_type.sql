-- Ask MR: the kind of spending each category holds, so answers fit it.
--
--   BILL       fixed payments that arrive as bills (utilities, subscriptions, loans): a rise is
--              "a bigger bill, not extra spending", compared bill by bill, never "buy less".
--   ESSENTIAL  needed, amount varies (transport, health, education, work): counts and averages,
--              never a cut-back suggestion for health or education.
--   FLEXIBLE   spending by choice (dining, shopping, entertainment…): today's behaviour.
--   EXCLUDED   money moved rather than spent (transfers, cash withdrawals): not judged.
--   NULL       not set (OTHER and new categories): treated like FLEXIBLE, without a label.
--
-- Additive only: a new nullable column with a check, and defaults for rows that have none yet,
-- so a re-run never overwrites an admin's choice. Edited in the Admin Dashboard (Categories),
-- published through the usual changeset.

ALTER TABLE budget_insights.bi_category
    ADD COLUMN IF NOT EXISTS spend_type varchar(10);

ALTER TABLE budget_insights.bi_category
    DROP CONSTRAINT IF EXISTS ck_bi_category_spend_type;
ALTER TABLE budget_insights.bi_category
    ADD CONSTRAINT ck_bi_category_spend_type
    CHECK (spend_type IS NULL OR spend_type IN ('BILL', 'ESSENTIAL', 'FLEXIBLE', 'EXCLUDED'));

COMMENT ON COLUMN budget_insights.bi_category.spend_type IS
    'Ask MR spending type: BILL, ESSENTIAL, FLEXIBLE, EXCLUDED, or NULL (not set). Edited in the Admin Dashboard';

UPDATE budget_insights.bi_category SET spend_type = 'BILL'
 WHERE spend_type IS NULL
   AND code IN ('UTILITIES', 'BILLS_UTILITIES', 'SUBSCRIPTIONS', 'DEBT_PAYMENTS');
UPDATE budget_insights.bi_category SET spend_type = 'ESSENTIAL'
 WHERE spend_type IS NULL
   AND code IN ('TRANSPORT', 'HEALTH_CARE', 'EDUCATION', 'WORK');
UPDATE budget_insights.bi_category SET spend_type = 'FLEXIBLE'
 WHERE spend_type IS NULL
   AND code IN ('DINING_OUT', 'ENTERTAINMENT', 'PERSONAL_CARE', 'SHOPPING', 'GIFTS_DONATION',
                'TRAVEL', 'GYM');
UPDATE budget_insights.bi_category SET spend_type = 'EXCLUDED'
 WHERE spend_type IS NULL
   AND code IN ('TRANSFERS', 'CASH_WITHDRAWAL');

-- The suggestion for a bill that rose: check it, never "use less". Admin-editable like the others.
INSERT INTO budget_insights.bi_ask_mr_tip (id, code, text_en, text_si, text_ta, sort_order)
VALUES (
    md5('ask-mr-tip:check-bill')::uuid,
    'check-bill',
    'If a {category} bill looks higher than you expected, it may be worth checking it with the provider.',
    '{category} බිල්පතක් ඔබ බලාපොරොත්තු වූවාට වඩා වැඩි ලෙස පෙනේ නම්, සේවා සපයන්නා සමඟ එය පරීක්ෂා කිරීම වටී.',
    '{category} கட்டணம் நீங்கள் எதிர்பார்த்ததை விட அதிகமாகத் தெரிந்தால், சேவை வழங்குநரிடம் சரிபார்ப்பது பயனுள்ளது.',
    50)
ON CONFLICT (code) DO NOTHING;
