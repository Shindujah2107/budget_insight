-- Budget Insights admin: the budget policy every customer's budget, alerts and recommendations use.
--
-- One row (id = 1). Edited in the Admin Dashboard (Configuration > Budget Policy) as a draft and
-- applied on publish, like categories and rules; every change is in bi_reference_release.diff.
-- The YAML values (recommendations.*-pct, trailing-months, default-category-alert-threshold-pct,
-- pace-tolerance-pct) are used only if this row is missing.
--
-- The seed equals the values that were live in YAML, so deploying this changes nothing for
-- customers until an admin publishes a new value.

CREATE TABLE IF NOT EXISTS budget_insights.bi_budget_policy (
    id                        smallint     NOT NULL DEFAULT 1,
    needs_pct                 smallint     NOT NULL,
    wants_pct                 smallint     NOT NULL,
    savings_pct               smallint     NOT NULL,
    trailing_months           smallint     NOT NULL,
    default_alert_pct         smallint     NOT NULL,
    pace_tolerance_pct        smallint     NOT NULL,
    alert_cat_approaching_on  boolean      NOT NULL DEFAULT true,
    alert_cat_exceeded_on     boolean      NOT NULL DEFAULT true,
    alert_budget_exceeded_on  boolean      NOT NULL DEFAULT true,
    updated_at                timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_budget_policy PRIMARY KEY (id),
    CONSTRAINT ck_bi_bp_singleton CHECK (id = 1),
    CONSTRAINT ck_bi_bp_split CHECK (
        needs_pct BETWEEN 0 AND 100 AND wants_pct BETWEEN 0 AND 100 AND savings_pct BETWEEN 0 AND 100
        AND needs_pct + wants_pct + savings_pct = 100),
    CONSTRAINT ck_bi_bp_trailing CHECK (trailing_months BETWEEN 1 AND 12),
    CONSTRAINT ck_bi_bp_alert CHECK (default_alert_pct BETWEEN 50 AND 95),
    CONSTRAINT ck_bi_bp_pace CHECK (pace_tolerance_pct BETWEEN 0 AND 25)
);

COMMENT ON TABLE budget_insights.bi_budget_policy IS
    'Budget policy (single row). Edited in Admin > Configuration > Budget Policy, applied on publish';

INSERT INTO budget_insights.bi_budget_policy
    (id, needs_pct, wants_pct, savings_pct, trailing_months, default_alert_pct, pace_tolerance_pct)
VALUES (1, 50, 30, 20, 3, 80, 0)
ON CONFLICT (id) DO NOTHING;

-- A category limit saved without the customer choosing a threshold now stores NULL, meaning "use
-- the policy's default_alert_pct" -- so a later change of the default reaches it. Existing rows keep
-- the value they have (80 stored before this release cannot be told apart from a chosen 80).
ALTER TABLE budget_insights.bi_budget_category_limit ALTER COLUMN alert_threshold_pct DROP NOT NULL;
ALTER TABLE budget_insights.bi_budget_category_limit ALTER COLUMN alert_threshold_pct DROP DEFAULT;
COMMENT ON COLUMN budget_insights.bi_budget_category_limit.alert_threshold_pct IS
    'Customer-chosen approaching-limit %; NULL = bi_budget_policy.default_alert_pct';
