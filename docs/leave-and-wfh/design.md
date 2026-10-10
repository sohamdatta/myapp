# Leave and Work From Home: Implementation Design

Oct 4, 2026

## Scope

This design covers the leave and work-from-home features listed in the attendance features document, built on the user and attendance modules as they stand in the repository (sohamdatta/myapp, commit `4eb18e3`). Nothing here is built yet.

| In | Waits | Waits on |
| --- | --- | --- |
| Leave types and a policy per type | Leave encashment paid out | Payroll |
| Balance kept as a ledger; monthly or yearly accrual | Statutory earned-leave rules by state | Consultant sign-off |
| Apply, approve, reject, cancel; full and half days | Manager approval, team calendar for managers | Employee records with reporting lines |
| "On leave" as a day result; unpaid leave marked | Loss-of-pay into pay | Payroll |
| HR applies on behalf and adjusts balances | Maternity and other statutory leave | Their own rules |
| Who-is-away calendar |  |  |
| Year-end run with preview |  |  |
| Comp-off for work on a day off |  |  |
| Work from home: future dates, eligibility, limits, cancel |  |  |
| Punch from home; regular remote days; half day |  |  |

Not in this design: hourly leave, multi-level approval, unlimited leave, attachments on a request.

Leave attaches to `membership`, as attendance does.

## Fit with the user and attendance modules

Leave is a third module in the same shape as the first two. Nothing in them is redesigned; four things in attendance are extended.

| Existing rule | How this design follows it |
| --- | --- |
| Every tenant table has `tenant_id` and forced row-level security | The five new tables use the same policy and the same loop that applies it |
| Only `app_tenant` reaches Organization data | No new database role; platform and sign-in roles cannot name the new tables (checked) |
| Foreign keys carry `tenant_id` | A leave request cannot point at another Organization's user or leave type (checked) |
| Facts are add-only | Ledger entries and year-end runs can be inserted but not updated or deleted by the application's role, like punches |
| Nobody decides their own request | A database check on `leave_request`, as on `attendance_request` |
| Permissions are codes with scopes; no role names in code | Four `leave.*` permissions, seeded per role; `SessionCtx.scopeOf` and `reachesEveryone` are used as they are |
| Role granting depends on permissions held | Results for an HR Admin are unchanged after the new permissions are seeded (checked) |
| Derived data is rebuilt, not patched | Approving or cancelling leave deletes the affected `attendance_day` rows; they are worked out again on the next read |
| No scheduled jobs | Accruals and comp-off expiry are written when a balance is next read, the way days fill in on read |
| One audit log | Leave actions write to `app.audit_event` through `UserService.audit` |
| Services in `core/`, thin controllers in `web/`, one page per area | Same layout; one migration, `V4__leave_and_wfh.sql`, added to `Migrator` |

The four extensions to attendance: `DayCalculator` takes approved leave as an input and can return `on_leave`; a punch can be accepted as `remote`; an attendance request can be for a future date and can be cancelled after approval; an assignment can name regular remote days.

## Data model

Five new tables in the `app` schema and a few columns on existing ones. The full SQL is on the Schema tab; it loads on PostgreSQL 16 on top of the three existing migrations.

| Table | One row is | Key columns | Can be changed? |
| --- | --- | --- | --- |
| `attendance_settings` | An Organization's settings | `leave_year_start_month` (1 or 4), `wfh_enabled`, `wfh_requires_punch`, `wfh_limit_per_week`, `wfh_limit_per_month` | Yes |
| `leave_type` | A kind of leave | `name`, `kind` (regular or comp-off), `paid`, `accrual` (none, monthly, yearly), `yearly_quota`, `prorate_joiners`, `carry_forward_cap`, `when_short` (refuse or unpaid), what happens to the excess at year end (lapse or encash), `count_days_off`, `expiry_days` | Yes |
| `leave_request` | One leave, for a date range | `from_date`, `to_date`, `half`, `days`, `unpaid_days`, `reason`, `status`, `applied_by`, `decided_by` | Status only |
| `leave_ledger` | One change to a balance | `on_date`, `days` (signed), `kind`, `leave_request_id`, `year_end_run_id`, `expires_on`, `entered_by`, `reason` | No, add-only |
| `leave_year_end_run` | A leave year that was closed | `leave_year`, `run_by`, `run_at` | No, add-only |

