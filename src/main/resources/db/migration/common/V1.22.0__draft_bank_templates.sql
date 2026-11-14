-- =====================================================================================
-- V1.22.0  Seven known bank SMS formats as template DRAFTS for admin review
-- =====================================================================================
-- These formats were held back from the V1.3.0 seed ("review tier": one third-party sample
-- each, leadsgen-tech/salli, Apache-2.0). They are added as drafts in the admin changeset
-- (bi_config_change, op CREATE), not as live templates: nothing reaches a phone until an
-- admin reviews them in Configuration > Changes and publishes. Discarding a draft removes it.
--
--   7056 WALLET_PAYMENT     "Your Q+ transaction at <payee> for LKR <amt> has been successful."
--   7056 ONLINE_TRANSFER    "Dear Customer,Your Fund Transfer of Rs. <amt> to Account Number ..."
--   7463 CEFTS_INWARD       "Inward CEFTS trf of LKR <amt> (<name>) from: ... credited to your A/C"
--   7463 CEFTS_OUTWARD      "IB CEFTS trf of LKR <amt> & charge of LKR <fee> (...) to: <payee> debited"
--   7463 INTEREST_CREDIT    "Profit of your Savings A/C ... Profit Amount (...)LKR <amt>."
--   7083 CARD_REFUND        "TRANSACTION REVERSAL, Credit account:...,Location:<m>,Amount:<amt> LKR"
--   7010 CASH_CHEQUE_DEBIT  "Cash Cheque Debit  CHQ/NO <n> Rs <amt> From A/C No XXXXXXXXXX870."
--
-- Not added: ComBank "Bill Payment in the amount of LKR ... was received" (it may confirm a
-- payment that already arrived as a debit SMS, so reading it could count the money twice).
--
-- A draft is skipped when that bank already has an active template of the same message type,
-- or already has a template draft of that type (e.g. one an admin built in the wizard). The
-- publish check (AdminPublishValidator, REGRESSION) also refuses to publish two templates that
-- read the same stored sample, which catches an admin template of another type for the same SMS.
--
-- created_by 00000000-0000-0000-0000-000000000000 = this migration. Re-runnable.
-- =====================================================================================

