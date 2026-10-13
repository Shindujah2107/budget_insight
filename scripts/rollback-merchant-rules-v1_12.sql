-- Rollback of V1.12.0's rule data: restores the merchant rules saved in
-- bi_merchant_category_map_bak_v1_12. Run manually (psql). Takes effect immediately: the
-- categoriser re-reads the rules from the database on every call.
-- The code keeps working with the restored REGEX rules; only TERMS rules need the new engine.
-- The column width and the TERMS check value are left in place (harmless).
BEGIN;
DELETE FROM budget_insights.bi_config_change WHERE entity_type = 'MERCHANT_RULE';
DELETE FROM budget_insights.bi_merchant_category_map;
INSERT INTO budget_insights.bi_merchant_category_map
SELECT * FROM budget_insights.bi_merchant_category_map_bak_v1_12;
UPDATE budget_insights.bi_config_changeset SET revision = revision + 1, updated_at = now() WHERE id = 1;
COMMIT;
