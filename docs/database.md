# Database migrations

The schema is built by the SQL files in `backend/src/main/resources/db/migration`, applied by
[Flyway](https://documentation.red-gate.com/fd) when the backend starts (`core/Migrator.java`). They run
as the database owner; the application then works only through its three roles (`app_platform`,
`app_auth`, `app_tenant`), whose passwords the migrator sets after every start.

## How it is protected

| Guard | Where | Catches |
| --- | --- | --- |
| Checksum lock | `MigratorFilesTest` + `backend/src/test/resources/migration-checksums.txt` | An applied migration edited or deleted, in CI, before any database sees it |
| Flyway validate | Every start | A database whose applied migrations differ from the files (checksums in `public.flyway_schema_history`); the backend refuses to start |
| Naming and order | `MigratorFilesTest` | A file not named `V<n>__<words>.sql`, a gap or a duplicate version |
| Upgrade path | `MigratorUpgradeTest` | Databases set up before Flyway (see below) |
| Lint | "Migrations" workflow, Squawk (`.squawk.toml`) | In a pull request's new migrations: locks without a timeout, constraints without `NOT VALID`, indexes on existing tables without `CONCURRENTLY`, dropped constraints |
| Schema diff | "Migrations" workflow, `.github/scripts/schema_diff.py` | Changes that break the release still running during a deploy (below); fails unless the pull request has the `breaking-change` label |
| Code owners | `.github/CODEOWNERS` | Asks the owner to review any change to migrations, money or security code |

## Adding a migration

1. Create `V<next>__<what_it_does>.sql` (lower case and underscores, the next whole number).
2. Start it with a short header comment (what it changes, whether it is breaking, how to undo it by hand),
   then `set lock_timeout = '5s';` so a migration waiting on a busy table gives up instead of
   blocking every request behind it.
3. Run the backend tests. `MigratorFilesTest` fails and prints the line to add to
   `migration-checksums.txt`; add it. That line is the deliberate "this file is now frozen" step.
4. Every tenant table gets `tenant_id` and forced row-level security, as in the existing files.

Never edit a merged migration, even to fix a typo: write the next one. If a change must be undone,
write a migration that undoes it.

## Breaking changes: expand, then contract

During a deploy the old and the new backend may both run against the new schema, so a change that
removes or renames something is split across two releases:

1. **Expand** (release N): add the new column or table; write to both; read the new one.
2. **Contract** (release N+1): remove the old one once no released code reads it.

On large tables: add the column, backfill in batches, then add the constraint `NOT VALID` and
`VALIDATE CONSTRAINT` in a later migration; build indexes on existing large tables `CONCURRENTLY`,
each in its own migration.

## Databases set up before Flyway

Until October 2026 a hand-written migrator applied the files and recorded their names in
`public.schema_migration`. On the first start with Flyway, the migrator sees that table, checks that it
lists every version from 1 to its highest, and baselines Flyway there (V19 for every existing
deployment), so nothing runs twice. Files from V20 on are applied and checksummed by Flyway.
`public.schema_migration` is no longer written; it can be dropped by a later migration.

## When the backend refuses to start

- **Checksum mismatch / "Migration ... was modified":** someone edited an applied file. Restore the file
  as it was (the checksum lock makes this rare) and put the change in a new migration. Do not run
  `flyway repair` against production unless you are sure the database matches the edited file.
- **"public.schema_migration does not list every version":** a database where the old migrator stopped
  part way. Compare the listed versions with the schema by hand, finish or record the missing ones, then
  start again.
- **An older build against a newer database** starts: Flyway ignores migrations newer than the build.
  That is what makes an app-only rollback possible, and why breaking changes follow expand-then-contract.

## Dry run before a deploy

`java -jar app.jar --check-migrations` (on a server: `deploy/hrms.sh <env> run --rm --no-deps -T app
--check-migrations`) prints the schema version, each migration the next start will apply, and whether
the applied ones match their files, then exits without changing anything. It exits 1 when the next start
would refuse. CI runs it in the production-like deployment job; the production guide runs it before
every update.

## The "Migrations" checks on a pull request

They run when a pull request touches `db/migration`, and again when its labels change.

- **Lint.** Squawk reads only the migrations the pull request adds or changes. Fix what it reports, or,
  when the rule does not apply, put `-- squawk-ignore <rule>` on the line before the statement with a
  comment saying why; the reviewer sees both. Rules switched off for the whole project, and why, are
  in `.squawk.toml`.
- **Schema diff.** Builds the schema from `main`'s migrations and from the branch's, and compares them.
  The job summary shows the report and the full diff. It fails on:
  - a table, view, column or function that is gone (dropped or renamed);
  - a column whose type changed;
  - a column that became NOT NULL;
  - a new NOT NULL column without a default.

  New or changed constraints are listed for review only. When a breaking change is intended and the
  release plan covers it (expand, then contract), add the label `breaking-change` to the pull request;
  the check runs again and passes.
