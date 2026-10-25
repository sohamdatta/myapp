#!/bin/sh
# Nightly backup of the demo / UAT database (OCI only; production uses Lightsail's managed backups).
#
# Each night at BACKUP_AT_UTC it writes, into /backups:
#   hrms-<UTC time>.dump        the hrms database (pg_dump custom format, for pg_restore)
#   roles-<UTC time>.sql        the database roles and their passwords (pg_dumpall --roles-only)
# keeps the last BACKUP_KEEP_DAYS days of them, and, when BACKUP_BUCKET is set, copies them
# to OCI Object Storage (rclone remote "oci", set up by the environment in docker-compose.oci.yml).
#
# It connects as the postgres superuser: the app's tables FORCE row-level security, so
# a dump taken as the table owner would stop with an error rather than skip rows.
#
#   backup.sh          wait for the time each day, then back up (the container's default)
#   backup.sh now      back up once, at once, and exit
set -eu
umask 077

AT="${BACKUP_AT_UTC:-21:30}"
KEEP="${BACKUP_KEEP_DAYS:-14}"

backup_once() {
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  dump="/backups/hrms-$stamp.dump"
  roles="/backups/roles-$stamp.sql"

  # Each step checked explicitly: under "||" below, set -e does not stop a function.
  pg_dump --dbname=hrms --format=custom --file="$dump.part" || { rm -f "$dump.part"; return 1; }
  pg_dumpall --roles-only --file="$roles.part" || { rm -f "$dump.part" "$roles.part"; return 1; }
  # Prove the dump can be read before keeping it.
  pg_restore --list "$dump.part" > /dev/null || { rm -f "$dump.part" "$roles.part"; return 1; }
  mv "$dump.part" "$dump" && mv "$roles.part" "$roles" && chmod 600 "$dump" "$roles" || return 1
  echo "$(date -u +%FT%TZ) backup written: $dump ($(du -h "$dump" | cut -f1))"

  find /backups -maxdepth 1 \( -name 'hrms-*.dump' -o -name 'roles-*.sql' \) -mtime +"$KEEP" -print -delete

  if [ -n "${BACKUP_BUCKET:-}" ]; then
    rclone copy --no-traverse "$dump" "oci:$BACKUP_BUCKET/" \
      && rclone copy --no-traverse "$roles" "oci:$BACKUP_BUCKET/" \
      && echo "$(date -u +%FT%TZ) copied to bucket $BACKUP_BUCKET" \
      || echo "$(date -u +%FT%TZ) WARNING: copy to bucket $BACKUP_BUCKET failed; the local copy is kept"
  fi
}

if [ "${1:-}" = "now" ]; then
  backup_once
  exit 0
fi

echo "Backing up every day at $AT UTC, keeping $KEEP days${BACKUP_BUCKET:+, copying to bucket $BACKUP_BUCKET}."
while true; do
  now=$(date -u +%s)
  next=$(date -u -d "$(date -u +%Y-%m-%d) $AT" +%s)
  [ "$next" -le "$now" ] && next=$((next + 86400))
  sleep $((next - now))
  backup_once || echo "$(date -u +%FT%TZ) ERROR: backup failed; trying again tomorrow"
done