A balance is not stored. It is the sum of a user's ledger entries for a leave type. The kinds of entry are accrual, taken, returned (a cancelled leave), adjustment, lapse, encash, comp-off earned and comp-off expired. A balance carried into a new year needs no entry; it simply continues.

Rules the database enforces itself:

- One user cannot have two pending or approved leaves that share a date (an exclusion constraint on the date range).
- An accrual for a given date is written once, however many times it is attempted.
- A request is charged to the ledger once and returned once.
- An adjustment must say who made it and why.
- A half day is for a single date.
- A leave year is closed once per Organization.
- Unpaid leave types have no accrual; comp-off has no quota.

Columns added to existing tables:

| Table | Column | Purpose |
| --- | --- | --- |
| `membership` | `wfh_allowed` | Whether this user may work from home |
| `attendance_assignment` | `remote_days` | Regular remote days of the week, dated with the rest of the assignment |
| `attendance_request` | `half`, `cancelled_at` | Half-day work from home; when an approved request was cancelled |
| `attendance_day` | `leave_days`, `leave_unpaid`, `remote` | How much of the day is leave, whether it is unpaid, whether it was worked remotely |

Three existing checks are widened: a punch's check result may be `remote`; a day's status may be `on_leave`; and an attendance request may be cancelled after it was approved (before, only a pending one could be).

## Permissions

Four new permission codes, in the same shape as attendance.

| Role | `leave.apply` | `leave.read` | `leave.approve` | `leave.manage` |
| --- | --- | --- | --- | --- |
| Org Admin | All | All | All | All |
| HR Admin |  | All | All | All |
| Manager |  | Team | Team |  |
| Employee | Self | Self |  |  |
| Payroll Operator, Payroll Approver |  | Assigned |  |  |
| Auditor |  | All |  |  |
| Ex-employee |  | Self |  |  |
| Support, read only |  | All |  |  |
| Support, write |  | All |  | All |

- `leave.apply`: apply for leave, cancel one's own.
- `leave.read`: see balances, ledgers and requests within the scope.
- `leave.approve`: approve or reject requests within the scope, never one's own.
- `leave.manage`: leave types and settings, apply on someone's behalf, adjust a balance, run the year end.

As with attendance: team scope returns nobody until employee records carry reporting lines, so HR and the Org Admin approve until then; assigned behaves as All until entity scoping is switched on; an HR Admin who takes leave also needs the Employee role.

Work from home adds no permissions. Requests use `attendance.punch` and `attendance.approve`; eligibility, limits and remote days are changed with `attendance.manage`.

## Leave rules

### Counting the days of a request

A pure function, `LeaveDays`, in the style of `DayCalculator`: given the date range, the half-day flag, and for each date the user's weekly offs and holidays, it returns the number of days and which dates count.

1. A date that is a weekly off or a holiday for the user does not count, unless the leave type says days off inside a leave count.
2. A half day counts 0.5 and is allowed only for a single date.
3. A request that counts zero days is refused.
4. A user with no work location and shift on a date in the range is refused with the existing `attendance.not_assigned` error.

The window: a user may apply for dates from 31 days back to 365 days ahead. HR, applying on someone's behalf, may use any date in a leave year that is still open.

### Accrual

| Type setting | Entry written | Dated |
| --- | --- | --- |
| Yearly | The yearly quota | First day of the leave year |
| Yearly, user joined during the year, proration on | Quota × months remaining ÷ 12 | Joining date |
| Monthly | Quota ÷ 12 | First day of each month, from the month of joining |
| None | Nothing | Balance changes only by adjustment or comp-off |

