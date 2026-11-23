SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights: day-to-day categories (V1.31.0).
--
-- WHAT CHANGES
--   1. Nine new categories: Groceries, Fuel, Household items, Food delivery, Mobile & data,
--      Internet & TV, Rent & housing, Insurance, Gaming (an app icon each; a colour; a 50/30/20
--      bucket; an Ask MR type). Like the V1.0.1 seed, only the English name is stored: the app
--      ships the Sinhala and Tamil names for its built-in categories, and an admin's own names
--      still win. (Sinhala and Tamil text can be wider than varchar(64) where a database counts
--      bytes, which is one more reason not to seed it here.)
--   2. Renames that keep the id (so payments, budget limits and history stay attached):
--      Utilities -> Electricity & Water, Health Care -> Health & Pharmacy,
--      Gym -> Sports & Fitness. Dining Out keeps its name.
--   3. Bills & Utilities is shared out: its rule's names go to Electricity & Water, Mobile & data,
--      Internet & TV and Household items (cooking gas); its rule and then the category are turned
--      off. Its payments move only through the admin "Re-sort old transactions" run, which
--      never touches a payment a person categorised.
--   4. Rules: fuel leaves Transport, groceries leave Shopping, gaming leaves Entertainment,
--      Uber Eats leaves Dining out, Sports & Fitness gains sports words.
--
-- NEVER OVERWRITES AN ADMIN
--   A seeded rule changes only while nobody has edited it since V1.12.0 (updated_at =
--   created_at). A category is renamed or turned off only while it still has its seeded name.
--   New rows are added with ON CONFLICT / NOT EXISTS. An admin's unpublished draft is left alone.
--
-- Golden regression: MigrationRules reads this file's v131_rules VALUES (the full rule set after
-- this migration), so src/test/resources/categorisation/*.csv tests exactly what ships.
-- =====================================================================================

-- 1. New categories -------------------------------------------------------------------------------
INSERT INTO budget_insights.bi_category
    (id, code, name_en, icon_key, colour_hex, spend_bucket, spend_type, sort_order, is_active, created_at)
SELECT md5('bi-category:' || v.code)::uuid, v.code, v.name_en, v.icon_key, v.colour,
       v.bucket, v.type, v.sort_order, true, now()
FROM (VALUES
    ('GROCERIES',     'Groceries',       'ic_groceries',     '#66DD98', 'NEEDS', 'ESSENTIAL', 19::smallint),
    ('FUEL',          'Fuel',            'ic_fuel',          '#E8704F', 'NEEDS', 'ESSENTIAL', 20::smallint),
    ('HOUSEHOLD',     'Household Items', 'ic_household',     '#D9B44A', 'NEEDS', 'ESSENTIAL', 21::smallint),
    ('FOOD_DELIVERY', 'Food Delivery',   'ic_food_delivery', '#F48FB1', 'WANTS', 'FLEXIBLE',  22::smallint),
    ('MOBILE_DATA',   'Mobile & Data',   'ic_mobile_data',   '#5FB8F0', 'NEEDS', 'BILL',      23::smallint),
    ('INTERNET_TV',   'Internet & TV',   'ic_internet_tv',   '#7C8CF8', 'NEEDS', 'BILL',      24::smallint),
    ('RENT_HOUSING',  'Rent & Housing',  'ic_rent_housing',  '#C9A27E', 'NEEDS', 'BILL',      25::smallint),
    ('INSURANCE',     'Insurance',       'ic_insurance',     '#6FC2B0', 'NEEDS', 'BILL',      26::smallint),
    ('GAMING',        'Gaming',          'ic_gaming',        '#A66CF0', 'WANTS', 'FLEXIBLE',  27::smallint)
) AS v(code, name_en, icon_key, colour, bucket, type, sort_order)
ON CONFLICT (code) DO NOTHING;

-- 2. Renames, same id, only while the seeded name is untouched -----------------------------------
UPDATE budget_insights.bi_category
   SET name_en = 'Electricity & Water', updated_at = now()
 WHERE code = 'UTILITIES' AND name_en = 'Utilities';
UPDATE budget_insights.bi_category
   SET name_en = 'Health & Pharmacy', updated_at = now()
 WHERE code = 'HEALTH_CARE' AND name_en = 'Health Care';
UPDATE budget_insights.bi_category
   SET name_en = 'Sports & Fitness', updated_at = now()
 WHERE code = 'GYM' AND name_en = 'Gym';

-- 3. + 4. Merchant rules: the full set after this migration ---------------------------------------
CREATE TEMP TABLE v131_rules (id uuid, category_code text, priority smallint, level text, names text[]) ON COMMIT DROP;
INSERT INTO v131_rules (id, category_code, priority, level, names) VALUES
    ('b1d00000-0000-4000-a000-000000000140'::uuid, 'OTHER', 5::smallint, 'HIGH',
     ARRAY['ATM', 'CDM', 'CRM', 'CASH DEPOSIT', 'CASH WITHDRAWAL', 'CASH PAYMENT', 'CHEQUE', 'CHQ',
           'ALERT CHARGE*', 'SMS CHARGE*', 'ANNUAL FEE*', 'CARD FEE*', 'TXN FEE*', 'SERVICE FEE*', 'MARKUP', 'INT PD',
           'INTEREST', 'PROFIT', 'STAMP DUTY', 'WITHHOLDING TAX']),
    ('b1d00000-0000-4000-a000-000000000001'::uuid, 'TRANSPORT', 10::smallint, 'NORMAL',
     ARRAY['RAILWAY', 'TRAIN', 'BUS', 'SLTB', 'TRANSIT', 'TAXI', 'CAB', 'CABS',
           'TUK TUK', 'PICKME', 'UBER', 'RIDE', 'PARKING', 'HIGHWAY', 'EXPRESSWAY', 'TOLL',
           'FUEL SURCHARGE']),
    ('b1d00000-0000-4000-a000-000000000150'::uuid, 'FUEL', 11::smallint, 'NORMAL',
     ARRAY['FUEL', 'PETROL', 'DIESEL', 'FILLING STATION', 'FUEL STATION', 'GAS STATION', 'CEYPETCO', 'LIOC',
           'LANKA IOC', 'SINOPEC', 'CPC']),
    ('b1d00000-0000-4000-a000-000000000151'::uuid, 'FOOD_DELIVERY', 15::smallint, 'NORMAL',
     ARRAY['PICKME FOOD', 'UBER EATS', 'FOODHUB', 'FOOD HUB', 'FOOD DELIVERY']),
    ('b1d00000-0000-4000-a000-000000000010'::uuid, 'DINING_OUT', 20::smallint, 'NORMAL',
     ARRAY['RESTAURANT*', 'CAFE', 'CAFFE', 'COFFEE', 'BAKER*', 'BAKE HOUSE', 'FOOD', 'KITCHEN',
           'PIZZA', 'BURGER*', 'CANTEEN', 'DELI', 'DINING', 'LUNCH', 'KFC', 'MCDONALDS',
           'PIZZA HUT', 'DOMINOS', 'BURGER KING', 'JAVA LOUNGE', 'BARISTA', '-APPLIANCE*']),
    ('b1d00000-0000-4000-a000-000000000152'::uuid, 'GROCERIES', 25::smallint, 'NORMAL',
     ARRAY['SUPERMARKET*', 'SUPERMART', 'SUPER CENTER', 'SUPER CENTRE', 'SUPERCENTRE', 'SUPERCENTER', 'MINI MART', 'GROCER*',
           'MARKET', 'KEELLS', 'CARGILLS', 'FOOD CITY', 'ARPICO', 'GLOMARK', 'LAUGFS SUPER', 'SPAR',
           'SATHOSA']),
    ('b1d00000-0000-4000-a000-000000000020'::uuid, 'SHOPPING', 30::smallint, 'NORMAL',
     ARRAY['STORE', 'STORES', 'MALL', 'BOUTIQUE', 'FASHION*', 'TEXTILE*', 'BAG', 'CELLULAR',
           'DARAZ', 'KAPRUKA', 'ODEL', 'COOL PLANET', 'FASHION BUG', 'NOLIMIT', 'ABANS', 'SINGER',
           'SOFTLOGIC', 'DAMRO']),
    ('b1d00000-0000-4000-a000-000000000153'::uuid, 'HOUSEHOLD', 35::smallint, 'NORMAL',
     ARRAY['GAS', 'LITRO', 'LAUGFS GAS', 'HARDWARE', 'HOMEWARE', 'HOME WARE', 'HOUSEHOLD']),
    ('b1d00000-0000-4000-a000-000000000030'::uuid, 'HEALTH_CARE', 40::smallint, 'NORMAL',
     ARRAY['HOSPITAL*', 'CLINIC*', 'MEDICAL', 'PHARMAC*', 'CHEMIST*', 'DRUG STORE', 'DENTAL', 'LAB',
           'LABORATOR*', 'ECHANNEL*', 'E CHANNEL*', 'CHANNELLING', 'OPERATING THEATRE', 'OPTICAL', 'OPTICIAN*', 'OSUSALA',
           'HEALTHGUARD', 'ASIRI', 'NAWALOKA', 'DURDANS', 'LANKA HOSPITALS', 'HEMAS']),
    ('b1d00000-0000-4000-a000-000000000154'::uuid, 'UTILITIES', 50::smallint, 'NORMAL',
     ARRAY['BILL PAY', 'BILL PAYMENT', 'UTILITY', 'UTILITIES', 'ELECTRICITY', 'CEB', 'LECO', 'WATER BOARD',
           'WATER BILL', 'NWSDB']),
    ('b1d00000-0000-4000-a000-000000000155'::uuid, 'MOBILE_DATA', 52::smallint, 'NORMAL',
     ARRAY['MOBITEL', 'DIALOG', 'HUTCH', 'AIRTEL', 'RELOAD', 'DATA PLAN*', 'DATA CARD', 'POSTPAID',
           'PREPAID', 'TELECOM']),
    ('b1d00000-0000-4000-a000-000000000156'::uuid, 'INTERNET_TV', 54::smallint, 'NORMAL',
     ARRAY['BROADBAND', 'INTERNET', 'SLT', 'FIBRE', 'FIBER', 'PEO TV', 'PEOTV', 'DIALOG TV',
           'LANKA BELL', 'DIALOG BROADBAND', 'MOBITEL BROADBAND', 'SLT BROADBAND', 'SLT FIBRE', 'HOME BROADBAND']),
    ('b1d00000-0000-4000-a000-000000000050'::uuid, 'SUBSCRIPTIONS', 60::smallint, 'NORMAL',
     ARRAY['SUBSCRIPTION*', 'MONTHLY PLAN', 'RENEWAL', 'NETFLIX', 'SPOTIFY', 'YOUTUBE PREMIUM', 'APPLE COM BILL', 'ITUNES',
           'GOOGLE PLAY', 'PLAY STORE', 'APP STORE', '-LICENSE', '-LICENCE', '-INSURANCE', '-PASSPORT']),
    ('b1d00000-0000-4000-a000-000000000060'::uuid, 'ENTERTAINMENT', 70::smallint, 'NORMAL',
     ARRAY['CINEMA*', 'MOVIE*', 'THEATRE', 'CONCERT*', 'SCOPE CINEMAS', 'PVR']),
    ('b1d00000-0000-4000-a000-000000000157'::uuid, 'GAMING', 72::smallint, 'NORMAL',
     ARRAY['GAMING', 'STEAM', 'STEAMPOWERED', 'PLAYSTATION', 'PSN', 'XBOX', 'NINTENDO', 'GARENA',
           'MIDASBUY', 'CODASHOP']),
    ('b1d00000-0000-4000-a000-000000000070'::uuid, 'GYM', 80::smallint, 'NORMAL',
     ARRAY['GYM', 'GYMNASIUM', 'FITNESS', 'POWER WORLD', 'SPORTS', 'SPORT', 'FUTSAL', 'SWIMMING',
           'BADMINTON']),
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
    ('b1d00000-0000-4000-a000-000000000158'::uuid, 'RENT_HOUSING', 135::smallint, 'NORMAL',
     ARRAY['RENT', 'RENTAL', 'HOUSING', 'APARTMENT*', 'CONDOMINIUM', '-CAR', '-VEHICLE', '-CAB']),
    ('b1d00000-0000-4000-a000-000000000159'::uuid, 'INSURANCE', 136::smallint, 'NORMAL',
     ARRAY['INSURANCE', 'ASSURANCE', 'CEYLINCO', 'AIA', 'ALLIANZ', 'SLIC', 'JANASHAKTHI', 'UNION ASSURANCE']),
    ('b1d00000-0000-4000-a000-000000000130'::uuid, 'GIFTS_DONATION', 140::smallint, 'NORMAL',
     ARRAY['TEMPLE', 'DONATION', 'DONATIONS', 'CHARITY', 'CHARITIES', 'KOVIL', 'CHURCH', 'MOSQUE',
           '-ROAD', '-RD', '-STREET', '-LANE', '-MAWATHA', '-AVENUE']);

-- Seeded rules nobody has edited since V1.12.0 take their new terms.
UPDATE budget_insights.bi_merchant_category_map m
   SET merchant_pattern = array_to_string(v.names, '|'),
       priority = v.priority,
       rule_spec = jsonb_build_object('matchType', 'REGEX', 'names', to_jsonb(v.names), 'priorityLevel', v.level),
       updated_at = now()
  FROM v131_rules v
 WHERE m.id = v.id
   AND m.match_type = 'TERMS'
   AND m.updated_at = m.created_at;

-- New rules (ids ...150 to ...159). A rule an admin deleted before is never brought back, because
-- only these new ids are inserted.
INSERT INTO budget_insights.bi_merchant_category_map
    (id, merchant_pattern, match_type, category_id, priority, is_active, rule_spec, created_at, updated_at)
SELECT v.id, array_to_string(v.names, '|'), 'TERMS', c.id, v.priority, true,
       jsonb_build_object('matchType', 'REGEX', 'names', to_jsonb(v.names), 'priorityLevel', v.level),
       now(), now()
  FROM v131_rules v
  JOIN budget_insights.bi_category c ON c.code = v.category_code
 WHERE v.id::text LIKE 'b1d00000-0000-4000-a000-00000000015_'
   AND NOT EXISTS (SELECT 1 FROM budget_insights.bi_merchant_category_map m WHERE m.id = v.id);

-- Bills & Utilities: its seeded rule is shared out above; turn it off while unedited, then the
-- category while nothing active still points at it and it keeps its seeded name.
UPDATE budget_insights.bi_merchant_category_map
   SET is_active = false, updated_at = now()
 WHERE id = 'b1d00000-0000-4000-a000-000000000040'::uuid
   AND is_active
   AND updated_at = created_at;
UPDATE budget_insights.bi_category c
   SET is_active = false, updated_at = now()
 WHERE c.code = 'BILLS_UTILITIES'
   AND c.is_active
   AND c.name_en = 'Bills & Utilities'
   AND NOT EXISTS (SELECT 1 FROM budget_insights.bi_merchant_category_map m
                    WHERE m.category_id = c.id AND m.is_active);

-- 5. "Re-sort old transactions" (admin): one row per run, one per moved payment, so a run can be
-- undone. Nothing here holds merchant text or amounts.
CREATE TABLE IF NOT EXISTS budget_insights.bi_recategorise_run (
    id            uuid                        NOT NULL,
    created_by    uuid                        NOT NULL,
    created_at    timestamp(6) with time zone NOT NULL DEFAULT now(),
    moved         integer                     NOT NULL DEFAULT 0,
    undone_at     timestamp(6) with time zone,
    undone_by     uuid,
    CONSTRAINT pk_bi_recategorise_run PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS budget_insights.bi_recategorise_move (
    run_id               uuid    NOT NULL,
    transaction_id       uuid    NOT NULL,
    user_id              uuid    NOT NULL,
    from_category_id     uuid    NOT NULL,
    to_category_id       uuid    NOT NULL,
    from_source          varchar(10),
    CONSTRAINT pk_bi_recategorise_move PRIMARY KEY (run_id, transaction_id),
    CONSTRAINT fk_bi_recategorise_move_run FOREIGN KEY (run_id) REFERENCES budget_insights.bi_recategorise_run (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_bi_recategorise_move_user ON budget_insights.bi_recategorise_move (user_id);
