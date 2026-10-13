-- =====================================================================================================
-- Budget Insights — realistic DEV dataset (synthetic, repeatable)
-- =====================================================================================================
-- Target   : DEV only. Database moipay_budget_insights, schema budget_insights.
-- Safe to  : re-run at any time. Every run first deletes everything this script owns (see section 0)
--            and rebuilds it relative to CURRENT_DATE, so "today / last 7 days / last 30 days / this
--            month" filters always have data and every persona lands in its intended budget status.
-- Owns     : only the 35 personas listed in section 2 — 34 synthetic user ids 5eed0000-0000-4000-8000-0000000000NN
--            plus the shared DEV test login 9a75b369-228e-419b-a97e-8f94e4f5e751 (persona 35, so the
--            mobile screens can be checked with a real token). Nothing else is touched.
-- Does NOT : change schema, reference data (categories, merchant rules, senders, templates), admin
--            config / releases, or bi_compliance_event (audit, append-only — never seeded or deleted).
-- Data     : synthetic. No names, phone numbers, NICs, real account or card numbers. Merchant names are
--            public Sri Lankan businesses used as realistic labels; masked tails are random 4 digits.
-- Run      : psql  "host=localhost port=5434 dbname=moipay_budget_insights user=moipay_admin sslmode=require" -f bi-dev-seed.sql
--            DBeaver: open this file, Execute Script (Alt+X). Takes a few seconds.
-- Remove   : bi-dev-seed-cleanup.sql
-- =====================================================================================================

BEGIN;
SET LOCAL search_path TO budget_insights, public;
SET LOCAL TIME ZONE 'Asia/Colombo';

-- -----------------------------------------------------------------------------------------------------
-- 0. Clean up everything this script owns (children first)
-- -----------------------------------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------------------------------
-- 1. Merchant catalogue (labels only; the category is resolved later from the LIVE merchant rules,
--    exactly as the categoriser would — so e.g. "CARGILLS FOOD CITY" lands in Dining Out because the
--    \bFOOD\b rule outranks \bSUPERMARKET\b, and KFC / SINGER / DARAZ land in Other)
-- -----------------------------------------------------------------------------------------------------
CREATE TEMP TABLE seed_merchant (
  grp text, merchant text, amt_min numeric, amt_max numeric, w numeric, h_lo int, h_hi int, rnd numeric
) ON COMMIT DROP;
INSERT INTO seed_merchant VALUES
 -- groceries
 ('GROCERY','KEELLS SUPERMARKET NUGEGODA',1200,14500,5,8,21,0.01), ('GROCERY','KEELLS SUPERMARKET KANDY',1200,13500,2,8,21,0.01),
 ('GROCERY','GLOMARK SUPERMARKET',1500,16000,2,9,21,0.01), ('GROCERY','LAUGFS SUPERMARKET',900,9000,2,8,22,0.01),
 ('GROCERY','SPAR SUPERMARKET COLOMBO',1800,15000,2,9,21,0.01), ('GROCERY','ARPICO SUPERMARKET',1500,18000,3,9,21,0.01),
 ('GROCERY','CARGILLS FOOD CITY RAJAGIRIYA',1000,13000,5,8,22,0.01), ('GROCERY','KEELLS SUPER BAMBALAPITIYA',900,9500,1,8,22,0.01),
 -- retail / online
 ('RETAIL','ODEL STORE',3500,28000,1,10,20,10), ('RETAIL','COOL PLANET STORES',2500,15000,1,10,20,10),
 ('RETAIL','FASHION BUG STORES',2000,12000,1.2,10,20,10), ('RETAIL','NOLIMIT STORES',2500,16000,1,10,20,10),
 ('ONLINE','DARAZ ONLINE',1500,25000,1,9,23,0.01), ('ONLINE','KAPRUKA ONLINE',2000,18000,0.5,9,23,0.01),
 -- fuel, rides, public transport, parking
 ('FUEL','CEYPETCO FILLING STATION',2500,12000,4,6,20,100), ('FUEL','LIOC FILLING STATION',2500,12000,3,6,20,100),
 ('RIDE','PICKME RIDE',280,2600,5,6,23,1), ('RIDE','UBER RIDE',300,2800,3,6,23,1), ('RIDE','KANGAROO CABS TAXI',800,4500,0.6,6,23,1),
 ('PUBLIC','SLTB BUS',60,450,1,6,20,1), ('PUBLIC','SL RAILWAY TRAIN TICKET',150,2500,0.6,5,19,1), ('PUBLIC','CMC PARKING',100,600,0.6,8,19,10),
 -- dining
 ('DINING','PIZZA HUT',2200,9500,2,11,22,0.01), ('DINING','BURGER KING',1500,5500,1.5,11,22,0.01),
 ('DINING','DOMINOS PIZZA',1800,8500,1.5,11,22,0.01), ('DINING','UPALI RESTAURANT',2500,9000,1,11,22,10),
 ('DINING','KFC',1400,6000,2,11,22,0.01), ('DINING','UBER EATS FOOD DELIVERY',1200,6500,2,11,23,0.01),
 ('DINING','OFFICE CANTEEN',250,900,2,11,14,10), ('DINING','MINISTRY OF CRAB RESTAURANT',15000,48000,0.12,19,22,10),
 ('CAFE','JAVA LOUNGE CAFE',900,4200,2,8,20,10), ('CAFE','BARISTA COFFEE',750,3500,2,8,20,10),
 ('CAFE','PERERA AND SONS BAKERY',350,2800,3,7,19,10),
 -- health
 ('PHARMACY','HEALTHGUARD PHARMACY',450,6500,3,8,21,0.01), ('PHARMACY','OSUSALA PHARMACY',300,4500,2,8,20,0.01),
 ('PHARMACY','UNION CHEMIST',400,5000,1.5,8,20,0.01),
 ('MEDICAL','ASIRI HOSPITAL',3500,28000,0.4,9,19,10), ('MEDICAL','NAWALOKA HOSPITAL',4000,35000,0.3,9,19,10),
 ('MEDICAL','DURDANS MEDICAL CENTRE',2500,15000,0.4,9,19,10), ('MEDICAL','LANKA HOSPITALS LAB',1800,12000,0.4,7,12,10),
 -- leisure, care, travel
 ('ENTERTAIN','SCOPE CINEMA',1200,4800,1,16,22,10), ('ENTERTAIN','PVR CINEMA ONE GALLE FACE',1500,5500,1,16,22,10),
 ('ENTERTAIN','GAMING ZONE',500,3500,0.5,15,22,10),
 ('CARE','SALON NAYOMI',1500,12000,0.6,10,19,100), ('CARE','SPA CEYLON',2500,14000,0.3,10,19,100),
 ('CARE','BARBER SHOP KOTTAWA',400,1500,0.6,9,19,50),
 ('TRAVEL','SRILANKAN AIRLINES',38000,185000,0.3,9,21,1), ('TRAVEL','JETWING TRAVEL BOOKING',22000,95000,0.3,9,21,10),
 ('TRAVEL','BOOKING.COM HOTEL',15000,80000,0.4,9,23,0.01),
 -- misc
 ('MISC','SAMPATH BANK ATM WITHDRAWAL',2000,20000,1,8,21,1000), ('MISC','LANKA SATHOSA',800,6000,1,8,20,0.01),
 -- big-ticket fillers (used to reach AHEAD / OVER targets)
 ('BIG','ABANS STORE',15000,95000,1,10,20,10), ('BIG','SINGER MEGA',12000,120000,1,10,20,10),
 ('BIG','SRILANKAN AIRLINES',38000,150000,0.6,9,21,1), ('BIG','ASIRI HOSPITAL',12000,60000,0.5,9,19,10),
 -- manual entries (cash spends a customer types in)
 ('MANUAL','VEGETABLE MARKET',300,3500,3,6,19,10), ('MANUAL','TUK TUK RIDE',200,1200,3,6,22,10),
 ('MANUAL','LUNCH PACKET',250,650,2,11,14,10), ('MANUAL','TEMPLE DONATION',500,5000,0.6,6,19,100),
 ('MANUAL','NEWSPAPER AGENT',300,1500,0.4,6,10,10);