Amounts are rounded to two decimals. A month counts for a joiner who joined on or before the 15th.

There is no scheduled job. Before a balance is read or a request is checked, the service writes any accrual entries that have fallen due and are missing. The unique index makes a repeated attempt harmless.

### Balance and shortfall

- **Balance** = sum of the ledger for the user and type.
- **Available** = balance, plus accruals that will fall due on or before the leave's first date, minus the days of the user's other pending requests of that type.
- If the request needs more than is available: a type set to *refuse* returns an error naming the shortfall; a type set to *unpaid* accepts it and records the excess in `unpaid_days`.
- An unpaid leave type has no balance; every day of it is unpaid.

### Deciding and cancelling

| Action | Who | Effect |
| --- | --- | --- |
| Approve | `leave.approve` reaching the user, not the user themselves | Status approved; one `taken` entry for the paid days, dated the first day; stored day results for the range are forgotten |
| Reject | Same | Status rejected, with a note |
| Cancel, pending | The user | Status cancelled |
| Cancel, approved, not yet started | The user | Status cancelled; one `returned` entry; day results forgotten |
| Cancel, approved, started or past | `leave.manage` only, and only in an open leave year | Same as above |
| Apply on behalf | `leave.manage`, not for themselves | Created already approved, with `applied_by` and `decided_by` set to HR |
| Adjust a balance | `leave.manage`, not their own | One `adjustment` entry with a reason; this is also how opening balances are loaded |

### Year end

The leave year starts in January or April, per Organization. A year can be closed once it has ended and no request dated in it is still pending.

1. **Preview.** For every user and type: closing balance, the type's carry-forward cap, and the excess over the cap. Nothing is written.
2. **Commit.** One `leave_year_end_run` row, and for each excess one entry dated the last day of the year: `lapse` or `encash`, as the type says. A balance within the cap needs no entry; it simply continues.
3. After the run, nothing dated in the closed year can be added or cancelled. A late correction is an adjustment dated in the open year.

`encash` entries are the list payroll will pay from. Until payroll exists they are a report.

### Comp-off

A comp-off type has no quota. HR grants a day (or half) for work on a weekly off or holiday; the entry carries an expiry date from the type's `expiry_days`. The People view lists days off that were worked for at least a half day and have no comp-off yet, taken from the stored day results.

Comp-off is used oldest first. When a balance is read, any earned entry past its expiry with days still unused gets one `comp_off_expired` entry for the remainder.

Employees claiming comp-off themselves is left for later.

## Work-from-home rules

Today a work-from-home request is for one day, today or up to 31 days back, and an approved day counts as a full day with no punch. The rules below extend that.

### Requests

1. **Dates.** A request may be for a date range from 31 days back to 60 days ahead. A range is stored as one request per working day in it, so each day can be approved, rejected or cancelled on its own and the existing one-open-request-per-day rule still holds. On-duty requests get the same window.
2. **Eligibility.** Refused if the Organization has work from home switched off, or the user's `wfh_allowed` is off.
3. **Limits.** Counted per calendar week (Monday to Sunday) and per calendar month, over the user's pending and approved work-from-home days. Regular remote days from the assignment do not count towards a limit. A request that would pass a limit is refused with the number already used. HR, applying on the user's behalf, is not bound by the limit; the audit event records that it was exceeded.
4. **Half day.** `half` set to first or second. It counts 0.5 towards limits.
5. **Leave on the same date.** Refused; and a leave request for a date with an approved work-from-home day cancels that day when the leave is approved.
6. **Cancel.** The user can cancel a pending request, or an approved one for today or later. The day's stored result is forgotten.

### Regular remote days

`remote_days` on the assignment lists days of the week the user works remotely as a matter of course, dated and add-only like the rest of the assignment. On those days no request is needed and none is counted against a limit.

