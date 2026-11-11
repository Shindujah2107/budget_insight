-- =====================================================================================
-- V1.20.1  Cash withdrawal category
-- =====================================================================================
-- Taking cash out of an ATM moves money from the account to the customer's pocket; it is not
-- spending. Counting it would also count the same money twice once the customer adds what they
-- spent the cash on. CASH_WITHDRAWAL rows stay in the transaction list but are left out of every
-- spend total, the breakdown, budgets and the 50/30/20 buckets (BiTransactionRepository).
--
-- The three ATM/cash message types now land in it by type alone (layer 0), and ATM rows already
-- stored under Other by that layer move with them. A category the customer chose is kept.
-- =====================================================================================

INSERT INTO budget_insights.bi_category
    (id, code, name_en, name_si, name_ta, icon_key, colour_hex, spend_bucket, sort_order, is_active, created_at)
VALUES
    ('b1c00000-0000-4000-a000-000000000018'::uuid, 'CASH_WITHDRAWAL', 'Cash withdrawal',
     'මුදල් ආපසු ගැනීම', 'பணம் எடுத்தல்', 'ic_cash_withdrawal', '#A8D672', NULL, 18, true, now())
ON CONFLICT DO NOTHING;

UPDATE budget_insights.bi_message_type
SET    fixed_category_code = 'CASH_WITHDRAWAL'
WHERE  code IN ('ATM_RECEIPT', 'CARD_ATM_WITHDRAWAL', 'CASH_WITHDRAWAL')
  AND  (fixed_category_code IS NULL OR fixed_category_code = 'OTHER');

UPDATE budget_insights.bi_transaction t
SET    category_id = c.id,
       auto_category_id = c.id
FROM   budget_insights.bi_category c,
       budget_insights.bi_bank_parser_template p
WHERE  c.code = 'CASH_WITHDRAWAL'
  AND  p.id = t.parser_template_id
  AND  p.message_type IN ('ATM_RECEIPT', 'CARD_ATM_WITHDRAWAL', 'CASH_WITHDRAWAL')
  AND  t.is_user_categorised = false;
