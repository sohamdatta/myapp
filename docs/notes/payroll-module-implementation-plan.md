# Payroll Module — Implementation Plan (India)

Full plan with diagram and schema: https://claude.ai/code/artifact/58abde2e-42de-4809-a659-ddd806b61c69
Dated: 7 Sep 2026

Sits below `payroll-feature-checklist.md` (what) and inside Stage B–C of `solo-developer-build-plan.md` (when). This is the how.

**12 sub-modules · 355 hours · ~24 weeks at 15 hrs/week · one state, one entity.**

## Seven decisions that are expensive to reverse

Make these before the first migration. Six of them are visible as columns in the schema.

1. **Components declare which statutory bases they enter.** Flags on each component — `is_pf_wage`, `is_esi_wage`, `is_bonus_wage`, `is_gratuity_wage`, `is_taxable`, `exemption_section` — and the engine sums by flag, never by component name. Skip it and every unusual salary structure needs an engineer.
2. **Compensation is bitemporal, never updated in place.** `valid_from`/`valid_to` + `recorded_at`/`superseded_at`. Skip it and retro pay, audit and historical reporting all die at once.
3. **A run is a pure function of its snapshot.** Freeze copies everything into an immutable snapshot; the calculator reads only that. Skip it and you lose the variance report, the parallel-run reconciliation and reproducibility.
4. **Statutory rules are versioned data keyed by jurisdiction and date.** Resolution by the *period's* date, never today's.
5. **Every payslip line records its rule version.** One FK. It is the entire lineage feature later.
6. **Money is integer paise.** Never float, never rounded at the edges.
7. **Rounding is a declared property** per component and per rule, with the pre-rounding value stored.

## The calculation kernel

`freeze snapshot → topologically sort components → evaluate earnings (sandboxed formulas, prorated) → sum statutory bases by flag → resolve rule versions by jurisdiction + period date → evaluate statutory components → round and emit lines carrying rule_version_id → lock`

Steps 2–7 are a pure function: same snapshot + engine version + ruleset version → byte-identical output. Nothing in the pipeline reads the current date, and nothing lets a user edit a computed number.

## Sub-modules

| ID | Name | Hours | The hard part |
|---|---|---|---|
| P1 | Run lifecycle | 18 | Enforcing the state machine in the DB, not just the app |
| P2 | Components and structures | 25 | Components are themselves effective-dated |
| P3 | Compensation ledger | 22 | Index for as-of queries; partial index on `superseded_at IS NULL` |
| P4 | Inputs and freeze | 25 | Idempotent re-import — the same file uploaded twice must replace, not double |
| P5 | Calculation kernel | 35 | Dependency cycles — detect at structure-save time, not at run time |
| P6 | Rule engine framework | 20 | Overlapping effective ranges; use an exclusion constraint |
| P7 | Rule packs | 79 | See below |
| P8 | Arrears and retro | 20 | Statutory recomputed on revised wages; an arrear can cross the ESI threshold retrospectively |
| P9 | Validation and exceptions | 15 | Discipline, not tech — they must genuinely block |
| P10 | Outputs | 61 | Return file formats: fixed-width, position-sensitive, unhelpful portal errors |
| P11 | Off-cycle and settlement | 20 | Final tax — the exit month closes the year, excess must be refunded |
| P12 | Employee views | 15 | — |

### P7 rule packs (79h)

| Pack | Hrs | Why it's harder than it looks |
|---|---|---|
| P7a PF | 12 | Above-ceiling policy per employer *and* per employee; deemed-wage basis; pension eligibility exclusions |
| P7b ESI | 10 | Contribution-period stickiness — most commonly wrong calculation in the category |
| P7c PT | 6 | One state, but the model must already handle non-monthly cycles and a YTD ceiling |
| P7d LWF | 4 | Trivial arithmetic, awkward cycles |
| P7e Min wage | 5 | Blocking validation by state/zone/skill/employment |
| P7f Income tax | 28 | Largest item. Both regimes, monthly re-projection, declaration/proof lifecycle, surcharge with marginal relief |
| P7g Gratuity | 6 | Payout on exit with deemed wages and exemption limit |
| P7h Bonus | 8 | Eligibility on one wage figure, computation on a different capped one |

Work packs **one at a time, end to end**: consultant's spec → fixtures from their hand-worked examples → implementation → their sign-off against a real register. Half-finished packs across five statutes means nothing runs.

Consider shipping income tax last within the statutory set, with a manual override field in the interim.

## Release train

| Release | Contains | Hrs | Weeks | Can do |
|---|---|---|---|---|
| R1 Calculates | P1–P6, P7a | 157 | 10.5 | Payslip with earnings + PF, re-runnable and diffable |
| R2 Statutory | P7b–P7e, P9 | 40 | 2.7 | Everything but income tax, approval blocked on exceptions |
| R3 Taxed | P7f, P8 | 48 | 3.2 | **Complete monthly calculation — the parallel-run milestone** |
| R4 Filed | P10 | 61 | 4.1 | Customer can stop using their old system |
| R5 Complete | P7g, P7h, P11, P12 | 49 | 3.3 | Gratuity, bonus, settlements, employee surface |

**R3 is the gate.** Two consecutive months reconciling to the rupee before going further. If it doesn't reconcile, the answer is more fixtures — never a patch to make one month agree.

R1 is ~10 weeks of evenings before anything visible. Mitigate by writing the fixture harness in week one and letting a growing green suite be the visible progress.

## Fixture plan

Two kinds of case, no others:
- **Hand-worked** (~60 by R3) — from the consultant's signed-off spreadsheet. These carry the authority.
- **Regression** (~3 full runs) — real registers, output frozen once verified.

Required cases per pack: PF (below/above ceiling restricted/unrestricted, deemed wages, pension-ineligible, mid-month joiner, LOP) · ESI (threshold crossings mid-period and at boundary, daily-wage exemption) · PT (each slab boundary, YTD ceiling in month 11, nil state) · Tax (both regimes same employee, mid-year increment, partly unproven declaration, previous employer income, marginal relief) · Proration (same joiner under all three methods) · Arrears (2-month-back revision with PF and tax recomputation) · Rounding (case where sum of rounded lines ≠ rounded sum).

**Never change a fixture's expected value to make a test pass.** Either the rule changed — consultant signs off a new expected value with a recorded reason — or you have a bug.

## Week one (15 hrs) — none of it is P1

1. Write the fixture runner first (4h). Everything after is safer.
2. Turn the consultant's spreadsheet into the first ten cases. They all fail. That's correct.
3. Write the schema for the seven decisions as one migration on empty tables (6h).
4. Decide the proration default and write it where a customer conversation can reach it.
