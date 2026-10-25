#!/usr/bin/env bash
# First-time setup of production on the AWS Lightsail server (docs/deploy/aws-lightsail.md, step 4).
# Run on the server, in the repository, after Docker is installed:
#
#   cd ~/hrms && deploy/aws/setup-production.sh
#
# It asks for the few values only you know (the database password, the first Super Admin),
# makes the rest (the app's database password, the data encryption key), writes
# deploy/env/production.env (chmod 600), checks the database and the address answer, starts
# the app and checks it over HTTPS. Secrets are typed into the server only; nothing is sent
# anywhere else. Safe to run again: it keeps an existing settings file unless told otherwise.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
env_file=deploy/env/production.env
template=deploy/env/production.env.example

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mStopped: %s\033[0m\n' "$*" >&2; exit 1; }

ask() {  # ask <prompt> <default>: prints the answer
  local answer
  read -r -p "$1${2:+ [$2]}: " answer
  printf '%s' "${answer:-$2}"
}

ask_secret() {  # ask_secret <prompt>: prints the answer, typed twice, never shown
  local a b
  while true; do
    read -r -s -p "$1: " a; echo >&2
    read -r -s -p "Again, to check: " b; echo >&2
    if [ -z "$a" ]; then echo "It cannot be empty." >&2; continue; fi
    if [ "$a" != "$b" ]; then echo "The two did not match; try again." >&2; continue; fi
    if [[ "$a" == *"'"* ]]; then echo "It cannot contain a single quote ('); choose another." >&2; continue; fi
    printf '%s' "$a"; return
  done
}

set_value() {  # set_value <KEY> <value>: written single-quoted, so $ and # are taken literally
  python3 - "$env_file" "$1" "$2" <<'PY'
import sys
path, key, value = sys.argv[1:]
lines = open(path).read().splitlines()
new = f"{key}='{value}'"
for i, line in enumerate(lines):
    if line.startswith(key + "="):
        lines[i] = new
        break
else:
    lines.append(new)
open(path, "w").write("\n".join(lines) + "\n")
PY
}

value_of() { sed -n "s/^$1=//p" "$env_file" | tail -1 | sed "s/^'\(.*\)'$/\1/"; }

# ---------------------------------------------------------------------------------------------
say "1. Checks"
command -v docker >/dev/null || fail "Docker is not installed: run the first block of step 4 in docs/deploy/aws-lightsail.md."
docker info >/dev/null 2>&1 || fail "Docker cannot be used yet: close this terminal, open Connect using SSH again, and rerun."
command -v python3 >/dev/null || fail "python3 is missing: sudo apt-get install -y python3"
public_ip="$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]' || true)"
echo "This server's public address: ${public_ip:-unknown}"

# ---------------------------------------------------------------------------------------------
say "2. Settings (deploy/env/production.env)"
if [ -f "$env_file" ]; then
  keep="$(ask "A settings file already exists. Keep it? (yes/no)" yes)"
  if [ "$keep" != "yes" ]; then
    mv "$env_file" "$env_file.old-$(date +%Y%m%d-%H%M%S)"
    echo "The old one was kept beside it, renamed."
  fi
fi

made_key=""
if [ ! -f "$env_file" ]; then
  install -m 600 "$template" "$env_file"

  default_domain="${public_ip:+${public_ip//./-}.sslip.io}"
  echo
  echo "The address people open. Until the product's own domain is bought, use the free name"
  echo "for this server's address (docs/roadmap/next-releases.md, 'Domain and HTTPS')."
  domain="$(ask "Address, without https://" "$default_domain")"
  [ -n "$domain" ] || fail "An address is needed."
  [[ "$domain" != http* ]] || fail "Leave out http:// and https://; HTTPS is set up by itself."

  echo
  echo "The database: Lightsail > Databases > hrms-prod-db > Connect tab."
  endpoint="$(ask "Database endpoint")"
  [[ "$endpoint" == *.rds.amazonaws.com ]] || fail "That does not look like a Lightsail database endpoint (…rds.amazonaws.com)."
  dbname="$(ask "Master database name" dbhrms)"
  dbuser="$(ask "Master user name" dbmasteruser)"
  dbpass="$(ask_secret "Master user password (it is not shown as you type)")"

  echo
  echo "The first Super Admin, created on the very first start only. Use your own email."
  admin_email="$(ask "Super Admin email")"
  [[ "$admin_email" == *@*.* ]] || fail "That does not look like an email address."
  admin_pass="$(ask_secret "Super Admin password (at least 12 characters, not used anywhere else)")"
  [ "${#admin_pass}" -ge 12 ] || fail "The Super Admin password needs at least 12 characters."

  made_key="$(openssl rand -base64 32)"
  set_value DOMAIN "$domain"
  set_value APP_DB_URL "jdbc:postgresql://$endpoint:5432/$dbname?sslmode=require"
  set_value APP_DB_OWNER_USER "$dbuser"
  set_value APP_DB_OWNER_PASSWORD "$dbpass"
  set_value APP_DB_ROLE_PASSWORD "$(openssl rand -hex 24)"
  set_value APP_SUPERADMIN_EMAIL "$admin_email"
  set_value APP_SUPERADMIN_PASSWORD "$admin_pass"
  set_value APP_DATA_KEY "$made_key"
  chmod 600 "$env_file"
  echo "Written: $env_file (readable by you only)."
fi

# ---------------------------------------------------------------------------------------------
say "3. The database answers"
url="$(value_of APP_DB_URL)"
hostport="${url#jdbc:postgresql://}"; hostport="${hostport%%/*}"
host="${hostport%%:*}"
dbname="${url##*/}"; dbname="${dbname%%\?*}"
if ! docker run --rm -e PGPASSWORD="$(value_of APP_DB_OWNER_PASSWORD)" -e PGSSLMODE=require \
     postgres:16 psql -h "$host" -U "$(value_of APP_DB_OWNER_USER)" -d "$dbname" -Atc 'select version()' ; then
  fail "Could not sign in to the database. Check the endpoint, the master database name and the password in $env_file (nano $env_file), then rerun."
fi

# ---------------------------------------------------------------------------------------------
say "4. The address points at this server"
domain="$(value_of DOMAIN)"
resolved="$(getent ahostsv4 "$domain" | awk 'NR==1 {print $1}')"
echo "$domain -> ${resolved:-nothing}"
if [ -n "$public_ip" ] && [ "$resolved" != "$public_ip" ]; then
  fail "$domain does not point at $public_ip yet. Fix its DNS A record (or wait for it to spread), then rerun."
fi

# ---------------------------------------------------------------------------------------------
say "5. Build and start (the first build takes several minutes)"
deploy/hrms.sh production up -d --build --wait --wait-timeout 900 \
  || { deploy/hrms.sh production logs --no-color --tail 80 app web; fail "The app did not become healthy; its log is above."; }

say "6. Check it over HTTPS"
code=""
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "https://$domain/api/auth/me" || true)"
  [ "$code" = 401 ] && break
  sleep 5
done
[ "$code" = 401 ] || { deploy/hrms.sh production logs --no-color --tail 60 web; fail "https://$domain did not answer as expected (last status: ${code:-none}); Caddy's log is above."; }
echo "https://$domain answers."

# ---------------------------------------------------------------------------------------------
say "Production is running: https://$domain"
echo "Sign in as the Super Admin, then create the first Organization from the Platform console."
if [ -n "$made_key" ]; then
  cat <<EOF

================================================================================================
 SAVE THIS NOW. It encrypts PAN, bank details and documents. Lose it and they cannot be read.
 Put it in your password manager AND somewhere offline, apart from the database backups.
 It is shown only this once (it also stays in $env_file on this server).

   APP_DATA_KEY: $made_key
================================================================================================
EOF
  read -r -p "Type 'saved' once it is stored safely: " ok
  while [ "$ok" != saved ]; do read -r -p "Type 'saved' once it is stored safely: " ok; done
  clear
  echo "Thank you. The key was cleared from the screen."
fi