### Whether a remote day needs punches

One setting per Organization, `wfh_requires_punch`.

| Setting | An approved or regular remote day | Punching |
| --- | --- | --- |
| Off (as now) | Counts as a full day | Not needed; punches, if any, are recorded and shown |
| On | Judged on its punches like an office day: hours, late and early marks | Required |

A punch on a remote day skips the work location's network and location checks and is stored with check result `remote`. The server decides this from the day, never from what the browser sends: the day must be an approved work-from-home day, the remote half of a half day, or a regular remote day. On any other day the location's check applies as it does now.

No position is requested or stored for a remote punch, and nothing else about the user's device or activity is collected.

## Changes to attendance code

### The day calculation

`DayCalculator` stays a pure function. Its input gains three things, all looked up by `AttendanceService.recompute` before the call:

- **Leave for the day:** none, a full day or a half (first or second), with the leave type's name and whether it is unpaid. Passed only for dates the leave actually counts.
- **Remote:** whether the day is an approved work-from-home day or a regular remote day, and for a half day, which half.
- **Remote needs punches:** the Organization's setting.

The order in which a day is decided, first match wins:

1. No assignment: `not_assigned`.
2. Full-day leave: `on_leave`. Punches on the day are listed in the trail but not counted.
3. Approved on-duty request: `on_duty`, a full day.
4. Remote day and punches are not required: `work_from_home`, a full day.
5. Holiday or weekly off: as now, with any work recorded as overtime.
6. Otherwise from the punches, as now. If the day is remote and punches are required, the result is the same as for an office day and the day is marked `remote`.

A half-day leave changes step 6 only: the day needs a half day's hours to be `present`, lateness is not counted when the first half is leave, and an early exit is not counted when the second half is. With fewer hours the result is `half_day`: the leave half counts and the other half does not.

The result gains `leaveDays` (0, 0.5 or 1), `leaveUnpaid` and `remote`, stored in the new `attendance_day` columns. The trail gains lines such as "Earned leave, approved" and "Remote day".

### Other changes

| Where | Change |
| --- | --- |
| `AttendanceService.punch` | Before the location's check, ask whether today is a remote day for the user; if so, skip the check and store `remote` |
| `AttendanceService.today` | Returns `remote: true` on such a day so the screen says "Working from home today" and does not ask for a position |
| `AttendanceService.createRequest` | Accepts `fromDate`, `toDate` and `half`; future dates; eligibility and limits for work from home; refuses a date that has approved leave |
| `AttendanceService.cancelRequest` | Also cancels an approved request for today or later, and forgets the day's result |
| `AttendanceService.assigned` | Reads `remote_days` |
| `AttendanceSettingsService.assign` | Accepts `remoteDays`; they may not overlap the weekly offs |
| `AttendanceSettingsService` | Reads and saves `attendance_settings`; sets `wfh_allowed` per user |
| Register CSV | Adds leave days, unpaid, and remote columns |
| Screens | The daily board and month list show `on_leave` with the leave type, and a remote marker |

Stored day results from before the migration stay valid: the new columns default to no leave and not remote.

## API

Leave endpoints are under `/api/org/leave`, with the same `Authorization` header and `{code, message}` errors as the rest. The Organization always comes from the session.

