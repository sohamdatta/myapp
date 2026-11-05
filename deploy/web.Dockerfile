# The web server: Caddy serving the built frontend and passing /api to the backend.
# It gets and renews the HTTPS certificate for DOMAIN by itself.
# Build from the repository root: docker build -f deploy/web.Dockerfile .
FROM node:26-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Which build this is, shown in the app (set by deploy/hrms.sh).
ARG APP_VERSION=dev
ARG APP_COMMIT=dev
ARG APP_BUILT=
ENV VITE_APP_VERSION=$APP_VERSION VITE_APP_COMMIT=$APP_COMMIT VITE_APP_BUILT=$APP_BUILT
RUN npm run build

FROM caddy:2
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
