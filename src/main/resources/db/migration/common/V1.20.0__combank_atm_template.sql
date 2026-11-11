-- =====================================================================================
-- V1.20.0  ComBank ATM withdrawal e-receipt
-- =====================================================================================
-- "Withdrawal at <ATM> for LKR 8,000.00 on 11/11/26 12:34 PM from card ending #8293. Click
-- link to view the Digital Receipt ..." was held back from the V1.3.0 seed with one sample; a
-- second real message (2026-11-11) confirmed it. The pattern stops at "#NNNN." so the link and
-- everything after it is never read.
--
-- A release row is written as an admin publish would, so handsets download the new template and
-- re-read the SMS they could not read before (the app does that when the version moves).
-- Re-runnable: ON CONFLICT DO NOTHING on the template's business key and the release version.
-- =====================================================================================

INSERT INTO budget_insights.bi_bank_parser_template
    (id, bank_code, message_type, pattern, field_map, template_version, is_active)
VALUES
    ('b1e70000-0000-4000-a000-000000000012'::uuid, '7056', 'ATM_RECEIPT',
     $re$^Withdrawal at (?<merchant>.+?) for (?<currency>[A-Z]{3}) (?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?|\.[0-9]{1,2}) on (?<date>[0-9]{2}/[0-9]{2}/[0-9]{2}) (?<time>[0-9]{2}:[0-9]{2} [AP]M) from card ending #(?<tail>[0-9]{4})\.$re$,
     $fm$ {"fieldMapVersion": 1, "senders": ["COMBANK"], "logicalType": "ATM_WITHDRAWAL", "instrumentType": "CARD", "amount": {"group": "amount", "normalise": "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO"}, "currency": {"group": "currency", "onlyAccepted": "LKR", "otherwise": "SERVER_REJECTS_BY_DESIGN"}, "direction": {"value": "DEBIT"}, "merchant": {"group": "merchant", "normalise": "TRIM_COLLAPSE_WHITESPACE"}, "txnDate": {"group": "date", "format": "dd/MM/yy"}, "txnTime": {"group": "time", "format": "hh:mm a"}, "maskedTail": {"group": "tail", "digits": 4}, "evidence": {"status": "REAL_SAMPLES", "realSamples": 2, "source": "leadsgen-tech/salli (Apache-2.0) samples/redacted/combank.txt; owner's own SMS 2026-11-11"}, "notes": ["The merchant is the ATM's location, not a shop: the message type's fixed category decides the category.", "The digital receipt link after the card tail is not matched and never leaves the device."], "reference": {"use": "NOT_SENT", "reason": "IngestMessage has no reference field."}} $fm$::jsonb,
     1, true)
ON CONFLICT ON CONSTRAINT ux_bi_tpl_bank_type_version DO NOTHING;

INSERT INTO budget_insights.bi_reference_release
    (version, published_by, published_by_role, reason, change_count, summary, diff)
SELECT COALESCE(MAX(version), 0) + 1, NULL, 'FLYWAY',
       'ComBank ATM withdrawal e-receipt template (V1.20.0)', 1,
       '{"templates": 1}'::jsonb,
       '[{"entity": "TEMPLATE", "op": "CREATE", "bankCode": "7056", "messageType": "ATM_RECEIPT"}]'::jsonb
FROM budget_insights.bi_reference_release
WHERE NOT EXISTS (SELECT 1 FROM budget_insights.bi_reference_release
                  WHERE reason = 'ComBank ATM withdrawal e-receipt template (V1.20.0)');