-- Discretionary mix per lifestyle profile (relative weights of merchant groups)
CREATE TEMP TABLE seed_mix (profile text, grp text, w numeric) ON COMMIT DROP;
INSERT INTO seed_mix VALUES
 ('family','GROCERY',34),('family','RETAIL',5),('family','ONLINE',3),('family','FUEL',8),('family','RIDE',5),('family','DINING',9),('family','CAFE',4),('family','PHARMACY',7),('family','MEDICAL',1.5),('family','ENTERTAIN',2),('family','CARE',2),('family','MISC',4),('family','TRAVEL',0.2),
 ('young_pro','GROCERY',12),('young_pro','RETAIL',7),('young_pro','ONLINE',5),('young_pro','FUEL',4),('young_pro','RIDE',16),('young_pro','DINING',18),('young_pro','CAFE',10),('young_pro','PHARMACY',3),('young_pro','MEDICAL',0.6),('young_pro','ENTERTAIN',5),('young_pro','CARE',3),('young_pro','MISC',3),('young_pro','TRAVEL',0.4),
 ('commuter','GROCERY',18),('commuter','RETAIL',3),('commuter','ONLINE',2),('commuter','FUEL',26),('commuter','RIDE',4),('commuter','PUBLIC',2),('commuter','DINING',10),('commuter','CAFE',6),('commuter','PHARMACY',4),('commuter','MEDICAL',0.6),('commuter','ENTERTAIN',2),('commuter','CARE',2),('commuter','MISC',4),
 ('student','GROCERY',8),('student','RETAIL',4),('student','ONLINE',4),('student','RIDE',12),('student','PUBLIC',14),('student','DINING',22),('student','CAFE',10),('student','PHARMACY',2),('student','ENTERTAIN',7),('student','CARE',2),('student','MISC',3),
 ('senior','GROCERY',30),('senior','RETAIL',2),('senior','RIDE',6),('senior','PUBLIC',3),('senior','DINING',4),('senior','CAFE',4),('senior','PHARMACY',18),('senior','MEDICAL',4),('senior','MISC',5),
 ('traveller','GROCERY',12),('traveller','RETAIL',10),('traveller','ONLINE',5),('traveller','FUEL',8),('traveller','RIDE',8),('traveller','DINING',20),('traveller','CAFE',8),('traveller','PHARMACY',3),('traveller','MEDICAL',1),('traveller','ENTERTAIN',6),('traveller','CARE',5),('traveller','MISC',3),('traveller','TRAVEL',2.5);

-- Monthly recurring payments: (profiles, merchant, day of month, min, max, probability, direction)
CREATE TEMP TABLE seed_recurring (profiles text, merchant text, dom int, amt_min numeric, amt_max numeric, prob numeric, direction text, rnd numeric) ON COMMIT DROP;
INSERT INTO seed_recurring VALUES
 ('family,young_pro,commuter,senior,traveller','CEB ELECTRICITY BILL PAY',8,3200,19000,1,'DEBIT',0.01),
 ('family,commuter,senior,traveller','NWSDB WATER BOARD BILL',12,800,3800,0.9,'DEBIT',0.01),
 ('family,senior,commuter','LITRO GAS',18,4115,4290,0.55,'DEBIT',0.01),
 ('family,young_pro,traveller,commuter','DIALOG BROADBAND',3,3490,7990,0.85,'DEBIT',0.01),
 ('family,young_pro,commuter,senior,traveller','SLT MOBITEL POSTPAID BILL',6,1200,4500,0.7,'DEBIT',0.01),
 ('student','DIALOG RELOAD',1,200,1000,0.95,'DEBIT',50), ('student','HUTCH RELOAD',16,100,700,0.8,'DEBIT',50),
 ('young_pro,family,traveller','NETFLIX SUBSCRIPTION',14,1450,3290,0.95,'DEBIT',0.01),
 ('young_pro,student,traveller','SPOTIFY SUBSCRIPTION',20,499,799,0.9,'DEBIT',0.01),
 ('young_pro,traveller','YOUTUBE PREMIUM RENEWAL',22,690,1290,0.6,'DEBIT',0.01),
 ('young_pro,commuter,traveller','POWER WORLD GYM MONTHLY',2,6500,12000,0.8,'DEBIT',100),
 ('student,family','SUSIPWAN INSTITUTE TUITION',5,4500,12000,0.9,'DEBIT',500),
 ('family','ROYAL INSTITUTE SCHOOL FEES',10,18000,45000,0.7,'DEBIT',500),
 ('commuter,traveller','VEHICLE LOAN INSTALMENT',25,28000,65000,0.95,'DEBIT',1),
 ('young_pro,traveller','HNB CARD PAYMENT',21,10000,60000,0.9,'DEBIT',1000),
 ('family,senior','FUND TRANSFER CEFT',26,10000,40000,0.8,'DEBIT',1000),
 ('family,young_pro,commuter,traveller,senior','SALARY CREDIT',25,0,0,1,'CREDIT',1000);

