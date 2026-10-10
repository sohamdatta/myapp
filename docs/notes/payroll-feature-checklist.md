# Indian Payroll — Feature Checklist

Full tiered list with filters: https://claude.ai/code/artifact/8f17ae3f-d0a9-48e8-b550-261cd6d7da9b
Dated: 30 Aug 2026 · **corrected 7 Sep 2026 for the Form 130/138 renumbering**

107 features. **45 cannot ship without · 41 won't sell without · 21 later.** Plus 11 traps.

## Tiers

- **Cannot ship without** — one payroll cannot be produced correctly at all
- **Won't sell without** — an evaluating customer walks
- **Later** — real demand, deferrable past your first ten customers

## 1. Setup and masters

**Core:** legal entity (PAN/TAN/CIN) · statutory registration numbers (PF establishment code, ESI code, PT registration per state, LWF, TDS deductor) · work locations carrying state/jurisdiction · payroll calendar (cut-off, pay date, proration month basis) · employee identity (PAN, Aadhaar masked, UAN, ESIC IP, DOB, DOJ) · bank details with IFSC validation · effective-dated employment/assignment · statutory eligibility flags (PF above-ceiling policy, EPS eligible, ESI applicable, PT state, disability, international worker)

**Sell:** departments/grades/cost centres · bulk import with column mapping (highest-leverage feature for winning deals) · document vault

**Later:** multiple entities in one run — *but note the competitive research: everyone gates or surcharges this, so including it in the base tier is cheap differentiation*

## 2. Salary structure

**Core:** component library (earning/deduction/employer contribution/reimbursement/non-payable) · formula-driven components with dependency ordering · per-component proration behaviour · effective-dated compensation history (never a mutable salary column)

**Sell:** CTC breakup and reverse derivation · structure templates by grade · flexible benefit basket · increment/revision workflow with retro effect

**Later:** salary revision letters

## 3. Monthly inputs

**Core:** days paid and LOP · joiners and leavers with part-month proration · one-time payments and deductions · arrears · input freeze after cut-off

**Sell:** overtime at statutory rate · loans and advances with EMI schedule · reimbursement payout · salary hold and release

**Later:** variable pay/commission plans · shift, night and on-call allowances

## 4. The statutory engine — this IS the product

Build as **versioned data keyed by jurisdiction and date**, never `if` statements.

**Provident fund** — 12% employee; employer 12% split EPS 8.33% (capped) + EPF 3.67% · ₹15,000 ceiling plus the employer's restrict-or-not policy · deemed-wage basis under the Code on Wages · EDLI and admin charges · EPS eligibility exclusions for new members above the ceiling · VPF · ECR file by the 15th · UAN and exit-date marking · international workers (later)

**ESI** — ₹21,000 gross threshold (₹25,000 disability); 0.75% employee / 3.25% employer · **contribution-period stickiness** · low-wage exemption from employee share · monthly file + half-yearly returns · non-implemented areas (later)

**Professional tax / LWF** — per-state slabs · non-monthly cycles (TN half-yearly, some annual) · ₹2,500/yr constitutional ceiling tracked YTD · nil states as a first-class case · LWF with its own per-state cycles · challans and registers

**Income tax** — both regimes with employee election (new is default: ₹75,000 standard deduction, slabs from ₹4L, 87A rebate ₹60,000 giving nil tax to ₹12L; old: ₹50,000 standard deduction, ₹12,500 rebate to ₹5L) · annual projection with monthly re-spreading · rebate, surcharge with marginal relief, cess · investment declaration · proof submission and disallowance · HRA/LTA/Chapter VI-A · previous employer income · other income and house property loss · **Form 138 quarterly and the Part C computation feeding Form 130** · perquisites (later) · regime comparison calculator (later)

**Gratuity / bonus / floors** — minimum wage validation per state/zone/skill as a hard block · gratuity accrual 4.81%, 5-year eligibility, deemed-wage basis, ₹20L exemption · statutory bonus: eligibility to ₹21,000, computed on ₹7,000 or state minimum wage whichever higher, 8.33%–20%, payable within 8 months of year end · set-on/set-off (later) · leave encashment provisioning (later)

## 5. Calculation

**Core:** deterministic and re-runnable (pure function of snapshot + engine version + ruleset version) · immutable once finalised · configurable proration engine · per-component and per-statute rounding · negative net pay blocked

**Sell:** off-cycle runs · dry run / what-if · variance against previous cycle

