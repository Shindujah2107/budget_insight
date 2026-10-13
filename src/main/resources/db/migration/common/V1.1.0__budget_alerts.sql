SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights R6 — the record of an alert having been raised (BI-25).
--
-- WHY A TABLE AT ALL
--   The threshold evaluation already exists: BudgetServiceImpl computes APPROACHING and
--   EXCEEDED for the summary on every read. What it cannot do is tell you whether the
--   customer has already been told. §24 requires that crossing a category limit raises
--   "exactly one in-app alert", and exactly-once is a statement about history, which has
--   to be written down somewhere. Without this table every ingest batch that left a
--   category over its cap would raise the alert again.
--
-- WHY TWO UNIQUE INDEXES AND NOT ONE CONSTRAINT
--   category_id is NULL for an alert about the overall budget rather than one category.
--   In PostgreSQL two NULLs do not collide, so a single UNIQUE (user_id, period_start,
--   category_id, alert_type) would silently fail to dedupe exactly the rows it was added
--   to dedupe -- every overall-budget alert would insert again. The same nullable-column
--   behaviour already bites bi_account.bank_code, which is why ingest refuses to resolve
--   an account without a tail. Two partial indexes make the rule hold for both shapes.
--
-- WHY THE AMOUNTS ARE COPIED IN
--   amount_at_raise and limit_at_raise record what was true at the moment of the crossing.
--   A customer who later raises their cap, or re-categorises a transaction out of the
--   category, would otherwise be looking at an alert whose numbers no longer reconcile
--   with anything -- and the alert is meant to be evidence of a moment, not a live view.
--   The live view is the summary.
--
-- NOT PURGED HERE
--   Erasure and retention are R7. This table holds user_id and is in scope for both; the
--   erasure path added there must include it.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS budget_insights.bi_budget_alert (
    id                  uuid                        NOT NULL,
    user_id             uuid                        NOT NULL,
    tenant_id           uuid                        NOT NULL,

    -- The budget period the crossing belongs to, matching bi_budget.period_start.
    period_start        date                        NOT NULL,

    -- NULL means the alert is about the overall monthly target, not one category.
    category_id         uuid,

    alert_type          varchar(24)                 NOT NULL,

    -- The threshold that fired, for an APPROACHING alert. NULL on an EXCEEDED one, which
    -- fires at the cap itself rather than at a configurable percentage of it.
    threshold_pct       smallint,

    amount_at_raise     numeric(19, 2)              NOT NULL,
    limit_at_raise      numeric(19, 2)              NOT NULL,

    raised_at           timestamp(6) with time zone NOT NULL DEFAULT now(),

    -- Set when the customer has seen it. Never unset: an alert that can be un-acknowledged
    -- is an alert that can be shown twice, which is the thing this table exists to prevent.
    acknowledged_at     timestamp(6) with time zone,

    CONSTRAINT pk_bi_budget_alert PRIMARY KEY (id),
    CONSTRAINT fk_bi_alert_category FOREIGN KEY (category_id)
        REFERENCES budget_insights.bi_category (id),
    CONSTRAINT ck_bi_alert_type CHECK (alert_type IN (
        'CATEGORY_APPROACHING',
        'CATEGORY_EXCEEDED',
        'BUDGET_EXCEEDED'
    )),
    CONSTRAINT ck_bi_alert_amounts CHECK (amount_at_raise >= 0 AND limit_at_raise >= 0)
);

-- One alert per category per period per type.
CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_alert_user_period_cat_type
    ON budget_insights.bi_budget_alert (user_id, period_start, category_id, alert_type)
    WHERE category_id IS NOT NULL;

-- And one per period per type for the overall budget, where category_id is NULL and the
-- index above cannot see it.
CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_alert_user_period_overall_type
    ON budget_insights.bi_budget_alert (user_id, period_start, alert_type)
    WHERE category_id IS NULL;

-- The read path: this customer's alerts for the current period, newest first.
CREATE INDEX IF NOT EXISTS ix_bi_alert_user_raised
    ON budget_insights.bi_budget_alert (user_id, raised_at DESC);