-- -----------------------------------------------------------------------------------------------------
-- 2. Personas  (status = the CURRENT month; history months follow past_ratio)
--    start_days / end_days: history window relative to today.  caps: category caps for this month,
--    APPROACH = 80–95 % used (approaching alert), EXCEED = over the cap, SAFE = well under.
-- -----------------------------------------------------------------------------------------------------
CREATE TEMP TABLE seed_persona (
  n int, user_id uuid, behaviour text, profile text, monthly_limit numeric, freq numeric, scale numeric,
  platform text, accounts text, start_days int, end_days int, consent text, past_ratio numeric,
  caps text, synced_current boolean, manual_share numeric
) ON COMMIT DROP;
INSERT INTO seed_persona VALUES
 ( 1,NULL,'ON_TRACK','family',145000,2.0,1.0,'ANDROID','7083:BANK,7083:CARD',400,0,'ACTIVE',0.86,'SHOPPING:APPROACH,DINING_OUT:SAFE,TRANSPORT:SAFE',true,0.05),
 ( 2,NULL,'ON_TRACK','young_pro',95000,1.8,0.8,'ANDROID','7056:BANK,7056:CARD',400,0,'ACTIVE',0.84,'DINING_OUT:SAFE,TRANSPORT:APPROACH',true,0.08),
 ( 3,NULL,'ON_TRACK','traveller',280000,2.4,1.6,'IOS','7056:CARD,7010:BANK',400,0,'ACTIVE',0.80,'DINING_OUT:SAFE,SHOPPING:SAFE,ENTERTAINMENT:APPROACH',true,0.20),
 ( 4,NULL,'ON_TRACK','senior',60000,0.9,0.7,'ANDROID','7010:BANK',400,0,'ACTIVE',0.78,'HEALTH_CARE:SAFE',true,0.15),
 ( 5,NULL,'ON_TRACK','commuter',130000,1.7,1.0,'ANDROID','7287:CARD,7083:BANK',400,0,'ACTIVE',0.88,'TRANSPORT:APPROACH,SHOPPING:SAFE',true,0.05),
 ( 6,NULL,'ON_TRACK','student',38000,1.3,0.5,'ANDROID','7463:CARD',400,0,'ACTIVE',0.82,'DINING_OUT:SAFE',true,0.30),
 ( 7,NULL,'ON_TRACK','family',180000,2.2,1.2,'IOS','7083:BANK,7056:CARD',400,0,'ACTIVE',0.85,'',true,0.25),
 ( 8,NULL,'AHEAD_OF_PACE','young_pro',110000,2.3,1.0,'ANDROID','7056:CARD,7056:BANK',400,0,'ACTIVE',0.97,'DINING_OUT:EXCEED,SHOPPING:APPROACH',true,0.05),
 ( 9,NULL,'AHEAD_OF_PACE','family',160000,2.2,1.1,'ANDROID','7083:BANK,7287:CARD',400,0,'ACTIVE',0.98,'SHOPPING:APPROACH,HEALTH_CARE:SAFE',true,0.05),
 (10,NULL,'AHEAD_OF_PACE','traveller',240000,2.8,1.5,'IOS','7056:CARD,7083:BANK',400,0,'ACTIVE',1.00,'TRAVEL:SAFE,DINING_OUT:APPROACH',true,0.20),
 (11,NULL,'AHEAD_OF_PACE','commuter',90000,1.9,0.9,'ANDROID','7083:CARD,7083:BANK',400,0,'ACTIVE',0.95,'TRANSPORT:EXCEED',false,0.05),
 (12,NULL,'AHEAD_OF_PACE','student',45000,1.6,0.6,'ANDROID','7463:CARD',400,0,'ACTIVE',0.96,'DINING_OUT:APPROACH',true,0.30),
 (13,NULL,'OVER_BUDGET','young_pro',85000,2.6,1.1,'ANDROID','7056:CARD,7056:BANK',400,0,'ACTIVE',1.12,'DINING_OUT:EXCEED,ENTERTAINMENT:EXCEED,SHOPPING:APPROACH',true,0.05),
 (14,NULL,'OVER_BUDGET','traveller',200000,3.0,1.7,'IOS','7056:CARD,7010:BANK',400,0,'ACTIVE',1.08,'TRAVEL:EXCEED,DINING_OUT:EXCEED',true,0.20),
 (15,NULL,'OVER_BUDGET','student',30000,1.5,0.6,'ANDROID','7463:CARD',400,0,'ACTIVE',1.15,'DINING_OUT:EXCEED,TRANSPORT:APPROACH',true,0.30),
 (16,NULL,'OVER_BUDGET','family',120000,2.4,1.2,'ANDROID','7083:BANK,7083:CARD',400,0,'ACTIVE',1.06,'SHOPPING:EXCEED,HEALTH_CARE:APPROACH',false,0.05),
 (17,NULL,'OVER_BUDGET','commuter',75000,2.0,1.1,'ANDROID','7287:CARD,7010:BANK',400,0,'ACTIVE',1.10,'TRANSPORT:EXCEED',true,0.05),
 (18,NULL,'NO_BUDGET','traveller',NULL,2.8,1.6,'ANDROID','7056:CARD,7083:BANK',400,0,'ACTIVE',NULL,'',true,0.05),
 (19,NULL,'NO_BUDGET','family',NULL,1.6,1.0,'IOS','7083:BANK',400,0,'ACTIVE',NULL,'',true,0.30),
 (20,NULL,'NO_BUDGET','senior',NULL,0.5,0.6,'ANDROID','7010:BANK',400,0,'ACTIVE',NULL,'',true,0.20),
 (21,NULL,'FEW_TXNS','young_pro',50000,0,0.8,'ANDROID','7056:CARD',12,0,'ACTIVE',NULL,'',true,0.00),
 (22,NULL,'EMPTY','family',75000,0,1,'ANDROID','',3,0,'ACTIVE',NULL,'',true,0),
 (23,NULL,'EMPTY','young_pro',NULL,0,1,'IOS','',2,0,'ACTIVE',NULL,'',true,0),
 (24,NULL,'WITHDRAWN','family',NULL,1.4,1.0,'ANDROID','7083:BANK,7463:CARD',400,40,'WITHDRAWN',NULL,'',false,0.05),
 (25,NULL,'DORMANT','commuter',NULL,1.2,0.9,'ANDROID','7287:CARD',400,75,'ACTIVE',NULL,'',false,0.05),
 (26,NULL,'ON_TRACK','young_pro',70000,1.8,0.8,'ANDROID','7056:CARD',9,0,'ACTIVE',NULL,'DINING_OUT:SAFE',true,0.05),
 (27,NULL,'ON_TRACK','student',40000,1.5,0.5,'ANDROID','7463:CARD',400,0,'ACTIVE',0.80,'',true,0.30),
 (28,NULL,'ON_TRACK','commuter',100000,1.6,1.0,'IOS','7056:CARD,7083:BANK',400,0,'ACTIVE',0.87,'TRANSPORT:SAFE',true,0.20),
 (29,NULL,'AHEAD_OF_PACE','family',140000,2.0,1.0,'ANDROID','7083:BANK,7463:CARD',400,0,'ACTIVE',0.99,'SHOPPING:EXCEED',true,0.05),
 (30,NULL,'ON_TRACK','young_pro',120000,2.0,1.0,'ANDROID','7287:CARD,7056:BANK',400,0,'ACTIVE',0.83,'DINING_OUT:APPROACH',true,0.05),
 (31,NULL,'OVER_BUDGET','senior',55000,1.0,0.8,'ANDROID','7010:BANK',400,0,'ACTIVE',0.92,'HEALTH_CARE:EXCEED',true,0.10),
 (32,NULL,'ON_TRACK','traveller',320000,2.5,1.8,'IOS','7056:CARD,7083:BANK',400,0,'ACTIVE',0.79,'TRAVEL:SAFE',true,0.20),
 (33,NULL,'NO_BUDGET','young_pro',NULL,2.0,1.0,'ANDROID','7287:CARD,7083:BANK',400,0,'ACTIVE',NULL,'',true,0.05),
 (34,NULL,'AHEAD_OF_PACE','student',35000,1.4,0.5,'ANDROID','7463:CARD',400,0,'ACTIVE',0.96,'',true,0.30),
 -- 35: the shared DEV test login, so the mobile screens can be checked with a real token
 (35,'9a75b369-228e-419b-a97e-8f94e4f5e751','AHEAD_OF_PACE','young_pro',120000,2.2,1.0,'ANDROID','7083:CARD,7056:BANK',400,0,'ACTIVE',0.95,'DINING_OUT:EXCEED,SHOPPING:APPROACH,TRANSPORT:SAFE',true,0.10);
