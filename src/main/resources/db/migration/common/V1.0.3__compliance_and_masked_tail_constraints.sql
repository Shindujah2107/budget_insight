SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights — three CHECK constraints the baseline should have carried.
--
-- WHY THESE THREE
--   Every other enum-backed column in this schema is guarded by a CHECK that lists the
--   Java enum's constants: instrument_type, direction, source on bi_transaction,
--   parse_status, match_type, channel, action on bi_transaction_audit, and source on
--   bi_compliance_event itself. Two columns were missed, and one column carries a
--   compliance claim that nothing enforces. Both gaps came across from core with the
--   rest of the DDL; neither is new.
--
--   bi_compliance_event.event_type / .action
--       This is the one table that is never purged -- not on consent withdrawal, not on
--       erasure. A value written here is permanent. It is also the table a regulator
--       reads. An event_type outside ComplianceEventType would therefore be a permanent,
--       unreadable row in the record that is meant to be the authoritative one, and
--       nothing today would refuse it.
--
--   bi_account.masked_tail
--       The column is documented, in BiAccount and again in InsightsMapper, as holding
--       the last four digits of an instrument and nothing else -- the boundary that
--       keeps a PAN out of this database. varchar(8) does not enforce that: it permits
--       eight characters of anything. The regex below is what makes the documented
--       invariant true rather than merely intended, and it is deliberately narrower than
--       the column so the column width never has to be argued about again.
--
-- SAFETY
--   All three tables are empty on a fresh Budget Insights database, which every
--   environment has, because this service's database is new. If this is ever applied
--   somewhere with existing rows, PostgreSQL validates them and fails loudly on a
--   violation rather than accepting bad data -- which is the correct outcome: a
--   masked_tail that is not four digits is a leak to investigate, not a row to coerce.
-- =====================================================================================

ALTER TABLE budget_insights.bi_compliance_event
    ADD CONSTRAINT ck_bi_ce_event_type CHECK (event_type IN (
        'OTP_DETECTED',
        'PIN_DETECTED',
        'SENDER_REJECTED',
        'CONSENT_GRANTED',
        'CONSENT_WITHDRAWN',
        'CATEGORY_OVERRIDDEN',
        'EXPORT_REQUESTED',
        'ERASURE_COMPLETED',
        'INGEST_RATE_LIMITED'
    ));

ALTER TABLE budget_insights.bi_compliance_event
    ADD CONSTRAINT ck_bi_ce_action CHECK (action IN (
        'DISCARDED',
        'REJECTED',
        'THROTTLED',
        'RECORDED'
    ));

-- Exactly four digits. Not "at most eight characters", not "mostly digits".
ALTER TABLE budget_insights.bi_account
    ADD CONSTRAINT ck_bi_account_masked_tail CHECK (masked_tail ~ '^[0-9]{4}$');
