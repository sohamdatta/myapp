# Employee exit: design, as built

An employee's exit is a tracked process, like onboarding: the resignation or
decision is recorded, the notice period runs, every team clears the person,
the full and final settlement is worked out and paid within the legal
deadlines, the letters go out, and the login ends on the right day. It
replaced the single "Record exit" action, which now starts an exit with its
last working day.

The schema is `backend/src/main/resources/db/migration/V11__employee_exit.sql`.
The code is in `core/Exit*.java`; the screens are `MyExit.jsx`, `Exits.jsx`,
`Settlement.jsx`, `ExitLetters.jsx` and `setup/ExitSetup.jsx`.

## Kinds of exit

Six kinds share one record (`exit_case`) and one flow.

| Kind | Started by | Notice | What differs |
|---|---|---|---|
| Resignation | The employee in My exit, or HR from a letter | By employment type (Setup → Exits); the probation notice while on probation | The manager recommends a last day, then HR accepts; can be withdrawn |
| Termination | HR | Served, paid in lieu, or none | No acceptance letter; HR issues the termination letter |
| End of contract | Proposed by the app 30 days before `contract_end` | None | HR confirms or cancels (contract renewed); a cancelled proposal is not made again for the same date |
| Retirement | Proposed by the app 60 days before the retirement day: the last day of the month the retirement age is reached | None | As above |
| Death | HR, with the date of passing | None | The employment ends at once; no tasks for the person, no interview, no messages to them; nominees shown; gratuity due whatever the service; no former-employee access |
| Absconding | HR, once the last day at work is at least the absconding days ago | None | Waits as "absent" until the show-cause notice and then the final notice have each had the days to reply; then HR confirms with the last day at work |

Retrenchment and lay-off follow separate rules in the Industrial Relations Code
and are not covered.

## Statuses

`proposed` (the app's proposals, and absconding while the notices run) →
`submitted` (resigned) → `recommended` (the manager agreed a day) →
`accepted` (the last working day is fixed) → `closed`. Also `withdrawn` and
`cancelled`. One open exit per employment (a partial unique index).

The employment stays `active` through the notice. The day after the last
working day the app ends it (`exited`, `exit_date`), moves the reports to the
employment HR named (or the leaver's own manager) by a dated job change, and
the membership's roles are replaced by Ex-employee. This happens on the first
request that looks, whoever makes it.

## Resignation and notice

- The notice is the employment type's (30 permanent, 15 probation, 30 fixed
  term, 0 intern, 15 consultant by default). The proposed last day is today
  plus the notice.
- The manager's step can be turned off; with no manager, HR accepts directly.
- A shortfall (the last day before the notice end) is waived, set against
  earned leave (default), owed by the employee, or, for terminations, paid in
  lieu. Notice owed is never deducted from wages (Code on Wages s.18): it is a
  separate demand on the settlement.
- During notice the Organization chooses which leave types may be applied for
  (default: all, because sick leave cannot be told apart from other kinds).
- Before acceptance the employee withdraws at once; after it, they ask and HR
  decides. After the last day nothing can be withdrawn or cancelled.

## Clearance

Exit checklists use the joining checklist engine, with `kind = 'exit'`, owners
manager, IT, Finance, HR, the leaver or a named user, and due dates counted
from `exit_accepted` or `last_working_day`. Tasks nobody holds the role for
fall to the HR who accepted. A task can be done with an amount owed by or to
the employee; those amounts become settlement lines. Overdue tasks are chased
once a day; everyone involved is told the day before the last day.

Clearance never holds up wages. It gates only the relieving letter.

## Full and final settlement

`prepare` works out lines from the exit, leave and clearance; running it again
while a draft refreshes quantities and dates and keeps typed amounts.

| Line | Quantity | Due |
|---|---|---|
| Salary | Days of the last month to the last day, less unpaid leave | 2 working days after the last day (Sundays and all-location holidays skipped) |
| Leave encashed | Balance of the encashable types (Setup → Exits) on the last day, less notice set against it | With the wages |
| Notice pay | Days paid in lieu | With the wages |
| Notice owed | Days the employee owes | A demand, asked for separately |
| Gratuity | Years counted: 5 years' service, 1 for fixed term, none on death; a part year over 6 months counts | 30 days after the last day |
| Reimbursement, recovery | From clearance amounts | With the wages, or a demand |
| Income tax | Typed by HR | With the wages |

HR types the amounts until payroll exists. A second person (HR or a Payroll
Approver) approves; the database refuses the same person. Approval issues the
PDF statement to the employee's documents and tells them. Each line is marked
paid with its date and bank reference; a paid line never changes (trigger).
Once every line the employer pays is paid, the settlement is paid. HR is told
the day before an unpaid line falls due.

## Letters

Exit letter templates sit beside the joining ones (Setup → Letters, under
"Leaving") with extra placeholders: `exit.kind`, `exit.submitted_on`,
`exit.last_working_day`, `exit.notice_days`, `exit.notice_end`,
`exit.response_by`, `exit.absent_since`, `employment.tenure`,
`employment.designations`.

| Letter | When |
|---|---|
| Resignation acceptance | Automatically, on acceptance |
| Termination, retirement, end of contract | HR issues, for that kind |
| Show-cause, final notice | HR issues, for absconding; each starts the reply period |
| Experience | HR issues, from the last day |
| Relieving | HR issues, from the last day once every required clearance task is done |
| Settlement statement | Automatically, on approval |

## Exit interview

HR sends the questions (Setup → Exits; ratings 1 to 5, choices and text,
versioned; each interview keeps the questions it was sent). The employee
answers or declines in My exit. Answers and HR's notes from an interview held
in person are encrypted; only HR (`exit.manage`) reads them, and the manager
sees only whether it happened. Not offered on death or absconding.

## Afterwards

- **Former-employee access.** An ex-employee signs in to My exit only, for
  their letters and settlement, until `former_access_until` (12 months after
  the last day by default; none on death). They are told once two weeks
  before; then the Ex-employee role is removed and the login ends.
- **Closing.** HR closes once clearance is done, the settlement paid and the
  relieving letter issued, saying whether the person could be hired again.
  The rehire check on a new joiner shows that answer and how the last exit
  ended.
- **Retention.** After the exited-retention years (Setup → Onboarding), the
  person's personal data is removed: identifiers, bank, addresses, contacts,
  family and nominees, documents and letters, onboarding answers, the
  interview, notes; the name becomes "Former employee". The employment, job
  history, exit dates and settlement figures stay. Skipped while any of the
  person's exits is under legal hold, or while they work here again.

## Permissions

`exit.read` (tenant for HR, Owner, Auditor and support; team for managers;
assigned for the Payroll Approver) and `exit.manage` (HR Admin, Org Admin).
Nobody decides, settles or issues letters for their own exit. The app's
proposals are HR's until confirmed: neither the employee nor the manager sees
them.

## Open legal points

For the consultant review: whether leave encashment falls under the two-day
rule; how notice pay owed may be recovered; when earlier service counts for
gratuity on a rehire. The retention minimum (5 years) should be checked
against the wage-register rules that apply.
