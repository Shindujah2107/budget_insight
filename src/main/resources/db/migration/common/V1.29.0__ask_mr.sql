-- =====================================================================================
-- V1.29.0  Ask MR (Premium Insights): settings, tip wording, usage counts, "Worth knowing" log
-- =====================================================================================
-- Ask MR explains a change in the customer's recorded spending: what happened, what contributed,
-- what could help, and where the evidence is. Every figure is worked out on each request from the
-- customer's transactions (AskMrServiceImpl); nothing here stores an amount.
--
--   bi_budget_policy.ask_mr_*   the settings, beside the other policy switches, so they are
--                               drafted, approved, published, audited and cached the same way.
--                               OFF by default; Ask MR never grants Premium.
--   bi_ask_mr_tip               the optional suggestions ("What could help") in EN/SI/TA, with
--                               {placeholders}; the app fills in the figures.
--   bi_ask_mr_usage             counts per question per day (answers shown, evidence opened,
--                               cards dismissed). No customer, no amounts.
--   bi_ask_mr_worth             which "Worth knowing" topic a customer was shown and when, so at
--                               most N a week and never the same topic twice in 14 days.
--                               Personal: exported and erased with the customer's data.
--
-- Additive and re-runnable.
-- =====================================================================================

ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS ask_mr_on boolean NOT NULL DEFAULT false;
ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS ask_mr_free_teaser boolean NOT NULL DEFAULT true;
ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS ask_mr_weekly_summary boolean NOT NULL DEFAULT true;
ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS ask_mr_worth_per_week smallint NOT NULL DEFAULT 2;
ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS ask_mr_min_amount integer NOT NULL DEFAULT 1000;
ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS ask_mr_min_pct smallint NOT NULL DEFAULT 15;
ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS ask_mr_early_day smallint NOT NULL DEFAULT 5;
ALTER TABLE budget_insights.bi_budget_policy ADD COLUMN IF NOT EXISTS ask_mr_one_off_tenths smallint NOT NULL DEFAULT 20;

-- Tips are edited in the Admin Dashboard (wording, on/off) through draft → publish like the rest of
-- the reference data, so each has a uuid id (fixed per code, the same in every environment) and a
-- code the rules in AskMrServiceImpl pick it by. Tips are never added or removed there.
CREATE TABLE IF NOT EXISTS budget_insights.bi_ask_mr_tip (
    id          uuid                        NOT NULL,
    code        varchar(40)                 NOT NULL,
    text_en     varchar(400)                NOT NULL,
    text_si     varchar(400)                NOT NULL,
    text_ta     varchar(400)                NOT NULL,
    is_active   boolean                     NOT NULL DEFAULT true,
    sort_order  smallint                    NOT NULL DEFAULT 0,
    updated_at  timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_ask_mr_tip PRIMARY KEY (id),
    CONSTRAINT ux_bi_ask_mr_tip_code UNIQUE (code)
);

INSERT INTO budget_insights.bi_ask_mr_tip (id, code, text_en, text_si, text_ta, sort_order)
SELECT md5('ask-mr-tip:' || v.code)::uuid, v.code, v.en, v.si, v.ta, v.sort_order
FROM (VALUES
  ('set-limit',
   'If you''d like {category} closer to {previousPeriod}''s level, you could set a monthly limit for it.',
   '{category} {previousPeriod} මට්ටමට ළං කර ගැනීමට කැමති නම්, ඒ සඳහා මාසික සීමාවක් සැකසිය හැක.',
   '{category} செலவை {previousPeriod} அளவுக்கு அருகில் கொண்டுவர விரும்பினால், அதற்கு ஒரு மாத வரம்பை அமைக்கலாம்.',
   10),
  ('review-limit',
   'If you''d like, you could review your {category} limit for the rest of the month.',
   'කැමති නම්, මාසයේ ඉතිරි කාලය සඳහා ඔබේ {category} සීමාව නැවත බැලිය හැක.',
   'விரும்பினால், மாதத்தின் மீதமுள்ள நாட்களுக்கு உங்கள் {category} வரம்பை மறுபரிசீலனை செய்யலாம்.',
   20),
  ('one-fewer',
   'One {merchant} purchase fewer a week would keep about {amount} a month.',
   'සතියකට {merchant} මිලදී ගැනීමක් අඩු කළොත් මසකට දළ වශයෙන් {amount}ක් ඉතිරි වේ.',
   'வாரத்திற்கு ஒரு {merchant} வாங்குதல் குறைத்தால் மாதத்திற்கு சுமார் {amount} மிச்சமாகும்.',
   30),
  ('daily-pace',
   'To finish the month within your target, about {amount} a day would do it.',
   'ඉලක්කය තුළ මාසය අවසන් කිරීමට, දිනකට දළ වශයෙන් {amount}ක් ප්‍රමාණවත් වේ.',
   'இலக்குக்குள் மாதத்தை முடிக்க, நாளொன்றுக்கு சுமார் {amount} போதுமானது.',
   40)
) AS v(code, en, si, ta, sort_order)
ON CONFLICT (code) DO NOTHING;

-- Tips go through draft -> publish like the rest of the reference data.
ALTER TABLE budget_insights.bi_config_change DROP CONSTRAINT IF EXISTS ck_bi_cc_entity;
ALTER TABLE budget_insights.bi_config_change
    ADD CONSTRAINT ck_bi_cc_entity
    CHECK (entity_type IN ('CATEGORY', 'MERCHANT_RULE', 'SENDER', 'TEMPLATE', 'BANK', 'BUDGET_POLICY', 'BILLER', 'ASK_MR_TIP'));

CREATE TABLE IF NOT EXISTS budget_insights.bi_ask_mr_usage (
    usage_day  date        NOT NULL,
    question   varchar(32) NOT NULL,
    shown      integer     NOT NULL DEFAULT 0,
    evidence   integer     NOT NULL DEFAULT 0,
    dismissed  integer     NOT NULL DEFAULT 0,
    CONSTRAINT pk_bi_ask_mr_usage PRIMARY KEY (usage_day, question)
);

CREATE TABLE IF NOT EXISTS budget_insights.bi_ask_mr_worth (
    id            uuid                        NOT NULL,
    user_id       uuid                        NOT NULL,
    topic         varchar(80)                 NOT NULL,
    shown_on      date                        NOT NULL,
    dismissed_at  timestamp(6) with time zone,
    CONSTRAINT pk_bi_ask_mr_worth PRIMARY KEY (id),
    CONSTRAINT ux_bi_ask_mr_worth_day UNIQUE (user_id, topic, shown_on)
);
CREATE INDEX IF NOT EXISTS ix_bi_ask_mr_worth_user ON budget_insights.bi_ask_mr_worth (user_id, shown_on);
