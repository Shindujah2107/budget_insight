-- =====================================================================================
-- V1.25.0  The bank of bank-SMS transactions stored before V1.23.0
-- =====================================================================================
-- Since V1.23.0 ingest records the bank of every SMS row (origin_bank_code). Rows stored before
-- that get it here from the parser template that read them, so the app can pair money leaving
-- one bank with money arriving at another (BOC's SMS shows only 3 account digits, so its rows
-- have no account to tell the bank by). Only SMS rows with no origin and no bank yet; re-runnable.
-- =====================================================================================

UPDATE budget_insights.bi_transaction t
SET origin_bank_code = p.bank_code
FROM budget_insights.bi_bank_parser_template p
WHERE t.parser_template_id = p.id
  AND t.origin IS NULL
  AND t.origin_bank_code IS NULL
  AND t.source <> 'MANUAL';
