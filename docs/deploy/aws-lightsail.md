# Production on AWS Lightsail (Mumbai)

Production runs on AWS Lightsail: one server runs the app and its web server, and a
Lightsail managed PostgreSQL holds the data. Both are in the Mumbai region, so employee data
stays in India. Demo and UAT run separately on Oracle Cloud
([guide](oracle-cloud-uat.md)); each change is checked there before it reaches production.

Cost at setup (October 2026): about **$54 a month**, a 4 GB server ($24) and a 2 GB
database ($30), plus a few cents for snapshots. Check current prices on the
[Lightsail pricing page](https://aws.amazon.com/lightsail/pricing/).

```
Browser ──HTTPS──► Lightsail server hrms-prod (Ubuntu 24.04, Docker)
                     ├─ Caddy: HTTPS certificate, the frontend, /api passed on
                     └─ the app (Java 21, port 8080, not exposed)
                                 │  private network, SSL
                                 ▼
                     Lightsail managed PostgreSQL 16: hrms-prod-db
```

## Production as built

| What | Value |
| --- | --- |
| Region and zone | Mumbai, `ap-south-1a` (server and database in the same zone) |
| Server | `hrms-prod`: Ubuntu 24.04 LTS, General purpose, Dual-stack, 4 GB RAM, 2 vCPUs, 80 GB ($24) |
| Static IP | `hrms-prod-ip`: `3.6.15.236` |
| SSH key | `hrms-prod` (Lightsail custom key; the `.pem` is kept by the owner, not in this repository) |
| Firewall | HTTP 80 and HTTPS 443 from anywhere (IPv4 and IPv6); SSH 22 from Lightsail browser SSH only |
| Database | `hrms-prod-db`: PostgreSQL 16.15, Standard, 2 GB ($30), public mode off, automatic backups on |
| Database login | master user `dbmasteruser`; master database **`dbhrms`** |
| Address | **`peopleos.nexvance.in`**: DNS at Cloudflare, records **DNS only** (step 3) |
| Tags | `env` = `production` on the server and the database |
| Settings file | `deploy/env/production.env` on the server (from `deploy/env/production.env.example`) |

The files used are in [`deploy/`](../../deploy): the two Dockerfiles, the `Caddyfile`,
`docker-compose.yml`, `hrms.sh`, `env/production.env.example` and
`aws/setup-production.sh`, which does the first install (step 4). CI builds and starts the
same setup on every push, against a database whose admin login is not a superuser, as on AWS.

## Settings: one file per environment

Each environment has its own settings file, and its own template in the repository:

| Environment | Template (in git) | Filled-in file (on that server only, never in git) | Run with |
| --- | --- | --- | --- |
| Production (AWS) | `deploy/env/production.env.example` | `deploy/env/production.env` | `deploy/hrms.sh production …` |
| Demo / UAT (Oracle) | `deploy/env/uat.env.example` | `deploy/env/uat.env` | `deploy/hrms.sh uat …` |

`deploy/hrms.sh` picks the environment's settings file and compose files, so production and
UAT never pick up each other's settings. Each file names its environment
(`HRMS_ENVIRONMENT=production`), and the script refuses a file used for the wrong one. It
also warns when the file can be read by other users. The filled-in files are ignored by git
(`deploy/env/*.env`). Production and UAT never share a value: each has its own
`APP_DATA_KEY`, passwords and Super Admin.

## 0. The AWS account

Do this once, before anything else.

1. Turn on **MFA for the root user**, then stop using root.
2. Create an IAM Identity Center (or IAM) user with administrator rights, with MFA, and use
   it from then on. With SSH limited to browser SSH (step 2), this login is the key to the
   server.
3. **Billing → Budgets**: a monthly cost budget of about **$70**, with an email alert.

## 1. The database

1. In the Lightsail console, **Databases** → **Create database**, location **Mumbai**.
2. Engine **PostgreSQL 16.x** (16.15 at setup). The app, CI and UAT run on 16; ask before
   using a later major version.
3. **Specify login credentials**: user name `dbmasteruser`, a strong password, saved in a
   password manager.
4. **Specify the master database name**. Production's is `dbhrms`; whatever it is, the same
   name goes at the end of `APP_DB_URL` (step 4). It cannot be renamed later, and the app
   works with any name.
5. Plan: **Standard, 2 GB, $30**. Not the $15 plan: it is not encrypted at rest, and the
   database holds PAN and bank details. Once customers rely on it, switch to the
   **High availability** plan ($60), which keeps a standby in a second data centre.
6. Name `hrms-prod-db`; tag `env` = `production`.
7. Once it is *Available*: **Networking** → check **public mode is off** (the server reaches
   it over Lightsail's private network), then copy its **endpoint** from the **Connect** tab.

The master user can create roles but is not a superuser. That is all the app needs: at
its first start it creates its own three database roles and the tables, and from then on
works only through those roles, whose row-level security keeps each Organization's data
apart.

## 2. The server

1. **Instances** → **Create instance**, location **Mumbai**, the same zone as the database.
2. **Linux/Unix → OS only → Ubuntu 24.04 LTS**.
3. Plan type **General purpose**, network type **Dual-stack**, size **4 GB RAM, 2 vCPUs,
   $24**. Smaller plans run out of memory building and running the app.
4. **Advanced settings → SSH key → Create custom key** named `hrms-prod`. The `.pem` file
   downloads once; keep it in a password manager, never in the repository.
5. Name `hrms-prod`; tag `env` = `production`; tick **Enable Automatic Snapshots**.
6. Once it is running, on the instance's **Networking** tab:
   - **Attach static IP** → create `hrms-prod-ip`, so the address never changes.
   - Firewall (one list for IPv4 and IPv6):
     - **HTTP 80** and **HTTPS 443**: any IPv4 or IPv6 address. Caddy needs 80 to get
       and renew the HTTPS certificate.
     - **SSH 22**: no source addresses, **Lightsail browser SSH** only (IPv4 and IPv6).
       The server is then reached through **Connect using SSH** in the console, from any
       place, behind the AWS login and its MFA. To use your own SSH client for a while,
       add your current IP address to this rule, and remove it afterwards.
     - Only if a biometric device that cannot use HTTPS will send punches: **Custom TCP
       8081** (or the `DEVICE_PORT` you set), limited to the office's address if it is fixed.

## 3. The address

Production is **`https://peopleos.nexvance.in`**. The domain `nexvance.in` is on Cloudflare;
its DNS has these records for production:

| Type | Name | Content | Proxy status |
| --- | --- | --- | --- |
| A | `peopleos` | `3.6.15.236` (the static IP) | **DNS only** (grey cloud) |
| AAAA | `peopleos` | `hrms-prod`'s IPv6 address (Lightsail → **Networking**) | **DNS only** |
| CAA | `nexvance.in` (`@`) | `0 issue "letsencrypt.org"` | — |

**Keep them DNS only, never Proxied (orange cloud).** Caddy on the server gets and renews the
HTTPS certificate from Let's Encrypt itself. Behind Cloudflare's proxy that fails (Cloudflare
shows **error 525, "SSL handshake failed"**), the app would see Cloudflare's addresses instead of
people's (breaking the office-network punch check), and Cloudflare does not pass port 8081 for
biometric devices. The CAA record must keep allowing `letsencrypt.org`.

To check the records from any computer, asking Cloudflare's resolver directly (an office resolver
may hold an old answer for a few minutes):

```bash
nslookup peopleos.nexvance.in 1.1.1.1      # must return 3.6.15.236, not 104.21.x or 172.67.x
```

`DOMAIN=peopleos.nexvance.in` in `deploy/env/production.env` tells Caddy which name to serve.
After changing it, or the records: `deploy/hrms.sh production up -d --force-recreate web`, then
`deploy/hrms.sh production logs --tail 80 web` shows "certificate obtained successfully".

Never set `DOMAIN` to `http://<IP address>`: that turns HTTPS off, and passwords and PAN
would cross the internet in clear text. (Before the domain existed, production used the free
name `3-6-15-236.sslip.io`; it no longer answers, since Caddy serves one name.)

## 4. Install and start

Everything happens in **Connect using SSH** on `hrms-prod` in the Lightsail console. Paste
each block, wait for it to finish, then go on.

**Block 1: Docker, fail2ban and swap.** Swap gives the first build room on 4 GB.

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
sudo apt-get install -y fail2ban
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
exit
```

Open **Connect using SSH** again, so the Docker group applies.

**Block 2: a deploy key, so the server can read the repository.** A deploy key reads this
one repository and nothing else, and keeps working once the repository is private.

```bash
ssh-keygen -t ed25519 -N '' -f ~/.ssh/hrms_deploy -C hrms-prod
printf 'Host github.com\n  IdentityFile ~/.ssh/hrms_deploy\n  IdentitiesOnly yes\n' >> ~/.ssh/config
ssh-keyscan -t ed25519 github.com >> ~/.ssh/known_hosts
cat ~/.ssh/hrms_deploy.pub
```

Copy the line it prints. On GitHub, **the repository → Settings → Deploy keys → Add deploy
key**: title `hrms-prod`, paste the line, leave **Allow write access** off, **Add key**.

**Block 3: get the code and run the setup.**

```bash
git clone git@github.com:sohamdatta/myapp.git hrms
cd hrms
deploy/aws/setup-production.sh
```

[`deploy/aws/setup-production.sh`](../../deploy/aws/setup-production.sh) asks for:

| It asks | Answer |
| --- | --- |
| Address | Type `peopleos.nexvance.in` (its DNS must already point here; step 3) |
| Database endpoint | From **Databases → hrms-prod-db → Connect** (`ls-….ap-south-1.rds.amazonaws.com`) |
| Master database name / user | Press Enter for `dbhrms` / `dbmasteruser` |
| Master user password | The database password from step 1, typed twice (not shown) |
| Super Admin email and password | Your own; at least 12 characters, used nowhere else |

It then makes the app's own database password and the data encryption key, writes
`deploy/env/production.env` (readable by you only), checks it can sign in to the database
and that the address points at this server, builds and starts everything (several minutes
the first time), and checks the site answers over HTTPS.

At the end it shows **`APP_DATA_KEY` once**. **Save it in your password manager and
somewhere offline, apart from the database backups**, then type `saved`. Without it, PAN,
bank details and documents can never be read again; stored beside the backups, the
encryption protects nothing.

Then open `https://peopleos.nexvance.in`, sign in as the Super Admin, and
create the first Organization from the Platform console, as the README describes.

If a check fails, the script stops and says what to fix; run it again afterwards. It keeps
the settings file it already wrote unless you answer `no` to "Keep it?".

### The settings file, for reference

`deploy/env/production.env` (edit with `nano deploy/env/production.env`, then
`deploy/hrms.sh production up -d`):

| Setting | Value |
| --- | --- |
| `HRMS_ENVIRONMENT` | `production` |
| `DOMAIN` | The address from step 3, without `https://` |
| `APP_DB_URL` | `jdbc:postgresql://<endpoint>:5432/dbhrms?sslmode=require` (the master database name at the end) |
| `APP_DB_OWNER_USER` / `APP_DB_OWNER_PASSWORD` | `dbmasteruser` and its password |
| `APP_DB_ROLE_PASSWORD` | Made by the script (`openssl rand -hex 24`) |
| `APP_SUPERADMIN_EMAIL` / `APP_SUPERADMIN_PASSWORD` | Used on the first start only |
| `APP_DATA_KEY` | Made by the script (`openssl rand -base64 32`); never change it, or existing data cannot be read |
| `APP_REQUIRE_DATA_KEY` | `true` |
| `APP_TRUST_FORWARDED_FOR` | `true` |

The script writes values in single quotes (`KEY='value'`), so characters such as `$` and
`#` in passwords are kept as typed; do the same when editing by hand.

## 5. Updating

Every change goes to UAT first, by itself, when CI passes on `main`
([UAT guide](oracle-cloud-uat.md), step 7). Once it has been checked there, deploy the
same commit to production by hand, when nobody is using it (Saturday morning, not Sunday
night). Production is never deployed by a workflow: its SSH stays closed to the internet.

**Before you start**

- [ ] CI is green on the commit, and UAT runs that same commit (`git log -1` on UAT, or the
      "Deploy to UAT" run in GitHub Actions).
- [ ] It has been checked on UAT, including any screen its migrations touch.
- [ ] You have the commit's short hash, and the hash production runs now (to roll back to).

**Deploy**

1. Take a database snapshot: **Databases** → `hrms-prod-db` → **Snapshots** → create, named
   `before-<commit>`. Wait until it shows **Available**.
2. In **Connect using SSH** on `hrms-prod`, fetch and build the new commit without starting it:

   ```bash
   cd ~/hrms
   git log -1 --format='%h %s'          # note what runs now, to roll back to
   git fetch origin && git checkout --detach <commit checked on UAT>
   deploy/hrms.sh production build app
   ```

3. **Dry run** the migrations with the new build, against production. It changes nothing:

   ```bash
   deploy/hrms.sh production run --rm --no-deps -T app --check-migrations
   ```

   It prints the schema version, each migration the start will apply, and whether the applied
   ones match their files. **Stop** if it says the next start would REFUSE to run, or lists a
   migration you did not expect: check out the old commit again and find out why
   ([database guide](../database.md)). The first time, it says the schema was set up by the
   earlier migrator and that Flyway will record a baseline; that is expected and runs no SQL.
4. Start it:

   ```bash
   deploy/hrms.sh production up -d --wait --wait-timeout 600
   deploy/hrms.sh production logs --tail 100 app
   ```

   The log shows Flyway applying the migrations from the dry run, then the app starting.
5. Check: the site opens and you can sign in; open a screen the change touched.

**If it goes wrong**

- **The new build misbehaves:** check out the commit you noted and
  `deploy/hrms.sh production up -d --build`. The older build starts against the newer schema
  (Flyway ignores migrations newer than the build), and works as long as the migration followed
  expand-then-contract ([database guide](../database.md)).
- **A migration damaged data, or broke the older build:** restore the `before-<commit>` snapshot
  (**Databases** → `hrms-prod-db` → **Snapshots** → restore; it creates a new database), point
  `APP_DB_URL` in `deploy/env/production.env` at it, check out the old commit and start it.
  Anything entered since the snapshot is lost, which is why updates happen when nobody is using
  the app. Then fix forward with a new migration.

## 6. Backups and checks

- **The database:** Lightsail backs it up every day and can restore it to any point in
  the last days it keeps (point-in-time restore). Restore it once into a new database,
  before customers rely on it, to know the steps work.
- **The server:** the automatic snapshots from step 2. The server holds nothing that
  cannot be rebuilt except `deploy/env/production.env` and the Caddy certificates.
- **The key:** `APP_DATA_KEY`, in the two places from step 4.
- **Alarms:** under each resource's **Metrics**, add alarms for CPU and, on the database,
  free storage.

## When something is wrong

| What you see | Likely cause |
| --- | --- |
| `No settings for production: deploy/env/production.env is missing` | The setup script has not been run yet, or you are not in `~/hrms` |
| `git clone` says `Permission denied (publickey)` | The deploy key is not added on GitHub yet (step 4, block 2) |
| `Docker cannot be used yet` | Close the terminal and open **Connect using SSH** again after block 1 |
| `Could not sign in to the database` | Wrong endpoint, database name or password; fix them in `deploy/env/production.env` and rerun the script |
| `… does not point at … yet` | The address's DNS record is missing or not spread yet (step 3) |
| `deploy/env/production.env says HRMS_ENVIRONMENT=uat` | UAT's settings file was copied to production: start again from `production.env.example` |
| The app keeps restarting; the log says it cannot connect | Wrong endpoint, password or database name in `APP_DB_URL`, or the database is in another region |
| A connection error mentioning encryption or `pg_hba.conf` | `?sslmode=require` is missing from `APP_DB_URL` |
| `database "hrms" does not exist` | `APP_DB_URL` ends in `hrms`; production's database is `dbhrms` |
| `APP_DATA_KEY is not set, and APP_REQUIRE_DATA_KEY is true` | The key is empty in `deploy/env/production.env` |
| The browser warns about the certificate | The DNS record does not point at the static IP yet, or port 80/443 is closed |
| Your own SSH client cannot connect | Expected: SSH accepts Lightsail browser SSH only (step 2) |
| Uploading a document fails at once | A proxy in front with a smaller upload limit; Caddy here allows 11 MB |
| Every punch is refused as off the office network | `APP_TRUST_FORWARDED_FOR` is not `true`, or the office's public address is not in the work location |

`deploy/hrms.sh production logs app` shows the app's log; `… logs web` shows Caddy's.
