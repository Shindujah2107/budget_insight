-- =====================================================================================
-- V1.27.0  Bill suggestions: found bills are suggested, never added automatically
-- =====================================================================================
--   * SUGGESTED: a recurring payment the bills radar found, waiting for the customer to review
--     it ("Track payment" or "Not recurring"). Not a bill yet: no reminders, no questions, not in
--     the bills list or total. Bills found before this release keep their status (DETECTED).
--   * user_schedule: the customer tracked the bill and set its amount, repeat and due date;
--     detection never changes them again (it only notes the latest payment).
--   * evidence: the payments the suggestion was found from (up to 12 transaction ids), shown as
--     "Why this was suggested".
-- No data changes. Additive and re-runnable.
-- =====================================================================================

ALTER TABLE budget_insights.bi_user_bill ADD COLUMN IF NOT EXISTS user_schedule boolean NOT NULL DEFAULT false;
ALTER TABLE budget_insights.bi_user_bill ADD COLUMN IF NOT EXISTS evidence varchar(1000);

ALTER TABLE budget_insights.bi_user_bill DROP CONSTRAINT IF EXISTS ck_bi_user_bill_status;
ALTER TABLE budget_insights.bi_user_bill ADD CONSTRAINT ck_bi_user_bill_status
    CHECK (status IN ('SUGGESTED', 'DETECTED', 'CONFIRMED', 'DISMISSED', 'MANUAL'));
