# Modules: what a customer pays for

Oct 19, 2026 · built in V16

A customer can buy Payroll only, Attendance only, both, or neither. Core HR is always included.

| Module | Covers | Sold |
|---|---|---|
| **Core HR** | People and employee records, the org chart, users and roles, joining (onboarding, offers, codes), leaving (exits, settlements, letters), the audit log, and the work calendar: locations, shifts, holidays and who works where | Always included |
| **Attendance** | Punching, biometric devices and device files, the daily board, requests, marks, attendance modes, leave and work from home | Add-on |
| **Payroll** | Salary setup and pay, monthly runs, statutory deductions, payslips, the bank file and journal | Add-on |

## Switching modules

The Platform console does it; there is no online payment yet.

- **When creating an Organization,** tick the modules it has bought. Both are ticked by default; Core HR needs nothing.
- **Afterwards,** *Modules* on the Organization's row switches each one on or off, with an optional **trial end date**. A module with a trial is on through that date (India time) and off from the next day, without anyone touching it; the console then shows "trial ended".
- **Who:** the Super Admin, or a platform user with the billing role.
- **Recorded:** each change goes to the platform audit log (`tenant.module_on`, `tenant.module_off`, with the trial date).
- **Stored:** `platform.tenant_module` (Organization, module, on or off, trial end). Organizations that existed before V16 have both modules on.

## What "off" means

Switching off **hides and refuses**; it deletes nothing. Switching on again brings everything back as it was.

- **Screens.** The menu leaves out the module's pages: Attendance, Leave, My attendance and My leave without Attendance; Payroll, My pay and the Pay tab on an employee without Payroll. Payroll's two roles are not offered when inviting or editing roles while Payroll is off.
- **API.** Every request to the module's endpoints is refused with 403 `module.off`: "Attendance is not part of Hooli Ltd's subscription. The Org Admin can ask for it to be added". This is checked on the server for every request, from the session, so a stale screen or a hand-made request gets the same answer.
- **Devices.** A biometric device belonging to an Organization without Attendance is treated as unregistered.
- **Timing.** A change takes effect on the next request; people see the new menu when the page next loads.

Which endpoints belong to which module:

| Module | Endpoints |
|---|---|
| Attendance | `/api/org/attendance/*` except the work calendar below; `/api/org/leave/*` except reading leave types; `/api/org/attendance/devices/*`; `/iclock/*` |
| Payroll | `/api/org/payroll/*` and the pay endpoints under `/api/org` served by `PayrollController` |
| Core HR (always) | Work calendar: `locations`, `shifts`, `holidays`, `assignments`, employee codes, reading attendance settings. Leave types (read): exit settings name the leave a settlement encashes |

## Payroll without Attendance

- **Where people work.** Payroll takes each person's state (for professional tax and labour welfare fund) from their work location, and working days from their shift, weekly offs and holidays. So the work calendar stays in Core HR. Without Attendance, HR finds it under **Work locations** in the menu, without punch checks, modes, devices or work-from-home settings.
- **Loss of pay.** With Attendance, it comes from unpaid leave and (if set) absence. Without it, HR enters it in the run: *This month's inputs → For: Loss of pay (days)*, in whole or half days, never more than the month has. Days entered are added to whatever attendance gives, so the same input also works for an Organization with both modules.
- **When it counts.** Freezing a run fixes the month's inputs. Switching Attendance on or off affects a run once it is reopened and frozen again.

## Attendance without Payroll

Nothing else changes: attendance, leave and the register work as before. People see no My pay, and the employee record has no Pay tab.

## What the Org Admin sees

*User management → Subscription*: Core HR (included), and Attendance and Payroll, each on (with any trial end) or not included. Changes are asked for from the HRMS team.

## Tests

- `UserModuleTest`: modules by default and as chosen; a trial on, then ended; off and on; bad module, bad or past trial dates, a trial over a year, an unknown Organization, an Org Admin trying; the platform audit.
- `PayrollTest`: a payroll-only month where recorded unpaid leave is not read and the loss-of-pay days entered are; whole or half days, never more than the month; Attendance back on and the run reopened, both count.
- `AttendanceTest`: a device is a stranger while its Organization has no Attendance.
- `e2e/tests/19-modules.spec.ts`: the Super Admin creates a payroll-only Organization on screen; its owner's menu, the refused and allowed requests, Work locations without punch settings, the Subscription tab; an Attendance trial switched on and seen at once; Payroll switched off (menu, requests and roles gone) and on again.
