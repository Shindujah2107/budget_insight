-- =====================================================================================
-- V1.24.0  Bill categories, the daily bills job's resume marker, ComBank attempt sender
-- =====================================================================================
--   * bi_user_bill.category_id: the category the customer chose for a bill (Rent → Housing…).
--     Marking the bill paid files the payment there. Null = as before (Bills & utilities).
--   * bi_job_cursor: where a long-running daily job stopped, so a later run the same day
--     continues instead of starting over (BillsDailyJob).
--   * The ComBank payment-attempt template (V1.23.0) listed two senders; the owner confirmed the
--     attempt SMS comes from COMBANK. Templates are never edited in place: version 2 with COMBANK
--     only, version 1 retired, and a release row so phones pick it up.
-- Additive and re-runnable.
-- =====================================================================================

ALTER TABLE budget_insights.bi_user_bill ADD COLUMN IF NOT EXISTS category_id uuid;

CREATE TABLE IF NOT EXISTS budget_insights.bi_job_cursor (
    job_name    varchar(64)                 NOT NULL,
    run_date    date                        NOT NULL,
    last_key    varchar(64),
    completed   boolean                     NOT NULL DEFAULT false,
    updated_at  timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_job_cursor PRIMARY KEY (job_name)
);

INSERT INTO budget_insights.bi_bank_parser_template
    (id, bank_code, message_type, pattern, field_map, template_version, is_active)
SELECT 'b1e70000-0000-4000-a000-000000000021'::uuid, t.bank_code, t.message_type, t.pattern,
       jsonb_set(t.field_map, '{senders}', '["COMBANK"]'::jsonb), 2, true
FROM budget_insights.bi_bank_parser_template t
WHERE t.id = 'b1e70000-0000-4000-a000-000000000020'::uuid
ON CONFLICT ON CONSTRAINT ux_bi_tpl_bank_type_version DO NOTHING;

UPDATE budget_insights.bi_bank_parser_template
SET is_active = false
WHERE id = 'b1e70000-0000-4000-a000-000000000020'::uuid
  AND EXISTS (SELECT 1 FROM budget_insights.bi_bank_parser_template
              WHERE id = 'b1e70000-0000-4000-a000-000000000021'::uuid);

INSERT INTO budget_insights.bi_reference_release
    (version, published_by, published_by_role, reason, change_count, summary, diff)
SELECT COALESCE(MAX(version), 0) + 1, NULL, 'FLYWAY',
       'ComBank payment attempt: sender COMBANK only (V1.24.0)', 1,
       '{"templates": 1}'::jsonb,
       '[{"entity": "TEMPLATE", "op": "UPDATE", "bankCode": "7056", "messageType": "PAYMENT_ATTEMPT"}]'::jsonb
FROM budget_insights.bi_reference_release
WHERE NOT EXISTS (SELECT 1 FROM budget_insights.bi_reference_release
                  WHERE reason = 'ComBank payment attempt: sender COMBANK only (V1.24.0)');
