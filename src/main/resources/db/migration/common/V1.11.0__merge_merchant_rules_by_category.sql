SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights: one merchant rule per category.
--
-- WHY
--   The seed (V1.0.2) split most categories over two or three REGEX rules, so the admin
--   Merchant mapping list showed Transport x3, Dining Out x3, Bills & Utilities x3-4 and so
--   on. Three seed rules (Dining Out ...012, Shopping ...020, Personal Care ...100) were
--   also written in a shape MerchantRuleBuilder.derive() cannot read back (".*", or a term
--   without a closing \b), so the list showed them with an empty Names column.
--
-- WHAT
--   For each category below, the keeper rule gets the merged name list, stored exactly as
--   MerchantRuleBuilder.generate("REGEX", names) would store it (\bA\b|\bB\b) plus its
--   rule_spec, so it opens in the simple form. Every other active rule of that category
--   that the keeper now covers is deactivated (not deleted). Inactive rules drop out of
--   the admin list; the 30d column is computed live from merchant strings, so it simply
--   shows the merged total.
--
--   Pattern changes that are not 1:1:
--     Dining Out     \bHOTEL\b.*\bDINING\b    -> name DINING (any merchant with the word)
--     Shopping       \bGROCER (prefix)        -> GROCER, GROCERS, GROCERY, GROCERIES
--     Personal Care  \bCOSMETIC (prefix)      -> COSMETIC, COSMETICS
--     Bills          admin rule CEB / DIALOG MOBILE / DIALOG TV / DIALOG BROADBAND
--                    -> CEB, DIALOG (keeps the 100-character name limit; DIALOG covers all three)
--
-- SAFETY (no keyword is ever lost)
--   A rule is retired only when ALL of these hold:
--     - same category, active, Normal priority band (10-199), not the keeper;
--     - no pending draft for it in bi_config_change;
--     - it is either a V1.0.2 seed row whose pattern is still the seed pattern, or a rule
--       saved from the admin form (REGEX/EXACT) whose every name the merged pattern matches.
--   A category is skipped entirely (RAISE NOTICE) when the keeper is missing, inactive, has
--   a pending draft, was edited away from its seed pattern, or the merged pattern already
--   exists on another row. Anything not provably covered stays active as it is.
--
--   Categorisation runs on the server only (CategorisationServiceImpl); no handset reference
--   data changes, so no bi_reference_release row is written. The changeset revision is bumped
--   so an open admin tab gets a stale-check instead of publishing over this.
-- =====================================================================================

CREATE TEMP TABLE tmp_bi_seed_rule (id uuid PRIMARY KEY, pattern text NOT NULL) ON COMMIT DROP;
INSERT INTO tmp_bi_seed_rule (id, pattern) VALUES
    ('b1d00000-0000-4000-a000-000000000001', '\bFUEL\b|\bPETROL\b|\bDIESEL\b|\bFILLING STATION\b'),
    ('b1d00000-0000-4000-a000-000000000002', '\bTAXI\b|\bCAB\b|\bRIDE\b|\bPARKING\b'),
    ('b1d00000-0000-4000-a000-000000000003', '\bRAILWAY\b|\bTRAIN\b|\bBUS\b|\bTRANSIT\b'),
    ('b1d00000-0000-4000-a000-000000000010', '\bRESTAURANT\b|\bCAFE\b|\bCOFFEE\b|\bBAKERY\b'),
    ('b1d00000-0000-4000-a000-000000000011', '\bFOOD\b|\bKITCHEN\b|\bPIZZA\b|\bBURGER\b'),
    ('b1d00000-0000-4000-a000-000000000012', '\bHOTEL\b.*\bDINING\b|\bCANTEEN\b|\bDELI\b'),
    ('b1d00000-0000-4000-a000-000000000020', '\bSUPERMARKET\b|\bGROCER|\bMINI MART\b|\bSUPERMART\b'),
    ('b1d00000-0000-4000-a000-000000000021', '\bSTORE\b|\bSTORES\b|\bMALL\b|\bBOUTIQUE\b'),
    ('b1d00000-0000-4000-a000-000000000030', '\bPHARMACY\b|\bCHEMIST\b|\bDRUG STORE\b'),
    ('b1d00000-0000-4000-a000-000000000031', '\bHOSPITAL\b|\bCLINIC\b|\bMEDICAL\b|\bLAB\b|\bDENTAL\b'),
    ('b1d00000-0000-4000-a000-000000000040', '\bELECTRICITY\b|\bWATER BOARD\b|\bGAS\b'),
    ('b1d00000-0000-4000-a000-000000000041', '\bBROADBAND\b|\bINTERNET\b|\bTELECOM\b|\bMOBITEL\b|\bRELOAD\b'),
    ('b1d00000-0000-4000-a000-000000000042', '\bBILL PAY\b|\bBILLPAY\b|\bUTILITY\b'),
    ('b1d00000-0000-4000-a000-000000000100', '\bSALON\b|\bSPA\b|\bBARBER\b|\bCOSMETIC');

DO $$
DECLARE
    t            record;
    v_cat_id     uuid;
    v_pattern    text;
    v_pg_pattern text;
    v_spec       jsonb;
    v_keeper     budget_insights.bi_merchant_category_map%ROWTYPE;
    v_retired    integer;
    v_total      integer := 0;
