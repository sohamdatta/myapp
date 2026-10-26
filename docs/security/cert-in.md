# CERT-In readiness

The CERT-In directions of 28 April 2022 (under section 70B(6) of the IT Act) put four duties on a service like this one.
This page says what is built, what each duty needs from a person, and what is still open.
For what to do in an incident, see [incident-response.md](incident-response.md).

| Duty | What the directions ask | What we have | Still needs a person |
| --- | --- | --- | --- |
| Logs | Keep ICT system logs for **180 days**, within Indian jurisdiction | Caddy writes a JSON line per request; `deploy/logs/ship-logs.sh` sends a day of every container's log plus the server's sign-in log to a bucket. Page crashes reported by browsers are kept 90 days in the database (they hold no personal data) | Create the bucket in an Indian region with a 180-day lifecycle rule; give the server a write-only key; schedule the script and look at its first run |
| Clocks | All systems synchronised to the **NIC or NPL** NTP servers | `deploy/ntp/chrony.conf` and `deploy/ntp/check-time-sync.sh` (fails if a source is outside NIC/NPL, unsynchronised, or more than 100 ms off) | Install chrony on both servers, apply the sample, schedule the check; for AWS and Oracle, confirm the platform time service is not the only source |
| Incident report | Report within **6 hours** of noticing | [incident-response.md](incident-response.md) with a template and timeline | Fill in roles; rehearse once |
| Point of Contact | Give CERT-In a name, address, email and phone | | Register (below) |

## What was built, and how it was tested

- **Access log.** The `log` block in `deploy/Caddyfile`, JSON to the container's output (already rotated by Docker at 5 × 20 MB, which is why the daily shipping matters). Caddy shows the Authorization and Cookie headers as `REDACTED`; invitation and token values in a query string are replaced by `REDACTED`.
- **Shipping.** `deploy/logs/ship-logs.sh [date]` writes `<environment>/<date>/<container>.log.gz` and `host-auth.log.gz`. Tested by `deploy/logs/test-ship-logs.sh` with stand-ins for docker, rclone and journalctl (runs in CI).
- **Time check.** `deploy/ntp/check-time-sync.sh`, tested by `deploy/ntp/test-check-time-sync.sh` against a stand-in `chronyc` (runs in CI).
- **Not tested for real:** no real chrony, rclone or bucket was available when this was written. The first live run on a server is part of the checklist; the scripts' parsing of real `chronyc -c` output is the likeliest thing to need a fix.

## Checklist for a person

Tick and date each. Nothing here can be done from code.

### Logs (Indian region)

- [ ] Bucket in an Indian region (AWS `ap-south-1` Mumbai or `ap-south-2` Hyderabad; Oracle `ap-mumbai-1` or `ap-hyderabad-1`), versioning on, public access blocked
- [ ] Lifecycle rule: delete objects older than **180 days** (not shorter)
- [ ] A key that can only write to the bucket, saved in `deploy/env/<environment>.env` as the rclone settings (`RCLONE_CONFIG_HRMSLOGS_*`) and `LOG_DEST=hrmslogs:<bucket>`
- [ ] Daily schedule on each server, for example `15 1 * * * root /srv/hrms/deploy/logs/ship-logs.sh` with the environment loaded
- [ ] First run looked at: three objects per day appear and open
- [ ] Someone other than the author can retrieve a day of logs from the bucket (do it once)

### Clocks

- [ ] chrony installed on production and UAT, `deploy/ntp/chrony.conf` applied
- [ ] `deploy/ntp/check-time-sync.sh` prints OK on both, and is scheduled daily with its output kept
- [ ] If the NIC servers are not reachable from the cloud network, record that and the source used instead (CERT-In allows servers traceable to NIC/NPL)

### Point of Contact and rehearsal

- [ ] Choose the Point of Contact and a deputy
- [ ] Register on cert-in.org.in (name, designation, organisation, address, email, phone); save the confirmation
- [ ] Fill in the roles table in the incident-response page and print it
- [ ] Agree with the legal advisor who is the data fiduciary for customer data, and who tells the Board and the people
- [ ] Rehearsal held and recorded in the incident-response page (date: )

### Other

- [ ] Keep customers' contracts in step: they should say we report incidents to CERT-In and tell them promptly
