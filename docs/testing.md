# Tests and coverage

Two automated suites run on every pull request and every push to main.

| Suite | Where | What it checks | Count |
| --- | --- | --- | --- |
| Backend | `backend/src/test` (JUnit, real PostgreSQL) | Services, rules and database security per module | 232 tests |
| Browser | `e2e/tests` (Playwright, Chromium) | The app end to end, screens and API, in India time and in UTC | 163 tests in 23 files |

Negative cases have files of their own: `13-negative-access` to `16-negative-payroll` for the main story, and `23-negative-tax-settings` for tax declarations, settings, devices and setup lists. Together with refusals inside the other tests, the browser suite asserts more than 230 refused requests; the backend tests assert several hundred more.

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

## Measuring it

**Backend tests.** `mvn verify` in `backend` writes `target/site/jacoco/index.html`. CI prints the totals in the job summary and keeps the report as the `backend-coverage` artifact.

**Browser tests.**

1. Start the backend with the JaCoCo agent (`-javaagent:…/org.jacoco.agent-0.8.13-runtime.jar=destfile=e2e.exec`).
2. Run the suite with `E2E_COVERAGE=coverage-raw npm test` in `e2e`, so every page records the frontend code it ran.
3. Stop the backend so it writes `e2e.exec`.
4. Run `npm run coverage -- coverage-raw` for the frontend report.

**Both suites together.** Merge `backend/target/jacoco.exec` and `e2e.exec` with the JaCoCo command-line tool (`merge`, then `report`).