BEGIN
    FOR t IN
        SELECT * FROM (VALUES
            ('TRANSPORT',       'b1d00000-0000-4000-a000-000000000001'::uuid,
             ARRAY['FUEL','PETROL','DIESEL','FILLING STATION','RAILWAY','TRAIN','BUS','TRANSIT',
                   'TAXI','CAB','RIDE','PARKING']),
            ('DINING_OUT',      'b1d00000-0000-4000-a000-000000000010'::uuid,
             ARRAY['RESTAURANT','CAFE','COFFEE','BAKERY','FOOD','KITCHEN','PIZZA','BURGER',
                   'CANTEEN','DELI','DINING']),
            ('SHOPPING',        'b1d00000-0000-4000-a000-000000000020'::uuid,
             ARRAY['STORE','STORES','MALL','BOUTIQUE','SUPERMARKET','SUPERMART','MINI MART',
                   'GROCER','GROCERS','GROCERY','GROCERIES']),
            ('HEALTH_CARE',     'b1d00000-0000-4000-a000-000000000030'::uuid,
             ARRAY['HOSPITAL','CLINIC','MEDICAL','LAB','DENTAL','PHARMACY','CHEMIST','DRUG STORE']),
            ('BILLS_UTILITIES', 'b1d00000-0000-4000-a000-000000000040'::uuid,
             ARRAY['BILL PAY','BILLPAY','UTILITY','ELECTRICITY','WATER BOARD','GAS','CEB',
                   'BROADBAND','INTERNET','TELECOM','MOBITEL','DIALOG','RELOAD']),
            ('PERSONAL_CARE',   'b1d00000-0000-4000-a000-000000000100'::uuid,
             ARRAY['SALON','SPA','BARBER','COSMETIC','COSMETICS'])
        ) AS v(category_code, keeper_id, names)
    LOOP
        SELECT id INTO v_cat_id FROM budget_insights.bi_category WHERE code = t.category_code;
        SELECT * INTO v_keeper FROM budget_insights.bi_merchant_category_map WHERE id = t.keeper_id;

        IF v_cat_id IS NULL OR v_keeper.id IS NULL OR v_keeper.category_id <> v_cat_id
           OR NOT v_keeper.is_active THEN
            RAISE NOTICE 'V1.11.0 skip %: keeper missing, inactive or moved', t.category_code;
            CONTINUE;
        END IF;
        IF EXISTS (SELECT 1 FROM budget_insights.bi_config_change
                   WHERE entity_type = 'MERCHANT_RULE' AND target_id = t.keeper_id) THEN
            RAISE NOTICE 'V1.11.0 skip %: keeper has a pending draft', t.category_code;
            CONTINUE;
        END IF;

        -- Same output as MerchantRuleBuilder.generate("REGEX", names): \bNAME\b joined by |
        SELECT string_agg('\b' || replace(n, '.', '\.') || '\b', '|' ORDER BY ord)
          INTO v_pattern
          FROM unnest(t.names) WITH ORDINALITY AS u(n, ord);
        v_pg_pattern := replace(v_pattern, '\b', '\y');   -- PostgreSQL word boundary

        IF NOT EXISTS (SELECT 1 FROM tmp_bi_seed_rule s
                       WHERE s.id = t.keeper_id AND s.pattern = v_keeper.merchant_pattern)
           AND NOT (v_keeper.merchant_pattern = v_pattern) THEN
            RAISE NOTICE 'V1.11.0 skip %: keeper was edited (%), left as is',
                t.category_code, v_keeper.merchant_pattern;
            CONTINUE;
        END IF;
        IF EXISTS (SELECT 1 FROM budget_insights.bi_merchant_category_map
                   WHERE merchant_pattern = v_pattern AND match_type = 'REGEX' AND id <> t.keeper_id) THEN
            RAISE NOTICE 'V1.11.0 skip %: merged pattern already exists on another rule', t.category_code;
            CONTINUE;
        END IF;

        v_spec := jsonb_build_object('matchType', 'REGEX', 'names', to_jsonb(t.names),
                                     'priorityLevel', 'NORMAL');

        UPDATE budget_insights.bi_merchant_category_map
           SET merchant_pattern = v_pattern, match_type = 'REGEX', rule_spec = v_spec,
               updated_at = now()
         WHERE id = t.keeper_id;

        UPDATE budget_insights.bi_merchant_category_map r
           SET is_active = false, updated_at = now()
         WHERE r.category_id = v_cat_id
           AND r.id <> t.keeper_id
           AND r.is_active
           AND r.priority BETWEEN 10 AND 199
           AND NOT EXISTS (SELECT 1 FROM budget_insights.bi_config_change c
                           WHERE c.entity_type = 'MERCHANT_RULE' AND c.target_id = r.id)
           AND (
                 EXISTS (SELECT 1 FROM tmp_bi_seed_rule s
                         WHERE s.id = r.id AND s.pattern = r.merchant_pattern)
              OR (     r.rule_spec->>'matchType' IN ('REGEX', 'EXACT')
                   AND jsonb_typeof(r.rule_spec->'names') = 'array'
                   AND jsonb_array_length(r.rule_spec->'names') > 0
                   AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(r.rule_spec->'names') AS n(name)
                                   WHERE NOT (n.name ~* v_pg_pattern)))
           );
        GET DIAGNOSTICS v_retired = ROW_COUNT;
        v_total := v_total + v_retired;
        RAISE NOTICE 'V1.11.0 %: merged % names into %, deactivated % rule(s)',
            t.category_code, cardinality(t.names), t.keeper_id, v_retired;
    END LOOP;

    UPDATE budget_insights.bi_config_changeset SET revision = revision + 1, updated_at = now() WHERE id = 1;
    RAISE NOTICE 'V1.11.0 done: % rule(s) deactivated', v_total;
END $$;
