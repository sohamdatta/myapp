# The nightly database backup for the OCI demo / UAT server (see docker-compose.oci.yml).
# pg_dump 16 to match the server, plus rclone to copy the dumps to OCI Object Storage.
# Build from the repository root: docker build -f deploy/oci/backup.Dockerfile .
FROM postgres:16-alpine
RUN apk add --no-cache rclone
COPY --chmod=755 deploy/oci/backup.sh /usr/local/bin/backup.sh
# Runs as root so it can write to the host folder mounted at /backups; files are made 600.
ENTRYPOINT ["/usr/local/bin/backup.sh"]
