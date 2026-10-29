# Labour Codes wage check (release R5, first slice)

`WageCheck` (Payroll → Wage check, `GET /api/org/payroll/wage-check?month=yyyy-mm`, default the latest approved month). Read-only: it reads the bases the engine recorded on each approved payslip (`wages`, `addedBack`) and shows per person the pay that counts, the wage share, the conditional allowances added back to reach half of pay, and the extra PF that caused (employee plus employer share, with the Organization's PF restriction and the person's full-wage setting). A summary gives the number topped up and the monthly totals. Needs `payroll.read` or `payroll.prepare`.

## What is not built yet (rest of R5)
- What-if simulator (move amounts between basic and allowances and see employer cost and take-home before saving a structure).
- Knock-on to gratuity and leave encashment, and fixed-term gratuity: these need their own rule data, which does not exist yet.
- State rules as data (professional tax, LWF, Shops and Establishments variants) with consultant sign-off per version.
- Notice before absence deductions (7 days to reply before loss of pay, informed within 15 days after): to be confirmed against the rule text with the consultant first.
- Overtime in the 50% test, once overtime pay exists.
- Settlement clock on the exits list.
- Screens and a browser test.

## Notes
- The "half of pay" threshold is in the payroll engine (`PayrollEngine`), not in rule data, from before this slice. The check mirrors the engine; moving the share into rule data is part of the consultant's sign-off work.
- A structure's balancing allowance sees employer PF before the add-back, so pay in the check can differ slightly from CTC minus final employer PF.
