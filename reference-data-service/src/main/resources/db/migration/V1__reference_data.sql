-- Reference data. Prices, FX rates and corporate actions are bitemporal: a correction supersedes the old row
-- (superseded_at) instead of overwriting it, so reports can be reproduced as known at any earlier moment.
CREATE TABLE securities (
    id               VARCHAR(40)  PRIMARY KEY,
    symbol           VARCHAR(40)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    asset_class      VARCHAR(10)  NOT NULL CHECK (asset_class IN ('EQUITY', 'BOND')),
    currency         CHAR(3)      NOT NULL,
    coupon_rate      NUMERIC(12, 8),
    coupons_per_year INT CHECK (coupons_per_year IN (1, 2, 4, 12)),
    issue_date       DATE,
    maturity_date    DATE,
    day_count        VARCHAR(12) CHECK (day_count IN ('THIRTY_360', 'ACT_365')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (asset_class = 'EQUITY' OR (coupon_rate IS NOT NULL AND coupons_per_year IS NOT NULL
                                      AND issue_date IS NOT NULL AND maturity_date > issue_date AND day_count IS NOT NULL))
);

CREATE TABLE prices (
    id            BIGSERIAL PRIMARY KEY,
    security_id   VARCHAR(40)    NOT NULL REFERENCES securities (id),
    price_date    DATE           NOT NULL,
    price         NUMERIC(24, 10) NOT NULL CHECK (price > 0),
    source        VARCHAR(40)    NOT NULL DEFAULT 'MANUAL',
    recorded_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    superseded_at TIMESTAMPTZ
);
-- One current price per security and date; history kept in superseded rows.
CREATE UNIQUE INDEX prices_current ON prices (security_id, price_date) WHERE superseded_at IS NULL;
CREATE INDEX prices_asof ON prices (security_id, price_date, recorded_at);

CREATE TABLE fx_rates (
    id            BIGSERIAL PRIMARY KEY,
    currency      CHAR(3)         NOT NULL,
    base_currency CHAR(3)         NOT NULL,
    rate_date     DATE            NOT NULL,
    rate          NUMERIC(24, 12) NOT NULL CHECK (rate > 0),   -- units of base per one unit of currency
    recorded_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    superseded_at TIMESTAMPTZ,
    CHECK (currency <> base_currency)
);
CREATE UNIQUE INDEX fx_current ON fx_rates (currency, base_currency, rate_date) WHERE superseded_at IS NULL;
CREATE INDEX fx_asof ON fx_rates (currency, base_currency, rate_date, recorded_at);

CREATE TABLE corporate_actions (
    row_id           BIGSERIAL PRIMARY KEY,
    id               VARCHAR(40)  NOT NULL,          -- business id shared by versions
    version          INT          NOT NULL,
    security_id      VARCHAR(40)  NOT NULL REFERENCES securities (id),
    type             VARCHAR(20)  NOT NULL CHECK (type IN ('CASH_DIVIDEND', 'SPLIT')),
    ex_date          DATE         NOT NULL,
    pay_date         DATE         NOT NULL,
    amount_per_share NUMERIC(24, 10),
    ratio            NUMERIC(24, 10),
    recorded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    superseded_at    TIMESTAMPTZ,
    cancelled        BOOLEAN      NOT NULL DEFAULT false,
    UNIQUE (id, version),
    CHECK ((type = 'CASH_DIVIDEND' AND amount_per_share > 0) OR (type = 'SPLIT' AND ratio > 0)),
    CHECK (pay_date >= ex_date)
);
CREATE INDEX corporate_actions_security ON corporate_actions (security_id, ex_date);
