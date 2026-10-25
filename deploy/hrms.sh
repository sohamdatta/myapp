#!/usr/bin/env bash
# Runs docker compose for one environment, with that environment's own settings file and
# compose files, so production and UAT can never pick up each other's settings.
#
#   deploy/hrms.sh <environment> <docker compose arguments...>
#
#   deploy/hrms.sh production up -d --build      production (AWS Lightsail, managed database)
#   deploy/hrms.sh uat up -d --build             demo / UAT (Oracle Cloud, database on the server)
#   deploy/hrms.sh uat logs -f app
#   deploy/hrms.sh uat exec backup backup.sh now
#
# Settings: deploy/env/<environment>.env, made from deploy/env/<environment>.env.example.
# The filled-in files hold secrets: they are ignored by git and must stay chmod 600.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

[ $# -ge 1 ] || usage
env="$1"
shift

case "$env" in
  production) files=(-f "$here/docker-compose.yml") ;;
  uat)        files=(-f "$here/docker-compose.yml" -f "$here/docker-compose.oci.yml") ;;
  -h|--help)  usage ;;
  *) echo "Unknown environment '$env': use production or uat." >&2; exit 2 ;;
esac

env_file="$here/env/$env.env"
if [ ! -f "$env_file" ]; then
  echo "No settings for $env: $env_file is missing." >&2
  echo "Make it with: cp $here/env/$env.env.example $env_file && chmod 600 $env_file, then fill it in." >&2
  exit 1
fi
if [ -n "$(find "$env_file" -perm /077 2>/dev/null)" ]; then
  echo "Warning: $env_file can be read by other users; run: chmod 600 $env_file" >&2
fi
# A settings file says which environment it is for; refuse one copied to the wrong place.
declared="$(sed -n 's/^HRMS_ENVIRONMENT=//p' "$env_file" | tail -1)"
if [ "$declared" != "$env" ]; then
  echo "$env_file says HRMS_ENVIRONMENT=${declared:-<missing>}, not $env. Is it the right file?" >&2
  exit 1
fi

# HRMS_ENV_FILE: the file the app container reads its settings from (docker-compose.yml).
export HRMS_ENV_FILE="env/$env.env"
exec docker compose --env-file "$env_file" "${files[@]}" "$@"
