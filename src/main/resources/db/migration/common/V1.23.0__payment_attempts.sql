-- =====================================================================================
-- V1.23.0  Payment attempts and recorded payments paired with bank SMS
-- =====================================================================================
-- Some banks announce a payment only as "… LKR 6,800.00 attempted. Please use code …" and send
-- no SMS when it goes through (ComBank Digital transfers and bill payments, for some customers).
--
--   * PAYMENT_ATTEMPT is a message type that is never a transaction. On the phone the code is
--     removed from the message the moment it is read; only the amount and the channel are used,
--     to ask the customer "Did this payment go through?". Ingest refuses the type outright.
--   * The ComBank attempt template below (field map kind ATTEMPT) reads only "ComBank Digital-
--     <channel> <currency> <amount> attempted." and stops there: the code and the app hash after
--     it are never part of the match.
--   * bi_transaction gains origin (ATTEMPT: a confirmed attempt; BILL: a bill marked paid),
--     origin_bank_code, user_bill_id and linked_key. A bank SMS for a payment the customer already
--     recorded (or the reverse) is paired with it, one to one, instead of being counted twice
--     (PaymentPairing).
--
-- Additive and re-runnable. The template is published with a release row, as V1.20.0 did: it
-- comes from the owner's own SMS (2026-11-14), and an older app discards OTP messages before any
-- template is tried, so it changes nothing there.
-- =====================================================================================

ALTER TABLE budget_insights.bi_transaction ADD COLUMN IF NOT EXISTS origin varchar(16);
ALTER TABLE budget_insights.bi_transaction ADD COLUMN IF NOT EXISTS origin_bank_code varchar(16);
ALTER TABLE budget_insights.bi_transaction ADD COLUMN IF NOT EXISTS user_bill_id uuid;
ALTER TABLE budget_insights.bi_transaction ADD COLUMN IF NOT EXISTS linked_key varchar(128);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_bi_txn_origin') THEN
    ALTER TABLE budget_insights.bi_transaction
      ADD CONSTRAINT ck_bi_txn_origin CHECK (origin IS NULL OR origin IN ('ATTEMPT', 'BILL'));
  END IF;
END $$;

-- One SMS pairs with one recorded payment, and a resent one finds the pair.
CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_txn_linked_key
    ON budget_insights.bi_transaction (user_id, linked_key)
    WHERE linked_key IS NOT NULL;
-- Recorded payments still waiting for their bank SMS.
CREATE INDEX IF NOT EXISTS ix_bi_txn_unpaired_recorded
    ON budget_insights.bi_transaction (user_id, amount)
    WHERE origin IS NOT NULL AND linked_key IS NULL AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_bi_txn_user_bill
    ON budget_insights.bi_transaction (user_bill_id)
    WHERE user_bill_id IS NOT NULL;

INSERT INTO budget_insights.bi_message_type
    (code, display_name, logical_type, default_direction, default_instrument, fixed_category_code)
VALUES
    ('PAYMENT_ATTEMPT', 'Payment attempted (asks the customer)', 'PAYMENT', 'DEBIT', 'BANK', NULL)
ON CONFLICT (code) DO NOTHING;

INSERT INTO budget_insights.bi_bank_parser_template
    (id, bank_code, message_type, pattern, field_map, template_version, is_active)
VALUES
    ('b1e70000-0000-4000-a000-000000000020'::uuid, '7056', 'PAYMENT_ATTEMPT',
     $re$^ComBank Digital-(?<merchant>[A-Za-z][A-Za-z ]*[A-Za-z]) (?<currency>[A-Z]{3}) (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) attempted\.$re$,
     $fm$ {"fieldMapVersion": 1, "kind": "ATTEMPT", "senders": ["COMBANK", "ComBank_Q+"], "logicalType": "PAYMENT", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"group": "currency", "onlyAccepted": "LKR", "otherwise": "SERVER_REJECTS_BY_DESIGN"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"policy": "NOT_IN_SMS"}, "evidence": {"status": "REAL_SAMPLES", "realSamples": 1, "source": "owner's own SMS 2026-11-14 (code masked by the owner)"}, "notes": ["kind ATTEMPT: never a transaction. The phone removes the code from the message first and only asks the customer whether the payment went through.", "The merchant is the channel (e.g. Transfer to Other Banks, Bill Payment); the message names no payee.", "The match stops at 'attempted.': the code and the app hash after it are never read."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb,
     1, true)
ON CONFLICT ON CONSTRAINT ux_bi_tpl_bank_type_version DO NOTHING;

INSERT INTO budget_insights.bi_reference_release
    (version, published_by, published_by_role, reason, change_count, summary, diff)
SELECT COALESCE(MAX(version), 0) + 1, NULL, 'FLYWAY',
       'ComBank payment attempt template (V1.23.0)', 1,
       '{"templates": 1}'::jsonb,
       '[{"entity": "TEMPLATE", "op": "CREATE", "bankCode": "7056", "messageType": "PAYMENT_ATTEMPT"}]'::jsonb
FROM budget_insights.bi_reference_release
WHERE NOT EXISTS (SELECT 1 FROM budget_insights.bi_reference_release
                  WHERE reason = 'ComBank payment attempt template (V1.23.0)');
