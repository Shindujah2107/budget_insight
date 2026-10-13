-- =====================================================================================
-- V1.14.0 — accept a 3- or 4-digit masked account/card tail
-- =====================================================================================
-- Some Sri Lankan bank SMS show only the last three digits of the account or card
-- (e.g. BOC "From A/C No XXXXXXXXXX463"). Until now the service stored exactly four
-- digits, so those messages were recorded against no account.
--
-- A shorter tail is still a masked tail: it identifies the instrument less, never more.
-- The rule stays strict otherwise — never more than four digits, digits only — so a
-- device that sends an unmasked number is still refused (PayloadNormaliser) and could
-- not be stored here even if it were not.
--
-- Existing rows all have four digits and satisfy the wider check; nothing is rewritten.
-- =====================================================================================

ALTER TABLE bi_account
    DROP CONSTRAINT IF EXISTS ck_bi_account_masked_tail;

ALTER TABLE bi_account
    ADD CONSTRAINT ck_bi_account_masked_tail CHECK (masked_tail ~ '^[0-9]{3,4}$');
