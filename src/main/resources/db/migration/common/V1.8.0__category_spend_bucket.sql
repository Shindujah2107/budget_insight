-- Budget Insights admin: the 50/30/20 bucket of each spending category.
--
-- Until now the bucket lists lived in YAML (recommendations.needs / wants / savings), so moving a
-- category between buckets, or bucketing a category added in the Admin Dashboard, needed a build.
-- From V1.8.0 the bucket is a column on the category, edited in the dashboard and published with
-- the rest of the reference data. The YAML lists remain only as a fallback.
--
-- NULL means "not counted": the category's spend is reported as unbucketed. OTHER and TRANSFERS
-- stay NULL on purpose (see the comment on recommendations in application-aws-*.yml).

ALTER TABLE budget_insights.bi_category
    ADD COLUMN IF NOT EXISTS spend_bucket varchar(8);

ALTER TABLE budget_insights.bi_category
    DROP CONSTRAINT IF EXISTS ck_bi_category_spend_bucket;
ALTER TABLE budget_insights.bi_category
    ADD CONSTRAINT ck_bi_category_spend_bucket
    CHECK (spend_bucket IS NULL OR spend_bucket IN ('NEEDS', 'WANTS', 'SAVINGS'));

COMMENT ON COLUMN budget_insights.bi_category.spend_bucket IS
    '50/30/20 bucket: NEEDS, WANTS, SAVINGS, or NULL (not counted). Edited in the Admin Dashboard';

-- Backfill: the mapping approved on 20 September 2026, identical to the YAML lists. Only rows with
-- no bucket yet, so a re-run never overwrites an admin's choice.
UPDATE budget_insights.bi_category SET spend_bucket = 'NEEDS'
 WHERE spend_bucket IS NULL
   AND code IN ('BILLS_UTILITIES', 'UTILITIES', 'TRANSPORT', 'HEALTH_CARE', 'EDUCATION', 'WORK');
UPDATE budget_insights.bi_category SET spend_bucket = 'WANTS'
 WHERE spend_bucket IS NULL
   AND code IN ('DINING_OUT', 'ENTERTAINMENT', 'SHOPPING', 'SUBSCRIPTIONS', 'TRAVEL',
                'GIFTS_DONATION', 'GYM', 'PERSONAL_CARE');
UPDATE budget_insights.bi_category SET spend_bucket = 'SAVINGS'
 WHERE spend_bucket IS NULL
   AND code IN ('DEBT_PAYMENTS');