UPDATE seed_persona SET user_id = ('5eed0000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid WHERE user_id IS NULL;

-- Accounts (synthetic masked tails)
CREATE TEMP TABLE seed_account (id uuid, user_id uuid, idx int, bank_code text, instrument text, tail text, template_id uuid, display_name text) ON COMMIT DROP;
INSERT INTO seed_account
SELECT md5('seed-acct-' || p.user_id || '-' || a.ord)::uuid, p.user_id, a.ord::int,
       split_part(a.spec, ':', 1), split_part(a.spec, ':', 2),
       lpad(((abs(hashtext(p.user_id::text || a.spec)) % 9000) + 1000)::text, 4, '0'),
       (SELECT t.id FROM bi_bank_parser_template t
         WHERE t.bank_code = split_part(a.spec, ':', 1) AND t.is_active
           AND t.message_type = CASE split_part(a.spec, ':', 1) || ':' || split_part(a.spec, ':', 2)
                 WHEN '7056:CARD' THEN 'CARD_PURCHASE' WHEN '7056:BANK' THEN 'ACCOUNT_TXN'
                 WHEN '7083:CARD' THEN 'CARD_ALERT'    WHEN '7083:BANK' THEN 'ACCOUNT_TXN'
                 WHEN '7010:BANK' THEN 'ACCOUNT_TXN'   WHEN '7287:CARD' THEN 'CARD_DEBIT'
                 WHEN '7463:CARD' THEN 'POS_TXN' END
         ORDER BY t.template_version DESC LIMIT 1),
       CASE split_part(a.spec, ':', 1) WHEN '7056' THEN 'Commercial Bank' WHEN '7083' THEN 'HNB' WHEN '7010' THEN 'BOC'
            WHEN '7287' THEN 'Seylan' WHEN '7463' THEN 'Amana Bank' END
       || CASE split_part(a.spec, ':', 2) WHEN 'CARD' THEN ' card' ELSE ' savings' END
FROM seed_persona p, LATERAL unnest(string_to_array(nullif(p.accounts, ''), ',')) WITH ORDINALITY a(spec, ord);

-- -----------------------------------------------------------------------------------------------------
-- 3. Generate transactions (deterministic: setseed per persona)
-- -----------------------------------------------------------------------------------------------------
CREATE TEMP TABLE seed_txn (
  seq bigserial, user_id uuid, d date, ts timestamptz, merchant text, grp text, amount numeric(19,2),
  direction text, recurring boolean, manual boolean, rnd numeric
) ON COMMIT DROP;

DO $$
DECLARE
  p record; r record; m record; g text;
  first_month date := (date_trunc('month', current_date) - interval '5 months')::date;
  v_start date; v_end date; d date; k int; i int; amt numeric; hr int; v_dom int; mlen int;
  is_manual boolean;
BEGIN
  FOR p IN SELECT * FROM seed_persona WHERE freq > 0 ORDER BY n LOOP
    PERFORM setseed(p.n / 1000.0);
    v_start := greatest(first_month, current_date - p.start_days);
    v_end   := current_date - p.end_days;
    d := v_start;
    WHILE d <= v_end LOOP
      mlen := extract(day from (date_trunc('month', d) + interval '1 month - 1 day'))::int;
      v_dom := extract(day from d)::int;
      -- recurring bills / subscriptions / salary
      FOR r IN SELECT * FROM seed_recurring
               WHERE p.profile = ANY (string_to_array(profiles, ',')) AND least(seed_recurring.dom, mlen) = v_dom LOOP
        IF random()::numeric < r.prob AND p.n NOT IN (21) THEN
          IF r.direction = 'CREDIT' THEN
            amt := round(coalesce(p.monthly_limit, 90000 * p.scale) * (1.45 + random()::numeric * 0.4), -3);
          ELSE
            amt := r.amt_min + (r.amt_max - r.amt_min) * random()::numeric;
          END IF;
          INSERT INTO seed_txn(user_id, d, ts, merchant, grp, amount, direction, recurring, manual, rnd)
          VALUES (p.user_id, d, d + make_interval(hours => 7 + (random()::numeric * 3)::int, mins => (random()::numeric * 59)::int),
                  r.merchant, 'RECURRING', amt, r.direction, true, false, r.rnd);
        END IF;
      END LOOP;
      -- discretionary purchases (more at weekends; paydays bump groceries/retail)
      k := floor(p.freq * (CASE WHEN extract(isodow from d) IN (6, 7) THEN 1.3 ELSE 0.88 END)
                          * (CASE WHEN v_dom BETWEEN 25 AND 28 THEN 1.2 ELSE 1 END) * (0.45 + random()::numeric * 1.1) + random()::numeric)::int;
      FOR i IN 1..k LOOP
        is_manual := random()::numeric < p.manual_share;
        IF is_manual THEN
          g := 'MANUAL';
        ELSE
          SELECT x.grp INTO g FROM seed_mix x WHERE x.profile = p.profile ORDER BY -ln(random()::numeric) / x.w LIMIT 1;
        END IF;
        SELECT * INTO m FROM seed_merchant WHERE seed_merchant.grp = g ORDER BY -ln(random()::numeric) / seed_merchant.w LIMIT 1;
        amt := (m.amt_min + (m.amt_max - m.amt_min) * power(random()::numeric, 1.8)) * p.scale;
        hr := m.h_lo + floor(random()::numeric * (m.h_hi - m.h_lo + 1))::int;
        INSERT INTO seed_txn(user_id, d, ts, merchant, grp, amount, direction, recurring, manual, rnd)
        VALUES (p.user_id, d, d + make_interval(hours => hr, mins => (random()::numeric * 59)::int, secs => (random()::numeric * 59)::int),
                m.merchant, g, amt, 'DEBIT', false, is_manual, m.rnd);
      END LOOP;
      d := d + 1;
    END LOOP;
  END LOOP;
END $$;

-- -----------------------------------------------------------------------------------------------------
-- 4. Shape each budgeted month to the persona's intended status
--    current month : ON_TRACK  = 55–85 % of the pro-rata target so far
--                    AHEAD     = above pro-rata, below the limit
--                    OVER      = 106–130 % of the limit
--    past months   : past_ratio × limit (± 8 %), only for months with ≥ 20 days of history
-- -----------------------------------------------------------------------------------------------------
DO $$
DECLARE
  p record; ms date; me date; mlen int; elapsed int; target numeric; total numeric; fixed numeric; disc numeric;
  f numeric; big record; gap numeric; amt numeric; last_seq bigint; adj numeric; months int; v_start date;
  first_month date := (date_trunc('month', current_date) - interval '5 months')::date;
  cur date := date_trunc('month', current_date)::date;
BEGIN
  FOR p IN SELECT * FROM seed_persona WHERE monthly_limit IS NOT NULL AND freq > 0 AND behaviour <> 'FEW_TXNS' ORDER BY n LOOP
    PERFORM setseed(0.5 + p.n / 1000.0);
    v_start := greatest(first_month, current_date - p.start_days);
    FOR months IN 0..5 LOOP
      ms := (cur - make_interval(months => months))::date;
      me := (ms + interval '1 month - 1 day')::date;
      mlen := extract(day from me)::int;
      IF months = 0 THEN
        elapsed := (current_date - ms) + 1;
        target := CASE p.behaviour
          WHEN 'ON_TRACK'      THEN p.monthly_limit * elapsed / mlen * (0.55 + random()::numeric * 0.30)
          WHEN 'AHEAD_OF_PACE' THEN least(p.monthly_limit * elapsed / mlen * (1.25 + random()::numeric * 0.5), p.monthly_limit * 0.97)
          WHEN 'OVER_BUDGET'   THEN p.monthly_limit * (1.06 + random()::numeric * 0.24)
          ELSE NULL END;
      ELSE
        IF (me - greatest(ms, v_start)) < 19 OR p.past_ratio IS NULL THEN CONTINUE; END IF;
        target := p.monthly_limit * (p.past_ratio + (random()::numeric - 0.5) * 0.16);
      END IF;
      IF target IS NULL THEN CONTINUE; END IF;

      SELECT coalesce(sum(amount) FILTER (WHERE recurring), 0), coalesce(sum(amount) FILTER (WHERE NOT recurring), 0)
        INTO fixed, disc
        FROM seed_txn WHERE user_id = p.user_id AND direction = 'DEBIT' AND d BETWEEN ms AND least(me, current_date);

      IF months = 0 AND p.behaviour = 'ON_TRACK' AND fixed > target * 0.6 THEN
        DELETE FROM seed_txn WHERE seq IN (
          SELECT seq FROM (SELECT seq, sum(amount) OVER (ORDER BY amount, seq) AS keep
                             FROM seed_txn WHERE user_id = p.user_id AND recurring AND direction = 'DEBIT'
                              AND d BETWEEN ms AND current_date) z
           WHERE keep > target * 0.5);
        SELECT coalesce(sum(amount), 0) INTO fixed FROM seed_txn
         WHERE user_id = p.user_id AND recurring AND direction = 'DEBIT' AND d BETWEEN ms AND current_date;
      END IF;

      -- a lot to make up: add realistic big-ticket purchases first
      gap := target - fixed - disc * 1.6;
      WHILE gap > 0 LOOP
        SELECT * INTO big FROM seed_merchant WHERE grp = 'BIG' ORDER BY -ln(random()::numeric) / w LIMIT 1;
        amt := least(gap, big.amt_min + (big.amt_max - big.amt_min) * random()::numeric);
        amt := greatest(amt, big.amt_min * 0.5);
        INSERT INTO seed_txn(user_id, d, ts, merchant, grp, amount, direction, recurring, manual, rnd)
        VALUES (p.user_id,
                ms + floor(random()::numeric * ((least(me, current_date) - ms) + 1))::int,
                NULL, big.merchant, 'BIG', amt, 'DEBIT', false, false, big.rnd);
        disc := disc + amt;
        gap := gap - amt;
      END LOOP;

      -- scale discretionary spend so the month lands on target (bills stay as billed)
      IF disc > 0 THEN
        f := greatest((target - fixed) / disc, 0.2);
        UPDATE seed_txn SET amount = amount * f
         WHERE user_id = p.user_id AND direction = 'DEBIT' AND NOT recurring AND d BETWEEN ms AND least(me, current_date);
      END IF;
    END LOOP;
  END LOOP;
END $$;

-- big-ticket rows inserted above need a timestamp
UPDATE seed_txn s SET ts = s.d + make_interval(hours => m.h_lo + (abs(hashtext(s.seq::text)) % greatest(m.h_hi - m.h_lo, 1)),
                                            mins => abs(hashtext('m' || s.seq)) % 59)
  FROM (SELECT DISTINCT ON (merchant) merchant, h_lo, h_hi FROM seed_merchant WHERE grp = 'BIG') m
 WHERE s.ts IS NULL AND s.merchant = m.merchant;

-- realistic rounding (fuel to Rs 100, rides to the rupee, supermarkets to the cent …), never below Rs 50
UPDATE seed_txn SET amount = greatest(CASE WHEN rnd >= 1 THEN round(amount / rnd) * rnd ELSE round(amount, 2) END, 50)
 WHERE direction = 'DEBIT';
UPDATE seed_txn SET amount = round(amount, -3) WHERE direction = 'CREDIT';

-- today's rows must not be in the future
UPDATE seed_txn SET ts = least(ts, now() - interval '3 minutes') WHERE d = current_date;
UPDATE seed_txn SET ts = d + time '00:05' WHERE ts < d;   -- very early today: keep on the same date

-- persona 21: a brand-new customer with just three transactions this month
INSERT INTO seed_txn(user_id, d, ts, merchant, grp, amount, direction, recurring, manual, rnd) VALUES
 ('5eed0000-0000-4000-8000-000000000021', current_date - 2, (current_date - 2) + time '12:40', 'BARISTA COFFEE', 'CAFE', 1450.00, 'DEBIT', false, false, 10),
 ('5eed0000-0000-4000-8000-000000000021', current_date - 1, (current_date - 1) + time '18:15', 'PICKME RIDE', 'RIDE', 642.00, 'DEBIT', false, false, 1),
 ('5eed0000-0000-4000-8000-000000000021', current_date,      date_trunc('minute', now() - interval '20 minutes'), 'KEELLS SUPERMARKET NUGEGODA', 'GROCERY', 3287.50, 'DEBIT', false, false, 0.01);

-- -----------------------------------------------------------------------------------------------------
-- 5. Write accounts, then transactions with the category the LIVE rules give
--    (Java \b ≈ PostgreSQL \y; rules are case-insensitive; lowest priority number wins)
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bi_account (id, user_id, tenant_id, bank_code, instrument_type, masked_tail, display_name, is_active, created_at)
SELECT a.id, a.user_id, a.user_id, a.bank_code, a.instrument, a.tail, a.display_name, true,
       (current_date - p.start_days)::timestamptz + interval '9 hours'
FROM seed_account a JOIN seed_persona p USING (user_id);

CREATE TEMP TABLE seed_final ON COMMIT DROP AS
WITH resolved AS (
  SELECT s.*, p.platform, p.n,
         coalesce((SELECT r.category_id FROM bi_merchant_category_map r JOIN bi_category c ON c.id = r.category_id AND c.is_active
                   WHERE r.is_active AND CASE r.match_type
                           WHEN 'EXACT'  THEN upper(s.merchant) = upper(r.merchant_pattern)
                           WHEN 'PREFIX' THEN upper(s.merchant) LIKE upper(r.merchant_pattern) || '%'
                           ELSE upper(s.merchant) ~* regexp_replace(r.merchant_pattern, '\\b', '\\y', 'g') END
                   ORDER BY r.priority, r.id LIMIT 1),
                  (SELECT id FROM bi_category WHERE code = 'OTHER')) AS auto_cat,
         abs(hashtext('acct' || s.seq)) % 100 AS dice
  FROM seed_txn s JOIN seed_persona p USING (user_id)
)
SELECT r.*,
       md5('seed-txn-' || r.user_id || '-' || r.seq)::uuid AS id,
       -- account: bills/salary on the bank account, purchases mostly on the card
       CASE WHEN r.manual AND r.dice < 75 THEN NULL
            ELSE coalesce(
              (SELECT a.id FROM seed_account a WHERE a.user_id = r.user_id
                 AND a.instrument = CASE WHEN r.recurring OR r.grp IN ('MISC') THEN 'BANK'
                                         WHEN r.dice < 70 THEN 'CARD' ELSE 'BANK' END ORDER BY a.idx LIMIT 1),
              (SELECT a.id FROM seed_account a WHERE a.user_id = r.user_id ORDER BY a.idx LIMIT 1)) END AS account_id
FROM resolved r;

-- user re-categorisations a real customer would make (~2 % of rows + every temple donation)
ALTER TABLE seed_final ADD COLUMN category_id uuid, ADD COLUMN user_categorised boolean DEFAULT false;
UPDATE seed_final SET category_id = auto_cat;
UPDATE seed_final SET category_id = (SELECT id FROM bi_category WHERE code = 'SHOPPING'), user_categorised = true
 WHERE merchant LIKE 'CARGILLS FOOD CITY%' AND dice < 35;
UPDATE seed_final SET category_id = (SELECT id FROM bi_category WHERE code = 'GIFTS_DONATION'), user_categorised = true
 WHERE merchant = 'TEMPLE DONATION';
UPDATE seed_final SET category_id = (SELECT id FROM bi_category WHERE code = 'DINING_OUT'), user_categorised = true
 WHERE merchant IN ('LUNCH PACKET', 'KFC') AND dice < 60;
UPDATE seed_final SET category_id = (SELECT id FROM bi_category WHERE code = 'WORK'), user_categorised = true
 WHERE merchant = 'NEWSPAPER AGENT' AND n IN (3, 10, 14, 32);

INSERT INTO bi_transaction (id, user_id, tenant_id, account_id, amount, currency, direction, merchant, description,
                            txn_date, txn_timestamp, category_id, auto_category_id, is_user_categorised, source,
                            parser_template_id, idempotency_key, created_at, updated_at, deleted_at)
SELECT f.id, f.user_id, f.user_id, f.account_id, f.amount, 'LKR', f.direction, f.merchant,
       CASE WHEN f.manual THEN CASE f.merchant WHEN 'VEGETABLE MARKET' THEN 'Pola — weekly vegetables'
                                               WHEN 'TUK TUK RIDE' THEN 'Three-wheeler'
                                               WHEN 'LUNCH PACKET' THEN 'Rice and curry'
                                               WHEN 'TEMPLE DONATION' THEN 'Poya day'
                                               ELSE NULL END END,
       f.d, f.ts, f.category_id, f.auto_cat, f.user_categorised,
       CASE WHEN f.manual THEN 'MANUAL' WHEN f.platform = 'IOS' THEN 'SMS_IOS' ELSE 'SMS_ANDROID' END,
       CASE WHEN f.manual THEN NULL ELSE (SELECT a.template_id FROM seed_account a WHERE a.id = f.account_id) END,
       'seed-' || f.seq,
       f.ts + interval '2 minutes',
       CASE WHEN f.user_categorised THEN f.ts + interval '1 day' END,
       -- a few hand-typed entries the customer later deleted (must NOT appear anywhere)
       CASE WHEN f.manual AND f.dice >= 97 AND f.d < date_trunc('month', current_date) THEN f.ts + interval '3 hours' END
FROM seed_final f;

-- audit trail consistent with how the app writes it
INSERT INTO bi_transaction_audit (id, transaction_id, action, old_category_id, new_category_id, actor, created_at)
SELECT md5('seed-audit-c-' || t.id)::uuid, t.id, 'CREATE', NULL, t.category_id, 'USER:' || t.user_id, t.created_at
  FROM bi_transaction t WHERE t.user_id IN (SELECT user_id FROM seed_user) AND t.source = 'MANUAL'
UNION ALL
SELECT md5('seed-audit-r-' || t.id)::uuid, t.id, 'RECATEGORISE', t.auto_category_id, t.category_id, 'USER:' || t.user_id, t.updated_at
  FROM bi_transaction t WHERE t.user_id IN (SELECT user_id FROM seed_user) AND t.is_user_categorised
UNION ALL
SELECT md5('seed-audit-d-' || t.id)::uuid, t.id, 'DELETE', t.category_id, NULL, 'USER:' || t.user_id, t.deleted_at
  FROM bi_transaction t WHERE t.user_id IN (SELECT user_id FROM seed_user) AND t.deleted_at IS NOT NULL;

-- -----------------------------------------------------------------------------------------------------
-- 6. Monthly budgets (+ roll-forward chain) and category caps
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bi_budget (id, user_id, tenant_id, period_start, period_end, overall_limit, currency, created_at)
SELECT md5('seed-budget-' || p.user_id || '-' || ms)::uuid, p.user_id, p.user_id, ms, (ms + interval '1 month - 1 day')::date,
       -- a few customers raised their target over time
       CASE WHEN ms < date_trunc('month', current_date) - interval '2 months' AND p.n % 3 = 0
            THEN round(p.monthly_limit * 0.9, -3) ELSE p.monthly_limit END,
       'LKR', greatest(ms::timestamptz, (current_date - p.start_days)::timestamptz) + interval '8 hours'
FROM seed_persona p
CROSS JOIN LATERAL (SELECT (date_trunc('month', current_date) - make_interval(months => k))::date AS ms FROM generate_series(0, 5) k) mm
WHERE p.monthly_limit IS NOT NULL
  AND (mm.ms = date_trunc('month', current_date)::date OR (p.past_ratio IS NOT NULL AND (mm.ms + interval '1 month - 1 day')::date - greatest(mm.ms, current_date - p.start_days) >= 19));

UPDATE bi_budget b SET rolled_from_id = prev.id
  FROM bi_budget prev
 WHERE b.user_id IN (SELECT user_id FROM seed_user) AND prev.user_id = b.user_id
   AND prev.period_start = (b.period_start - interval '1 month')::date AND hashtext(b.user_id::text) % 2 = 0;

-- Caps sized like a customer would: the category's typical month (median of completed months),
-- falling back to a sensible default for new customers
CREATE TEMP TABLE seed_capspec ON COMMIT DROP AS
SELECT p.n, p.user_id, p.behaviour, p.monthly_limit, c.code, c.id AS category_id, split_part(spec, ':', 2) AS kind
FROM seed_persona p
CROSS JOIN LATERAL unnest(string_to_array(nullif(p.caps, ''), ',')) spec
JOIN bi_category c ON c.code = split_part(spec, ':', 1);

CREATE TEMP TABLE seed_catmonth ON COMMIT DROP AS
SELECT s.user_id, s.code, s.category_id, date_trunc('month', t.txn_date)::date AS ms, sum(t.amount) AS spent
FROM seed_capspec s
JOIN bi_transaction t ON t.user_id = s.user_id AND t.category_id = s.category_id AND t.direction = 'DEBIT' AND t.deleted_at IS NULL
GROUP BY 1, 2, 3, 4;

INSERT INTO bi_budget_category_limit (id, budget_id, category_id, limit_amount, alert_threshold_pct, created_at)
SELECT md5('seed-cap-' || b.id || '-' || s.code)::uuid, b.id, s.category_id,
       CASE WHEN b.period_start < date_trunc('month', current_date)
            -- past months: comfortably above what was spent
            THEN greatest(round(greatest(coalesce(m.spent, 0), 2000) / 0.72, -3), 1000)
            -- this month: the customer's typical month for the category
            ELSE greatest(round(coalesce(
                   (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY h.spent) FROM seed_catmonth h
                     WHERE h.user_id = s.user_id AND h.code = s.code AND h.ms < date_trunc('month', current_date)),
                   CASE s.code WHEN 'TRAVEL' THEN 60000 WHEN 'SHOPPING' THEN 30000 WHEN 'DINING_OUT' THEN 15000
                               WHEN 'TRANSPORT' THEN 15000 WHEN 'HEALTH_CARE' THEN 8000 ELSE 6000 END)::numeric, -3), 2000) END,
       CASE WHEN s.kind = 'APPROACH' AND s.code = 'TRANSPORT' THEN 75 ELSE 80 END,
       b.period_start::timestamptz + interval '8 hours 30 minutes'
