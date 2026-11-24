-- LED-2: one account per wallet and two system accounts
CREATE TABLE accounts (
    id      UUID        PRIMARY KEY,
    type    VARCHAR(20) NOT NULL CHECK (type IN ('WALLET', 'EXTERNAL_BANK', 'PAYOUT_HOLD')),
    balance BIGINT      NOT NULL DEFAULT 0,
    -- CON-6 / INV3: a wallet balance and the hold are never negative
    CONSTRAINT balance_not_negative CHECK (type = 'EXTERNAL_BANK' OR balance >= 0)
);

INSERT INTO accounts (id, type, balance) VALUES
    ('00000000-0000-0000-0000-000000000001', 'EXTERNAL_BANK', 0),
    ('00000000-0000-0000-0000-000000000002', 'PAYOUT_HOLD',   0);

CREATE TABLE wallets (
    id           UUID        PRIMARY KEY REFERENCES accounts (id),
    user_id      UUID        NOT NULL UNIQUE,   -- CON-6: one wallet per user
    username     VARCHAR(20) NOT NULL UNIQUE,
    display_name VARCHAR(50) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE ledger_entries (
    id                     UUID         PRIMARY KEY,
    seq                    BIGSERIAL    NOT NULL UNIQUE,  -- posting order (INV6) and cursor
    posting_id             UUID         NOT NULL,         -- entries of one business event
    account_id             UUID         NOT NULL REFERENCES accounts (id),
    type                   VARCHAR(30)  NOT NULL,
    amount                 BIGINT       NOT NULL,
    balance_after          BIGINT       NOT NULL,         -- LED-4
    counterparty_wallet_id UUID,
    note                   VARCHAR(140),
    reference_type         VARCHAR(20)  NOT NULL,         -- TRANSFER, DEPOSIT, WITHDRAWAL
    reference_id           UUID         NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ledger_entries_account_seq ON ledger_entries (account_id, seq DESC);

-- LED-1: an entry is never updated or deleted
CREATE FUNCTION ledger_entries_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger entries cannot be updated or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_no_change
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_append_only();

CREATE TABLE transfers (
    id             UUID         PRIMARY KEY,
    from_wallet_id UUID         NOT NULL REFERENCES wallets (id),
    to_wallet_id   UUID         NOT NULL REFERENCES wallets (id),
    amount         BIGINT       NOT NULL CHECK (amount > 0),
    note           VARCHAR(140),
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX transfers_from_created ON transfers (from_wallet_id, created_at);

-- IDM: one record per user, endpoint and key (CON-6)
CREATE TABLE idempotency_records (
    user_id       UUID        NOT NULL,
    endpoint      VARCHAR(40) NOT NULL,
    idem_key      VARCHAR(64) NOT NULL,
    request_hash  VARCHAR(64) NOT NULL,
    status_code   INT,
    response_body TEXT,
    location      VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, endpoint, idem_key)
);
