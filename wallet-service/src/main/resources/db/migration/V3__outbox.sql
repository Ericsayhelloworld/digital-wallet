-- EVT-1: events are written here in the same transaction as the change they describe
CREATE TABLE outbox (
    seq          BIGSERIAL    PRIMARY KEY,      -- publishing order (EVT-2)
    event_id     UUID         NOT NULL UNIQUE,
    aggregate_id UUID         NOT NULL,         -- the Kafka message key
    type         VARCHAR(50)  NOT NULL,
    envelope     TEXT         NOT NULL,         -- the complete JSON message
    created_at   TIMESTAMPTZ  NOT NULL,
    sent_at      TIMESTAMPTZ
);
CREATE INDEX outbox_unsent ON outbox (seq) WHERE sent_at IS NULL;
