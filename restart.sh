#!/usr/bin/env bash
# Stops the app if it is running, pulls the latest code, and starts it again.
# Usage: ./restart.sh
set -e
cd "$(dirname "$0")"

port_in_use() {
  (echo > "/dev/tcp/localhost/$1") 2>/dev/null
}

echo "==> Stopping the app (if it is running)"
# The frontend dev server, the Maven launcher, and the backend it started.
PATTERNS=("node_modules/.bin/vite" "node_modules/vite/" "spring-boot:run" "LoginAppApplication")
for pattern in "${PATTERNS[@]}"; do
  pkill -f "$pattern" 2>/dev/null || true
done

# Wait for both ports to be released; force anything that is still holding on.
for attempt in $(seq 1 20); do
  if ! port_in_use 5173 && ! port_in_use 8080; then
    break
  fi
  if [ "$attempt" -eq 10 ]; then
    for pattern in "${PATTERNS[@]}"; do
      pkill -9 -f "$pattern" 2>/dev/null || true
    done
  fi
  sleep 1
done
if port_in_use 5173 || port_in_use 8080; then
  echo "Port 5173 or 8080 is still in use by something this script did not start." >&2
  echo "Find it with:  ss -ltnp | grep -E ':(5173|8080)'" >&2
  exit 1
fi

echo "==> Pulling the latest code"
if ! git pull --ff-only; then
  echo "The pull did not succeed, usually because of local changes. Nothing was started." >&2
  echo "See what changed with:  git status" >&2
  exit 1
fi

# Install frontend packages when they are missing or the lock file is newer.
if [ ! -d frontend/node_modules ] || [ frontend/package-lock.json -nt frontend/node_modules/.package-lock.json ]; then
  echo "==> Installing frontend packages"
  (cd frontend && npm install --no-audit --no-fund)
fi

echo "==> Starting (press Ctrl+C to stop)"
exec ./start.sh
