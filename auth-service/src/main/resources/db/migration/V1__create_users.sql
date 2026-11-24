CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(20)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(50)  NOT NULL,
    roles         VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);
