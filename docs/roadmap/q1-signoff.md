# Q1 Quality hardening: what is done, what is not, what a person must do

As of 26 Oct 2026. Pull requests are named by number; "merged" means on `main`.

## Done and merged

Coverage gate (JaCoCo), jqwik property tests for the formula language, golden payslips (18 scenarios), ArchUnit rules
(existing violations frozen), Error Prone at error level, the boilerplate base (`SessionCtx`, `@RequiresModule`, batching and
session-touch fixes), the cross-Organization sweep, Dependabot, the Vite fix, the pull request template, CODEOWNERS, Flyway with
checksum lock, Squawk and schema diff, the fixed-date workflow repaired (and a real parser stack overflow it found, fixed).

## Open pull requests (merge when CI is green)

| PR | What |
| --- | --- |
| #44 | Accessibility scan (axe) in its own project, three real accessibility bugs fixed |
| #46 | CERT-In: Caddy JSON access log, daily log shipping, NTP check, incident runbook |
| #47 | Restore drill script and log (stacked on #46) |
| #48 | Error boundaries per page region, client crash reports (V27) |
| #49 | 2,000-employee scale check (payroll test, k6 reads, manual workflow) |
| #50 | OWASP dependency-check, weekly |
| #51 | Deploy images pinned to Java 21 |

## Deliberately deferred, and why

- **Spotless.** Each touched file would be reformatted whole, burying payroll and tax diffs that CODEOWNERS exists to review, and
  conflicting with the open branches. Do it once after the PRs above merge: formatting-only commit, listed in
  `.git-blame-ignore-revs`, golden and property tests re-run.
- **Test-only seed API and splitting the remaining browser suites.** An endpoint that creates Organizations without sign-in is
  the biggest security risk on the list. If built: its own Spring profile, startup refusal when it meets a production
  database, a CI check that the release jar lacks it, a shared-secret header. The suites already run in separate projects with
  their own Organizations where converted (#43, #44, #48); the rest can seed through the existing admin APIs.
- **Hot-spot fixes (batching payslip inserts, pay holds in one query).** The scale check shows payroll for 2,000 employees
  calculates in 1.6 s against a 60 s budget, so there is no evidence yet that they are needed. Re-measure on a real month
  (attendance, leave, holds) first.

## Findings from this work, not fixed

- The People list is cut at 500 rows, so an Organization of 2,000 cannot see everyone (no paging).
- The org chart answers with 315 KB for 2,000 people.
- A leave request crossing 31 Dec / 1 Jan: should January days come from next year's balance? Needs a decision.
- Golden payslip figures were produced by the engine and checked by hand; they are **provisional** until the consultant confirms
  them (scenario 17 has a ₹1 rounding question).

## What only a person can do

- [ ] Payroll consultant: compare the 18 golden payslips with their spreadsheet; change `status` to `signed-off`.
- [ ] Add the repository secret `NVD_API_KEY`, run the Dependency check workflow once, triage the findings.
- [ ] Run the Scale check workflow once; paste the numbers into the table in `docs/testing.md`; then turn *enforce* on.
- [ ] CERT-In: everything in [security/cert-in.md](../security/cert-in.md) (bucket in an Indian region with a 180-day rule, chrony,
      the Point of Contact registration, the rehearsal).
- [ ] A real, timed restore drill on UAT and on production ([restore-drills.md](../deploy/restore-drills.md)).
- [ ] Decide the Java version for production on purpose (#51).
- [ ] Decide the People list paging and the January-leave question above.
