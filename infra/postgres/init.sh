#!/bin/bash
# One database and one owner per service: no service can read another's tables.
set -euo pipefail
for svc in refdata trades accounting; do
  var="DB_PASSWORD_${svc^^}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE USER $svc WITH PASSWORD '${!var}';
CREATE DATABASE $svc OWNER $svc;
REVOKE ALL ON DATABASE $svc FROM PUBLIC;
SQL
done