FROM bi_budget b
JOIN seed_capspec s ON s.user_id = b.user_id
LEFT JOIN seed_catmonth m ON m.user_id = b.user_id AND m.code = s.code AND m.ms = b.period_start;

-- -----------------------------------------------------------------------------------------------------
-- 6b. Make this month's caps tell their story (APPROACH = 80–95 % used, EXCEED = over) with realistic
--     purchases, then pin every persona exactly on its intended status. Works on any day of the month.
-- -----------------------------------------------------------------------------------------------------
CREATE TEMP TABLE seed_topup_merchant (code text, merchant text, src_tpl boolean) ON COMMIT DROP;
INSERT INTO seed_topup_merchant VALUES
 ('DINING_OUT','PIZZA HUT',true), ('SHOPPING','ODEL STORE',true), ('TRANSPORT','CEYPETCO FILLING STATION',true),
 ('HEALTH_CARE','ASIRI HOSPITAL',true), ('ENTERTAINMENT','SCOPE CINEMA',true), ('TRAVEL','JETWING TRAVEL BOOKING',true);

DO $$
DECLARE
  s record; v_cap numeric; v_spent numeric; v_total numeric; v_limit numeric; v_ideal numeric; v_head numeric;
  v_want numeric; v_need numeric; v_acct uuid; v_tpl uuid; v_merchant text; v_src text; v_seq int := 0; r record;
  ms date := date_trunc('month', current_date)::date;
  mlen int := extract(day from (date_trunc('month', current_date) + interval '1 month - 1 day'))::int;
  elapsed int := (current_date - date_trunc('month', current_date)::date) + 1;
