#!/usr/bin/env bash
# Creates .env with random local passwords (only if it doesn't exist). Values are never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then echo ".env already exists; leaving it alone."; exit 0; fi
gen() { openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24; }
umask 077
cat > .env <<ENV
POSTGRES_PASSWORD=$(gen)
DB_PASSWORD_REFDATA=$(gen)
DB_PASSWORD_TRADES=$(gen)
DB_PASSWORD_ACCOUNTING=$(gen)
KEYCLOAK_ADMIN_PASSWORD=$(gen)
SEEDER_CLIENT_SECRET=$(gen)
DEMO_ADMIN_PASSWORD=$(gen)
DEMO_ACCOUNTANT_PASSWORD=$(gen)
DEMO_VIEWER_PASSWORD=$(gen)
ENV
echo "Wrote .env (passwords for the demo users admin / accountant / viewer are in it)."
