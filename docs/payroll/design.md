# Payroll — design, as built

The live design is the claude.ai document [Payroll module — configurable design](https://claude.ai/code/artifact/0af98c52-4583-4973-ac9d-be1a41213738) (Main and Detailed design tabs). This page records what release 1 built from it, where the build differs, and what is still to come. The schema is `backend/src/main/resources/db/migration/V13__payroll.sql`; it is the source of truth.

## What release 1 does

| Area | Built |
| --- | --- |
| Setup | Employer details (legal name, PAN, TAN, address, the HDFC salary account debited); PF, ESI, PT and LWF registrations; proration (calendar days, working days or a fixed 30); whether absence is loss of pay; PF restricted to the ceiling; metro locations for HRA; the variance and one-time thresholds that raise exceptions; release payslips on payment or by hand |
| Components | Earnings, deductions, employer contributions and information lines. Each is a formula, a fixed amount, an amount entered each month, or worked out by law. Each says whether it is wages under the Code on Wages (always, never, or only to bring wages up to half of pay), counts for ESI, is taxable, is prorated, is one-time, how it rounds, and its ledger. Every change is a version from the first of a month; earlier months keep the version they were paid with |
| Formulas | `if`, `case`, `min`, `max`, `abs`, `round`, `round_up`, `round_down`, `table("name", key)`, `ytd(COMPONENT)`; components in capitals, facts in lower case (`location.state`, `location.metro`, `designation`, `department`, `gender`, `employment.type`, `days.paid`, `days.lop`, `days.in_month`, `period.month`, `period.tax_month`, `months_of_service`). Checked on screen with the position of any mistake; loops between formulas are refused and named |
| Tables | The Organization's own values by a key (a meal card by state, an allowance by designation), read with `table()` |
| Structures | A yearly CTC or gross turned into monthly components, each with the component's formula or the structure's own, and one balancing component that takes what is left. Dated versions |
| Pay groups | People paid together, with an input cut-off, a pay day and the structure new people get. Setting someone's pay adds them to the first group |
| Employee pay | Under People → an employee → Pay: CTC history, this month's breakup, a revision with the month before and after side by side, a different amount for one component, and the statutory profile (PF member, PF on full wages, EPS, ESI exempt, PT and LWF exempt, tax regime). Nobody sees or sets their own pay; HR Admins enter everyone else's |
| The monthly run | Inputs (bonus, incentive, recovery) and holds → freeze (a snapshot of the configuration and every employee's facts) → calculate → approve by someone else → mark paid with the bank reference → release payslips. Reopen and send back with a reason; every calculation is kept as an attempt |
| Exceptions | Blocking ones stop approval until fixed or, where allowed, accepted by the approver with a reason: no salary set, a formula that fails, no state for the work location, no rules for that state, a missing registration, negative net pay, no bank account, no PAN. Warnings: zero days paid, a division by zero, a large one-time payment, no UAN, a change in net pay above the threshold, ESI wages crossing the threshold. Held pay is still worked out, and its problems show as warnings so they do not block everyone else |
| Explain | Every line of every payslip carries how it was worked out: the formula and the values it read, the full-month amount and the days paid, the rule and its status, the figure before rounding. Employees see the days and proration part as "Why" |
| Statutory | PF (12% each side, EPS 8.33%, ₹15,000 ceiling, wages with the half-of-pay add-back); ESI (0.75% and 3.25%, ₹21,000 threshold, contribution periods, rounded up); professional tax for Goa, Karnataka and Maharashtra (women's slabs in Maharashtra, the February amount, the yearly ceiling); labour welfare fund for the same three states in their months; income tax for both regimes, projected over the year with rebate and marginal relief, and spread over the months left |
| Outputs | The salary register (CSV), an accounting journal that must balance (CSV), an HDFC-style bank file without held pay or people without an account (CSV), and payslip PDFs |
| My pay | Each released payslip with a PDF, and why pay changed from the month before: one-time amounts, days paid, a revision, PF and ESI, professional tax, the labour welfare fund and income tax |

## The rules are data

Statutory rules live in `app.payroll_rule`, one row per statute, state and date range, the same for every Organization. A run resolves the rules in force for its month into its snapshot, so a later change to a rule never changes a month already calculated. Every rule shipped in release 1 is marked **draft**, with its public source, until the payroll consultant signs it off; the Runs screen and every run say so, and Explain shows "Rule awaiting consultant sign-off" on each line it worked out.

## Where the build differs from the design

- **Holds are read when the run is calculated**, not from the frozen snapshot, so pay can be held after the inputs are frozen; recalculating applies it.
- **Someone whose accepted exit ends before the month starts is not in that month's run.** Their last month is paid through the exit settlement.
- **Exceptions are accepted by the approver**, not by the person who prepared the run, which keeps the decision with the second person.
- **The bank file layout** follows HDFC's bulk upload columns as published; the exact layout from the customer's HDFC corporate banking setup is still to be confirmed.

## Deferred to later releases

Spreadsheet import of salaries and year-to-date history; a parallel run against the old payroll; Ask for help on a payslip; the PF ECR and ESI return files; Form 130 (the Form 16 replacement) and quarterly TDS returns; loans and advances; tax declarations and proofs (only the regime is recorded today); arrears when pay is revised for a month already approved (refused today with "arrears come in the next release").

## Open items before real salaries are paid

1. The payroll consultant signs off each rule row, or corrects it.
2. The HDFC bulk payment layout is confirmed against the customer's corporate banking setup.
3. A month is run in parallel with the customer's current payroll and the differences explained.
