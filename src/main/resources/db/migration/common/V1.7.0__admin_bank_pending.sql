-- Budget Insights admin: a bank can be added from "Add bank SMS sender", together with its first
-- sender draft.
--
-- is_pending true while the bank was created with a sender draft that is not published yet.
--            Publishing clears it; discarding (or deleting) that draft removes the row again if
--            nothing else uses it. Banks added any other way are never pending.
--
-- Bank codes stay 4-digit CBSL codes (ck_bi_bank_code from V1.6.0 is unchanged): Budget Insights
-- reads bank SMS only.

ALTER TABLE budget_insights.bi_bank
    ADD COLUMN IF NOT EXISTS is_pending boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN budget_insights.bi_bank.is_pending IS
    'Created with an unpublished sender draft; cleared on publish, removed on discard if unused';
