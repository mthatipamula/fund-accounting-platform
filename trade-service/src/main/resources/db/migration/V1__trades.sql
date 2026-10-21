-- Trades are bitemporal: an amendment or cancellation is a new version; the previous version gets superseded_at.
CREATE TABLE trade_versions (
    row_id        BIGSERIAL PRIMARY KEY,
    id            VARCHAR(40)     NOT NULL,
    version       INT             NOT NULL,
    fund_id       VARCHAR(40)     NOT NULL,
    security_id   VARCHAR(40)     NOT NULL,
    side          VARCHAR(4)      NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity      NUMERIC(28, 10) NOT NULL CHECK (quantity > 0),
    price         NUMERIC(24, 10) NOT NULL CHECK (price > 0),
    commission    NUMERIC(24, 10) NOT NULL DEFAULT 0 CHECK (commission >= 0),
    trade_date    DATE            NOT NULL,
    settle_date   DATE            NOT NULL,
    recorded_at   TIMESTAMPTZ     NOT NULL,
    superseded_at TIMESTAMPTZ,
    cancelled     BOOLEAN         NOT NULL DEFAULT false,
    entered_by    VARCHAR(100)    NOT NULL,
    reason        VARCHAR(500),
    UNIQUE (id, version),
    CHECK (settle_date >= trade_date)
);
CREATE UNIQUE INDEX trade_current ON trade_versions (id) WHERE superseded_at IS NULL;
CREATE INDEX trade_fund_asof ON trade_versions (fund_id, recorded_at);

-- A retried "create trade" with the same Idempotency-Key returns the first result instead of booking twice.
CREATE TABLE idempotency_keys (
    key        VARCHAR(100) PRIMARY KEY,
    trade_id   VARCHAR(40)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Transactional outbox: events are written in the same transaction as the trade and published to Kafka afterwards.
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- Closed periods, replicated from the accounting service's period events. Trades dated inside one are refused.
CREATE TABLE closed_periods (
    fund_id        VARCHAR(40) PRIMARY KEY,
    closed_through DATE,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