| Method | Path | Needs | Purpose |
| --- | --- | --- | --- |
| GET | `/me/balances` | `leave.read` | The caller's balance per leave type, with what is available and what is pending |
| GET | `/me/ledger?type=` | `leave.read` | The entries behind one balance, newest first |
| GET | `/me/requests` | `leave.apply` | The caller's requests |
| POST | `/me/requests/preview` | `leave.apply` | Counts the days of a range and says whether the balance covers it; writes nothing |
| POST | `/me/requests` | `leave.apply` | Applies: `leaveTypeId`, `fromDate`, `toDate`, `half`, `reason` |
| POST | `/me/requests/{id}/cancel` | `leave.apply` | Cancels a pending request, or an approved one that has not started |
| GET | `/requests?status=` | `leave.approve` | Requests the caller may decide |
| POST | `/requests/{id}/approve`, `/reject` | `leave.approve` | Decides one, with an optional note |
| POST | `/requests/{id}/cancel` | `leave.manage` | Cancels an approved leave that has started |
| GET | `/calendar?from=&to=` | `leave.read` beyond self | Who is away on each date, within the caller's scope |
| GET | `/users/{id}/balances`, `/users/{id}/ledger` | `leave.read` reaching the user | One user's balances and entries |
| POST | `/users/{id}/requests` | `leave.manage` | Applies on the user's behalf; created approved |
| POST | `/users/{id}/adjustments` | `leave.manage` | `leaveTypeId`, `days` (signed), `onDate`, `reason` |
| POST | `/users/{id}/comp-off` | `leave.manage` | Grants comp-off for a worked day off: `workDate`, `days` |
| GET | `/comp-off/candidates` | `leave.manage` | Worked days off with no comp-off yet |
| GET, POST | `/types` | read: `leave.read`; write: `leave.manage` | Lists and creates leave types; `PUT /types/{id}` changes one |
| GET | `/year-end/preview?year=` | `leave.manage` | What closing the year would write |
| POST | `/year-end` | `leave.manage` | Closes the year |

Added to `/api/org/attendance`:

| Method | Path | Needs | Purpose |
| --- | --- | --- | --- |
| GET, PUT | `/settings` | read: `attendance.read` for everyone; write: `attendance.manage` | Leave year start and the work-from-home settings |
| PUT | `/users/{id}/wfh` | `attendance.manage` | Sets `wfh_allowed` |
| GET | `/me/wfh` | `attendance.punch` | Whether the caller may work from home, the limits, and how many days are used this week and month |

Changed: `POST /me/requests` accepts a date range and `half`; `POST /me/requests/{id}/cancel` also cancels an approved request for today or later; `POST /assignments` accepts `remoteDays`.

## Code structure and screens

### Backend

| File | Holds |
| --- | --- |
| `core/LeaveDays.java` | Pure: counts the days of a request from weekly offs and holidays |
| `core/LeaveAccrual.java` | Pure: which accrual entries are due for a user and type up to a date |
| `core/LeaveService.java` | Balances, ledger, apply, decide, cancel, calendar; takes a clock, as the attendance services do |
| `core/LeaveAdminService.java` | Leave types, on-behalf requests, adjustments, comp-off, year end |
| `web/LeaveController.java`, `web/LeaveAdminController.java` | Thin controllers under `/api/org/leave` |
| `core/DayCalculator.java`, `AttendanceService.java`, `AttendanceSettingsService.java` | The changes in the previous section |
| `core/Db.java` | Translations for the overlap constraint, the own-request check and a duplicate leave type name |
| `core/Migrator.java` | `V4__leave_and_wfh.sql` added to the list |
| `web/AppConfig.java` | Two new beans |

### Frontend

| File | Holds |
| --- | --- |
| `src/api.js` | A `leave` group beside `attendance` |
| `src/nav.js` | "My leave" under My work; "Leave" under Organization |
| `src/pages/MyLeave.jsx` | The employee's leave page |
| `src/pages/Leave.jsx` | The HR pages, as tabs |
| `src/pages/MyAttendance.jsx`, `Attendance.jsx`, `AttendanceSetup.jsx` | Work-from-home changes |

### Screens

