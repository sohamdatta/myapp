# The web server: Caddy serving the built frontend and passing /api to the backend.
# It gets and renews the HTTPS certificate for DOMAIN by itself.
# Build from the repository root: docker build -f deploy/web.Dockerfile .
FROM node:26-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM caddy:2
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
