SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights: final merchant categorisation rule set (one rule per category).
--
-- Replaces EVERYTHING in bi_merchant_category_map (active, inactive, seed and admin-made)
-- with the 13 rules below, and drops any unpublished merchant-rule drafts, which would
-- otherwise point at deleted rows.
--
-- Every pattern is written exactly as MerchantRuleBuilder.generate("REGEX", names) writes it
-- (\bNAME\b joined by |) and carries its rule_spec, so each rule shows its names and opens
-- in the simple form of the admin screen. Matching is case-insensitive "contains" on whole
-- words. Priority: lower is checked first; all 13 are in the Normal band (10-199).
--
-- No table has a foreign key to bi_merchant_category_map; the 30d column is computed from
-- merchant strings, so no transaction or history is affected. Rules are server-side only
-- (CategorisationServiceImpl), so no handset reference release is needed. The changeset
-- revision is bumped so an open admin tab gets a stale-check.
-- =====================================================================================

DELETE FROM budget_insights.bi_config_change WHERE entity_type = 'MERCHANT_RULE';
DELETE FROM budget_insights.bi_merchant_category_map;

INSERT INTO budget_insights.bi_merchant_category_map
    (id, merchant_pattern, match_type, category_id, priority, is_active, rule_spec, created_at, updated_at)
SELECT v.id,
       (SELECT string_agg('\b' || replace(n, '.', '\.') || '\b', '|' ORDER BY ord)
          FROM unnest(v.names) WITH ORDINALITY AS u(n, ord)),
       'REGEX',
       c.id,
       v.priority,
       true,
       jsonb_build_object('matchType', 'REGEX', 'names', to_jsonb(v.names), 'priorityLevel', 'NORMAL'),
       now(),
       now()
FROM (VALUES
    ('b1d00000-0000-4000-a000-000000000001'::uuid, 'TRANSPORT',        10::smallint,
     ARRAY['FUEL','PETROL','DIESEL','FILLING STATION','RAILWAY','TRAIN','BUS','TRANSIT',
           'TAXI','CAB','RIDE','PARKING']),
    ('b1d00000-0000-4000-a000-000000000010'::uuid, 'DINING_OUT',       20::smallint,
     ARRAY['RESTAURANT','CAFE','COFFEE','BAKERY','FOOD','KITCHEN','PIZZA','BURGER',
           'CANTEEN','DELI','DINING']),
    ('b1d00000-0000-4000-a000-000000000020'::uuid, 'SHOPPING',         30::smallint,
     ARRAY['STORE','STORES','MALL','BOUTIQUE','SUPERMARKET','SUPERMART','MINI MART',
           'GROCER','GROCERS','GROCERY','GROCERIES']),
    ('b1d00000-0000-4000-a000-000000000030'::uuid, 'HEALTH_CARE',      40::smallint,
     ARRAY['HOSPITAL','CLINIC','MEDICAL','LAB','DENTAL','PHARMACY','CHEMIST','DRUG STORE']),
    ('b1d00000-0000-4000-a000-000000000040'::uuid, 'BILLS_UTILITIES',  50::smallint,
     ARRAY['BILL PAY','BILLPAY','UTILITY','ELECTRICITY','WATER BOARD','GAS','CEB',
           'BROADBAND','INTERNET','TELECOM','MOBITEL','DIALOG','RELOAD']),
    ('b1d00000-0000-4000-a000-000000000050'::uuid, 'SUBSCRIPTIONS',    60::smallint,
     ARRAY['SUBSCRIPTION','MONTHLY PLAN','RENEWAL']),
    ('b1d00000-0000-4000-a000-000000000060'::uuid, 'ENTERTAINMENT',    70::smallint,
     ARRAY['CINEMA','THEATRE','MOVIE','GAMING']),
    ('b1d00000-0000-4000-a000-000000000070'::uuid, 'GYM',              80::smallint,
     ARRAY['GYM','FITNESS']),
    ('b1d00000-0000-4000-a000-000000000080'::uuid, 'EDUCATION',        90::smallint,
     ARRAY['SCHOOL','COLLEGE','UNIVERSITY','TUITION','INSTITUTE']),
    ('b1d00000-0000-4000-a000-000000000090'::uuid, 'TRAVEL',          100::smallint,
     ARRAY['AIRLINE','AIRWAYS','TRAVEL','TOURS','BOOKING']),
    ('b1d00000-0000-4000-a000-000000000100'::uuid, 'PERSONAL_CARE',   110::smallint,
     ARRAY['SALON','SPA','BARBER','COSMETIC','COSMETICS']),
    ('b1d00000-0000-4000-a000-000000000110'::uuid, 'TRANSFERS',       120::smallint,
     ARRAY['TRANSFER','FUND TRANSFER','CEFT','SLIPS']),
    ('b1d00000-0000-4000-a000-000000000120'::uuid, 'DEBT_PAYMENTS',   130::smallint,
     ARRAY['LOAN','INSTALMENT','INSTALLMENT','EMI','CARD PAYMENT'])
) AS v(id, category_code, priority, names)
JOIN budget_insights.bi_category c ON c.code = v.category_code;

-- Fail the migration (and roll it back) rather than leave a category without its rule.
DO $$
DECLARE
    v_count integer;
BEGIN
    SELECT count(*) INTO v_count FROM budget_insights.bi_merchant_category_map WHERE is_active;
    IF v_count <> 13 THEN
        RAISE EXCEPTION 'V1.11.1 expected 13 merchant rules, found % (a category code is missing)', v_count;
    END IF;
    UPDATE budget_insights.bi_config_changeset SET revision = revision + 1, updated_at = now() WHERE id = 1;
    RAISE NOTICE 'V1.11.1 done: merchant rules replaced with the final set of 13';
END $$;
