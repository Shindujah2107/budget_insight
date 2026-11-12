-- =====================================================================================
-- V1.21.0  Cash wallet
-- =====================================================================================
-- One pooled cash wallet per customer and currency. Its balance is a durable ledger, not a
-- sum over the transaction list: every change is an entry (opening, ATM withdrawal, cash
-- expense or income, a confirmed movement between cash and a bank account, an audited
-- correction, or the reversal of an earlier entry), written under a row lock on the wallet in
-- the same database transaction as the transaction it belongs to.
--
-- Activation is explicit: the customer states the cash they hold and that is the opening
-- entry. Withdrawals and cash expenses from before activation are never replayed.
--
-- Additive only. Existing transactions keep NULL in the two new columns and behave as before.
-- =====================================================================================

ALTER TABLE budget_insights.bi_transaction
    ADD COLUMN IF NOT EXISTS payment_source varchar(16),
    ADD COLUMN IF NOT EXISTS movement       varchar(24);

ALTER TABLE budget_insights.bi_transaction DROP CONSTRAINT IF EXISTS ck_bi_txn_payment_source;
ALTER TABLE budget_insights.bi_transaction
    ADD CONSTRAINT ck_bi_txn_payment_source CHECK (payment_source IS NULL OR payment_source = 'CASH_WALLET');
ALTER TABLE budget_insights.bi_transaction DROP CONSTRAINT IF EXISTS ck_bi_txn_movement;
ALTER TABLE budget_insights.bi_transaction
    ADD CONSTRAINT ck_bi_txn_movement CHECK (movement IS NULL OR movement IN
        ('BANK_TO_CASH', 'CASH_TO_BANK', 'OWN_ACCOUNT', 'BALANCE_CORRECTION'));

COMMENT ON COLUMN budget_insights.bi_transaction.payment_source IS
    'CASH_WALLET: paid from (or into) the customer''s cash wallet. NULL: as before.';
COMMENT ON COLUMN budget_insights.bi_transaction.movement IS
    'A confirmed movement of the customer''s own money (cash/bank, own accounts, balance correction): neither spending nor income.';

CREATE TABLE IF NOT EXISTS budget_insights.bi_cash_wallet (
    id            uuid                        NOT NULL,
    user_id       uuid                        NOT NULL,
    currency      varchar(3)                  NOT NULL,
    activated_at  timestamp(6) with time zone,
    balance       numeric(19,2)               NOT NULL DEFAULT 0,
    revision      bigint                      NOT NULL DEFAULT 0,
    created_at    timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp(6) with time zone,
    CONSTRAINT pk_bi_cash_wallet PRIMARY KEY (id),
    CONSTRAINT ux_bi_cash_wallet_user_currency UNIQUE (user_id, currency),
    CONSTRAINT ck_bi_cash_wallet_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_bi_cash_wallet_revision CHECK (revision >= 0)
);

CREATE TABLE IF NOT EXISTS budget_insights.bi_cash_wallet_entry (
    id                 uuid                        NOT NULL,
    wallet_id          uuid                        NOT NULL,
    user_id            uuid                        NOT NULL,
    kind               varchar(24)                 NOT NULL,
    amount             numeric(19,2)               NOT NULL,
    balance_after      numeric(19,2)               NOT NULL,
    revision           bigint                      NOT NULL,
    transaction_id     uuid,
    reverses_entry_id  uuid,
    reversed           boolean                     NOT NULL DEFAULT false,
    reason             varchar(24),
    previous_balance   numeric(19,2),
    stated_balance     numeric(19,2),
    actor              varchar(64)                 NOT NULL,
    created_at         timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_cash_wallet_entry PRIMARY KEY (id),
    CONSTRAINT fk_bi_cash_entry_wallet FOREIGN KEY (wallet_id)
        REFERENCES budget_insights.bi_cash_wallet (id) ON DELETE CASCADE,
    CONSTRAINT ck_bi_cash_entry_kind CHECK (kind IN ('OPENING', 'WITHDRAWAL', 'EXPENSE', 'INCOME',
        'BANK_TO_CASH', 'CASH_TO_BANK', 'CORRECTION', 'REVERSAL'))
);

-- A transaction has at most one live wallet effect: a duplicate import or a retried save can
-- never credit or debit the wallet twice.
CREATE UNIQUE INDEX IF NOT EXISTS ux_bi_cash_entry_live_txn
    ON budget_insights.bi_cash_wallet_entry (transaction_id)
    WHERE transaction_id IS NOT NULL AND reversed = false AND reverses_entry_id IS NULL;
CREATE INDEX IF NOT EXISTS ix_bi_cash_entry_wallet
    ON budget_insights.bi_cash_wallet_entry (wallet_id, created_at DESC);

-- Reconciliations by client reference: a retry returns the recorded outcome instead of
-- applying the review again.
CREATE TABLE IF NOT EXISTS budget_insights.bi_cash_wallet_operation (
    id              uuid                        NOT NULL,
    user_id         uuid                        NOT NULL,
    client_ref      varchar(96)                 NOT NULL,
    request_hash    varchar(64)                 NOT NULL,
    wallet_id       uuid                        NOT NULL,
    revision_after  bigint                      NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_bi_cash_wallet_operation PRIMARY KEY (id),
    CONSTRAINT ux_bi_cash_op_user_ref UNIQUE (user_id, client_ref),
    CONSTRAINT fk_bi_cash_op_wallet FOREIGN KEY (wallet_id)
        REFERENCES budget_insights.bi_cash_wallet (id) ON DELETE CASCADE
);

-- One balance check per credited withdrawal, due seven days after it; resolved by a review.
CREATE TABLE IF NOT EXISTS budget_insights.bi_cash_wallet_review (
    id                 uuid                        NOT NULL,
    wallet_id          uuid                        NOT NULL,
    user_id            uuid                        NOT NULL,
    entry_id           uuid                        NOT NULL,
    due_at             timestamp(6) with time zone NOT NULL,
    resolved_at        timestamp(6) with time zone,
    resolved_revision  bigint,
    CONSTRAINT pk_bi_cash_wallet_review PRIMARY KEY (id),
    CONSTRAINT ux_bi_cash_review_entry UNIQUE (entry_id),
    CONSTRAINT fk_bi_cash_review_wallet FOREIGN KEY (wallet_id)
        REFERENCES budget_insights.bi_cash_wallet (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_bi_cash_review_open
    ON budget_insights.bi_cash_wallet_review (wallet_id, due_at) WHERE resolved_at IS NULL;