BEGIN
  -- (a) caps
  FOR s IN SELECT cs.*, l.id AS limit_id, l.limit_amount, b.overall_limit
             FROM seed_capspec cs
             JOIN bi_budget b ON b.user_id = cs.user_id AND b.period_start = ms
             JOIN bi_budget_category_limit l ON l.budget_id = b.id AND l.category_id = cs.category_id
            WHERE cs.kind IN ('APPROACH', 'EXCEED') ORDER BY cs.n, cs.code LOOP
    v_cap := s.limit_amount;
    SELECT coalesce(sum(amount), 0) INTO v_spent FROM bi_transaction
     WHERE user_id = s.user_id AND category_id = s.category_id AND direction = 'DEBIT' AND deleted_at IS NULL AND txn_date >= ms;
    SELECT coalesce(sum(amount), 0) INTO v_total FROM bi_transaction
     WHERE user_id = s.user_id AND direction = 'DEBIT' AND deleted_at IS NULL AND txn_date >= ms;
    v_limit := s.overall_limit;
    v_ideal := v_limit * elapsed / mlen;
    -- how much more this persona may spend this month and still keep its status
    v_head := CASE s.behaviour WHEN 'OVER_BUDGET' THEN 1e12
                               WHEN 'AHEAD_OF_PACE' THEN v_limit * 0.95 - v_total
                               ELSE v_ideal * 0.9 - v_total END;
    v_want := CASE s.kind WHEN 'EXCEED' THEN v_cap * 1.07 ELSE v_cap * 0.86 END;
    v_need := v_want - v_spent;
    IF s.kind = 'APPROACH' AND v_spent >= v_cap * 0.8 THEN
      v_need := 0;
      IF v_spent >= v_cap THEN v_cap := round(v_spent / 0.86, -2); END IF;
    ELSIF s.kind = 'EXCEED' AND v_spent > v_cap THEN
      v_need := 0;
    ELSIF v_need > greatest(v_head, 0) THEN
      -- not enough room: shrink the cap so the story still holds without changing the status
      v_need := greatest(v_head, 0);
      v_cap := round((v_spent + v_need) / CASE s.kind WHEN 'EXCEED' THEN 1.07 ELSE 0.86 END, -2);
      IF v_cap < 1000 THEN v_cap := greatest(round((v_spent + 1500) / 0.45, -3), 2000); v_need := 0; END IF;  -- stays SAFE
    END IF;
    IF v_need >= 100 THEN
      v_seq := v_seq + 1;
      SELECT merchant INTO v_merchant FROM seed_topup_merchant WHERE code = s.code;
      SELECT a.id, a.template_id INTO v_acct, v_tpl FROM seed_account a WHERE a.user_id = s.user_id
       ORDER BY (a.instrument = 'CARD') DESC, a.idx LIMIT 1;
      SELECT CASE WHEN p.platform = 'IOS' THEN 'SMS_IOS' ELSE 'SMS_ANDROID' END INTO v_src FROM seed_persona p WHERE p.user_id = s.user_id;
      INSERT INTO bi_transaction (id, user_id, tenant_id, account_id, amount, currency, direction, merchant, txn_date, txn_timestamp,
                                  category_id, auto_category_id, is_user_categorised, source, parser_template_id, idempotency_key, created_at)
      VALUES (md5('seed-topup-' || s.user_id || '-' || s.code)::uuid, s.user_id, s.user_id, v_acct, round(v_need, 2), 'LKR', 'DEBIT',
              v_merchant, greatest(current_date - 1, ms),
              least(greatest(current_date - 1, ms) + time '19:42', now() - interval '10 minutes'),
              s.category_id, s.category_id, false, v_src, v_tpl, 'seed-topup-' || s.code, now());
    END IF;
    UPDATE bi_budget_category_limit SET limit_amount = v_cap WHERE id = s.limit_id;
  END LOOP;

  -- (b) pin the headline status
  FOR s IN SELECT p.*, b.overall_limit FROM seed_persona p JOIN bi_budget b ON b.user_id = p.user_id AND b.period_start = ms
            WHERE p.behaviour IN ('ON_TRACK', 'AHEAD_OF_PACE', 'OVER_BUDGET') ORDER BY p.n LOOP
    v_limit := s.overall_limit;
    v_ideal := v_limit * elapsed / mlen;
    LOOP
      SELECT coalesce(sum(amount), 0) INTO v_total FROM bi_transaction
       WHERE user_id = s.user_id AND direction = 'DEBIT' AND deleted_at IS NULL AND txn_date >= ms;
      EXIT WHEN (s.behaviour = 'ON_TRACK' AND v_total <= v_ideal * 0.95)
             OR (s.behaviour = 'AHEAD_OF_PACE' AND v_total > v_ideal * 1.05 AND v_total < v_limit * 0.98)
             OR (s.behaviour = 'OVER_BUDGET' AND v_total > v_limit * 1.03);
      IF (s.behaviour = 'ON_TRACK') OR (s.behaviour = 'AHEAD_OF_PACE' AND v_total >= v_limit * 0.98) THEN
        -- spent too much: drop this month's largest discretionary purchase (never a bill or a cap top-up)
        SELECT t.id INTO r FROM bi_transaction t
         WHERE t.user_id = s.user_id AND t.direction = 'DEBIT' AND t.txn_date >= ms AND t.idempotency_key NOT LIKE 'seed-topup-%'
         ORDER BY (t.merchant IN (SELECT merchant FROM seed_recurring)) ASC, t.amount DESC LIMIT 1;
        EXIT WHEN r IS NULL;
        DELETE FROM bi_transaction_audit WHERE transaction_id = r.id;
        DELETE FROM bi_transaction WHERE id = r.id;
      ELSE
        -- spent too little: one more realistic purchase closes the gap
        v_need := CASE s.behaviour WHEN 'OVER_BUDGET' THEN v_limit * 1.08 - v_total
                                   ELSE least(v_ideal * 1.3, v_limit * 0.9) - v_total END;
        v_seq := v_seq + 1;
        SELECT a.id, a.template_id INTO v_acct, v_tpl FROM seed_account a WHERE a.user_id = s.user_id
         ORDER BY (a.instrument = 'CARD') DESC, a.idx LIMIT 1;
        INSERT INTO bi_transaction (id, user_id, tenant_id, account_id, amount, currency, direction, merchant, txn_date, txn_timestamp,
                                    category_id, auto_category_id, is_user_categorised, source, parser_template_id, idempotency_key, created_at)
        VALUES (md5('seed-pace-' || s.user_id || '-' || v_seq)::uuid, s.user_id, s.user_id, v_acct, round(greatest(v_need, 100), -1), 'LKR', 'DEBIT',
                CASE WHEN v_need > 20000 THEN 'SINGER MEGA' ELSE 'LANKA SATHOSA' END, current_date,
                date_trunc('minute', now() - interval '25 minutes'),
                (SELECT id FROM bi_category WHERE code = 'OTHER'), (SELECT id FROM bi_category WHERE code = 'OTHER'), false,
                CASE WHEN s.platform = 'IOS' THEN 'SMS_IOS' ELSE 'SMS_ANDROID' END, v_tpl, 'seed-pace-' || v_seq, now());
      END IF;
    END LOOP;
  END LOOP;
