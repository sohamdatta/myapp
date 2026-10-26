# Engineering standards

Every change to myapp follows these standards. A pull request that breaks one either fixes it or records an exception (see the end). They exist so that a wrong payslip, a cross-Organization leak or a slow month-end never reaches a customer.

Where a rule says **(CI)**, a check enforces it; the rest are for the reviewer, using the checklist at the end.

Contents: [Non-negotiables](#1-non-negotiables) · [Java 21](#2-java-21) · [Less boilerplate](#3-less-boilerplate) · [Clean code](#4-clean-code) · [Database and performance](#5-database-and-performance) · [Testing](#6-testing) · [Frontend](#7-frontend) · [Security and privacy](#8-security-and-privacy) · [Migrations](#9-migrations) · [Tooling](#10-tooling) · [Review checklist](#11-review-checklist) · [Exceptions](#12-exceptions)

---

## 1. Non-negotiables

These hold in every change, with no exception process.

1. **Every tenant table has `tenant_id` and forced row-level security.** Tenant data is read only inside `db.inTenant(...)`. **(CI: migration test)**
2. **Money is `BigDecimal`, never `double`.** Rounding happens once, at the step the component declares.
3. **Statutory figures are rule data**, versioned and signed off by the payroll consultant. No rate, ceiling, slab or due date in Java or SQL code.
4. **Every change a person makes writes an audit event.** Punches, ledgers and filings are append-only; corrections are new rows.
5. **Maker-checker wherever money moves**: the person who prepares never approves.
6. **PAN, bank account, UAN and ESI numbers are encrypted at rest**, decrypted only at the moment of use, and never written to logs, notifications or AI prompts.
7. **The payroll and tax engines stay pure**: no clock, no database, no network inside `Formula` or the calculation itself. Inputs in, result and trace out.

## 2. Java 21

Use the language we have. New code uses these; old code is moved over when it is touched.

| Use | Instead of | Where |
| --- | --- | --- |
| `record` for every request, response and row type | `Map<String, Object>`, JavaBeans with getters and setters | Controllers, services, query results |
| `sealed` interfaces with records for results and states | Status strings, flags, `null` meaning "not found" | Run states, filing states, check results |
| Pattern-matching `switch` with no `default` on a sealed type | `if`/`else` chains, `instanceof` then cast | Anywhere a sealed type is handled; the compiler then flags a missing case |
| Record patterns: `case Approved(var by, var at) ->` | Reading fields one by one after a cast | Handling results |
| Text blocks `"""` for SQL over one line | `+`-joined strings | All multi-line SQL |
| `var` for locals whose type is obvious from the right side | Repeating long generic types | Locals only; never fields or signatures |
| `List.of`, `Map.of`, `.toList()` | Mutable collections returned from methods | Return values are immutable |
| `getFirst()` / `getLast()` (sequenced collections) | `list.get(0)`, `list.get(list.size() - 1)` | Lists, `LinkedHashMap`, `TreeSet` |
| `java.time` types in requests (`LocalDate`, `YearMonth`) | Dates as `String`, parsed by hand | Request records |
| Virtual threads (`spring.threads.virtual.enabled=true`) | A larger Tomcat thread pool | Request handling; keep the Hikari pool sized to the database, not to threads |
| `Optional` as a return type for "may be absent" | Returning `null` | Lookups; never as a field or parameter |

**Example: a result the compiler checks**

```java
sealed interface ClaimCheck permits Matches, Mismatch, Unreadable {}
record Matches() implements ClaimCheck {}
record Mismatch(String field, String declared, String found) implements ClaimCheck {}
record Unreadable(String reason) implements ClaimCheck {}

String message(ClaimCheck check) {
    return switch (check) {                       // no default: a new case is a compile error here
        case Matches m -> "Matches the claim";
        case Mismatch(var field, var declared, var found) -> field + ": declared " + declared + ", found " + found;
        case Unreadable(var reason) -> "Could not read the proof: " + reason;
    };
}
```

## 3. Less boilerplate

### 3.1 Inject the session, don't parse the header in every method

Most controllers still take `@RequestHeader("Authorization")` and call `Requests.session(...)`. `LeaveController` is converted and is the pattern; new code and any controller you touch use the argument resolver and `@RequiresModule`:

```java
// Before
@GetMapping("/me/balances")
public List<Map<String, Object>> balances(@RequestHeader(value = "Authorization", required = false) String authorization) {
    return leave.balances(Requests.session(auth, authorization, "leave"));
}

// After
@GetMapping("/me/balances")
// @RequiresModule(Modules.LEAVE) is on the controller class
public List<Map<String, Object>> balances(SessionCtx ctx) {
    return leave.balances(ctx);
}
```

`SessionCtxResolver` supplies `SessionCtx` and applies `@RequiresModule` itself (parameter, then method, then class; `RequiresModule.NONE` opts out). Put `SessionCtx` first in the parameter list so the caller is identified, and `module.off` raised, before a request body is read. Precedence is tested once in `SessionCtxResolverTest`, not in every endpoint.

### 3.2 Typed rows instead of maps

`Sql.query` returns `List<Map<String, Object>>`, which spreads string keys and casts across the code (1,594 uses today). New queries map to records:

```java
record Balance(UUID leaveTypeId, String name, BigDecimal available) {}

List<Balance> rows = Sql.list(c, Balance.class, """
        select t.id as leave_type_id, t.name, sum(l.days) as available
        from app.leave_ledger l join app.leave_type t on t.id = l.leave_type_id
        where l.membership_id = ?
        group by t.id, t.name
        """, membershipId);
```

`Sql.list` (added in Q1) maps columns to record components by name (`leave_type_id` → `leaveTypeId`). JSON responses then come from the records, so the API shape is visible in one place.

### 3.3 Validation in one place

Request records carry Bean Validation annotations (`@NotBlank`, `@Size`, `@PastOrPresent`); `@Valid` on the controller parameter; `ApiExceptionHandler` turns violations into the same 400 answer the app gives today. Services keep only rules that need the database.

### 3.4 The frontend

- Data loading and saving go through shared hooks (`useLoad`, `useSave` in `hooks.js`), not `useEffect` plus `fetch` in each page.
- Forms with more than three fields use the shared form helpers; a new page does not re-implement field errors, busy state or toasts.

## 4. Clean code

| Rule | Limit | Enforced |
| --- | --- | --- |
| Class or component file | 400 lines; over that, split by responsibility | Review (warn in CI) |
| Method | 40 lines; one level of abstraction | Review |
| Parameters | 4; more becomes a record | Review |
| Nesting | 3 levels; use early returns | Review |

- **Names say what, comments say why.** A comment never repeats the code; it gives the rule, the law, or the reason a simpler way fails.
- **One service, one area.** A service over 400 lines is split by sub-area, as `ExitService` / `ExitSettlement` / `ExitTasks` already are. The current splits due are `JoinerService`, `ExitService`, `PayrollEngine`, `OnboardingConfigService`, `EmployeeService`, and on the frontend `AttendanceSetup`, `People`, `Joiners`, `PayrollSetup`.
- **Errors are written for the person reading them**: what is wrong and what to do ("The state is the two-letter code…"), through `ApiException`. Never a stack trace or a SQL message in a response.
- **No dead code, no commented-out code, no TODO without an issue number.**
- **No magic numbers**: a limit gets a named constant, or is rule data if the law sets it (Non-negotiable 3).

## 5. Database and performance

1. **No queries inside loops.** Read the set in one query (`where id = any(?)` with an array), write it with a JDBC batch or `insert … select from unnest(?)`. **(Review; the performance suite catches regressions)**
2. **Every new query is checked with `EXPLAIN ANALYZE`** on the 2,000-employee seed Organization; any sequential scan over a tenant table is justified in the PR.
3. **Every foreign key and every column in a `where` or `order by` on a large table has an index**, created in the same migration.
4. **Lists are paginated** with a limit (default 50, maximum 500) and a stable order.
5. **Writes that happen on every request are throttled**; for example, the session's `last_seen_at` is updated at most once a minute.
6. **Prefer joins to scalar subqueries per row** (for example, the display name from `Names.of`) on lists.
7. **Budgets (proposed, measured by the k6 suite):**

| Operation | Budget |
| --- | --- |
| Any read endpoint | p95 under 300 ms at 50 concurrent users |
| Any write endpoint | p95 under 500 ms |
| Payroll compute, 2,000 employees | Under 60 s |
| Attendance month view, 500 people | Under 1 s |
| Frontend route chunk | Under 60 kB gzipped |

**Example: a batch instead of a loop of inserts**

```java
// Before: one round trip per payslip line (2,000 employees × 20 lines = 40,000 statements)
for (var line : calc.lines()) {
    Sql.update(c, "insert into app.payslip_line (...) values (?, ?, ?, ...)", ...);
}

// After: one batch per payslip run
Sql.batch(c, "insert into app.payslip_line (...) values (?, ?, ?, ...)",
        calc.lines().stream().map(l -> new Object[] {tenantId, payslipId, l.position(), l.code(), l.amount()}).toList());
```

`Sql.batch` binds each row as `Sql.update` does and returns the number of rows written. It cannot return generated keys, so a parent row whose children need its id gets the id made in Java (`UUID.randomUUID()`), and the parents are written first, then the children (`PayrollRuns.calculate` writes payslips, then their lines, then exceptions). Session and run-level reads follow the same rule in reverse: read the set once (`select employment_id from app.pay_hold where run_id = ?`) and test membership in memory.

## 6. Testing

Every pull request adds or changes tests. A bug fix starts with a failing test that reproduces it.

| Layer | Tool | Rule |
| --- | --- | --- |
| Pure logic (formulas, day counts, tax, checks) | JUnit, jqwik property tests | Every branch; property tests for `Formula` and rounding |
| Services | JUnit against real PostgreSQL | Happy path, each refusal, and the audit event |
| Money | Golden payslips and statement files | A calculation change is a reviewed diff to a golden file, checked against the consultant's figures |
| Access | Cross-Organization sweep | Every endpoint refuses another Organization's ids (404 or 403) **(CI)** |
| Browser | Playwright | Each suite seeds its own Organization; runs in UTC and India time **(CI)** |
| Accessibility | axe in Playwright | No serious or critical violations on any page **(CI)** |
| Performance | k6 on the seed Organization | Budgets in section 5, weekly and before each gate |

**Coverage gates (CI, JaCoCo `check`)**

| Scope | Lines | Branches |
| --- | --- | --- |
| Whole backend (today 83% / 70%) | Never below the last release; +2 points per release up to 90% / 80% | Same |
| Payroll and tax classes (`Formula`, `PayrollEngine`, `PayrollRuns`, `PayrollConfig`, `Payslips`, `TaxDeclarations`, `ExitSettlement`) | 95% | 90% |

**Rules for reliable tests**

- Dates come from a test clock or are clamped (`withinMonth()`, `withinTaxYear()`); never "today + n" across a month or tax-year end.
- No sleeps; wait for a condition.
- A flaky test is fixed or quarantined the same day, with an owner and a date; quarantine longer than a week is a release blocker.

## 7. Frontend

- Every API call goes through `api.js`; no `fetch` elsewhere.
- One error boundary per page region; a crash shows a message and is reported, never a blank screen.
- Every input has a `<label>`; every clickable thing is a `<button>` or `<a>`, never a clickable `<div>`.
- No `dangerouslySetInnerHTML`.
- Text shown to people lives in the page's `*Text.js` file, ready for translation.
- Pages are lazy-loaded; no single route chunk over 60 kB gzipped.
- Logic outside components (navigation, routing, formatting) has Vitest unit tests.

## 8. Security and privacy

- New endpoints declare their permission and scope; the negative-access suite gets a case for each.
- Sensitive actions (approve a run, download a bank file, reveal PAN or bank details, change roles) require a recent MFA step-up (from R2).
- Logs carry ids, never names, PAN, bank numbers, amounts or document contents.
- Files are checked by content (`FileSniffer`), not by extension, before storage.
- Anything sent to an AI provider is redacted first, opt-in per Organization, and logged (see the AI ground rules in the roadmap).
- Secrets come from the environment; production refuses to start without `APP_DATA_KEY` (`APP_REQUIRE_DATA_KEY=true`).

## 9. Migrations

**Tools**

| Need | Tool | How |
| --- | --- | --- |
| Versioning and applying | **Flyway** (open source, with `flyway-database-postgresql`), run by `Migrator` | Same `V<n>__name.sql` files in `db/migration`; Flyway records each file's checksum in `flyway_schema_history` and refuses to start when an applied file was edited or a version is missing. Existing databases were baselined at V19 from `public.schema_migration`; `--check-migrations` is the dry run before a deploy (docs/database.md). |
| Script review (lint) | **Squawk** with its GitHub Action | Lints every new migration in a pull request and comments on it: dropping or renaming a column, adding a constraint without `NOT VALID`, an index without `CONCURRENTLY`, a type change that rewrites a table. **(CI)** |
| Breaking-change detection | Schema diff in CI | CI applies `main`'s migrations and the branch's to two empty databases, diffs `pg_dump --schema-only`, and posts the diff on the pull request. A removed or renamed column, table or function, or a tightened type or constraint, fails unless the PR carries the `breaking-change` label and a code owner approves. |
| Data safety | Migration tests | Every migration runs from an empty database and from an anonymized copy of the previous release's data. **(CI)** |
| Optional, paid | Atlas Pro | Adds destructive, backward-incompatible and locking analyzers if Squawk proves too narrow; `migrate lint` now needs Atlas Pro. |

**Rules**

- Forward only; one migration per release; never edit an applied migration. **(CI: Flyway validate)**
- **Expand, then contract.** A breaking change ships across two releases: release N adds the new column or table and writes both; release N+1 removes the old one once no code reads it. The app must run against both the old and the new schema during a deploy.
- Large tables change in steps: add the column, backfill in batches, then add the constraint (`NOT VALID`, then `VALIDATE CONSTRAINT`).
- Indexes on existing large tables are created `CONCURRENTLY`, in their own migration (no transaction).
- Every migration has a short header comment: what it changes, whether it is breaking, and how to undo it by hand if a deploy is rolled back.
- A signed-off rule row is never changed by a migration; changes go to a draft rule for sign-off.
- Migration pull requests are reviewed by a code owner (CODEOWNERS on `db/migration`).

## 10. Tooling

The checks below block a merge once in place. "In place" ones run today; the rest arrive in roadmap
release Q1.

| Tool | Checks | Status |
| --- | --- | --- |
| Flyway validate, checksum lock | Applied migrations unchanged (section 9) | In place |
| Squawk, schema diff | New migrations linted; breaking changes fail unless labelled (section 9) | In place |
| CODEOWNERS | Payroll, tax, migrations and security code request the owner's review | In place (approval enforced once there is a second reviewer) |
| oxlint | Frontend lint | In place (warnings as errors: Q1) |
| Spotless | Java and SQL formatting | Deferred: one formatting-only commit after the Q1 pull requests merge, listed in `.git-blame-ignore-revs` ([Q1 sign-off](roadmap/q1-signoff.md)) |
| Error Prone | Common Java bug patterns at compile time | In place (errors fail the build) |
| ArchUnit | `web` depends only on `core`; no SQL outside `core`; no new public method returns `Map<String, Object>`; controllers have no business logic | In place (existing violations frozen) |
| JaCoCo `check` | Coverage gates in section 6 | In place |
| Dependabot, OWASP dependency-check, `npm audit` | Dependencies, weekly | Dependabot in place; OWASP check is a weekly workflow that needs the `NVD_API_KEY` secret; `npm audit` not added |
| Pull request template | The checklist below | In place |

## 11. Review checklist

Copy into the pull request description and tick each line, or say why it does not apply.

**Correctness**
- [ ] Tests reproduce the bug or cover the new behaviour, including refusals
- [ ] Money changes come with golden-file diffs that match the consultant's figures
- [ ] Dates are safe at month end, 31 March and in both time zones

**Tenancy, security, privacy**
- [ ] New tables have `tenant_id` and forced row-level security
- [ ] New endpoints declare permission and scope, with a negative-access test
- [ ] Changes write audit events; identifiers stay encrypted and out of logs

**Java 21 and boilerplate**
- [ ] Records instead of maps for new requests, responses and rows
- [ ] Sealed types and pattern-matching `switch` for states and results
- [ ] Session injected (`SessionCtx` parameter), not parsed from the header
- [ ] Text blocks for SQL; `java.time` types in requests

**Performance**
- [ ] No queries inside loops; batches for multi-row writes
- [ ] New queries checked with `EXPLAIN ANALYZE` on the seed Organization; indexes added
- [ ] Lists paginated

**Migrations**
- [ ] Squawk clean, or each warning explained
- [ ] Schema diff reviewed; breaking changes follow expand-then-contract and carry the `breaking-change` label
- [ ] Header comment says what changes and how to undo it

**Clean code**
- [ ] Files, methods and parameters within the limits in section 4
- [ ] Comments explain why; no dead code or untracked TODOs
- [ ] Error messages say what is wrong and what to do

**Docs**
- [ ] The design page in `docs/` updated; README status updated if a limitation changed

## 12. Exceptions

When a rule cannot be met (a deadline, a library limit), the pull request says which rule, why, and the issue that will fix it, and a code owner approves. Non-negotiables in section 1 have no exceptions.
