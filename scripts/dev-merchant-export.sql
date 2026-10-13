-- Export DEV merchants for the categorisation before/after report (V1.12.0).
--
-- Run against the DEV Budget Insights database (SSM tunnel, see scripts/dev-seed/README.md):
--   psql "host=localhost port=5434 dbname=moipay_budget_insights user=moipay_admin sslmode=require" \
--        -f scripts/dev-merchant-export.sql
-- Writes dev-merchants.csv in the current folder: one row per distinct
-- merchant | message type | direction | category the engine gave it | transactions | customers.
-- Counts and merchant text only: no amounts, users or SMS bodies. Run BEFORE deploying V1.12.0
-- (the "before" column is the engine's category at ingest time, auto_category_id).
\copy (SELECT replace(replace(t.merchant, '|', '/'), E'\n', ' '), COALESCE(p.message_type, CASE WHEN t.source = 'MANUAL' THEN 'MANUAL' ELSE '' END), t.direction, COALESCE(ac.code, 'OTHER'), COUNT(*), COUNT(DISTINCT t.user_id) FROM budget_insights.bi_transaction t LEFT JOIN budget_insights.bi_bank_parser_template p ON p.id = t.parser_template_id LEFT JOIN budget_insights.bi_category ac ON ac.id = t.auto_category_id WHERE t.deleted_at IS NULL AND t.merchant IS NOT NULL GROUP BY 1, 2, 3, 4 ORDER BY 5 DESC) TO 'dev-merchants.csv' WITH (FORMAT text, DELIMITER '|')
