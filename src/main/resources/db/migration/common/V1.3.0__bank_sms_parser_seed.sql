SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights - bank SMS sender allowlist and parser templates, first DEV seed (D-1).
--
-- SCOPE
--   Eleven banks are in the DEV test scope. Five have templates here, limited to the formats
--   the R&D classed SAFE TO SEED (two or more real samples, unambiguous meaning):
--     7056 Commercial Bank   4 templates   senders COMBANK, ComBank_Q+
--     7083 Hatton National   4 templates   sender  HNB
--     7010 Bank of Ceylon    1 template    sender  BOC
--     7287 Seylan Bank       1 template    sender  SEYLAN
--     7463 Amana Bank        1 template    sender  AMANABANK
--   bank_code is moipay_core.bank_list.bank_code as text, confirmed against DEV 2026-09-21.
--
-- NOT SEEDED
--   * Sampath 7278, NTB 7162, NSB 7719: real evidence exists but is single-source and in
--     review. Not in this first seed.
--   * DFCC 7454, Standard Chartered 7038: no real SMS body and no verified sender.
--   * HSBC 7092: sender verified (HSBC Alerts), message format not verified.
--   * Single-sample formats of the seeded banks (ComBank ATM, Q+ payment, Q+ fund transfer,
--     bill payment, BOC cheque debit, HNB reversal, Amana CEFTS in/out and profit): in review.
--   * OTP, login/security, marketing, declined, rejected, cancelled, pending/request-only and
--     informational messages. Also the OTP-only sender BOCONLINE and the marketing sender HSBC SL.
--     The allowlist has no exclusion column, so every row is a parser sender.
--
-- ACCOUNT TAILS
--   A tail is captured only where the SMS shows exactly the four digits. BOC (3 digits) and HNB
--   account alerts (2 digits) declare field_map.maskedTail.policy = ABSENT: the masked account is
--   matched but never captured, the device sends no maskedTail, and the transaction is stored
--   with account_id NULL. Nothing is padded or truncated. No pattern puts account digits into
--   merchant. ck_bi_account_masked_tail is unchanged.
--
-- DIRECTION AND DATE
--   Every template states its direction, as a fixed value or a map from the matched word.
--   None relies on the server's DEBIT default. Where the SMS has no date, field_map.txnDate
--   is SMS_RECEIVED_DATE in Asia/Colombo.
--
-- HNB CHARGE / REFUND
--   7083 ACCOUNT_CHARGE accepts a space or a line break before `Remarks` and maps
--   `debit ed` to DEBIT and `credit ed` to CREDIT (e.g. an FX markup refund).
--
-- MESSAGE_TYPE NAMES THE GRAMMAR
--   One row per grammar. field_map.logicalType carries the concept. All rows are
--   template_version 1, and ux_bi_tpl_bank_type_version has no collisions.
--
-- ACTIVE, AND WHAT KEEPS THAT SAFE
--   Rows are active so DEV can be tested. Nothing reaches a device until the environment
--   turns on moipay.budget-insights.enabled and .ingest.enabled and the customer has granted
--   consent (InsightsIngestController.guard). Both flags default to false in every profile.
--   Every format rests on third-party redacted samples (leadsgen-tech/salli, Apache-2.0),
--   evidence status COMMUNITY_SOURCE_NEEDS_VALIDATION, and Compliance gate 2 is still open.
--
-- PATTERNS
--   Anchored with ^, no inline flags, no lookbehind, named groups with letter/digit names: the
--   common subset of java.util.regex, ICU and ECMAScript 2018. Each real sample matches exactly
--   one template. Checked by BankSmsTemplateSeedTest against
--   src/test/resources/bank-sms/redacted-samples.json.
--
-- Re-runnable: ON CONFLICT DO NOTHING on both business keys. Ids are fixed so a template id
-- means the same thing in every environment (bi_transaction.parser_template_id).
-- =====================================================================================

INSERT INTO budget_insights.bi_sms_sender_allowlist
    (id, bank_code, sender_id, country_code, is_active)