END $$;

-- -----------------------------------------------------------------------------------------------------
-- 7. Alerts, raised at the transaction that crossed the line (current + previous month)
-- -----------------------------------------------------------------------------------------------------
CREATE TEMP TABLE seed_running ON COMMIT DROP AS
SELECT t.user_id, b.period_start, b.overall_limit, t.category_id, t.amount, t.txn_timestamp, t.id,
       sum(t.amount) OVER (PARTITION BY t.user_id, b.period_start ORDER BY t.txn_timestamp, t.id) AS cum_total,
       sum(t.amount) OVER (PARTITION BY t.user_id, b.period_start, t.category_id ORDER BY t.txn_timestamp, t.id) AS cum_cat
FROM bi_transaction t
JOIN bi_budget b ON b.user_id = t.user_id AND t.txn_date BETWEEN b.period_start AND b.period_end
WHERE t.user_id IN (SELECT user_id FROM seed_user) AND t.direction = 'DEBIT' AND t.deleted_at IS NULL
  AND b.period_start >= (date_trunc('month', current_date) - interval '1 month')::date;

INSERT INTO bi_budget_alert (id, user_id, tenant_id, period_start, category_id, alert_type, threshold_pct, amount_at_raise, limit_at_raise, raised_at, acknowledged_at)
-- overall target exceeded
SELECT md5('seed-alert-b-' || x.user_id || x.period_start)::uuid, x.user_id, x.user_id, x.period_start, NULL, 'BUDGET_EXCEEDED', NULL::smallint,
       x.cum_total, x.overall_limit, x.txn_timestamp + interval '1 minute',
       CASE WHEN x.period_start < date_trunc('month', current_date) THEN x.txn_timestamp + interval '20 hours' END
