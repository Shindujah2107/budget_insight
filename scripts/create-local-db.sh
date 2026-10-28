#!/usr/bin/env bash
# One-time setup of the local PostgreSQL database for the backend.
#
# Creates the `moneyreserve` user (password `moneyreserve`, local development only) and the
# `moneyreserve` database it owns. The tables are created by Flyway the first time the backend
# starts. Safe to run again: anything that already exists is left as it is.
#
# Run it as a Postgres user that can create roles (your own, on a Homebrew install). psql asks for
# that user's password if your server requires one:
#   ./scripts/create-local-db.sh
set -euo pipefail

PSQL="${PSQL:-psql}"
DB_NAME="${DB_NAME:-moneyreserve}"
DB_USER="${DB_USER:-moneyreserve}"
DB_PASSWORD="${DB_PASSWORD:-moneyreserve}"

"$PSQL" -v ON_ERROR_STOP=1 -d postgres <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${DB_USER}') THEN
    CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD}';
  END IF;
END
\$\$;
SELECT 'CREATE DATABASE ${DB_NAME} OWNER ${DB_USER}'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${DB_NAME}')\gexec
SQL

echo "Database '${DB_NAME}' and user '${DB_USER}' are ready."
