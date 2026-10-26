# Restore drills

A backup nobody has restored is a hope, not a backup. Drill at least once a quarter, and before the first customer relies on production.

## How

On a machine that can reach PostgreSQL (for UAT: the server, via `deploy/hrms.sh uat exec db …`; for production: a throwaway
database created from the Lightsail snapshot, then `pg_dump` it, or use the nightly dump from the bucket):

```bash
export PGHOST=… PGUSER=postgres PGPASSWORD=…
deploy/restore-drill.sh /path/to/hrms-<time>.dump                  # restore into a scratch database, check, drop it
deploy/restore-drill.sh dump --roles roles-<time>.sql              # on a server that never ran the app
deploy/restore-drill.sh dump --jar backend/target/login-app-0.0.1-SNAPSHOT.jar   # also: would the new build start on this data?
```

It prints one line: `RESTORE-DRILL ok restore=41s tables=137 organizations=8 schema=27`. Add that line to the log below.
`restore=` is the time to restore the database itself (the number the roadmap asks to be timed). The time to *recover* adds
fetching the dump, creating the server or database, pointing the app at it and checking the site; measure those once by hand
and write them in the log too.

## Upgrade from the previous release's data

A migration that works on an empty database can still fail on real data (a new `not null` column, a unique index over
existing duplicates). Before each release that adds migrations:

1. Take the latest UAT dump, **from the release currently deployed**. UAT holds demo or masked data only, so it is safe to copy; never use a production dump on a laptop.
2. `deploy/restore-drill.sh dump --jar <jar of the new build>`. The jar runs `--check-migrations` against the restored copy: it lists what would be applied and refuses if an applied migration was edited.
3. For a full test, restore with `--keep`, start the new build against the kept database and let Flyway apply the migrations. Look at the log and open the app.

Empty-database migration is covered on every pull request: the whole backend test suite creates databases from nothing.

## Log

| Date | Dump (age) | Restored on | `restore=` | Whole recovery | Result / notes |
| --- | --- | --- | --- | --- | --- |
| 2026-10-26 | local test dump, seconds old | CI-like Postgres 16, 137 tables, 8 Organizations | 3 s | not measured | script check only; **not a real drill** |
| _first real drill_ | | | | | |

## Honest limits

- The drill proves the dump restores and the schema matches the migration history. It does not prove the data is *right*; `--compare` against a quiet source compares row counts, nothing deeper.
- Lightsail's point-in-time restore and the OCI bucket copy are different paths from a dump; drill each of them once (checklist in [README.md](README.md)).
