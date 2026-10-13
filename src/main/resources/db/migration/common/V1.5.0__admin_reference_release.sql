-- =====================================================================================
-- Budget Insights admin dashboard: reference releases and the configuration changeset.
--
-- RELEASE
--   Every admin publish writes one bi_reference_release row, and MAX(version) becomes the
--   templateVersion /reference/sync serves and ingest's stale check compares against. Until
--   now that number was MAX(template_version) of active templates, so adding a sender or
--   retiring a template never made a handset re-download.
--
--   Release 1 is seeded at today's value (GREATEST(max active template_version, 1) = 1 in
--   every environment), so no handset notices this migration; the first admin publish is 2.
--
-- CHANGESET
--   Admin edits are drafts in bi_config_change (one pending change per item) until a Super
--   Admin publishes them. bi_config_changeset is a single-row revision counter used for
--   optimistic locking (If-Match) and for the publish's stale check.
--
-- NEW COLUMNS
--   rule_spec    the Admin's merchant-rule form input {matchType, names[], priorityLevel}.
--   builder_spec the template wizard's definition: literal bank wording and field slots.
--                NEVER sample text. Never sent to handsets.
--   updated_at   edit audit on the two reference tables that lacked it.
-- =====================================================================================
SET lock_timeout = '3s';

CREATE TABLE IF NOT EXISTS budget_insights.bi_reference_release (
    version           integer                     NOT NULL,
    published_at      timestamp(6) with time zone NOT NULL DEFAULT now(),
    published_by      uuid,
    published_by_role varchar(32)                 NOT NULL,
    reason            varchar(500)                NOT NULL,
    change_count      integer                     NOT NULL,
    summary           jsonb                       NOT NULL,
    diff              jsonb                       NOT NULL,
    CONSTRAINT pk_bi_reference_release PRIMARY KEY (version),
    CONSTRAINT ck_bi_rr_version CHECK (version >= 1)
);

COMMENT ON COLUMN budget_insights.bi_reference_release.published_by IS
    'Staff user id. NULL only for the Flyway seed row.';
COMMENT ON COLUMN budget_insights.bi_reference_release.diff IS
    'Before/after of each applied change. Configuration only, never customer data or SMS text.';

INSERT INTO budget_insights.bi_reference_release
    (version, published_by, published_by_role, reason, change_count, summary, diff)
SELECT GREATEST(COALESCE(MAX(template_version), 0), 1), NULL, 'FLYWAY',
       'Initial seed (V1.0.1-V1.3.0)', 0, '{}'::jsonb, '[]'::jsonb
FROM budget_insights.bi_bank_parser_template
WHERE is_active
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS budget_insights.bi_config_change (
    id           uuid                        NOT NULL,
    entity_type  varchar(24)                 NOT NULL,
    target_id    uuid                        NOT NULL,
    op           varchar(12)                 NOT NULL,
    payload      jsonb,
    before_state jsonb,
    created_by   uuid                        NOT NULL,
    created_at   timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_by   uuid                        NOT NULL,
    updated_at   timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_config_change PRIMARY KEY (id),
    CONSTRAINT ux_bi_cc_target UNIQUE (entity_type, target_id),
    CONSTRAINT ck_bi_cc_entity CHECK (entity_type IN ('CATEGORY', 'MERCHANT_RULE', 'SENDER', 'TEMPLATE')),
    CONSTRAINT ck_bi_cc_op CHECK (op IN ('CREATE', 'UPDATE', 'DEACTIVATE', 'ACTIVATE', 'DELETE', 'RETIRE'))
);

CREATE TABLE IF NOT EXISTS budget_insights.bi_config_changeset (
    id         smallint                    NOT NULL DEFAULT 1,
    revision   bigint                      NOT NULL DEFAULT 0,
    updated_at timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_config_changeset PRIMARY KEY (id),
    CONSTRAINT ck_bi_ccs_singleton CHECK (id = 1)
);

INSERT INTO budget_insights.bi_config_changeset (id) VALUES (1) ON CONFLICT DO NOTHING;

ALTER TABLE budget_insights.bi_merchant_category_map ADD COLUMN IF NOT EXISTS updated_at timestamp(6) with time zone;
ALTER TABLE budget_insights.bi_merchant_category_map ADD COLUMN IF NOT EXISTS rule_spec jsonb;
ALTER TABLE budget_insights.bi_sms_sender_allowlist  ADD COLUMN IF NOT EXISTS updated_at timestamp(6) with time zone;
ALTER TABLE budget_insights.bi_bank_parser_template  ADD COLUMN IF NOT EXISTS builder_spec jsonb;
