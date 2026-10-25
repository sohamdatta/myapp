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

## Adding a migration

1. Create `V<next>__<what_it_does>.sql` (lower case and underscores, the next whole number).
2. Start it with a short header comment: what it changes, whether it is breaking, and how to undo it by hand.
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
- **"Detected applied migration not resolved locally":** the database is newer than this build; deploy
  the newer build.

## Coming next (roadmap Q1)

Squawk to lint each new migration in pull requests, a schema diff of `main` against the branch that
fails on breaking changes unless labelled, and code owners for this folder.
