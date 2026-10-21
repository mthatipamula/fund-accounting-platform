CREATE TABLE funds (
    id             VARCHAR(40)  PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    base_currency  CHAR(3)      NOT NULL,
    cost_method    VARCHAR(10)  NOT NULL CHECK (cost_method IN ('FIFO', 'AVERAGE')),
    inception_date DATE         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE share_classes (
    id                     VARCHAR(40)    PRIMARY KEY,
    fund_id                VARCHAR(40)    NOT NULL REFERENCES funds (id),
    name                   VARCHAR(100)   NOT NULL,
    management_fee_rate    NUMERIC(10, 6) NOT NULL CHECK (management_fee_rate >= 0 AND management_fee_rate < 0.2),
    initial_nav_per_share  NUMERIC(20, 6) NOT NULL CHECK (initial_nav_per_share > 0),
    UNIQUE (fund_id, name)
);

CREATE TABLE investors (
    id         VARCHAR(40)  PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    type       VARCHAR(30)  NOT NULL DEFAULT 'INSTITUTION',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Investor subscriptions and redemptions: bitemporal like trades.
CREATE TABLE capital_versions (
    row_id         BIGSERIAL PRIMARY KEY,
    id             VARCHAR(40)     NOT NULL,
    version        INT             NOT NULL,
    fund_id        VARCHAR(40)     NOT NULL REFERENCES funds (id),
    share_class_id VARCHAR(40)     NOT NULL REFERENCES share_classes (id),
    investor_id    VARCHAR(40)     NOT NULL REFERENCES investors (id),
    type           VARCHAR(12)     NOT NULL CHECK (type IN ('SUBSCRIPTION', 'REDEMPTION')),
    dealing_date   DATE            NOT NULL,
    amount         NUMERIC(24, 6),
    shares         NUMERIC(28, 10),
    recorded_at    TIMESTAMPTZ     NOT NULL,
    superseded_at  TIMESTAMPTZ,
    cancelled      BOOLEAN         NOT NULL DEFAULT false,
    entered_by     VARCHAR(100)    NOT NULL,
    UNIQUE (id, version),
    CHECK ((type = 'SUBSCRIPTION' AND amount > 0) OR (type = 'REDEMPTION' AND shares > 0))
);
CREATE UNIQUE INDEX capital_current ON capital_versions (id) WHERE superseded_at IS NULL;

-- Trade versions replicated from trade-service via Kafka. Idempotent: (id, version) is unique.
CREATE TABLE trade_events (
    row_id        BIGSERIAL PRIMARY KEY,
    id            VARCHAR(40)     NOT NULL,
    version       INT             NOT NULL,
    fund_id       VARCHAR(40)     NOT NULL,
    security_id   VARCHAR(40)     NOT NULL,
    side          VARCHAR(4)      NOT NULL,
    quantity      NUMERIC(28, 10) NOT NULL,
    price         NUMERIC(24, 10) NOT NULL,
    commission    NUMERIC(24, 10) NOT NULL,
    trade_date    DATE            NOT NULL,
    settle_date   DATE            NOT NULL,
    recorded_at   TIMESTAMPTZ     NOT NULL,
    superseded_at TIMESTAMPTZ,
    cancelled     BOOLEAN         NOT NULL,
    UNIQUE (id, version)
);
CREATE INDEX trade_events_fund ON trade_events (fund_id, recorded_at);

-- Period close history. The latest row per fund is the current close; reopening adds a row with closed_through NULL.
CREATE TABLE period_closes (
    id             BIGSERIAL PRIMARY KEY,
    fund_id        VARCHAR(40)  NOT NULL REFERENCES funds (id),
    closed_through DATE,
    action         VARCHAR(10)  NOT NULL CHECK (action IN ('CLOSE', 'REOPEN')),
    performed_at   TIMESTAMPTZ  NOT NULL,
    performed_by   VARCHAR(100) NOT NULL,
    nav            NUMERIC(24, 6)
);
CREATE INDEX period_closes_fund ON period_closes (fund_id, id DESC);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX accounting_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
