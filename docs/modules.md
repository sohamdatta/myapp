# Modules: what a customer pays for

Oct 19, 2026 · built in V16 · Oct 24, 2026 · Leave split from Attendance in V19

A customer can buy any mix of Attendance, Leave and Payroll, or none of them. Core HR is always included.

| Module | Covers | Sold |
|---|---|---|
| **Core HR** | People and employee records, the org chart, users and roles, joining (onboarding, offers, codes), leaving (exits, settlements, letters), the audit log, and the work calendar: locations, shifts, holidays and who works where | Always included |
| **Attendance** | Punching, biometric devices and device files, the daily board, attendance requests (corrections, on duty, work from home), marks, attendance modes, and work-from-home settings | Add-on |
| **Leave** | Balances, applying for leave, approvals, who is away, adjustments, leave recorded on someone's behalf, comp-off grants, leave types, the leave year and the year end | Add-on; meant to be part of a free plan |
| **Payroll** | Salary setup and pay, monthly runs, statutory deductions, payslips, the bank file and journal | Add-on |

### Why Leave is its own module (V19)

Leave is the first thing a small company wants from an HR system, and it needs nothing from punching. Selling it apart lets a free plan offer Core HR with Leave, and keep Attendance and Payroll paid.

**Work from home stays in Attendance.** It only changes how punching is checked (remote days, punches needed or not, weekly and monthly limits), and its requests share one form and one approval list with attendance corrections and on-duty days. Without Attendance there is no punching for it to change.

## Switching modules

The Platform console does it; there is no online payment yet.

- **When creating an Organization,** tick the modules it has bought. All three are ticked by default; Core HR needs nothing.
- **Afterwards,** *Modules* on the Organization's row switches each one on or off, with an optional **trial end date**. A module with a trial is on through that date (India time) and off from the next day, without anyone touching it; the console then shows "trial ended".
- **Independent.** Each module is switched on its own. Switching Attendance on does not bring Leave, and switching Leave off leaves Attendance as it is.
- **Who:** the Super Admin, or a platform user with the billing role.
- **Recorded:** each change goes to the platform audit log (`tenant.module_on`, `tenant.module_off`, with the module and trial date).
- **Stored:** `platform.tenant_module` (Organization, module, on or off, trial end). Organizations that existed before V16 have Attendance and Payroll on. V19 gave every Organization Leave on exactly the terms of its Attendance (on, off, or on until the same trial end), so nobody gained or lost anything.

## What "off" means

Switching off **hides and refuses**; it deletes nothing. Switching on again brings everything back as it was.

- **Screens.** The menu leaves out the module's pages: Attendance and My attendance without Attendance; Leave and My leave without Leave; Payroll, My pay and the Pay tab on an employee without Payroll. Payroll's two roles are not offered when inviting or editing roles while Payroll is off. On *Leave → Balances*, the days off that were worked (to grant as comp-off) are shown only with Attendance, since only punching records them.
- **API.** Every request to the module's endpoints is refused with 403 `module.off`: "Leave is not part of Hooli Ltd's subscription. The Org Admin can ask for it to be added". This is checked on the server for every request, from the session, so a stale screen or a hand-made request gets the same answer.
- **Devices.** A biometric device belonging to an Organization without Attendance is treated as unregistered.
- **Timing.** A change takes effect on the next request; people see the new menu when the page next loads.

Which endpoints belong to which module:

| Module | Endpoints |
|---|---|
| Attendance | `/api/org/attendance/*` except the work calendar below, including saving attendance settings; `/api/org/attendance/devices/*`; `/iclock/*` |
| Leave | `/api/org/leave/*` except reading leave types. Includes `PUT /api/org/leave/year`, which sets when the leave year starts (January or April) without touching any work-from-home setting |
| Payroll | `/api/org/payroll/*` and the pay endpoints under `/api/org` served by `PayrollController` |
| Core HR (always) | Work calendar: `locations`, `shifts`, `holidays`, `assignments`, employee codes, reading attendance settings. Leave types (read): exit settings name the leave a settlement encashes. `GET /api/org/setup` and `POST /api/org/setup/starter` (Getting started): they fill the work calendar whatever modules are on, and leave types only with Leave |

The leave year is stored with the attendance settings (`app.attendance_settings.leave_year_start_month`), where it has been since V4; only the endpoint that changes it moved. Saving the attendance settings can still change it too, for an Organization with Attendance. Changing it through Leave is recorded as `leave.year_changed`, and is refused once a leave year has been closed, as before.

## Payroll and the other modules

- **Where people work.** Payroll takes each person's state (for professional tax and labour welfare fund) from their work location, and working days from their shift, weekly offs and holidays. So the work calendar stays in Core HR. Without Attendance, HR finds it under **Work locations** in the menu, without punch checks, modes, devices or work-from-home settings.
- **Loss of pay.** Unpaid leave counts when the Organization has **Leave**. Absence without leave counts (if payroll settings say so) when it has **Attendance**. HR can always enter more in the run: *This month's inputs → For: Loss of pay (days)*, in whole or half days, never more than the month has. Days entered are added to whatever leave and attendance give. Without either module, the days entered are the only source. A day of unpaid leave counts once, with either module or both.
- **When it counts.** Freezing a run fixes the month's inputs. Switching Attendance or Leave on or off affects a run once it is reopened and frozen again. The run screen says which sources it reads.

## Attendance without Payroll

Nothing else changes: attendance and the register work as before. People see no My pay, and the employee record has no Pay tab.

## What the Org Admin sees

*User management → Subscription*: Core HR (included), and Attendance, Leave and Payroll, each on (with any trial end) or not included. Changes are asked for from the HRMS team.

## Tests

- `UserModuleTest`: modules by default and as chosen; a trial on, then ended; off and on; bad module, bad or past trial dates, a trial over a year, an unknown Organization, an Org Admin trying; the platform audit. A Leave-only Organization: its session, menu modules and Subscription tab; Attendance and Leave switched independently. Every Organization with Attendance has a Leave row.
- `LeaveTest`: the leave year set under Leave keeps the work-from-home settings, is audited, refuses other months and employees, and is refused once a year is closed.
- `PayrollTest`: a month with neither Attendance nor Leave, where recorded unpaid leave is not read and the loss-of-pay days entered are; whole or half days, never more than the month; Leave back on alone and the run reopened, the unpaid leave counts; Attendance back on too, it still counts once.
- `AttendanceTest`: a device is a stranger while its Organization has no Attendance.
- `e2e/tests/19-modules.spec.ts`: the Super Admin creates a payroll-only Organization on screen; its owner's menu, the refused and allowed requests (Leave refused with its own message), Work locations without punch settings, the Subscription tab; an Attendance trial switched on and seen at once, without Leave; Attendance off and Leave on by itself: leave screens and requests, the leave year set and refused for a wrong month, Attendance still refused; Payroll switched off (menu, requests and roles gone) and on again.
- The upgrade was tried by hand on a database built by the version before V19, with three Organizations (Attendance on, Attendance on a trial, Attendance off): after V19 each had Leave on the same terms, including the trial date.
