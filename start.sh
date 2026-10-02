#!/usr/bin/env bash
# Starts the Spring Boot backend and the React frontend together.
# Press Ctrl+C to stop both.
set -e
cd "$(dirname "$0")"

if [ ! -d frontend/node_modules ]; then
  (cd frontend && npm install)
fi

(cd backend && mvn -q spring-boot:run) &
BACKEND_PID=$!
trap 'kill $BACKEND_PID 2>/dev/null' EXIT

cd frontend && npm run dev
