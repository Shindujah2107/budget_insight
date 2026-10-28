-- -------------------------------------------------------------------------------------
-- BI-27 — where a data export lives between the request and the download.
--
-- The DIP's architecture diagram puts exports in object storage. No bucket exists for
-- Budget Insights in Terraform, and provisioning one carries a Compliance question of its
-- own (how long may a file holding a customer's complete financial history persist). This
-- table is the DEV-completable answer: the same contract -- request returns an id, the id
-- is then downloaded -- with the artefact held in the database the data already lives in,
-- under the same encryption at rest, for 24 hours. Replacing this with S3 later changes
-- the storage and not the API.
--
-- What it does add is duration: a second copy of the customer's history exists for the
-- life of the row. That is why expires_at is NOT NULL with no default long enough to
-- forget about, why the retention job deletes expired rows, and why erasure deletes this
-- table too.
-- -------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS budget_insights.bi_data_export (
    id              uuid                        NOT NULL,
    user_id         uuid                        NOT NULL,
    -- The serialised export document. text, not jsonb: nothing queries inside it, and a
    -- document that must round-trip byte for byte should not be reformatted by the column
    -- type on the way in.
    document        text                        NOT NULL,
    size_bytes      integer                     NOT NULL,
    requested_at    timestamp(6) with time zone NOT NULL DEFAULT now(),
    expires_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_bi_data_export PRIMARY KEY (id)
);

-- The customer's own list, newest first.
CREATE INDEX IF NOT EXISTS ix_bi_data_export_user
    ON budget_insights.bi_data_export (user_id, requested_at DESC);

-- The retention job's single-pass scan.
CREATE INDEX IF NOT EXISTS ix_bi_data_export_expiry
    ON budget_insights.bi_data_export (expires_at);
