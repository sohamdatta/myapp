#!/bin/sh
# Runs once, when the PostgreSQL data volume is first created (OCI demo / UAT server only).
# Sets the database up the way AWS Lightsail gives it to production: an admin login that may
# create roles and databases but is not a superuser and cannot bypass row-level security,
# and an hrms database it owns. The app creates its own roles and tables on its first start.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v pw="$APP_DB_OWNER_PASSWORD" <<'SQL'
create role dbmasteruser login password :'pw' createrole createdb;
create database hrms owner dbmasteruser;
SQL