| Screen | For | Shows |
| --- | --- | --- |
| My leave | Anyone with `leave.apply` | A tile per leave type with the balance; opening a tile lists its ledger entries. "Apply for leave" opens a form that shows, as dates are chosen, how many days count and what will be left. Below, the user's requests with Cancel |
| Leave → Requests | `leave.approve` | Pending requests with the user's balance and who else is away on those dates; approve or reject with a note |
| Leave → Calendar | `leave.read` beyond self | A month grid: who is on leave, on duty or remote each day |
| Leave → Balances | `leave.read` for everyone | Each user's balance per type; with `leave.manage`: adjust, apply on behalf, grant comp-off |
| Leave → Setup | `leave.manage` | Leave types, the leave year, and the year-end preview and run |
| My attendance | Existing | "Working from home today" on a remote day; the request form takes a date range and a half day, and shows how many work-from-home days are left this week and month |
| Attendance → People | Existing | A work-from-home switch per user; regular remote days in the assign form |
| Attendance → Setup | Existing | Work-from-home settings for the Organization |
| Attendance → Daily | Existing | Counts for on leave and remote; the leave type on a leave day |

All in the look already applied: sidebar, tiles, tabs under the page title, tables that become lists on a phone.

## Audit events and error codes

Written to the Organization's audit log in the same transaction as the change.

| Event | When |
| --- | --- |
| `leave.type_created`, `leave.type_changed` | A leave type is added or changed |
| `leave.applied_on_behalf` | HR applies for a user |
| `leave.approved`, `leave.rejected` | A request is decided |
| `leave.cancelled` | An approved leave is cancelled, by the user or by HR |
| `leave.adjusted` | A balance adjustment, with days and reason |
| `leave.comp_off_granted` | Comp-off is granted |
| `leave.year_closed` | The year-end run, with counts of lapsed and encashed days |
| `attendance.settings_changed` | Leave year or work-from-home settings change |
| `attendance.wfh_allowed_set` | A user's eligibility changes |
| `attendance.request_cancelled` | An approved work-from-home or on-duty day is cancelled |

Applying for leave and cancelling a pending request are not audit events; the request row is the record, as with attendance requests.

| Code | Status | Meaning |
| --- | --- | --- |
| `leave.overlap` | 409 | The user already has leave on one of those dates |
| `leave.no_days` | 400 | The range has no working days |
| `leave.insufficient_balance` | 409 | Not enough balance; the message gives available and needed |
| `leave.window` | 400 | Dates outside the allowed window |
| `leave.own_request` | 409 | Deciding, adjusting or applying on behalf for oneself |
| `leave.started` | 409 | The leave has started; only HR can cancel it |
| `leave.year_closed` | 409 | The date is in a closed leave year |
| `leave.year_open_requests` | 409 | The year cannot be closed while requests in it are pending |
| `leave.type_inactive` | 409 | The leave type is not in use |
| `attendance.wfh_not_allowed` | 403 | Work from home is off for the Organization or the user |
| `attendance.wfh_limit` | 409 | The weekly or monthly limit would be passed |
| `attendance.leave_on_date` | 409 | A work-from-home or on-duty request for a date with approved leave |

## Tests

**Done so far:** only the schema. `V4__leave_and_wfh.sql` loads on PostgreSQL 16 on top of the three existing migrations, and 16 checks pass against it: the platform and sign-in roles cannot reach the new tables; one Organization sees nothing of another's and cannot point a request at another's user or leave type; a repeated accrual is refused; ledger entries cannot be updated or deleted; an adjustment needs who and why; overlapping leave is refused; nobody can decide their own leave; the balance is the ledger's sum; a cancelled leave frees its dates; a half day is for one date; an approved work-from-home request can be cancelled; a remote punch and an on-leave day are accepted; a year is closed once; and an HR Admin's role-granting results are unchanged. No application code or application tests exist yet.

**To write**, in the pattern of the existing tests (pure tests with no database; service tests on PostgreSQL with a clock the test controls):

