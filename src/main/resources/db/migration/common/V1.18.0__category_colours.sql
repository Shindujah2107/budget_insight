-- =====================================================================================
-- V1.18.0  Category colours
-- =====================================================================================
-- Categories now carry the colour the app draws them in (doughnut, icon circles, bars).
-- Seeds the app's palette, but only where no colour has been set, so a colour an admin
-- already chose is kept. The app falls back to its own palette for any category left empty.
-- =====================================================================================

UPDATE budget_insights.bi_category c
SET    colour_hex = v.colour_hex
FROM (VALUES
    ('DINING_OUT',      '#F3C15C'),
    ('SHOPPING',        '#E6823D'),
    ('TRANSPORT',       '#1AACAB'),
    ('BILLS_UTILITIES', '#9071FF'),
    ('HEALTH_CARE',     '#428DE6'),
    ('SUBSCRIPTIONS',   '#EC517F'),
    ('PERSONAL_CARE',   '#ECBCF7'),
    ('TRANSFERS',       '#BA8B5B'),
    ('OTHER',           '#CFC7CE'),
    ('EDUCATION',       '#B2C1F8'),
    ('ENTERTAINMENT',   '#E07794'),
    ('UTILITIES',       '#CBE853'),
    ('DEBT_PAYMENTS',   '#F7A072'),
    ('GIFTS_DONATION',  '#B98D9F'),
    ('GYM',             '#469C79'),
    ('TRAVEL',          '#86B4C7'),
    ('WORK',            '#E4BB91')
) AS v(code, colour_hex)
WHERE  c.code = v.code
  AND  c.colour_hex IS NULL;

COMMENT ON COLUMN budget_insights.bi_category.colour_hex IS
    'Display colour #RRGGBB sent to the app; seeded with the app palette (V1.18.0), edited in the Admin Dashboard. Empty = the app''s own colour for the code.';
