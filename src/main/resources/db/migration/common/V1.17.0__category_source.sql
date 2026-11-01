-- Budget Insights: why each transaction has its category, and per-customer merchant choices.
--
-- Until now a transaction no rule matched was stored as OTHER exactly like one a rule (or the
-- customer) deliberately put in OTHER, so the app could not say "this needs a category" without
-- also flagging ATM receipts and other deliberate Others. category_source records the reason:
-- RULE, USER or FALLBACK (nothing matched).

ALTER TABLE budget_insights.bi_transaction
    ADD COLUMN IF NOT EXISTS category_source varchar(16);

ALTER TABLE budget_insights.bi_transaction
    DROP CONSTRAINT IF EXISTS ck_bi_txn_category_source;
ALTER TABLE budget_insights.bi_transaction
    ADD CONSTRAINT ck_bi_txn_category_source
    CHECK (category_source IS NULL OR category_source IN ('RULE', 'USER', 'FALLBACK'));

COMMENT ON COLUMN budget_insights.bi_transaction.category_source IS
    'RULE (merchant rule or message type), USER (customer chose), FALLBACK (nothing matched, filed under OTHER)';

-- Backfill, deterministic part. A customer's choice is USER; any category other than OTHER can only
-- have come from a rule or the message type. Rows still NULL after this are in OTHER without a
-- customer choice: whether a rule put them there can only be answered by running today's rules, so
-- CategorySourceBackfill does that once at startup (only for rows still NULL, so it is idempotent).
UPDATE budget_insights.bi_transaction
   SET category_source = 'USER'
 WHERE category_source IS NULL AND is_user_categorised = true;

UPDATE budget_insights.bi_transaction t
   SET category_source = 'RULE'
  FROM budget_insights.bi_category c
 WHERE t.category_source IS NULL
   AND c.id = t.category_id
   AND c.code <> 'OTHER';

-- "Needs a category" totals per customer and period read only FALLBACK rows.
CREATE INDEX IF NOT EXISTS ix_bi_txn_user_fallback_date
    ON budget_insights.bi_transaction (user_id, txn_date)
    WHERE category_source = 'FALLBACK' AND deleted_at IS NULL;

-- One customer's "always use this category for this merchant". Never shared between customers;
-- erased and exported with the rest of their data.
CREATE TABLE IF NOT EXISTS budget_insights.bi_user_merchant_category (
    id            uuid PRIMARY KEY,
    user_id       uuid         NOT NULL,
    merchant_key  varchar(128) NOT NULL,
    category_id   uuid         NOT NULL REFERENCES budget_insights.bi_category (id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    CONSTRAINT ux_bi_umc_user_merchant UNIQUE (user_id, merchant_key)
);
