#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
  CREATE USER auth_user WITH PASSWORD '${AUTH_DB_PASSWORD}';
  CREATE DATABASE auth_db OWNER auth_user;

  CREATE USER wallet_user WITH PASSWORD '${WALLET_DB_PASSWORD}';
  CREATE DATABASE wallet_db OWNER wallet_user;

  CREATE USER notification_user WITH PASSWORD '${NOTIFICATION_DB_PASSWORD}';
  CREATE DATABASE notification_db OWNER notification_user;
EOSQL