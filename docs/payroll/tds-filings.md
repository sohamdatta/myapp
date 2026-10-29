# TDS challans and quarterly statements (release R3, part 1)

Status: built and tested; **rules are drafts until the payroll consultant signs them off**. See `docs/roadmap/next-releases.md` section 3.

## Challans
`TdsChallans`: record a deposit (BSR code 7 digits, serial 5 digits, date, tax/surcharge/cess/interest/fee) against the approved runs of one month. Tax + surcharge + cess must equal the TDS deducted in those runs to the paisa; a run is on one challan. A different person approves (database constraint too). The year view shows deducted vs deposited per month, the due date (rule data: 7th of next month, 30 April for March) and days late.

**Decision for the owner:** the roadmap says a run can be marked Paid only after its TDS has a challan, and also that the challan is recorded after the run is Paid. Real deposits fall due by the 7th of the following month, after salary is paid, so a hard gate on Paid cannot work. Not implemented; overdue months are shown instead.

## Form 138 statement
`TdsStatements`: one numbered version per build, per tax year and quarter, from approved runs and approved challans. Blocking checks: open run in the quarter, missing/unapproved challan, challan not tying to the runs, deductor details incomplete. A missing or invalid PAN is blocking but an approver can accept it with a reason. The review spreadsheet is stored encrypted (it has full PANs) and needs a fresh two-step code to download. After filing with the department's tools, record the token and date; the statement and its lines are then locked by the database. A later build is a correction holding only added, changed or withdrawn lines.

## Form 122, Part B: other employers
`OtherEmployerIncome`: an employee declares salary from a previous employer this tax year (name, TAN, months worked, taxable salary, PF, professional tax, tax deducted). HR accepts it or returns it with a note; a returned entry counts for nothing, and the employee can change it and send it back. Payroll counts declared entries until the tax window's cut-over month and accepted ones from then on: the salary joins income so far, the tax joins tax paid so far, and PF and professional tax join what was already paid (so the yearly PT ceiling holds across employers). It is added to the year-to-date figures in the payroll snapshot, so the pure engine is unchanged.

Proof files are not attached to these entries yet (the existing proof storage hangs off declaration claims); HR sees the figures and the employer's TAN.

## Not built yet
- The text file in the return preparation utility's layout (layout and version are an open question for the consultant), and Q4 annual salary detail.
- Form 122 Part C (house-property loss, other income), proof files for Part B, Form 130 Part B and Part A matching.
- Screens and browser tests.