| Test class | Covers |
| --- | --- |
| `LeaveDaysTest` (pure) | Weekly offs and holidays skipped or counted; half day; a range over a month end; zero days |
| `LeaveAccrualTest` (pure) | Yearly and monthly entries; proration for a joiner; the 15th-of-month rule; an April leave year; nothing written twice |
| `DayCalculatorTest` (additions) | Full-day leave beats punches, holidays and remote; half-day leave with enough and too few hours; remote day with and without required punches; decision order |
| `LeaveTest` (PostgreSQL) | Apply, approve, reject, cancel before and after the start; available balance with pending requests and future accruals; refuse versus unpaid shortfall; overlap; on behalf; adjustment; comp-off grant, use and expiry; year-end preview equals what the run writes; closed year refuses changes; scopes for employee, manager, HR, auditor, support; isolation between Organizations |
| `AttendanceTest` (additions) | Leave turns the day into `on_leave` and cancelling turns it back; work-from-home range, eligibility, weekly and monthly limits, half day, cancel; remote punch accepted only on a remote day; regular remote days; leave on a work-from-home date |

Then the browser flow, as for attendance: set up a type, apply, approve, see the day and the balance change, cancel, run a year end on a past year.

## Build steps

Nine steps. Each ends with something usable and tested. Hours are estimates, in line with the features document (48 for leave before payroll, 30 for work from home).

| Step | Builds | Done when | Hours |
| --- | --- | --- | --- |
| 1 | Migration, leave types, Organization settings | HR can add leave types and set the leave year | 8 |
| 2 | Ledger, accrual on read, My leave with balances and entries, adjustments | A user sees a balance and how it was reached; HR can load opening balances | 10 |
| 3 | Apply, preview, approve, reject, cancel; `on_leave` in the day calculation | An approved leave shows on the daily board and reduces the balance; cancelling reverses both | 12 |
| 4 | Apply on behalf; who-is-away calendar | HR can record leave for someone; the calendar matches the board | 6 |
| 5 | Year-end preview and run | The run writes exactly what the preview showed, once | 8 |
| 6 | Comp-off; days off inside a leave | A worked Sunday can be granted, used and expires; the weekends rule changes the count | 6 |
| 7 | Work from home: date ranges, future dates, cancel, eligibility, limits | A user can plan next week from home and is stopped at the limit | 10 |
| 8 | Remote punch and the punches-required setting | On a remote day a punch is accepted from anywhere and nowhere else | 6 |
| 9 | Regular remote days, half day, board counts, register columns | A hybrid week needs no requests; the board shows who is where | 14 |

About 80 hours. Steps 1 to 3 (30 hours) give working leave. Step 7 alone (10 hours) brings work from home level with what Keka's help pages describe, and can be done before any of the leave steps since it needs only the settings table from step 1.

Not in these steps: encashment into pay (with payroll), statutory earned-leave rules (after the consultant confirms them), manager approval (with reporting lines).

## Open decisions

Each has a default in this design so building can start; changing one later is cheap unless noted.

- **Leave year.** Default January to December, switchable to April to March per Organization. Changing it after a year has been closed is not supported.
- **Who approves.** HR and the Org Admin, until employee records with reporting lines exist. Same as attendance.
- **Shortfall.** Default is to refuse. The alternative, letting the excess become unpaid, is a per-type setting.
- **Half-day leave with too few hours.** The design gives `half_day`: the leave half counts, the other half does not. The alternative is to treat the other half as absent and flag it for HR.
- **Accrual rounding.** Two decimals. A quota that does not divide by 12 gives balances such as 1.25; if that is unwelcome, round each month to the nearest half.
- **Remote days and punches.** Default: no punch needed, as now.
- **Work-from-home limits.** Both weekly and monthly are offered; either can be left empty. Beyond the limit the request is refused, and HR can still record it.
- **Comp-off claims.** HR grants. Employees claiming for themselves is left out.
- **Statutory earned leave.** Not modelled. The type settings can express "18 days a year, carry forward up to 30", but not "one day per 20 days worked after 180 days". That needs the consultant's confirmation and day counts from attendance, and is a later step.
- **The Maven build.** As for attendance, none of this has been or can be built with Maven in the environment this was written in.
