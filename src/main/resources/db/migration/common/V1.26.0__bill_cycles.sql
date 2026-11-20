-- =====================================================================================
-- V1.26.0  Bill cycles: each due date of a bill, and how it was settled
-- =====================================================================================
-- Until now "paid this cycle" was worked out from bi_user_bill.last_paid_on / next_due_on, and a
-- bill moving on to its next due date forgot the one before. bi_bill_cycle keeps one row per bill
-- per due date:
--
--   DUE            waiting (asked about from the due date for 10 days)
--   PAID           settled once, by one payment (transaction_id), with the amount and date paid
--   NOT_CONFIRMED  10 days passed with no answer: reminders stop, the customer can still answer
--   SKIPPED        the customer said they did not pay it
--
-- One row per bill and due date, and one payment settles at most one cycle. Marking a bill paid
-- locks the bill row first and checks the cycle before any payment is recorded (BillServiceImpl).
--
-- No backfill: rows are created from now on, the current cycle when it is first needed. Nothing
-- is inferred about past due dates. Personal: exported and erased with the customer's data.
-- Additive and re-runnable.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS budget_insights.bi_bill_cycle (
    id               uuid                        NOT NULL,
    user_id          uuid                        NOT NULL,
    user_bill_id     uuid                        NOT NULL,
    due_on           date                        NOT NULL,
    expected_amount  numeric(19, 2)              NOT NULL,
    status           varchar(16)                 NOT NULL,
    actual_amount    numeric(19, 2),
    paid_on          date,
    transaction_id   uuid,
    -- MARKED (the customer said so), FOUND (a payment the bills radar matched), RECORDED (the
    -- bill's own last payment when the cycle was first created), SKIPPED (did not pay).
    settled_by       varchar(16),
    created_at       timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp(6) with time zone,
    CONSTRAINT pk_bi_bill_cycle PRIMARY KEY (id),
    CONSTRAINT ux_bi_bill_cycle_due UNIQUE (user_bill_id, due_on),
    CONSTRAINT ux_bi_bill_cycle_txn UNIQUE (transaction_id),
    CONSTRAINT fk_bi_bill_cycle_bill FOREIGN KEY (user_bill_id)
        REFERENCES budget_insights.bi_user_bill (id) ON DELETE CASCADE,
    CONSTRAINT ck_bi_bill_cycle_status CHECK (status IN ('DUE', 'PAID', 'NOT_CONFIRMED', 'SKIPPED')),
    CONSTRAINT ck_bi_bill_cycle_amounts CHECK (expected_amount >= 0 AND (actual_amount IS NULL OR actual_amount >= 0)),
    CONSTRAINT ck_bi_bill_cycle_paid CHECK (status <> 'PAID' OR paid_on IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS ix_bi_bill_cycle_user ON budget_insights.bi_bill_cycle (user_id, due_on);
