#!/usr/bin/env bash
# Sends one day of logs to the log bucket, so they are kept outside the server for 180 days.
# CERT-In (directions of 28 April 2022) asks for ICT system logs to be kept for 180 days, inside India.
#
#   deploy/logs/ship-logs.sh [YYYY-MM-DD]      default: yesterday (UTC)
#
# Settings (environment):
#   LOG_DEST       rclone destination, such as hrmslogs:hrms-logs-mumbai (required). The bucket must be in an
#                  Indian region; the 180-day rule is a lifecycle rule on the bucket, not done here.
#   HRMS_ENVIRONMENT   production or uat; the first part of every object name (default: unknown)
#   CONTAINERS     container names to collect (default: all running containers)
#   HOST_LOGS      yes/no: also collect the server's sign-in log (SSH) (default: yes)
#
# Objects: <environment>/<date>/<container>.log.gz and <environment>/<date>/host-auth.log.gz.
# Run daily from cron or a systemd timer (docs/security/cert-in.md). Exits non-zero if anything was not stored,
# so the timer shows a failure. Safe to run again for the same day: objects are replaced.
set -euo pipefail

: "${LOG_DEST:?LOG_DEST is not set (an rclone destination in an Indian-region bucket)}"
env_name="${HRMS_ENVIRONMENT:-unknown}"
day="${1:-$(date -u -d yesterday +%F)}"
[[ "$day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "Date must be YYYY-MM-DD, got '$day'." >&2; exit 2; }
next="$(date -u -d "$day + 1 day" +%F)"
since="${day}T00:00:00Z"
until="${next}T00:00:00Z"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failed=0
stored=0

store() {   # store <local file> <object name>
  local file="$1" name="$2" size
  size="$(wc -c <"$file")"
  if rclone copyto "$file" "$LOG_DEST/$env_name/$day/$name" --s3-no-check-bucket; then
    echo "stored $env_name/$day/$name ($size bytes)"
    stored=$((stored + 1))
  else
    echo "FAILED to store $env_name/$day/$name" >&2
    failed=$((failed + 1))
  fi
}

if [ -n "${CONTAINERS:-}" ]; then
  names="$CONTAINERS"
else
  names="$(docker ps --format '{{.Names}}')"
fi
[ -n "$names" ] || { echo "No containers to collect from." >&2; failed=$((failed + 1)); }

for c in $names; do
  out="$work/$c.log.gz"
  # docker logs writes the container's stderr to stderr; both belong in the record
  if { docker logs --timestamps --since "$since" --until "$until" "$c" 2>&1 | gzip -9 >"$out"; } && [ "${PIPESTATUS[0]}" -eq 0 ]; then
    store "$out" "$c.log.gz"
  else
    echo "FAILED to read the log of $c" >&2
    failed=$((failed + 1))
  fi
done

if [ "${HOST_LOGS:-yes}" = yes ]; then
  out="$work/host-auth.log.gz"
  if command -v journalctl >/dev/null 2>&1 \
     && journalctl --no-pager -o short-iso -u ssh -u sshd -u fail2ban --since "$since" --until "$until" 2>/dev/null | gzip -9 >"$out" \
     && [ -s "$out" ]; then
    store "$out" "host-auth.log.gz"
  elif [ -r /var/log/auth.log ]; then
    # older layout: the file holds many days, keep the lines starting with this date
    grep -E "^($day|$(date -u -d "$day" '+%b %e'))" /var/log/auth.log | gzip -9 >"$out" || true
    store "$out" "host-auth.log.gz"
  else
    echo "FAILED: no server sign-in log found (journalctl or /var/log/auth.log)" >&2
    failed=$((failed + 1))
  fi
fi

echo "done: $stored stored, $failed failed"
[ "$failed" -eq 0 ]
