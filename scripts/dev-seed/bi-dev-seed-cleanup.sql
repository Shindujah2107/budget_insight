-- Removes everything bi-dev-seed.sql created (and nothing else). DEV only.
-- bi_compliance_event is never touched (append-only audit). Reference data / admin config untouched.
BEGIN;
SET LOCAL search_path TO budget_insights, public;
CREATE TEMP TABLE seed_user (user_id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO seed_user
SELECT ('5eed0000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid FROM generate_series(1, 34) n
UNION ALL SELECT '9a75b369-228e-419b-a97e-8f94e4f5e751'::uuid;
DELETE FROM bi_transaction_audit WHERE transaction_id IN (SELECT id FROM bi_transaction WHERE user_id IN (SELECT user_id FROM seed_user));
DELETE FROM bi_budget_alert       WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_transaction        WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_budget_category_limit WHERE budget_id IN (SELECT id FROM bi_budget WHERE user_id IN (SELECT user_id FROM seed_user));
UPDATE bi_budget SET rolled_from_id = NULL WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_budget             WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_account            WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_consent            WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_device_sync_state  WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_insight_cache      WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_data_export        WHERE user_id IN (SELECT user_id FROM seed_user);
DELETE FROM bi_raw_message        WHERE user_id IN (SELECT user_id FROM seed_user);
COMMIT;
