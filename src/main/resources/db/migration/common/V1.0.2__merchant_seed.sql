SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights — starter merchant categorisation rules (R1).
--
-- WHAT THIS IS
--   The first, deterministic pass of categorisation: merchant string -> category.
--   Without at least one rule the engine falls straight through to OTHER for every
--   transaction, which makes the breakdown a single bar and R1 impossible to demonstrate.
--
-- WHAT THIS IS NOT
--   This is not the production rule set. The real one is derived from the labelled SMS
--   sample set (dependency D-1) and will show which merchant strings actually appear in
--   Sri Lankan bank alerts, with what spelling and spacing. That set replaces and extends
--   what is here.
--
-- WHY THESE RULES ARE SAFE TO SHIP AHEAD OF D-1
--   Every pattern below is a generic category word, not a guessed merchant spelling
--   ("PHARMACY", not a particular chain). A generic word either appears in the merchant
--   string or it does not. A guessed brand spelling, by contrast, fails silently: it
--   simply never matches, and nobody finds out until a customer asks why their groceries
--   are filed under Other. Where a rule does fire wrongly, the customer re-categorises and
--   the isUserCategorised latch makes that correction permanent.
--
-- PRIORITY
--   Lower value is evaluated first. The blocks are spaced ten apart so D-1 rules can be
--   interleaved without renumbering these.
--
-- MATCH TYPE
--   REGEX throughout, matched case-insensitively as a substring (the engine uses find(),
--   not matches()). Word boundaries keep "GYM" from matching "GYMKHANA CLUB" and keep
--   "BUS" from matching "BUSINESS".
--
-- Re-runnable: ON CONFLICT DO NOTHING against ux_bi_mcm_pattern_type.
-- =====================================================================================

INSERT INTO budget_insights.bi_merchant_category_map
    (id, merchant_pattern, match_type, category_id, priority, is_active)
SELECT v.id, v.pattern, 'REGEX', c.id, v.priority, true
FROM (VALUES
    -- Transport ----------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000001'::uuid, '\bFUEL\b|\bPETROL\b|\bDIESEL\b|\bFILLING STATION\b', 'TRANSPORT',      10::smallint),
    ('b1d00000-0000-4000-a000-000000000002'::uuid, '\bTAXI\b|\bCAB\b|\bRIDE\b|\bPARKING\b',              'TRANSPORT',      10::smallint),
    ('b1d00000-0000-4000-a000-000000000003'::uuid, '\bRAILWAY\b|\bTRAIN\b|\bBUS\b|\bTRANSIT\b',          'TRANSPORT',      10::smallint),

    -- Dining out ---------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000010'::uuid, '\bRESTAURANT\b|\bCAFE\b|\bCOFFEE\b|\bBAKERY\b',      'DINING_OUT',     20::smallint),
    ('b1d00000-0000-4000-a000-000000000011'::uuid, '\bFOOD\b|\bKITCHEN\b|\bPIZZA\b|\bBURGER\b',          'DINING_OUT',     20::smallint),
    ('b1d00000-0000-4000-a000-000000000012'::uuid, '\bHOTEL\b.*\bDINING\b|\bCANTEEN\b|\bDELI\b',         'DINING_OUT',     20::smallint),

    -- Shopping -----------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000020'::uuid, '\bSUPERMARKET\b|\bGROCER|\bMINI MART\b|\bSUPERMART\b', 'SHOPPING',     30::smallint),
    ('b1d00000-0000-4000-a000-000000000021'::uuid, '\bSTORE\b|\bSTORES\b|\bMALL\b|\bBOUTIQUE\b',         'SHOPPING',       30::smallint),

    -- Health care --------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000030'::uuid, '\bPHARMACY\b|\bCHEMIST\b|\bDRUG STORE\b',            'HEALTH_CARE',    40::smallint),
    ('b1d00000-0000-4000-a000-000000000031'::uuid, '\bHOSPITAL\b|\bCLINIC\b|\bMEDICAL\b|\bLAB\b|\bDENTAL\b', 'HEALTH_CARE', 40::smallint),

    -- Bills and utilities ------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000040'::uuid, '\bELECTRICITY\b|\bWATER BOARD\b|\bGAS\b',            'BILLS_UTILITIES', 50::smallint),
    ('b1d00000-0000-4000-a000-000000000041'::uuid, '\bBROADBAND\b|\bINTERNET\b|\bTELECOM\b|\bMOBITEL\b|\bRELOAD\b', 'BILLS_UTILITIES', 50::smallint),
    ('b1d00000-0000-4000-a000-000000000042'::uuid, '\bBILL PAY\b|\bBILLPAY\b|\bUTILITY\b',               'BILLS_UTILITIES', 50::smallint),

    -- Subscriptions ------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000050'::uuid, '\bSUBSCRIPTION\b|\bMONTHLY PLAN\b|\bRENEWAL\b',      'SUBSCRIPTIONS',  60::smallint),

    -- Entertainment ------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000060'::uuid, '\bCINEMA\b|\bTHEATRE\b|\bMOVIE\b|\bGAMING\b',        'ENTERTAINMENT',  70::smallint),

    -- Gym ----------------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000070'::uuid, '\bGYM\b|\bFITNESS\b',                                'GYM',            80::smallint),

    -- Education ----------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000080'::uuid, '\bSCHOOL\b|\bCOLLEGE\b|\bUNIVERSITY\b|\bTUITION\b|\bINSTITUTE\b', 'EDUCATION', 90::smallint),

    -- Travel -------------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000090'::uuid, '\bAIRLINE\b|\bAIRWAYS\b|\bTRAVEL\b|\bTOURS\b|\bBOOKING\b', 'TRAVEL',   100::smallint),

    -- Personal care ------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000100'::uuid, '\bSALON\b|\bSPA\b|\bBARBER\b|\bCOSMETIC',            'PERSONAL_CARE',  110::smallint),

    -- Transfers ----------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000110'::uuid, '\bTRANSFER\b|\bFUND TRANSFER\b|\bCEFT\b|\bSLIPS\b',  'TRANSFERS',      120::smallint),

    -- Debt payments ------------------------------------------------------------------
    ('b1d00000-0000-4000-a000-000000000120'::uuid, '\bLOAN\b|\bINSTALMENT\b|\bINSTALLMENT\b|\bEMI\b|\bCARD PAYMENT\b', 'DEBT_PAYMENTS', 130::smallint)
) AS v(id, pattern, category_code, priority)
JOIN budget_insights.bi_category c ON c.code = v.category_code
ON CONFLICT ON CONSTRAINT ux_bi_mcm_pattern_type DO NOTHING;