VALUES
    ('b1e50000-0000-4000-a000-000000000001'::uuid, '7056', 'COMBANK', 'LK', true),
    ('b1e50000-0000-4000-a000-000000000002'::uuid, '7056', 'ComBank_Q+', 'LK', true),
    ('b1e50000-0000-4000-a000-000000000003'::uuid, '7083', 'HNB', 'LK', true),
    ('b1e50000-0000-4000-a000-000000000004'::uuid, '7010', 'BOC', 'LK', true),
    ('b1e50000-0000-4000-a000-000000000005'::uuid, '7287', 'SEYLAN', 'LK', true),
    ('b1e50000-0000-4000-a000-000000000006'::uuid, '7463', 'AMANABANK', 'LK', true)
ON CONFLICT ON CONSTRAINT ux_bi_sender_bank_sender DO NOTHING;

INSERT INTO budget_insights.bi_bank_parser_template
    (id, bank_code, message_type, pattern, field_map, template_version, is_active)
VALUES
    -- 7056 Commercial Bank ---------------------------------------------------------
    ('b1e70000-0000-4000-a000-000000000001'::uuid, '7056', 'CARD_PURCHASE',
     $re$^Dear Cardholder, Purchase at (?<merchant>.+?) for (?<currency>[A-Z]{3}) (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) on (?<date>[0-9]{2}/[0-9]{2}/[0-9]{2}) (?<time>[0-9]{2}:[0-9]{2} [AP]M) has been authorised on your debit card ending #(?<tail>[0-9]{4})\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["COMBANK"], "logicalType": "CARD_PURCHASE", "instrumentType": "CARD", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"group": "currency", "onlyAccepted": "LKR", "otherwise": "SERVER_REJECTS_BY_DESIGN"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"group": "date", "format": "dd/MM/yy"}, "txnTime": {"group": "time", "format": "hh:mm a"}, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 2, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/combank.txt"}, "notes": ["Non-LKR purchases (e.g. USD) match and are refused by the server`s requireCurrency."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    ('b1e70000-0000-4000-a000-000000000002'::uuid, '7056', 'ACCOUNT_TXN',
     $re$^(?<dir>Credit|Debit) for Rs\. (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) (?:to|from) [0-9]*(?<tail>[0-9]{4}) at (?<time>[0-9]{2}:[0-9]{2}) at (?<merchant>.+)$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["ComBank_Q+"], "logicalType": "ACCOUNT_TRANSACTION", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"group": "dir", "normalise": "LOWERCASE_REMOVE_WHITESPACE", "map": {"credit": "CREDIT", "debit": "DEBIT"}}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": {"group": "time", "format": "HH:mm"}, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 2, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/combank.txt"}, "notes": ["The SMS carries the FULL UNMASKED account number. Only its last four digits are captured, the rest is consumed by a non-capturing [0-9]* and never leaves the device."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    ('b1e70000-0000-4000-a000-000000000003'::uuid, '7056', 'CRM_DEPOSIT',
     $re$^We wish to confirm a CRM Deposit at (?<time>[0-9]{2}:[0-9]{2}) for Rs\. (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) through (?<merchant>.+?) to your account [0-9]\*+(?<tail>[0-9]{4})(?![0-9])$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["ComBank_Q+"], "logicalType": "CASH_DEPOSIT", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "CREDIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": {"group": "time", "format": "HH:mm"}, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/combank.txt"}, "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    ('b1e70000-0000-4000-a000-000000000004'::uuid, '7056', 'JUSTPAY_PAYMENT',
     $re$^Dear Customer, your transaction made via "(?<channel>Justpay|JP/QR)" for Rs\. ?(?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) has been approved sucessfully\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["COMBANK"], "logicalType": "PAYMENT", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "channel", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"policy": "NOT_IN_SMS"}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 3, "source": "leadsgen-tech/salli (Apache-2.0) parser fixtures (combank)"}, "notes": ["Covers both `Justpay` and `JP/QR` (JustPay QR). No payee is named, so merchant is the channel word.", "`sucessfully` is the bank`s own spelling and is matched as-is."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    -- 7083 Hatton National Bank ----------------------------------------------------
    ('b1e70000-0000-4000-a000-000000000005'::uuid, '7083', 'ACCOUNT_TXN',
     $re$^LKR (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) (?<dir>debit ?ed|credit ?ed) to Ac No:[0-9]+X+[0-9]+ on (?<date>[0-9]{2}/[0-9]{2}/[0-9]{2}) (?<time>[0-9]{2}:[0-9]{2}:[0-9]{2}) Reason:(?:[0-9]+X+[0-9]+:)?(?<merchant>.+?) Bal:$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["HNB"], "logicalType": "ACCOUNT_TRANSACTION", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"group": "dir", "normalise": "LOWERCASE_REMOVE_WHITESPACE", "map": {"debited": "DEBIT", "credited": "CREDIT"}}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"group": "date", "format": "dd/MM/yy"}, "txnTime": {"group": "time", "format": "HH:mm:ss"}, "maskedTail": {"policy": "ABSENT", "reason": "SMS exposes 2 digits (Ac No:01902XXXXX33), ck_bi_account_masked_tail requires exactly 4. Never padded, never invented: the message is ingested with no maskedTail and the transaction is stored with account_id NULL (unattributed)."}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 3, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/hnb.txt"}, "notes": ["`debit ed`/`credit ed` with an inserted space is HNB`s own typo, tolerated.", "Reason: is the only descriptive text and is sent as merchant. For interest credits it starts with the masked account (e.g. 01902XXXXX45:Int.Pd:...). That prefix is matched outside the merchant group so no account digits reach bi_transaction.merchant."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    ('b1e70000-0000-4000-a000-000000000006'::uuid, '7083', 'ACCOUNT_CHARGE',
     $re$^A Transaction for LKR (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) has been (?<dir>debit ?ed|credit ?ed) to Ac No:[0-9]+X+[0-9]+ on (?<date>[0-9]{2}/[0-9]{2}/[0-9]{2}) (?<time>[0-9]{2}:[0-9]{2}:[0-9]{2})\s*\.\s*Remarks ?:(?<merchant>.+?)\.Bal:$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["HNB"], "logicalType": "BANK_CHARGE_OR_REFUND", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"group": "dir", "normalise": "LOWERCASE_REMOVE_WHITESPACE", "map": {"debited": "DEBIT", "credited": "CREDIT"}}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"group": "date", "format": "dd/MM/yy"}, "txnTime": {"group": "time", "format": "HH:mm:ss"}, "maskedTail": {"policy": "ABSENT", "reason": "SMS exposes 2 digits (Ac No:01902XXXXX33), ck_bi_account_masked_tail requires exactly 4. Never padded, never invented: the message is ingested with no maskedTail and the transaction is stored with account_id NULL (unattributed)."}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 4, "source": "leadsgen-tech/salli (Apache-2.0) parser fixtures (hnb)"}, "notes": ["`debit ed` is a charge (DEBIT). `credit ed` is a refund, e.g. an FX markup refund (CREDIT). The pattern accepts only these two words, so a match always resolves to an explicit direction.", "HNB puts either a space or a line break before `Remarks`. Both are accepted.", "The masked account is matched but never captured. Remarks is the merchant and carries no account digits."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    ('b1e70000-0000-4000-a000-000000000007'::uuid, '7083', 'CARD_ALERT',
     $re$^HNB SMS ALERT: ?(?<channel>INTERNET|PURCHASE), (?:Debit )?[Aa]ccount:[0-9]+\*+(?<tail>[0-9]{4}),Location:(?<merchant>.+?),Amount\(Approx\.\):(?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) (?<currency>[A-Z]{3}),Av\.Bal:.+?,Date:(?<date>[0-9]{2}\.[0-9]{2}\.[0-9]{2}),Time:(?<time>[0-9]{2}:[0-9]{2})$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["HNB"], "logicalType": "CARD_PURCHASE", "instrumentType": "CARD", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"group": "currency", "onlyAccepted": "LKR", "otherwise": "SERVER_REJECTS_BY_DESIGN"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"group": "date", "format": "dd.MM.yy"}, "txnTime": {"group": "time", "format": "HH:mm"}, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 2, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/hnb.txt"}, "notes": ["Only INTERNET and PURCHASE are evidenced, other channel words are not matched.", "The masked number is labelled `Account` in the SMS, instrumentType CARD follows the approved readiness table. With masked-card-to-backend-enabled=false the tail is dropped on arrival."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    ('b1e70000-0000-4000-a000-000000000008'::uuid, '7083', 'ATM_RECEIPT',
     $re$^HNB ATM Withdrawal e-Receipt\s+Amt\(Approx\.\): +(?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) (?<currency>[A-Z]{3})\s+A/C: [0-9]+\*+(?<tail>[0-9]{4})\s+Txn Fee: (?<fee>[0-9][0-9,]*(?:\.[0-9]{1,2})?) ?[A-Z]{3}\s+Location: (?<merchant>.+?) *, *[A-Z]{3}\s+Term ID: \S+\s+Date: (?<date>[0-9]{2}\.[0-9]{2}\.[0-9]{2}) Time:(?<time>[0-9]{2}:[0-9]{2})$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["HNB"], "logicalType": "ATM_WITHDRAWAL", "instrumentType": "CARD", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"group": "currency", "onlyAccepted": "LKR", "otherwise": "SERVER_REJECTS_BY_DESIGN"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"group": "date", "format": "dd.MM.yy"}, "txnTime": {"group": "time", "format": "HH:mm"}, "maskedTail": {"group": "tail", "digits": 4}, "fee": {"group": "fee", "use": "NOT_SENT_NO_FEE_COLUMN"}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/hnb.txt"}, "notes": ["Multi-line message, \\s+ spans the line breaks, no regex flags needed.", "amount is the withdrawal only, the fee has no column in bi_transaction."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    -- 7010 Bank of Ceylon ----------------------------------------------------------
    ('b1e70000-0000-4000-a000-000000000009'::uuid, '7010', 'ACCOUNT_TXN',
     $re$^(?<channel>[A-Za-z]+(?: [A-Za-z]+)*?) (?<dir>Debit|Credit|Withdrawal|Deposit) Rs (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) (?:From|To) A/C No X+[0-9]+\. Balance available Rs $re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["BOC"], "logicalType": "ACCOUNT_TRANSACTION", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"group": "dir", "normalise": "LOWERCASE_REMOVE_WHITESPACE", "map": {"debit": "DEBIT", "credit": "CREDIT", "withdrawal": "DEBIT", "deposit": "CREDIT"}}, "merchant": {"group": "channel", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"policy": "ABSENT", "reason": "SMS exposes 3 digits (A/C No XXXXXXXXXX999), ck_bi_account_masked_tail requires exactly 4. Never padded, never invented: the message is ingested with no maskedTail and the transaction is stored with account_id NULL (unattributed)."}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 6, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/boc.txt"}, "notes": ["No date or time in the SMS: txnDate is the SMS received date.", "The masked account is matched but deliberately not captured."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    -- 7287 Seylan Bank -------------------------------------------------------------
    ('b1e70000-0000-4000-a000-000000000010'::uuid, '7287', 'CARD_DEBIT',
     $re$^Seylan Card \.\.\.(?<tail>[0-9]{4}) debit Txn [0-9]+ of LKR (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) done on (?<date>[0-9]{2}/[0-9]{2}/[0-9]{4}) (?<time>[0-9]{2}:[0-9]{2}:[0-9]{2} [AP]M) at (?<merchant>.+?) *\. Avl bal $re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["SEYLAN"], "logicalType": "CARD_PURCHASE", "instrumentType": "CARD", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"group": "date", "format": "dd/MM/yyyy"}, "txnTime": {"group": "time", "format": "hh:mm:ss a"}, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 3, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/seylan.txt"}, "notes": ["Debits only: no Seylan credit sample exists.", "The merchant field is fixed-width padded and also carries ATM/branch names and fees."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true),
    -- 7463 Amana Bank --------------------------------------------------------------
    ('b1e70000-0000-4000-a000-000000000011'::uuid, '7463', 'POS_TXN',
     $re$^POS trx of LKR (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) at (?<merchant>.+?) authorised from your A/C \*+(?<tail>[0-9]{4})\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["AMANABANK"], "logicalType": "CARD_PURCHASE", "instrumentType": "BANK", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"value": "LKR"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"source": "SMS_RECEIVED_DATE", "zone": "Asia/Colombo"}, "txnTime": null, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "COMMUNITY_SOURCE_NEEDS_VALIDATION", "realSamples": 1, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/amana.txt"}, "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field. Any transaction or reference number in the SMS is matched, not captured."}} $fm$::jsonb,
     1, true)
ON CONFLICT ON CONSTRAINT ux_bi_tpl_bank_type_version DO NOTHING;
