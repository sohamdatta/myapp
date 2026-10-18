# Deploying on AWS Lightsail (Mumbai)

One Lightsail server runs the app and its web server; a Lightsail managed PostgreSQL holds
the data. Both sit in the Mumbai region, so employee data stays in India. Expect about
**$54 a month**: a 4 GB server ($24) and a 2 GB database ($30). Prices are AWS's; check
them on the [Lightsail pricing page](https://aws.amazon.com/lightsail/pricing/).

```
Browser ──HTTPS──► Lightsail server (Ubuntu, Docker)
                     ├─ Caddy: HTTPS certificate, the frontend, /api passed on
                     └─ the app (Java 21, port 8080, not exposed)
                                 │  private network, SSL
                                 ▼
                     Lightsail managed PostgreSQL 16
```

The files used here are in [`deploy/`](../../deploy): the two Dockerfiles, the
`Caddyfile`, `docker-compose.yml` and `.env.example`. CI builds and starts the same setup
on every push, against a database whose admin login is not a superuser, as on AWS.

## 1. The database

1. In the Lightsail console, **Databases** → **Create database**.
2. Region **Mumbai (ap-south-1)**.
3. Engine **PostgreSQL**, the newest **16.x**. The app is tested on 16; if only a later
   major version is offered, ask before using it.
4. Under credentials, keep the master user name `dbmasteruser`, and set a strong password
   (or let Lightsail make one, and copy it).
5. Name the master database **`hrms`**.
6. Plan: **Standard, 2 GB RAM, $30**. Do not pick the $15 plan: it is not encrypted at
   rest, and the database holds PAN and bank details. Once customers rely on it, switch to
   the **High availability** plan ($60), which keeps a standby in a second data centre.
7. Leave **public mode off**. The server reaches it over Lightsail's private network.
8. Create it, wait until it is *Available*, and copy its **endpoint** from the
   *Connect* tab.

The master user can create roles but is not a superuser. That is all the app needs: at
its first start it creates its own three database roles and the tables, and from then on
works only through those roles, whose row-level security keeps each Organization's data
apart.

## 2. The server

1. **Instances** → **Create instance**, region **Mumbai**, the same as the database.
2. Platform **Linux/Unix**, blueprint **OS Only → Ubuntu 24.04 LTS**.
3. Plan **4 GB RAM, 2 vCPU, $24**. Smaller plans run out of memory building and running
   the app.
4. Create it. Then, on the instance:
   - **Networking** → **Create static IP** and attach it, so the address never changes.
   - **Networking** → IPv4 firewall: add **HTTPS (443)**; HTTP (80) and SSH (22) are
     there already. Restrict SSH to your own IP address. If a biometric device that
     cannot use HTTPS will send punches, also add **Custom TCP 8081** (or the
     `DEVICE_PORT` you set), restricted to the office's IP address if it has a fixed one.
   - **Snapshots** → turn on **automatic snapshots**.

## 3. The domain

At your domain's DNS provider, add an **A record** for the address people will open
(for example `hr.example.com`) pointing at the static IP. Wait until
`nslookup hr.example.com` returns it: Caddy needs it to get the HTTPS certificate.

## 4. Install and start

Connect with **Connect using SSH** in the console (or your own SSH client), then:

```bash
# Docker, and the right to use it without sudo (log out and back in after this)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit
```

```bash
git clone https://github.com/sohamdatta/myapp.git hrms
cd hrms
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
nano deploy/.env
```

Fill in `deploy/.env`:

| Setting | Value |
| --- | --- |
| `DOMAIN` | The address from step 3, without `https://` |
| `APP_DB_URL` | `jdbc:postgresql://<endpoint>:5432/hrms?sslmode=require` |
| `APP_DB_OWNER_USER` / `APP_DB_OWNER_PASSWORD` | `dbmasteruser` and its password |
| `APP_DB_ROLE_PASSWORD` | `openssl rand -base64 24` |
| `APP_SUPERADMIN_EMAIL` / `APP_SUPERADMIN_PASSWORD` | Your own; used on the first start only |
| `APP_DATA_KEY` | `openssl rand -base64 32` |
| `APP_REQUIRE_DATA_KEY` | `true` |
| `APP_TRUST_FORWARDED_FOR` | `true` |

**Save `APP_DATA_KEY` in a password manager now, and somewhere offline too, apart from
the database backups.** Without it, PAN, bank details and documents can never be read
again; stored beside the backups, the encryption protects nothing.

Start everything:

```bash
docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml logs -f app    # Ctrl+C to stop watching
```

The first build takes several minutes. The app creates the tables on its first start;
when its health check passes, Caddy starts and fetches the certificate. Open
`https://<your domain>` and sign in as the Super Admin. From there, create the first
Organization from the Platform console, as the README describes.

## 5. Updating

```bash
cd ~/hrms
git pull
docker compose -f deploy/docker-compose.yml up -d --build
```

New database migrations run by themselves when the app starts. Deploy when nobody is
using it (Saturday morning, not Sunday night), and take a database snapshot first
(**Databases** → your database → **Snapshots**).

## 6. Backups and checks

- **The database:** Lightsail backs it up every day and can restore it to any point in
  the last days it keeps (point-in-time restore). Restore it once into a new database,
  before customers rely on it, to know the steps work.
- **The server:** the automatic snapshots from step 2. The server holds nothing that
  cannot be rebuilt except `deploy/.env` and the Caddy certificates.
- **The key:** `APP_DATA_KEY`, in the two places from step 4.
- **Alarms:** under each resource's **Metrics**, add alarms for CPU and, on the database,
  free storage.

## When something is wrong

| What you see | Likely cause |
| --- | --- |
| The app keeps restarting; the log says it cannot connect | Wrong endpoint or password in `APP_DB_URL`, or the database is in another region |
| A connection error mentioning encryption or `pg_hba.conf` | `?sslmode=require` is missing from `APP_DB_URL` |
| `APP_DATA_KEY is not set, and APP_REQUIRE_DATA_KEY is true` | The key is empty in `deploy/.env` |
| The browser warns about the certificate | The DNS record does not point at the static IP yet, or port 80/443 is closed |
| Uploading a document fails at once | A proxy in front with a smaller upload limit; Caddy here allows 11 MB |
| Every punch is refused as off the office network | `APP_TRUST_FORWARDED_FOR` is not `true`, or the office's public address is not in the work location |

`docker compose -f deploy/docker-compose.yml logs app` shows the app's log;
`… logs web` shows Caddy's.