WITH v (id, bank_code, message_type, pattern, field_map) AS (
  VALUES
    ('b1e70000-0000-4000-a000-000000000013'::uuid, '7056', 'WALLET_PAYMENT',
     $re$^Your Q\+ transaction at (?<merchant>.+?) for (?<currency>[A-Z]{3}) (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) has been successful\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["ComBank_Q+", "COMBANK"], "logicalType": "PAYMENT", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"group": "currency", "onlyAccepted": "LKR", "otherwise": "SERVER_REJECTS_BY_DESIGN"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"policy": "NOT_IN_SMS"}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/combank.txt"}, "notes": ["Q+ pays merchants, bills and top-ups by QR or app. The payee is the merchant.", "Which of the two ComBank senders sends it is not evidenced, so both are listed.", "The reference number after the amount is not read."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb),

    ('b1e70000-0000-4000-a000-000000000014'::uuid, '7056', 'ONLINE_TRANSFER',
     $re$^Dear Customer, ?Your (?<merchant>Fund Transfer) of Rs\. ?(?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) to Account Number [X*]*[0-9]+ is successful\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["ComBank_Q+", "COMBANK"], "logicalType": "TRANSFER", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"policy": "NOT_IN_SMS", "reason": "The only account in the SMS is the payee's. It is matched, never captured, so it is not mistaken for the customer's own account."}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/combank.txt"}, "notes": ["No payee name in the SMS, so the merchant is the words Fund Transfer.", "Rejected transfers (`has been rejected`) do not match."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb),

    ('b1e70000-0000-4000-a000-000000000015'::uuid, '7463', 'CEFTS_INWARD',
     $re$^Inward CEFTS trf of LKR (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) \((?<merchant>[^)]*)\) +from: .*? credited to your A/C \*+(?<tail>[0-9]{4})\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["AMANABANK"], "logicalType": "TRANSFER", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "CREDIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/amana.txt"}, "notes": ["The name in brackets (the sender of the money) is the merchant.", "The IBFT reference after from: is matched, not captured."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb),

    ('b1e70000-0000-4000-a000-000000000016'::uuid, '7463', 'CEFTS_OUTWARD',
     $re$^IB CEFTS trf of LKR (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2})(?: & charge of LKR [0-9][0-9,]*(?:\.[0-9]{1,2})?)? \([^)]*\) to: (?<merchant>.+?) debited from your A/C \*+(?<tail>[0-9]{4})\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["AMANABANK"], "logicalType": "TRANSFER", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/amana.txt"}, "notes": ["amount is the transfer only. The CEFTS charge has no column in bi_transaction and is not read.", "The payee (and the payee's bank) after to: is the merchant."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb),

    ('b1e70000-0000-4000-a000-000000000017'::uuid, '7463', 'INTEREST_CREDIT',
     $re$^(?<merchant>Profit) of your Savings A/C \*+[0-9]{4} has been distributed to A/C \*+(?<tail>[0-9]{4})\. Profit Amount \(Subject to Withholding Tax\) ?LKR (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2})\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["AMANABANK"], "logicalType": "ACCOUNT_TRANSACTION", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "CREDIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/amana.txt"}, "notes": ["Amana is an Islamic bank: savings earn profit, not interest. The tail is the account the profit was paid to."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb),

    ('b1e70000-0000-4000-a000-000000000018'::uuid, '7083', 'CARD_REFUND',
     $re$^TRANSACTION REVERSAL, Credit account:[0-9]+\*+(?<tail>[0-9]{4}),Location:(?<merchant>.+?),Amount:(?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) (?<currency>[A-Z]{3}),Av\.Bal:.+?,Date:(?<date>[0-9]{2}\.[0-9]{2}\.[0-9]{2}),Time:(?<time>[0-9]{2}:[0-9]{2})$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["HNB"], "logicalType": "REFUND_OR_REVERSAL", "instrumentType": "CARD", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"group": "currency", "onlyAccepted": "LKR", "otherwise": "SERVER_REJECTS_BY_DESIGN"}, "direction": {"value": "CREDIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"group": "date", "format": "dd.MM.yy"}, "txnTime": {"group": "time", "format": "HH:mm"}, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/hnb.txt"}, "notes": ["Same layout as the HNB card alert (CARD_ALERT), with money coming back. The merchant keeps the country suffix, as in CARD_ALERT."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb),

    ('b1e70000-0000-4000-a000-000000000019'::uuid, '7010', 'CASH_CHEQUE_DEBIT',
     $re$^(?<merchant>Cash Cheque) Debit +CHQ/NO [0-9]+ Rs (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) From A/C No X+[0-9]+\. Balance available Rs $re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["BOC"], "logicalType": "CHEQUE", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"policy": "ABSENT", "reason": "SMS exposes 3 digits (A/C No XXXXXXXXXX870), as in the BOC ACCOUNT_TXN template. Never padded, never invented."}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/boc.txt"}, "notes": ["No date in the SMS: txnDate is the SMS received date.", "The cheque number is matched, not captured."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb)
),
todo AS (
  SELECT v.* FROM v
  WHERE NOT EXISTS (SELECT 1 FROM budget_insights.bi_bank_parser_template t
                    WHERE t.bank_code = v.bank_code AND t.message_type = v.message_type AND t.is_active)
    AND NOT EXISTS (SELECT 1 FROM budget_insights.bi_config_change c
                    WHERE c.entity_type = 'TEMPLATE'
                      AND (c.target_id = v.id
                           OR (c.payload ->> 'bankCode' = v.bank_code
                               AND c.payload ->> 'messageType' = v.message_type)))
),
added AS (
  INSERT INTO budget_insights.bi_config_change
      (id, entity_type, target_id, op, payload, before_state, created_by, created_at, updated_by, updated_at)
  SELECT gen_random_uuid(), 'TEMPLATE', todo.id, 'CREATE',
         jsonb_build_object('bankCode', todo.bank_code, 'messageType', todo.message_type,
                            'pattern', todo.pattern, 'fieldMap', todo.field_map,
                            'templateVersion', 1, 'active', true, 'builderSpec', NULL),
         NULL, '00000000-0000-0000-0000-000000000000'::uuid, now(),
         '00000000-0000-0000-0000-000000000000'::uuid, now()
  FROM todo
  ON CONFLICT ON CONSTRAINT ux_bi_cc_target DO NOTHING
  RETURNING 1
)
UPDATE budget_insights.bi_config_changeset
SET revision = revision + 1, updated_at = now()
WHERE id = 1 AND EXISTS (SELECT 1 FROM added);
