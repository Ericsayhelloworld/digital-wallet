CREATE TABLE deposits (
    id             UUID         PRIMARY KEY,
    seq            BIGSERIAL    NOT NULL UNIQUE,
    wallet_id      UUID         NOT NULL REFERENCES wallets (id),
    amount         BIGINT       NOT NULL CHECK (amount > 0),
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_reason VARCHAR(100),
    created_at     TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ
);
CREATE INDEX deposits_wallet ON deposits (wallet_id, seq DESC);
CREATE INDEX deposits_pending ON deposits (created_at) WHERE status = 'PENDING';

CREATE TABLE withdrawals (
    id             UUID         PRIMARY KEY,
    seq            BIGSERIAL    NOT NULL UNIQUE,
    wallet_id      UUID         NOT NULL REFERENCES wallets (id),
    amount         BIGINT       NOT NULL CHECK (amount > 0),
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_reason VARCHAR(100),
    created_at     TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ
);
CREATE INDEX withdrawals_wallet ON withdrawals (wallet_id, seq DESC);
CREATE INDEX withdrawals_wallet_created ON withdrawals (wallet_id, created_at);
CREATE INDEX withdrawals_pending ON withdrawals (created_at) WHERE status = 'PENDING';
