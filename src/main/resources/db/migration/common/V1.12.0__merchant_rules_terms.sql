SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights: merchant categorisation on the generic keyword engine (TERMS).
--
-- WHAT CHANGES
--   1. match_type TERMS is allowed; merchant_pattern widens to 1000 characters.
--   2. Every current rule is copied to bi_merchant_category_map_bak_v1_12 (rollback:
--      scripts/rollback-merchant-rules-v1_12.sql).
--   3. Unpublished merchant-rule drafts are dropped (they describe rules replaced here).
--   4. All rules are replaced by the 15 below: one per spending category, the new
--      GIFTS_DONATION rule, and one High-priority OTHER rule for cash and bank charges (L2).
--
-- HOW TERMS MATCH (MerchantTerms / MerchantText)
--   The merchant is cleaned into words: upper case, processor prefix (PH *) dropped,
--   & -> AND, apostrophes removed, punctuation -> space, numbers of 5+ digits and a
--   trailing country code (LK, US, SG...) dropped.
--     WORD          whole word                         PHARMACY
--     PHRASE        words in sequence, or joined       FILLING STATION, BILL PAY (= BILLPAY)
--     STEM*         word starting with                 GROCER* (GROCERY, GROCERIES)
--     =EXACT        the whole cleaned merchant         (none used in this set)
--     -EXCLUDE      blocks the rule                    -SURCHARGE is deliberately NOT used:
--                                                      Fuel Surcharge stays Transport.
--   Winner: priority level (High 1-9 > Normal 10-199 > Low) -> most specific match
--   (exact > more words > word > stem) -> lower priority number. So DRUG STORE (Health)
--   beats STORE (Shopping), FOOD CITY (Shopping) beats FOOD (Dining), INTERNET BANKING
--   (Transfers) beats INTERNET (Bills) without reordering categories.
--
--   rule_spec keeps matchType "REGEX" (the admin form's "contains"), so the admin screen
--   shows names and "Contains" unchanged. UTILITIES stays without a rule (BILLS_UTILITIES
--   is the active one).
--
-- Layer 0 (ATM_RECEIPT, CRM_DEPOSIT, ACCOUNT_CHARGE -> OTHER) is configuration, not data:
--   moipay.budget-insights.categorisation.message-type-categories.
-- Golden regression: src/test/resources/categorisation/*.csv (MerchantCategorisationGoldenTest
--   reads this file's VALUES, so the tested rules are exactly the migrated ones).
-- =====================================================================================

ALTER TABLE budget_insights.bi_merchant_category_map
    ALTER COLUMN merchant_pattern TYPE varchar(1000);
-- Drop EVERY check constraint on match_type, not only ck_bi_mcm_match_type: DEV and staging
-- were first built by Hibernate ddl-auto (then baselined at 1.0.0), which names its own enum
-- check (e.g. bi_merchant_category_map_match_type_check) and would reject 'TERMS'.
DO $$
DECLARE
    v_name text;
BEGIN
    FOR v_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = 'budget_insights'
          AND rel.relname = 'bi_merchant_category_map'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%match_type%'
    LOOP
        EXECUTE format('ALTER TABLE budget_insights.bi_merchant_category_map DROP CONSTRAINT %I', v_name);
    END LOOP;
END $$;
ALTER TABLE budget_insights.bi_merchant_category_map
    ADD CONSTRAINT ck_bi_mcm_match_type CHECK (match_type IN ('EXACT', 'PREFIX', 'REGEX', 'TERMS'));

CREATE TABLE IF NOT EXISTS budget_insights.bi_merchant_category_map_bak_v1_12
    AS SELECT * FROM budget_insights.bi_merchant_category_map WITH NO DATA;
INSERT INTO budget_insights.bi_merchant_category_map_bak_v1_12
SELECT m.* FROM budget_insights.bi_merchant_category_map m
WHERE NOT EXISTS (SELECT 1 FROM budget_insights.bi_merchant_category_map_bak_v1_12 b WHERE b.id = m.id);

DELETE FROM budget_insights.bi_config_change WHERE entity_type = 'MERCHANT_RULE';
DELETE FROM budget_insights.bi_merchant_category_map;

INSERT INTO budget_insights.bi_merchant_category_map
    (id, merchant_pattern, match_type, category_id, priority, is_active, rule_spec, created_at, updated_at)
SELECT v.id,
       array_to_string(v.names, '|'),
       'TERMS',
       c.id,
       v.priority,
       true,
       jsonb_build_object('matchType', 'REGEX', 'names', to_jsonb(v.names), 'priorityLevel', v.level),
       now(),
       now()
FROM (VALUES
    ('b1d00000-0000-4000-a000-000000000140'::uuid, 'OTHER', 5::smallint, 'HIGH',
     ARRAY['ATM', 'CDM', 'CRM', 'CASH DEPOSIT', 'CASH WITHDRAWAL', 'CASH PAYMENT', 'CHEQUE', 'CHQ',
           'ALERT CHARGE*', 'SMS CHARGE*', 'ANNUAL FEE*', 'CARD FEE*', 'TXN FEE*', 'SERVICE FEE*', 'MARKUP', 'INT PD',
           'INTEREST', 'PROFIT', 'STAMP DUTY', 'WITHHOLDING TAX']),
    ('b1d00000-0000-4000-a000-000000000001'::uuid, 'TRANSPORT', 10::smallint, 'NORMAL',
     ARRAY['FUEL', 'PETROL', 'DIESEL', 'FILLING STATION', 'FUEL STATION', 'GAS STATION', 'CEYPETCO', 'LIOC',
           'LANKA IOC', 'RAILWAY', 'TRAIN', 'BUS', 'SLTB', 'TRANSIT', 'TAXI', 'CAB',
           'CABS', 'TUK TUK', 'PICKME', 'UBER', 'RIDE', 'PARKING', 'HIGHWAY', 'EXPRESSWAY',
           'TOLL']),
    ('b1d00000-0000-4000-a000-000000000010'::uuid, 'DINING_OUT', 20::smallint, 'NORMAL',
     ARRAY['RESTAURANT*', 'CAFE', 'CAFFE', 'COFFEE', 'BAKER*', 'BAKE HOUSE', 'FOOD', 'KITCHEN',
           'PIZZA', 'BURGER*', 'CANTEEN', 'DELI', 'DINING', 'LUNCH', 'KFC', 'MCDONALDS',
           'PIZZA HUT', 'DOMINOS', 'BURGER KING', 'UBER EATS', 'JAVA LOUNGE', 'BARISTA', '-APPLIANCE*']),
    ('b1d00000-0000-4000-a000-000000000020'::uuid, 'SHOPPING', 30::smallint, 'NORMAL',
     ARRAY['SUPERMARKET*', 'SUPERMART', 'SUPER CENTER', 'SUPER CENTRE', 'SUPERCENTRE', 'SUPERCENTER', 'MINI MART', 'GROCER*',
           'STORE', 'STORES', 'MALL', 'BOUTIQUE', 'MARKET', 'FASHION*', 'TEXTILE*', 'BAG',
           'CELLULAR', 'KEELLS', 'CARGILLS', 'FOOD CITY', 'ARPICO', 'GLOMARK', 'LAUGFS SUPER', 'SPAR',
           'SATHOSA', 'DARAZ', 'KAPRUKA', 'ODEL', 'COOL PLANET', 'FASHION BUG', 'NOLIMIT', 'ABANS',
           'SINGER', 'SOFTLOGIC', 'DAMRO']),
    ('b1d00000-0000-4000-a000-000000000030'::uuid, 'HEALTH_CARE', 40::smallint, 'NORMAL',
     ARRAY['HOSPITAL*', 'CLINIC*', 'MEDICAL', 'PHARMAC*', 'CHEMIST*', 'DRUG STORE', 'DENTAL', 'LAB',
           'LABORATOR*', 'ECHANNEL*', 'E CHANNEL*', 'CHANNELLING', 'OPERATING THEATRE', 'OPTICAL', 'OPTICIAN*', 'OSUSALA',
           'HEALTHGUARD', 'ASIRI', 'NAWALOKA', 'DURDANS', 'LANKA HOSPITALS', 'HEMAS']),
    ('b1d00000-0000-4000-a000-000000000040'::uuid, 'BILLS_UTILITIES', 50::smallint, 'NORMAL',
     ARRAY['BILL PAY', 'BILL PAYMENT', 'UTILITY', 'UTILITIES', 'ELECTRICITY', 'CEB', 'LECO', 'WATER BOARD',
           'WATER BILL', 'NWSDB', 'GAS', 'LITRO', 'LAUGFS GAS', 'BROADBAND', 'INTERNET', 'TELECOM',
           'SLT', 'MOBITEL', 'DIALOG', 'HUTCH', 'AIRTEL', 'RELOAD', 'DATA PLAN*', 'POSTPAID',
           'PREPAID']),
    ('b1d00000-0000-4000-a000-000000000050'::uuid, 'SUBSCRIPTIONS', 60::smallint, 'NORMAL',
     ARRAY['SUBSCRIPTION*', 'MONTHLY PLAN', 'RENEWAL', 'NETFLIX', 'SPOTIFY', 'YOUTUBE PREMIUM', 'APPLE COM BILL', 'ITUNES',
           'GOOGLE PLAY', 'PLAY STORE', 'APP STORE', '-LICENSE', '-LICENCE', '-INSURANCE', '-PASSPORT']),
    ('b1d00000-0000-4000-a000-000000000060'::uuid, 'ENTERTAINMENT', 70::smallint, 'NORMAL',
     ARRAY['CINEMA*', 'MOVIE*', 'GAMING', 'THEATRE', 'CONCERT*', 'SCOPE CINEMAS', 'PVR']),
    ('b1d00000-0000-4000-a000-000000000070'::uuid, 'GYM', 80::smallint, 'NORMAL',
     ARRAY['GYM', 'GYMNASIUM', 'FITNESS', 'POWER WORLD']),
    ('b1d00000-0000-4000-a000-000000000080'::uuid, 'EDUCATION', 90::smallint, 'NORMAL',
     ARRAY['SCHOOL*', 'COLLEGE*', 'UNIVERSITY', 'TUITION', 'INSTITUTE', 'ACADEMY', 'CAMPUS']),
    ('b1d00000-0000-4000-a000-000000000090'::uuid, 'TRAVEL', 100::smallint, 'NORMAL',
     ARRAY['AIRLINE*', 'AIRWAYS', 'EMIRATES', 'FLYDUBAI', 'TRAVEL*', 'TOURS', 'BOOKING', 'BOOKING COM',
           'AGODA', 'AIRBNB', 'EXPEDIA', 'JETWING', '-ECHANNEL*', '-E CHANNEL*', '-CHANNELLING', '-INSURANCE']),
    ('b1d00000-0000-4000-a000-000000000100'::uuid, 'PERSONAL_CARE', 110::smallint, 'NORMAL',
     ARRAY['SALON*', 'SPA', 'BARBER*', 'BEAUTY', 'COSMETIC*', 'HAIR']),
    ('b1d00000-0000-4000-a000-000000000110'::uuid, 'TRANSFERS', 120::smallint, 'NORMAL',
     ARRAY['TRANSFER*', 'FUND TRANSFER', 'TFR', 'TRF', 'CEFT*', 'SLIPS', 'IBFT', 'LPAY',
           'ONLINE TRANSFER', 'INTERNET BANKING', 'ONLINE BANKING', 'MOBILE BANKING', 'DIGITAL BANKING', 'MB', 'EREM', 'REMITTANCE*',
           'JUSTPAY', 'JUST PAY']),
    ('b1d00000-0000-4000-a000-000000000120'::uuid, 'DEBT_PAYMENTS', 130::smallint, 'NORMAL',
     ARRAY['LOAN*', 'INSTALMENT*', 'INSTALLMENT*', 'EMI', 'LEASING', 'FINANCE PLC', 'LOLC', 'CDB',
           'CREDIT CARD PAYMENT', 'CARD PAYMENT', '-DEBIT']),
    ('b1d00000-0000-4000-a000-000000000130'::uuid, 'GIFTS_DONATION', 140::smallint, 'NORMAL',
     ARRAY['TEMPLE', 'DONATION', 'DONATIONS', 'CHARITY', 'CHARITIES', 'KOVIL', 'CHURCH', 'MOSQUE',
           '-ROAD', '-RD', '-STREET', '-LANE', '-MAWATHA', '-AVENUE'])
) AS v(id, category_code, priority, level, names)
JOIN budget_insights.bi_category c ON c.code = v.category_code;

DO $$
DECLARE
    v_count integer;
BEGIN
    SELECT count(*) INTO v_count FROM budget_insights.bi_merchant_category_map WHERE is_active;
    IF v_count <> 15 THEN
        RAISE EXCEPTION 'V1.12.0 expected 15 merchant rules, found % (a category code is missing)', v_count;
    END IF;
    UPDATE budget_insights.bi_config_changeset SET revision = revision + 1, updated_at = now() WHERE id = 1;
    RAISE NOTICE 'V1.12.0 done: 15 TERMS merchant rules; previous rules in bi_merchant_category_map_bak_v1_12';
END $$;
