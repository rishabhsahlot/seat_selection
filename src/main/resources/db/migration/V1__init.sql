CREATE TYPE seat_status AS ENUM ('AVAILABLE', 'HELD', 'CONFIRMED');
CREATE TYPE reservation_mode AS ENUM ('HOLD', 'CONFIRM');
CREATE TYPE reservation_status AS ENUM ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED');

CREATE TABLE shows (
    id             UUID PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL CHECK (per_user_limit > 0),
    total_seats    INT         NOT NULL CHECK (total_seats > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per seat, one status per row: available + held + confirmed == total_seats by construction.
CREATE TABLE seats (
    show_id        UUID NOT NULL REFERENCES shows (id),
    seat_name      VARCHAR(16) NOT NULL,
    status         seat_status NOT NULL DEFAULT 'AVAILABLE',
    user_id        VARCHAR(64),
    reservation_id UUID,
    PRIMARY KEY (show_id, seat_name),
    CHECK ((status = 'AVAILABLE') = (reservation_id IS NULL))
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    show_id         UUID        NOT NULL REFERENCES shows (id),
    user_id         VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    -- A key replay must match (show_id, seats, mode) of the original, else it is a different request.
    mode            reservation_mode   NOT NULL,
    seats           VARCHAR(16)[] NOT NULL, -- stored sorted, so replays compare by plain equality
    amount_paise    BIGINT      NOT NULL,
    status          reservation_status NOT NULL,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, idempotency_key)
);

-- Index to quickly find held reservations that have expired and need to be cleaned up.
CREATE INDEX reservations_held_expiry ON reservations (expires_at) WHERE status = 'HELD';

-- Per-(show, user) count of seats currently held or confirmed; guarded upsert enforces the limit, since user can book through multiple reservations
CREATE TABLE user_show_holds (
    show_id    UUID NOT NULL REFERENCES shows (id),
    user_id    VARCHAR(64) NOT NULL,
    seat_count INT  NOT NULL CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);
