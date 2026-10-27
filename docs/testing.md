# Tests and coverage

Two automated suites run on every pull request and every push to main.

| Suite | Where | What it checks | Count |
| --- | --- | --- | --- |
| Backend | `backend/src/test` (JUnit, real PostgreSQL) | Services, rules and database security per module | 282 tests |
| Browser | `e2e/tests` (Playwright, Chromium) | The app end to end, screens and API, in India time and in UTC | 188 tests in 25 files |

Negative cases have files of their own: `13-negative-access` to `16-negative-payroll` for the main story, and `23-negative-tax-settings` for tax declarations, settings, devices and setup lists. Together with refusals inside the other tests, the browser suite asserts more than 230 refused requests; the backend tests assert several hundred more.

## Property tests

`FormulaPropertiesTest` uses [jqwik](https://jqwik.net) to generate thousands of inputs for the payroll formula language, where a hand-picked example shows only the cases someone thought of. It checks that:

- any text, including arbitrary Unicode and shuffled formula parts, is either accepted or refused with a `Formula.Error` whose position lies inside the text, and nothing else is ever thrown;
- generated well-formed formulas evaluate to what a separate reference evaluator computes, including the divide-by-zero flag and which components and facts were read;
- precedence, left-to-right order and unary minus behave as written;
- `round`, `round_up` and `round_down` land on a multiple of the step, in the right direction;
- the 2,000-character and nesting limits hold.

A failing property prints its seed. To reproduce it, put the seed in `@Property(seed = "...")` or run with `-Djqwik.seeds.whenfixed=true`. Settings are in `backend/src/test/resources/junit-platform.properties`; jqwik's record of past failures goes under `target/` and is not committed. When adding a property, break the code under test once on purpose and confirm the property fails.

## Coverage (24 Oct 2026)

| What | Lines | Branches |
| --- | --- | --- |
| Backend, backend tests only | 83.4% | 69.5% |
| Backend, browser tests only | 78.5% | 57.1% |
| **Backend, both suites together** | **91.1%** | **75.8%** |
| Frontend, browser tests | 83.4% | 77.1% |

API endpoints: 280 of 285 are called by at least one test. The rest are reached only through screens (sign-in, the invitation preview) or not at all (removing a settlement line).

Weakest areas, worth tests next:

- Payroll formula parser errors (`Formula`, 70% of lines)
- Exit tasks (`ExitTasks`, 73%)
- Joiner form sections (`JoinerSections`, 81%)
- The setup screens for flows and lists (`SetupFlows.jsx`, `SetupLists.jsx`, about 60%)
- The web layer's error branches (45%)

## The coverage gate

`mvn verify` fails when backend coverage drops below a floor set in `backend/pom.xml` (the `check` execution of the JaCoCo plugin). The floors are the numbers measured on 26 Oct 2026, rounded down with a point of room, so today's code passes and a change that removes tests, or adds code without them, does not.

| Scope | Lines | Branches |
| --- | --- | --- |
| Whole backend | 83% | 68% |
| `Formula` | 58% | 39% |
| `PayrollConfig` | 61% | 39% |
| `Payslips` | 32% | 16% |
| `PayrollRuns` | 87% | 64% |
| `PayrollEngine` | 89% | 70% |
| `ExitSettlement` | 85% | 65% |
| `TaxDeclarations` | 93% | 77% |

The rule for changes: a pull request that adds tests raises the floors it made possible, in the same pull request. The payroll and tax classes must reach 95% lines and 90% branches by Gate B; `Payslips` and `PayrollConfig` are far from that and need tests of their own, not just a higher number. The web layer has no controller tests of its own (the browser suite covers it), so the whole-backend floor counts it as uncovered by JUnit.

## Measuring it

**Backend tests.** `mvn verify` in `backend` writes `target/site/jacoco/index.html`. CI prints the totals in the job summary and keeps the report as the `backend-coverage` artifact.

**Browser tests.**

1. Start the backend with the JaCoCo agent (`-javaagent:…/org.jacoco.agent-0.8.13-runtime.jar=destfile=e2e.exec`).
2. Run the suite with `E2E_COVERAGE=coverage-raw npm test` in `e2e`, so every page records the frontend code it ran.
3. Stop the backend so it writes `e2e.exec`.
4. Run `npm run coverage -- coverage-raw` for the frontend report.

**Both suites together.** Merge `backend/target/jacoco.exec` and `e2e.exec` with the JaCoCo command-line tool (`merge`, then `report`).

## Golden payslips

`GoldenPayslipsTest` runs the pure payroll engine on 18 fixed scenarios and compares the whole payslip with a file: gross, deductions, net, employer cost, every line in order, and the exceptions raised. The scenarios are in `backend/src/test/resources/golden/payslips` (`NN-name.json`, with a shared `config.json`) and cover a full month, a joiner and a leaver part-way through, loss of pay (whole and half days), a one-time arrears payment, PF at the ceiling and on full wages, ESI covered and over the threshold, professional tax in February and by state and gender, labour welfare fund, metro and non-metro HRA, a state with no rules, and the new and old tax regimes. Each file says in `why` what it shows, and for the tax scenarios the hand calculation.

A change to any payroll figure fails the matching scenario with a message such as `16-high-pay-new-regime.json: deductions changed from 25907.00 to 22492.00`. If the change is intended, regenerate the figures and put the diff in the pull request for the code owner to read:

```
mvn -f backend/pom.xml test -Dtest=GoldenPayslipsTest -Dgolden.update=true
```

Updating refuses to run in CI. The harness also fails if a scenario is deleted.

**Status: provisional.** The figures were produced by the engine and checked by hand against the arithmetic in each scenario, but not yet against the payroll consultant's spreadsheet. When they are, replace the figures with the consultant's, set `"status": "signed-off"` and add the date. Payslip lines are not the whole run: pay holds, reopened runs and arrears over several months involve the database and are covered in `PayrollTest`.

A question for the consultant that the scenarios raise: whether annual tax is rounded to the whole rupee (as now) or to the nearest ten rupees (scenario 17).

## Dates that only break on some days

Regular CI runs on the day of the push, so a test or a rule that fails only near a month or tax-year end goes unseen until that day. Two guards cover this:

- **Fixed-date run** (`.github/workflows/fixed-date.yml`), every day at 02:00 India time and on demand (not on pull requests): the backend tests and the browser tests run with the server's clock moved to 30 December, 26 February and 28 March. libfaketime moves PostgreSQL and the JVM by one shared offset, so they agree and time still passes; the JVM needs `FAKETIME_DONT_FAKE_MONOTONIC=1`, or its internal timers stall. The Playwright runner and the browser keep the real clock: the tests read "now" from `nowMs()`, which adds `E2E_CLOCK_SHIFT_SECONDS`, and each browser context's clock starts there (`e2e/tests/helpers.ts`). Tests should use `nowMs()`, `isoDay()` and `currentYear()`, never `Date.now()` or `new Date()` directly.

  To try it locally (Linux, `apt install faketime`): compute the offset in seconds, start PostgreSQL and the backend with `LD_PRELOAD=/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1 FAKETIME=+<seconds> FAKETIME_DONT_FAKE_MONOTONIC=1`, then run `E2E_CLOCK_SHIFT_SECONDS=<seconds> npx playwright test`.

- **Browser-test dates check** (`e2e/check-dates.mjs`, the "Browser-test dates" job on pull requests): a line a pull request adds may not use `isoDay(n)` with n above 5 days. Use `withinMonth(n)` or `withinTaxYear(n)` from `e2e/tests/helpers.ts`, or end the line with `// date-safe: <why>` when the date is meant to be far away.

## The cross-Organization sweep

`CrossOrgSweepTest` starts the whole application on a random port with its own database (`hrms_test_crossorg`), seeds two Organizations (users, a location, a shift, a leave type, a balance and a request in each), and calls **every GET route under `/api/` except `/api/platform`** as an administrator of one, with the other's row ids in the path. The routes come from `RequestMappingHandlerMapping`, so a new endpoint is covered without anyone adding it.

What it asserts is about the **body**, not the status: no row id from any tenant table of the other Organization, and not its name, may appear in any response, whatever the status code. A 5xx also fails. It also checks it is not blind: an Organization's own ids must show up in its own responses.

- The framework's own error body repeats the request path (`"path":"…"`); that fragment is removed before scanning, nothing else is.
- Required query parameters are sent with a plausible value (an id, a date, a month or a year) so the request reaches the handler instead of stopping at a 400.
- **Mutation check done once:** switching row-level security off on `app.leave_type` makes it fail with a 200 from `/api/org/leave/balances` carrying the other Organization's leave type id.
- **Not covered yet:** POST/PUT/DELETE with the other Organization's ids; routes whose useful data needs modules other than users, attendance and leave to be seeded (payroll, exits, performance rows are only reached if the seed creates them); `/api/platform`.

## Page crashes and the client-error report

Each page region (the whole app, the menu, the bell, the page itself) sits in a `RegionBoundary`. A crash shows
"Something went wrong on this page" with Try again, leaves the rest of the app working, and is reported to
`POST /api/client-errors` (page id, region, a message blanked of emails, ids, long tokens and numbers, and the build).
Errors outside any boundary (handlers, timers, promises) are reported with region `window`; extension and cross-origin
"Script error." noise is dropped in the browser.

- The endpoint needs no sign-in, always answers 204, keeps nothing about the user or Organization, takes 10 reports a
  minute per address and 300 in all (turned-away reports are counted in a warning in the log), and clears rows after 90 days.
  Caddy caps the body at 8 KB. Read the reports with `select * from auth.client_error order by id desc`.
- `ClientErrorsTest` covers cleaning, limits and clearing; `e2e/tests/31-error-boundary.spec.ts` (project `boundary`) makes
  the Users page crash with a bad response and checks the alert, the rest of the app, the report, recovery and de-duplication.
- Only the Users page crash has been exercised end to end; the other boundaries are the same component.

## The accessibility scan

`e2e/tests/30-accessibility.spec.ts` (Playwright project `a11y`, tagged `@a11y`) runs axe (`@axe-core/playwright`, WCAG 2.0/2.1 A and AA) over the sign-in page, the invitation page, and **every sidebar page** that the Super Admin, an Org Admin and an employee can reach, plus the Org Admin's pages at 390 px. Pages are found from the sidebar, so a new page is scanned without anyone adding it. It makes its own Organization and needs no other spec.

- **The rule:** no serious or critical violation on any page (engineering standards, section 6). The baseline `e2e/a11y-baseline.json` is empty; if a violation must be tolerated for a while, it is recorded there by page and rule with a count of elements, and the test fails only when a count rises or a new one appears. `E2E_A11Y_UPDATE=1` rewrites the file; the test prints when counts have fallen.
- Moderate and minor findings are attached to the report (`minor-and-moderate.json`), not failed on. The selectors of serious findings are printed and attached (`where.json`).
- What it scans is each page as first drawn (after its loading skeleton has gone), not every tab, dialog or state inside it. Colour contrast in dark mode and keyboard order are not covered.
- **Found on first run and fixed here:** the loading skeleton had an `aria-label` on a plain `div` (now `role="status"`); the logo file input had no label; the audit log's scrollable table could not be reached by keyboard.
- The fixed-date workflow skips it (`--grep-invert @a11y`).
- **Mutation check done once:** removing the skeleton's role made the scan fail with `aria-prohibited-attr`.

## Scale check (2,000 employees)

Budgets from the roadmap: payroll calculation for 2,000 employees under 60 s; reads at the 95th percentile under 300 ms.

- `PayrollScaleTest` (does nothing unless `SCALE_TEST=true`) seeds one Organization through the services, then times
  `PayrollRuns.calculate` and the People list and search (service level, no HTTP). It leaves the database `hrms_test_scale`
  with `hr@scale.test` (password `correct-horse-battery`) in place.
- `perf/k6/reads.js` signs in as that person and reads the People list, a search, the org chart, payroll runs and `/api/auth/me`
  with 30 virtual users for a minute, per-request p95 thresholds tagged by name.
- The **Scale check** workflow (Actions, run by hand) does both on a runner. Budgets only print until someone runs it with
  *enforce* on; turn it on for good once the first real figures are in the table below.

| Date | Where | Payroll calculate, 2,000 | People list p95 | k6 reads p95 | Notes |
| --- | --- | --- | --- | --- | --- |
| 2026-10-27 | GitHub Actions runner (first run, [log](https://github.com/sohamdatta/myapp/actions/runs/40385478034)) | 1.75 s | 34 ms (service level) | **382 ms over all five reads, 30 users (budget 300)** | HTTP, through the real app; no per-read split yet; seeding 76 s |
| 2026-10-27 | same runner, People-list fix branch | 1.75 s | 34 ms (service level) | **p95 per read at 30 users: people list 528 ms, people search 460 ms, org chart 372 ms, payroll runs 286 ms (budget 300); all five 426 ms** | the runner has 2 vCPUs shared by k6, the app and PostgreSQL, so this overstates a server's latency, but even the trivial `payroll runs` read sits at the budget, which points at per-request cost (session touch, row-level-security set-up) as well as payload size; not investigated yet |
| 2026-10-26 | developer container, local PostgreSQL | 1.6 s | 62 ms (service level) | not run | seeding takes 141 s; employees have no attendance, leave, holds or arrears, so this is a floor, not the real month |

Findings so far: `EmployeeService.list` stopped at 500 rows with no signal, so an Organization of 2,000 could not see (or search)
everyone on the People list. Fixed: the cap is 5,000 and the list sends `X-Total-Count`; the People page says "Showing the first N of M" when it is cut off
(`EmployeeTest.aListCutOffByTheLimitSaysHowManyMatch`). The joiners and exits lists still stop at 500. The org chart answers with 315 KB for 2,000 people and is not fixed.
**Read budget: not met on this runner.** Next step is to find where a trivial read spends its time before changing anything.
Not covered yet: sign-in under load (password hashing), attendance month view, payslip release, readers during a calculation,
and a run on server-sized hardware.

## Sign-in limits

Wrong passwords are counted per email address in the database (`auth.login_failure`, V28), whether or not an account has that
address: as many as the platform's `auth.lockout.after_failures` within `auth.lockout.minutes` and the address is refused
(429 `auth.locked`, no minutes in the message) with the same answer, at the same point, for a real account and a stranger. A
per-email advisory lock makes parallel guesses count one by one. The password is hashed whatever the account's status, so a
suspended account is not quicker to refuse. Accepting an invitation with an existing login's password counts the same way.
`RateLimits` (in memory) holds only the per-client-address limits (100 failures in 15 minutes; IPv6 grouped by /64) and the
invitation-link limits (30 failed accepts, 120 looks per address). **Those in-memory limits are per application instance and reset on
restart**; move them to the database before running a second instance. **Accepted trade-off:** because the lock is per email,
anyone who knows an address can keep it locked out by failing on purpose. Changing that means a CAPTCHA or per-address+email
keys, which we have not done. `RateLimitsTest` and `SignInLimitsTest` cover all of this.

## Email outbox

Mail that has to arrive is written to `mail.outbox` in the same transaction as its cause (`MailOutbox.enqueue`), encrypted
(`Crypto.seal("mail", ...)`) and emptied once sent. `MailWorker` sends every 5 s through `SmtpMailSender`, waiting 1 m, 5 m, 30 m,
2 h, 6 h after failures, then `failed` (a person retries it on the platform Email page). A 5xx answer fails at once. Rows are
claimed with `for update skip locked`, so two instances never send one message twice at the same moment; delivery is still
at-least-once if a server stops mid-send. With no `APP_SMTP_HOST` there is no sender and mail waits. `MailOutboxTest` covers
encryption at rest, dedupe, suppression, backoff, permanent failure, a dead worker's claim, four parallel workers and the
tenant connection's inability to read the table. Settings come from the environment, not the database, so the password never
sits in a table. The browser tests will read a real SMTP sink (Mailpit) from the password-reset PR onward.
