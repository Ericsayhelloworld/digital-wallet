-- ADM-3: a stable order for the paginated list of all wallets (newest first)
ALTER TABLE wallets ADD COLUMN seq BIGSERIAL;
CREATE UNIQUE INDEX wallets_seq ON wallets (seq);
