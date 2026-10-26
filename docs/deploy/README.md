# Environments and deployment

Where the HRMS runs, how each environment is set up, and what is done and still to do.
Keep this page current: update the status and add a line to the log whenever something
changes on a server or in an account.

| Environment | Where | Data | Guide | Settings file (on the server) | Deployed |
| --- | --- | --- | --- | --- | --- |
| Production | AWS Lightsail, Mumbai (`ap-south-1a`) | Real employee data | [aws-lightsail.md](aws-lightsail.md) | `deploy/env/production.env` | By hand, after UAT: `deploy/hrms.sh production …` |
| Demo / UAT | Oracle Cloud Always Free, Mumbai | Demo or masked data only | [oracle-cloud-uat.md](oracle-cloud-uat.md) | `deploy/env/uat.env` | By itself when CI passes on `main` ([deploy-uat.yml](../../.github/workflows/deploy-uat.yml)) |

A change goes: pull request → CI → merge to `main` → UAT (automatic) → checked on UAT →
production (by hand, with a database snapshot first).

## Status

As of 25 Oct 2026.

### Production (AWS)

- [ ] Account: MFA on the root user, an admin user with MFA, and a billing budget alert (to confirm)
- [x] Server `hrms-prod`: Ubuntu 24.04, 4 GB, Dual-stack, automatic snapshots, tag `env=production`
- [x] Static IP `hrms-prod-ip` (`3.6.15.236`) attached
- [x] Firewall: HTTP 80 and HTTPS 443 from anywhere; SSH from Lightsail browser SSH only
- [x] Database `hrms-prod-db`: PostgreSQL 16.15, 2 GB, public mode off, backups on; master database `dbhrms`
- [x] Block 1 on the server: Docker, fail2ban, swap ([guide](aws-lightsail.md), step 4)
- [x] Block 2: deploy key `hrms-prod` added on GitHub
- [x] Block 3: `deploy/aws/setup-production.sh` run; site answers over HTTPS
- [x] Address `https://peopleos.nexvance.in`: Cloudflare DNS, A and AAAA **DNS only**, CAA allows Let's Encrypt ([guide](aws-lightsail.md), step 3)
- [ ] `APP_DATA_KEY` saved in a password manager and offline
- [ ] First Organization created from the Platform console
- [ ] One timed restore drill ([restore-drills.md](restore-drills.md)): record it in the log there
- [ ] Metric alarms: server CPU, database free storage
- [ ] Before customers rely on it: database to the **High availability** plan ($60)

### Demo / UAT (Oracle Cloud)

- [x] Account created, home region Mumbai
- [ ] Account upgraded to Pay As You Go (still free; needed for capacity and so the server is not reclaimed)
- [ ] Server: `VM.Standard.A1.Flex`, 4 OCPU, 24 GB, Ubuntu 24.04, 100 GB boot volume, **public IPv4 on**.
      The first attempt stopped at "Out of capacity" in Mumbai's only availability domain; try again after upgrading.
- [ ] Reserved public IP; ports 80/443 in the security list and in the server's own iptables
- [ ] Installed with `deploy/hrms.sh uat` ([guide](oracle-cloud-uat.md), step 4)
- [ ] Backups copied to Object Storage, and one test restore
- [ ] Automatic deploys from GitHub: `UAT_HOST`, `UAT_URL`, `UAT_SSH_KEY`, `UAT_KNOWN_HOSTS` ([guide](oracle-cloud-uat.md), step 7)

### Shared

- [x] Domain: `nexvance.in`, DNS on Cloudflare; production at `peopleos.nexvance.in`
- [ ] UAT address under `nexvance.in` (for example `uat.peopleos.nexvance.in`), once the UAT server exists
- [ ] Email: Amazon SES set up for the domain and out of the sandbox, mail settings in `production.env` ([roadmap](../roadmap/next-releases.md), "Email"); until then invitation links are copied by hand
- [ ] Repository made private (GitHub → Settings → Danger Zone); servers already read it with deploy keys
- [x] CI trimmed for a private repository's free minutes; weekly minutes alert ([actions-minutes.yml](../../.github/workflows/actions-minutes.yml))

## Decisions

| Decision | Why |
| --- | --- |
| Production on AWS Lightsail; demo / UAT on Oracle Cloud Always Free | Managed database, backups and support for real data; UAT at no cost |
| Mumbai for both | Employee data stays in India |
| PostgreSQL 16 everywhere | What the app, CI and both environments are tested on |
| Lightsail database Standard 2 GB, not $15 | The $15 plan is not encrypted at rest, and the data includes PAN and bank details |
| SSH to production from Lightsail browser SSH only | Works from anywhere behind the AWS login and MFA; port 22 is closed to the internet |
| One settings file per environment, run through `deploy/hrms.sh` | Production and UAT can never pick up each other's settings or secrets |
| Production at `peopleos.nexvance.in`; Cloudflare records DNS only, not proxied | Caddy gets its own certificate (proxied gave error 525); the app sees people's real addresses for the office-network punch check; device port 8081 still reachable |
| UAT holds demo or masked data only, with its own keys and passwords | A leak from UAT must not expose people or unlock production |

## Log

Newest first. One line per change to a server, an account or the deployment setup.

| Date | Environment | What changed |
| --- | --- | --- |
| 2026-10-25 | Production | Address moved to `peopleos.nexvance.in` (Cloudflare: A and AAAA DNS only, CAA `letsencrypt.org`); error 525 fixed by turning Cloudflare's proxy off |
| 2026-10-25 | Production | Setup script `deploy/aws/setup-production.sh` and install steps added (#10); server install pending |
| 2026-10-25 | Shared | Domain and HTTPS added to the roadmap; sslip.io address until then (#9) |
| 2026-10-25 | Shared | One settings file per environment and `deploy/hrms.sh` (#8) |
| 2026-10-25 | Production | Database `hrms-prod-db` created: PostgreSQL 16.15, Standard 2 GB, master database `dbhrms` |
| 2026-10-25 | Production | Server `hrms-prod` created; static IP `3.6.15.236`; firewall set to browser SSH only |
| 2026-10-25 | UAT | Oracle Cloud account created (Mumbai); first server attempt out of capacity |
| 2026-10-25 | Shared | CI savings, automatic UAT deploys and the minutes alert (#6) |
| 2026-10-25 | UAT | Demo / UAT setup for Oracle Cloud: compose overlay, nightly backup, guide (#5) |
