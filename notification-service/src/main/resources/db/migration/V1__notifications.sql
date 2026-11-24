CREATE TABLE notifications (
    id           UUID         PRIMARY KEY,
    seq          BIGSERIAL    NOT NULL UNIQUE,
    event_id     UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    type         VARCHAR(50)  NOT NULL,
    text         VARCHAR(300) NOT NULL,
    read         BOOLEAN      NOT NULL DEFAULT FALSE,
    reference_id UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    UNIQUE (event_id, user_id)               -- NTF-2: one notification per event and recipient
);
CREATE INDEX notifications_user ON notifications (user_id, seq DESC);

-- EVT-4: IDs of the events that were processed, so that duplicates are ignored
CREATE TABLE processed_events (
    event_id     UUID         PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL
);
