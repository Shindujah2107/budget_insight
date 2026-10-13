-- Budget Insights admin: bank edits and the budget policy go through the same draft -> publish
-- flow as categories, rules, senders and templates, so they appear in Review & publish and in
-- release history, and can be restored.
--
-- target_id stays a uuid: a bank's is derived from its code (name-based UUID of "BANK:<code>"),
-- the policy's is fixed. No other column changes.

ALTER TABLE budget_insights.bi_config_change DROP CONSTRAINT IF EXISTS ck_bi_cc_entity;
ALTER TABLE budget_insights.bi_config_change
    ADD CONSTRAINT ck_bi_cc_entity
    CHECK (entity_type IN ('CATEGORY', 'MERCHANT_RULE', 'SENDER', 'TEMPLATE', 'BANK', 'BUDGET_POLICY'));
