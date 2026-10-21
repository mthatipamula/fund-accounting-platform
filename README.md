# fund-accounting-platform

Portfolio and fund accounting for investment managers: trade capture, multi-currency valuation, a double-entry
general ledger, NAV and share-class accounting, investor capital activity, period close and reconciliation. Every
report answers **"as of"** a date and **"as known at"** a moment, so you can reproduce exactly what was reported
before a correction.

## Architecture

```
 Browser (React + TypeScript, OIDC code + PKCE)
    │  /api  (bearer token)
    ▼
 gateway (Spring Cloud Gateway) ── validates JWT, routes, retries reads
    │
    ├── reference-data-service ── securities, prices, FX, corporate actions (bitemporal)   PostgreSQL: refdata
    │        ▲ gRPC (MarketDataService)
    ├── accounting-service ──────── funds, share classes, investors, capital, valuation, GL,   PostgreSQL: accounting
    │        ▲  Kafka: trade-events      period close, reconciliation
    │        │  Kafka: period-events ▼
    └── trade-service ───────────── trade capture, versions, idempotency, outbox            PostgreSQL: trades

 Keycloak (OIDC)   Kafka (KRaft)   OpenTelemetry → Grafana (traces, metrics)
```

| Module | What it does |
|---|---|
| `accounting-engine` | Pure Java accounting core with no framework: lots, accruals, corporate actions, ledger, NAV. Fully unit-tested. |
| `reference-data-service` | REST and gRPC. Corrections never overwrite; each value keeps its history. |
| `trade-service` | Trade versions (amend and cancel need a reason and the expected version), `Idempotency-Key`, CSV import, transactional outbox to Kafka. Refuses trades dated in a closed period. |
| `accounting-service` | Consumes trades idempotently, loads market data over gRPC in parallel (virtual threads), runs the engine, and serves reports, capital activity, period close and reconciliation. |
| `gateway` | Single API entry point. |
| `service-common` | Shared resource-server security (roles from Keycloak), RFC 7807 errors, as-of parsing. |
| `web` | SPA: dashboard, positions, P&L, trades, capital and investors, general ledger, reference data, period close, reconciliation, fund setup. |

**Tech:** Java 21, Spring Boot 4.1, Spring gRPC, Spring Kafka, Spring Cloud Gateway, PostgreSQL 17 + Flyway,
Kafka 4.1 (KRaft), Keycloak 26 (OAuth2/OIDC), OpenTelemetry, React 19 + TypeScript + Vite, Docker, Kubernetes (GKE),
Terraform, GitHub Actions.

## Accounting capabilities

| Area | Supported now |
|---|---|
| Books and records | Trade-date accounting with payables and receivables until settlement (default T+2 business days); FIFO or average-cost lot relief |
| Instruments | Equities; fixed-rate bonds (30/360, ACT/365) with purchased and accrued interest, coupons and redemption at par |
| Corporate actions | Cash dividends (receivable on ex-date, cash on pay date), stock splits |
| Multi-currency | Local and base amounts; realized and unrealized P&L split into price and FX parts; daily revaluation of foreign cash, receivables and payables |
| General ledger | Double-entry journal, chart of accounts, trial balance, posting checks (books must balance or the entry is refused) |
| Valuation and NAV | Daily mark-to-market, NAV history, position weights, exceptions for missing prices or oversells instead of silent errors |
| Fund and investor accounting | Multiple share classes with their own management fees (accrued daily, paid monthly); P&L allocated across classes by NAV; subscriptions and redemptions at NAV per share; investor holdings |
| Controls | Bitemporal history for trades, prices, FX, corporate actions and capital; optimistic concurrency; idempotent creates; period close and audited reopen; role-based access (viewer, accountant, admin) |
| Operations | CSV import for trades, prices and FX; reconciliation of positions and cash against a custodian statement |

**Roadmap:** FX spot and forward trades; derivatives (futures, options, swaps); short positions; performance and incentive
fees with high-water marks and equalization; partnership capital allocation; tax-lot selection (HIFO, specific lot);
performance and returns reporting (TWR, IRR); a report writer and scheduled report packs; an AI assistant that explains
NAV movements.

## Run it locally

Requires Docker, plus JDK 21 and Node 24 for development.

```bash
./scripts/init-env.sh             # random local passwords into .env (never printed)
docker compose up -d --build      # about 3 minutes the first time
node scripts/seed.mjs             # demo fund with 3 months of history
```

| URL | |
|---|---|
| http://localhost:5173 | Web app. Sign in as `admin`, `accountant` or `viewer`; passwords are in `.env` |
| http://localhost:3000 | Grafana: traces (Tempo) and metrics across all services |
| http://localhost:8180 | Keycloak admin console (`admin` / `KEYCLOAK_ADMIN_PASSWORD`) |

Development loop: `docker compose up -d postgres kafka keycloak otel-lgtm`, run a service with
`./gradlew :trade-service:bootRun` (use `KAFKA_BOOTSTRAP=localhost:9094` and the DB env vars), and `cd web && npm run dev`.

Tests: `./gradlew test` runs the engine's hand-computed scenarios: FIFO and average cost, settlement, the FX price and FX
split, splits and dividends, bond accrual and maturity, share-class fees and redemptions, bitemporal corrections and
oversell exceptions.

## Deploy to GCP

See [deploy/README.md](deploy/README.md) for Terraform (GKE Autopilot, Cloud SQL with private IP, Artifact Registry,
keyless GitHub OIDC) and the Kubernetes manifests. It lists the costs before you apply anything.
