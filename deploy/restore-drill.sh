#!/usr/bin/env bash
# Restores a database dump into a scratch database, times it, checks the result, and drops it.
# The drill that shows a backup can really be restored, and how long it takes.
#
#   deploy/restore-drill.sh <dump file> [options]
#
# The dump is one made by deploy/oci/backup.sh (pg_dump custom format). Connection settings are the
# usual PG* variables (PGHOST, PGPORT, PGUSER, PGPASSWORD); the user must be able to create databases.
#
# Options:
#   --roles <roles.sql>   load the roles file first (needed on a server that has never run the app)
#   --compare <database>  also compare row counts with this live database (same server)
#   --keep                keep the scratch database for a look; its name is printed
#   --jar <app.jar>       run the new build's migration check against the restored data
#                         (a dump from the previous release proves the upgrade works on real data)
#
# Prints one summary line, such as:  RESTORE-DRILL ok restore=41s tables=62 organizations=3 schema=26
# Append the line to docs/deploy/restore-drills.md together with the date and the dump's age. Exit 0 only if
# the restore finished without errors and every check passed.
set -euo pipefail

usage() { sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[ $# -ge 1 ] || usage
dump="$1"; shift
roles=""; compare=""; keep=no; jar=""
while [ $# -gt 0 ]; do
  case "$1" in
    --roles)   roles="${2:?--roles needs a file}"; shift 2 ;;
    --compare) compare="${2:?--compare needs a database name}"; shift 2 ;;
    --keep)    keep=yes; shift ;;
    --jar)     jar="${2:?--jar needs a file}"; shift 2 ;;
    *) usage ;;
  esac
done
[ -r "$dump" ] || { echo "Cannot read $dump" >&2; exit 2; }

scratch="hrms_drill_$(date -u +%Y%m%d%H%M%S)"
admin=(psql -X -q -v ON_ERROR_STOP=1 -d postgres)
cleanup() {
  if [ "$keep" = yes ]; then echo "Kept scratch database: $scratch"
  else "${admin[@]}" -c "drop database if exists \"$scratch\" with (force)" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

fail() { echo "RESTORE-DRILL FAILED: $*" >&2; exit 1; }

pg_restore --list "$dump" >/dev/null 2>&1 || fail "the dump cannot be read: $dump"

if [ -n "$roles" ]; then
  # roles that already exist are fine ("already exists" is not an error here)
  psql -X -q -d postgres -f "$roles" >/dev/null 2>&1 || true
fi

"${admin[@]}" -c "create database \"$scratch\"" || fail "cannot create the scratch database"

start=$(date +%s)
pg_restore --dbname="$scratch" --exit-on-error --no-privileges --role="${PGUSER:-postgres}" "$dump" 2>"/tmp/$scratch.err" \
  || fail "pg_restore stopped: $(head -3 "/tmp/$scratch.err" | tr '\n' ' ')"
rm -f "/tmp/$scratch.err"
seconds=$(( $(date +%s) - start ))

q() { psql -X -At -v ON_ERROR_STOP=1 -d "$scratch" -c "$1"; }

tables=$(q "select count(*) from information_schema.tables where table_schema in ('app','auth','platform','public') and table_type='BASE TABLE'")
schema=$(q "select coalesce(max(version::int),0) from public.flyway_schema_history where success and version ~ '^[0-9]+$'") \
  || fail "the restored data has no migration history"
orgs=$(q "select count(*) from platform.tenant" 2>/dev/null || echo "?")
[ "$tables" -gt 10 ] || fail "only $tables tables came back"
[ "$schema" -gt 0 ] || fail "no applied migrations are recorded"

if [ -n "$compare" ]; then
  # every table in the restored copy has the same number of rows as the source
  mismatch=""
  while IFS='|' read -r s t; do
    a=$(psql -X -At -d "$scratch" -c "select count(*) from \"$s\".\"$t\"")
    b=$(psql -X -At -d "$compare" -c "select count(*) from \"$s\".\"$t\"")
    [ "$a" = "$b" ] || mismatch="$mismatch $s.$t(restored=$a,source=$b)"
  done < <(q "select table_schema||'|'||table_name from information_schema.tables where table_schema in ('app','auth','platform','public') and table_type='BASE TABLE' order by 1")
  [ -z "$mismatch" ] || fail "row counts differ:$mismatch (a live database changes between the dump and now; compare quietly)"
fi

if [ -n "$jar" ]; then
  out=$(APP_DB_URL="jdbc:postgresql://${PGHOST:-localhost}:${PGPORT:-5432}/$scratch" \
        APP_DB_OWNER_USER="${PGUSER:-postgres}" APP_DB_OWNER_PASSWORD="${PGPASSWORD:-}" \
        java -jar "$jar" --check-migrations 2>&1) || { echo "$out" >&2; fail "the new build would refuse to start on this data"; }
  echo "$out" | grep -E "Schema version|migration|match" | sed 's/^/  /'
fi

echo "RESTORE-DRILL ok restore=${seconds}s tables=$tables organizations=$orgs schema=$schema"
