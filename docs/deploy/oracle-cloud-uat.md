# Demo and UAT on Oracle Cloud (Mumbai or Hyderabad)

**Production stays on AWS Lightsail** ([guide](aws-lightsail.md)). This guide sets up a
second, separate copy of the app on Oracle Cloud Infrastructure (OCI) for demos and user
acceptance testing (UAT), on OCI's Always Free tier, so it costs **$0 a month** as long as
it stays within the free limits.

One free ARM server (4 cores, 24 GB) runs everything: the app, Caddy, and its own
PostgreSQL 16, with a nightly backup copied to OCI Object Storage.

```
Browser ──HTTPS──► OCI server (Ubuntu, ARM, Docker)
                     ├─ Caddy: HTTPS certificate, the frontend, /api passed on
                     ├─ the app (Java 21, port 8080, not exposed)
                     ├─ PostgreSQL 16 (not exposed)
                     └─ backup: pg_dump every night ──► /srv/hrms-backups ──► Object Storage bucket
```

It uses the same images as production, plus [`deploy/docker-compose.oci.yml`](../../deploy/docker-compose.oci.yml),
which adds the database and the backup, and the files in [`deploy/oci/`](../../deploy/oci).
CI builds and starts this setup on every push, takes a backup and checks it.

## How UAT differs from production

| | Production (AWS Lightsail) | Demo / UAT (OCI) |
| --- | --- | --- |
| Cost | About $54 a month ($84 with a standby database) | $0 within Always Free |
| Database | Managed, encrypted at rest, point-in-time restore | PostgreSQL in Docker on the server; nightly dump |
| If the server is lost | Rebuild the server; the data is in the managed database | Restore from last night's dump: up to a day lost |
| Data | Real employee data | **Demo or masked data only** |

Rules that keep UAT from becoming a risk:

- **No real employee data.** No real PAN, bank details, Aadhaar or documents. Use invented
  data or data masked before import. If real data must be tested, do it on production.
- **Its own secrets.** Never copy production's `APP_DATA_KEY`, database passwords or Super
  Admin password to this server. A leak from UAT should not unlock production.
- **Its own address**, such as `uat.hr.example.com`, so nobody mistakes it for production.

## 1. The Oracle Cloud account

1. Sign up at oracle.com/cloud/free. **Choose Mumbai or Hyderabad as the home region.** Always
   Free servers can only be made in the home region, and it cannot be changed later.
2. Once signed in, **upgrade the account to Pay As You Go** (Billing → Upgrade). You keep
   everything free and pay only for what goes past the free limits, but:
   - Oracle can reclaim Always Free servers that sit idle in a free-only account; a UAT
     server is idle much of the time.
   - Free ARM capacity is often short ("Out of capacity for shape VM.Standard.A1.Flex");
     paid accounts get it far more easily.
3. **Billing → Budgets**: create a budget of $1 a month with an alert to your email, so any
   charge is noticed the day it starts.

## 2. The server

1. **Compute → Instances → Create instance**.
2. Image: **Canonical Ubuntu 24.04** (the aarch64 one). Shape: **Ampere → VM.Standard.A1.Flex**,
   **4 OCPUs, 24 GB memory** (all of the free ARM allowance).
3. Networking: let it create a new virtual cloud network and public subnet, with a public IPv4 address.
4. SSH keys: upload your public key, or download the generated pair and keep it.
5. Boot volume: **100 GB** (the free allowance is 200 GB of block storage in all).
6. Create it. If it says out of capacity, try another availability domain, or try again later.
7. **Make the address permanent**: on the instance, **Attached VNICs → the VNIC → IPv4 addresses →
   edit → Reserved public IP**. Otherwise the address can change and the DNS record goes stale.
8. **Open the ports**: the subnet's **security list → Add ingress rules**, source `0.0.0.0/0`, TCP
   ports **80** and **443**. Restrict the existing SSH (22) rule to your own IP address.
   Leave the device port (8081) closed unless you test a biometric device.

## 3. The domain

At your DNS provider, add an **A record** for the UAT address (for example
`uat.hr.example.com`) pointing at the reserved IP. Wait until `nslookup uat.hr.example.com`
returns it.

## 4. Install and start

```bash
ssh ubuntu@<reserved IP>

# Oracle's Ubuntu image blocks every port but 22 in its own firewall, on top of the
# security list. Open 80 and 443 there too, and keep the rule after a restart.
sudo iptables -I INPUT 6 -p tcp -m state --state NEW --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -p tcp -m state --state NEW --dport 443 -j ACCEPT
sudo netfilter-persistent save

# Docker, and the right to use it without sudo (log out and back in after this)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit
```

```bash
git clone https://github.com/sohamdatta/myapp.git hrms
cd hrms
sudo install -d -m 700 /srv/hrms-backups
cp deploy/oci/env.example deploy/.env
chmod 600 deploy/.env
nano deploy/.env
```

