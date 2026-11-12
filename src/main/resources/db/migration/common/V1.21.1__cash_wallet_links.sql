-- =====================================================================================
-- V1.21.1  Cash wallet: same-day opening and bank reversals of ATM withdrawals
-- =====================================================================================
-- opening_covers_day: the customer said their opening count already includes the ATM
--   withdrawals made that day, so a withdrawal dated the opening day that the phone imports
--   only afterwards is not added again.
-- linked_transaction_id: on the entry that takes back an ATM withdrawal's cash because the bank
--   reversed it, the withdrawal it answers. One reversal per withdrawal.
-- Additive: both default to "as before".
-- =====================================================================================

ALTER TABLE budget_insights.bi_cash_wallet
    ADD COLUMN IF NOT EXISTS opening_covers_day boolean NOT NULL DEFAULT false;

ALTER TABLE budget_insights.bi_cash_wallet_entry
    ADD COLUMN IF NOT EXISTS linked_transaction_id uuid;

CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_cash_entry_live_link
    ON budget_insights.bi_cash_wallet_entry (linked_transaction_id)
    WHERE linked_transaction_id IS NOT NULL AND reversed = false AND reverses_entry_id IS NULL;
