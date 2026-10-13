SET lock_timeout = '3s';

-- =====================================================================================
-- Budget Insights — duplicate suppression for device-reported compliance events (BI-36).
--
-- WHY THIS EXISTS
--   Until now every row in bi_compliance_event was written by this service about something
--   this service observed, and a retry was impossible because there was no client to retry.
--   BI-36 adds a route a handset calls to report the OTP and PIN messages its own filter
--   destroyed -- the detections that never reach the server, and which the compliance
--   record therefore never saw.
--
--   A handset retries. It times out, it comes back from being offline, it resends its queue.
--   Without a key it can be deduplicated on, every one of those retries lands as a fresh
--   incident, and the table a regulator reads shows a spike in OTP capture attempts that
--   never happened. That is worse than a gap: a gap is visibly a gap.
--
-- WHY A HASH AND NOT THE VALUE
--   client_ref_hash holds a digest of the reference the device sent, never the reference.
--   This table's whole design is that it is physically incapable of carrying message
--   content -- four payload columns, no user foreign key, no amount. A free-text column
--   filled by the client would undo that in one field: a device could put a code in it, and
--   this is the one table in the schema that is never purged, not on consent withdrawal and
--   not on erasure. Storing sixty-four characters of hex instead keeps the property true by
--   construction rather than by the client behaving.
--
-- WHY A PARTIAL INDEX
--   Only device-reported rows have a reference. Every event the server raises for itself
--   leaves the column null, and there will always be far more of those. Indexing the rows
--   that have one keeps the index proportional to the thing it protects.
--
--   NULLs are distinct in a PostgreSQL unique index, so a plain unique constraint would
--   have worked too; the WHERE clause is about size, not correctness.
--
-- SAFETY
--   Additive and nullable. No existing row is read, rewritten or invalidated, no existing
--   write path names this column, and the index cannot conflict with anything already
--   stored because every existing row leaves it null. Re-runnable.
-- =====================================================================================

ALTER TABLE budget_insights.bi_compliance_event
    ADD COLUMN IF NOT EXISTS client_ref_hash varchar(64);

COMMENT ON COLUMN budget_insights.bi_compliance_event.client_ref_hash IS
    'SHA-256 of the reporting device''s reference for one detection, never the reference '
    'itself. Set only on device-reported events (source SMS_PARSER); null on everything the '
    'service raises for itself. Exists so a retried report is the same incident, not a second '
    'one.';

CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_ce_tenant_client_ref
    ON budget_insights.bi_compliance_event (tenant_id, client_ref_hash)
    WHERE client_ref_hash IS NOT NULL;