Fill in `deploy/.env` (each setting is explained in the file):

| Setting | Value |
| --- | --- |
| `DOMAIN` | The UAT address, without `https://` |
| `APP_DB_URL` | Leave as `jdbc:postgresql://db:5432/hrms` |
| `APP_DB_OWNER_PASSWORD`, `APP_DB_ROLE_PASSWORD`, `DB_SUPERUSER_PASSWORD` | Each `openssl rand -base64 24`, all different |
| `APP_SUPERADMIN_EMAIL` / `APP_SUPERADMIN_PASSWORD` | Your own, not production's |
| `APP_DATA_KEY` | `openssl rand -base64 32`, a new one, not production's |
| `BACKUP_*` | See step 6; the copy to Object Storage can be added later |

Save `APP_DATA_KEY` in a password manager, apart from the backups.

Start everything:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.oci.yml up -d --build
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.oci.yml logs -f app
```

On its very first start the database creates the `dbmasteruser` login and the `hrms`
database, set up as on AWS (may create roles, not a superuser). The app then creates its
tables, Caddy fetches the certificate, and `https://<UAT address>` opens. The first build
takes several minutes.

To save typing, put this in `~/.bashrc`:

```bash
alias dc='docker compose -f ~/hrms/deploy/docker-compose.yml -f ~/hrms/deploy/docker-compose.oci.yml'
```

## 5. Updating

Deploy each change to UAT first; once it has been checked there, deploy the same commit
to production.

```bash
cd ~/hrms
git pull                  # or: git checkout <commit to test>
dc exec backup backup.sh now
dc up -d --build
```

The backup before the update gives a way back if a migration goes wrong.

## 6. Backups

The `backup` service writes a dump of the database and of its roles to `/srv/hrms-backups`
every night at 03:00 India time, checks the dump can be read, and keeps 14 days.

That copy is on the same server. To keep one off it, in Object Storage (20 GB is free):

1. **Storage → Buckets → Create bucket**, named for example `hrms-uat-backups`. Leave it private.
2. On the bucket, **Lifecycle policy rules → Create rule**: delete objects older than 30 days.
3. Your user menu → **My profile → Customer secret keys → Generate secret key**. Copy the
   secret (shown once) and the access key.
4. Find your **namespace** under **Tenancy details**.
5. Fill in `deploy/.env`:

   ```
   BACKUP_BUCKET=hrms-uat-backups
   BACKUP_S3_ENDPOINT=https://<namespace>.compat.objectstorage.ap-mumbai-1.oraclecloud.com
   BACKUP_S3_REGION=ap-mumbai-1          # ap-hyderabad-1 for Hyderabad
   BACKUP_S3_ACCESS_KEY=...
   BACKUP_S3_SECRET_KEY=...
   ```

6. `dc up -d backup`, then `dc exec backup backup.sh now`. The log says "copied to bucket".

For a user whose only job is backups, create one with permission to write to that bucket
alone, and generate the secret key as that user.

### Restoring

Restore once now, before relying on it, so the steps are known to work.

```bash
dc stop app
ls /srv/hrms-backups                       # pick a pair from the same time
dc exec -T db psql -U postgres -d postgres < /srv/hrms-backups/roles-<time>.sql   # "already exists" errors are fine
dc exec db psql -U postgres -d postgres -c 'drop database hrms with (force)' -c 'create database hrms owner dbmasteruser'
dc exec -T db pg_restore -U postgres -d hrms --exit-on-error < /srv/hrms-backups/hrms-<time>.dump
dc start app
```

To reset UAT to an empty app instead: `dc down`, then `docker volume rm deploy_pgdata`
(this deletes every row), then `dc up -d`.

## When something is wrong

| What you see | Likely cause |
| --- | --- |
| "Out of capacity" when creating the server | No free ARM capacity at that moment: try another availability domain, or later; upgrading to Pay As You Go helps |
| The browser cannot reach the site at all | Port 80/443 missing from the security list, or from the server's own `iptables` (step 4) |
| The browser warns about the certificate | The DNS record does not point at the reserved IP yet |
| `Set DB_SUPERUSER_PASSWORD in deploy/.env` | `deploy/.env` was copied from `deploy/.env.example` (production's) instead of `deploy/oci/env.example` |
| The app cannot sign in to the database after changing `APP_DB_OWNER_PASSWORD` | The database set the password on its first start only: `dc exec db psql -U postgres -c "alter role dbmasteruser password '<new>'"` |
| "copy to bucket failed" in `dc logs backup` | Wrong endpoint, region, namespace or secret key; the local dump is kept |
| The server has disappeared | Reclaimed as idle in a free-only account: upgrade to Pay As You Go (step 1), rebuild, and restore from the bucket |

`dc logs app`, `dc logs web`, `dc logs db` and `dc logs backup` show each part's log.