**Later:** line-level lineage

## 6. Controls

**Core:** pre-approval exception report · salary visibility restriction · append-only audit trail
**Sell:** maker–checker · role-based access
**Later:** multi-level approval chains

## 7. Outputs

**Core:** payslip PDF (password protected, YTD) · salary register · bank payment file · statutory contribution summaries
**Sell:** return files (PF ECR, ESI contribution file, **Form 138** quarterly) · **Form 130 distribution** — TRACES generates it from your filed Form 138; what you owe is the detailed Part C salary computation and the distribution · accounting journal · cost reports
**Later:** statutory registers under the labour codes · provision reports

## 8. Employee-facing

**Sell:** payslip access and history · tax declaration and proof upload · **Form 130 download** (Form 16 for FY 2025-26 and earlier) · bank/profile updates with cut-off
**Later:** "why my pay changed" · reimbursement claims

## 9. Exit and F&F

**Sell:** full and final statement (final salary, leave encashment, gratuity, notice recovery, loans, assets, final tax) · exit statutory marking for PF/ESI
**Later:** clearance workflow · relieving and experience letters

## 10. Integrations

**Core:** spreadsheet import/export — the universal integration
**Sell:** attendance import in the customer's device export format
**Later:** accounting sync · bank API disbursement (via licensed partner) · public API and webhooks

## 11. The eleven traps

1. **Proration method** — calendar days vs working days vs fixed 30. Configurable, and visible on the payslip.
2. **ESI contribution-period stickiness** — coverage runs in fixed half-year blocks; crossing the threshold mid-period does not drop coverage. Naive implementations get every mid-year increment wrong. *(Competitive research: exactly one vendor of twelve documents this. Say it out loud.)*
3. **Arrears are not a difference** — PF, ESI and TDS must be recomputed on revised wages for the affected months.
4. **Tax projection is a moving target** — monthly re-projection, not an annual calculation. A December regime switch re-opens the year.
5. **Mid-month salary revision** — split period, prorate each half, apply statutory rules to the combined total. Different code path.
6. **Rounding** — each statute rounds its own way; the sum of rounded components ≠ the rounded sum. A rupee out fails a return upload.
7. **LOP flows into everything** — reduces wages → reduces PF → changes ESI eligibility → changes tax projection.
8. **The above-ceiling policy** — restrict PF to ₹15,000 or compute on full wages is the employer's choice, often per employee. Both paths plus an override.
9. **Interstate employees** — transfer changes PT jurisdiction, LWF, minimum wage floor, holiday calendar. Which state applies in the transfer month is a policy question needing an answer.
10. **The forms were renumbered** — under the Income Tax Act 2025, **Form 24Q became Form 138** and **Form 16 becomes Form 130** (s.395(4)(b)) from tax year 2026-27. The Q1 FY27 return already had to use Form 138. Form 130 has three parts rather than two, Part C carries a detailed salary computation Form 16 never required, and it is **TRACES-generated only** — issued after Form 138 is filed and processed, never offline. Form 16 remains correct for FY 2025-26. *Nine of twelve vendors still advertise the old numbers; don't copy them.*
11. **The first employee without a PAN** — higher deduction, no certificate issuable, the return rejects the record. Have the answer before a customer's first run.

## The irreducible version

One entity, one state, correctly:

| Area | Minimum |
|---|---|
| Masters | One entity + registrations, one state, employees with PAN/UAN/bank/DOJ |
| Structure | Components with formulas and ordering, effective-dated comp, per-component proration |
| Inputs | Days paid and LOP, joiners/leavers, one-time payments, arrears — by spreadsheet |
| Statutory | PF with deemed-wage basis, ESI with period stickiness, one state's PT, tax projection both regimes, minimum wage validation |
| Engine | Deterministic re-runnable, immutable once approved, rounding, negative net blocked |
| Controls | Exception report, audit trail, salary visibility |
| Outputs | Payslip PDF, salary register, one bank format, statutory summaries |
| Employee | Payslip access only |

**Build the statutory engine first and the interface last.** A plain interface with correct numbers sells; a beautiful one with a wrong PF calculation is a liability.

---

*Correction log: 7 Sep 2026 — Form 16/24Q references updated to Form 130/138 following the Income Tax Act 2025 renumbering, effective tax year 2026-27. Trap 10 rewritten. See `competitive-landscape-india-payroll.md`.*
