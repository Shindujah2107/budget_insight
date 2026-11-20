-- =====================================================================================
-- V1.28.0  "What changed?" (Premium Insights): its global switch
-- =====================================================================================
-- The switch lives on the budget policy row, beside the alert switches, so it is edited, drafted,
-- published, audited and cached exactly as they are (Admin › Spending › Budget policy). OFF by
-- default: it only makes the feature available to customers who are also entitled to Premium; it
-- never grants Premium. No data changes. Additive and re-runnable.
-- =====================================================================================

ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS what_changed_on boolean NOT NULL DEFAULT false;