FROM (SELECT DISTINCT ON (user_id, period_start) * FROM seed_running WHERE cum_total >= overall_limit
      ORDER BY user_id, period_start, txn_timestamp, id) x
UNION ALL
-- category cap exceeded
SELECT md5('seed-alert-e-' || x.user_id || x.period_start || x.category_id)::uuid, x.user_id, x.user_id, x.period_start, x.category_id,
       'CATEGORY_EXCEEDED', NULL::smallint, x.cum_cat, l.limit_amount, x.txn_timestamp + interval '1 minute',
       CASE WHEN x.period_start < date_trunc('month', current_date) THEN x.txn_timestamp + interval '1 day' END
FROM bi_budget b JOIN bi_budget_category_limit l ON l.budget_id = b.id
JOIN LATERAL (SELECT * FROM seed_running r WHERE r.user_id = b.user_id AND r.period_start = b.period_start
              AND r.category_id = l.category_id AND r.cum_cat >= l.limit_amount ORDER BY r.txn_timestamp, r.id LIMIT 1) x ON true
WHERE b.user_id IN (SELECT user_id FROM seed_user)
UNION ALL
-- category cap approaching (only when it was crossed before, not by the same purchase as, the cap itself)
SELECT md5('seed-alert-a-' || x.user_id || x.period_start || x.category_id)::uuid, x.user_id, x.user_id, x.period_start, x.category_id,
       'CATEGORY_APPROACHING', l.alert_threshold_pct, x.cum_cat, l.limit_amount, x.txn_timestamp + interval '1 minute',
       CASE WHEN x.period_start < date_trunc('month', current_date) OR hashtext(x.user_id::text) % 2 = 0
            THEN least(x.txn_timestamp + interval '6 hours', now()) END
FROM bi_budget b JOIN bi_budget_category_limit l ON l.budget_id = b.id
JOIN LATERAL (SELECT * FROM seed_running r WHERE r.user_id = b.user_id AND r.period_start = b.period_start
              AND r.category_id = l.category_id AND r.cum_cat >= l.limit_amount * l.alert_threshold_pct / 100.0
              ORDER BY r.txn_timestamp, r.id LIMIT 1) x ON true
WHERE b.user_id IN (SELECT user_id FROM seed_user)
  AND x.cum_cat < l.limit_amount;
UPDATE bi_budget_alert SET acknowledged_at = least(acknowledged_at, now()) WHERE acknowledged_at > now();

-- -----------------------------------------------------------------------------------------------------
-- 8. Consent (active, withdrawn) and device sync state
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bi_consent (id, user_id, tenant_id, consent_version, policy_hash, channel, granted_at, withdrawn_at, created_at)
SELECT md5('seed-consent-g-' || p.user_id)::uuid, p.user_id, p.user_id, '1.0',
       md5('budget-insights-policy-v1.0') || md5('seed'), p.platform,
       (current_date - p.start_days - 1)::timestamptz + interval '20 hours', NULL,
       (current_date - p.start_days - 1)::timestamptz + interval '20 hours'
FROM seed_persona p
UNION ALL
SELECT md5('seed-consent-w-' || p.user_id)::uuid, p.user_id, p.user_id, '1.0',
       md5('budget-insights-policy-v1.0') || md5('seed'), p.platform,
       (current_date - p.start_days - 1)::timestamptz + interval '20 hours',
       (current_date - p.end_days + 1)::timestamptz + interval '10 hours',
       (current_date - p.end_days + 1)::timestamptz + interval '10 hours'
FROM seed_persona p WHERE p.consent = 'WITHDRAWN';

INSERT INTO bi_device_sync_state (id, user_id, device_id, last_synced_at, last_ingest_seq, template_version, created_at, updated_at)
SELECT md5('seed-dev-' || p.user_id || '-' || k)::uuid, p.user_id,
       'seed-device-' || lpad(p.n::text, 2, '0') || CASE WHEN k = 2 THEN '-tablet' ELSE '' END,
       CASE WHEN p.end_days > 0 THEN (current_date - p.end_days)::timestamptz + interval '21 hours'
            ELSE now() - make_interval(mins => 15 + p.n * 7) END,
       (SELECT count(*) FROM seed_txn s WHERE s.user_id = p.user_id AND NOT s.manual),
       CASE WHEN p.synced_current THEN (SELECT coalesce(max(version), 1) FROM bi_reference_release) ELSE 0 END,
       (current_date - p.start_days)::timestamptz + interval '9 hours', now()
FROM seed_persona p CROSS JOIN generate_series(1, 2) k
WHERE k = 1 OR p.n IN (3, 10, 14);

COMMIT;

-- -----------------------------------------------------------------------------------------------------
-- Quick look (run after COMMIT)
-- -----------------------------------------------------------------------------------------------------
SELECT count(DISTINCT user_id) AS users_with_txns, count(*) AS transactions,
       count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted, min(txn_date) AS first_day, max(txn_date) AS last_day,
       round(sum(amount) FILTER (WHERE direction = 'DEBIT' AND deleted_at IS NULL)) AS total_spend_lkr
FROM budget_insights.bi_transaction WHERE idempotency_key LIKE 'seed-%';
