# Attendance modes: present by default, and biometric devices

Oct 18, 2026 · built in V15

People forget to punch. Each work location now chooses how attendance is recorded there:

| Mode | What happens | Good for |
|---|---|---|
| **Punch in the app** (`app`) | As before: people punch on their phone or computer, checked against the office network or position. | Offices where the app is used every day |
| **Biometric device** (`device`) | An eSSL or ZKTeco machine at the door sends each punch to the HRMS by itself (cloud push, "ADMS"). The app's punch button is off, except on a work-from-home day. | Offices and plants that already have a fingerprint or face machine |
| **Present by default** (`present`) | Nobody punches. Every working day counts as present unless HR, or the person's manager, marks it absent or a half day, with a reason. | Small offices; staff who work on trust |

Leave, holidays, weekly offs, on-duty and work-from-home days work the same in every mode.

## Choosing the mode

*Attendance → Setup → Work locations → Attendance mode.* A mode applies **from a date**. Days before it keep the mode they had, so past results never change. The date cannot fall in a month whose payroll is approved, or in a month already over: `PayrollConfig.openFrom`, the same rule pay changes follow. A change can be set ahead; the location list shows it ("Present by default from 1 Nov 2026").

Stored in `app.location_mode` (location, mode, effective from). Rows are only added; the newest row for the latest start date wins, as with attendance assignments. A location with no row is `app`. `AttendanceService.assigned()` reads the mode in force on the day, so every calculation, the board and payroll see it.

## Present by default

**Calculation.** `DayCalculator` gets `presentByDefault` and the day's `Mark`. After whole-day leave, on-duty, a full day at home, holidays and weekly offs (unchanged), a working day is:

| Mark | Result |
|---|---|
| none | present ("Present by default; no exception marked") |
| absent | absent, or a half day when half the day is leave or at home |
| half day | half day, or present when the other half is leave or at home |

Punches at such a location, if any, are kept on the day for reference and do not decide it. Lateness and overtime are not measured. Payroll's loss of pay follows: a marked absence counts when *absence is loss of pay* is on, as at any location.

**Marking.** On the daily board, a person at a present-by-default location shows "present by default" or "marked by hand"; *Details* opens *Mark the day*: absent, half day, or present to take a mark back, and a reason of at least 5 characters. `POST /api/org/attendance/users/{id}/marks {date, status, reason}`.

- Who: anyone with `attendance.approve` over the person: HR for everyone, a manager for their team. Never for themselves (`attendance.own_mark`, also a check constraint).
- Which days: today or earlier, from the joining date, on a working day of a present-by-default location. Refused: a day ahead (apply for leave instead), a holiday or weekly off, a day of whole-day leave, a location that counts punches, marking what is already marked, taking back when nothing is marked.
- Stored in `app.attendance_mark`, rows only added; the newest for the date is in force. The day's trail says who marked it and why ("Marked absent by Ravi Kumar: Did not come in"), and the audit log has `attendance.marked`.

**The employee** sees "No punching needed" in place of the punch button, the day's result as the headline, and the mark in the day's steps. A punch is refused with `attendance.present_by_default`.

## Biometric devices

**How the device talks.** eSSL and ZKTeco devices with cloud push speak a plain-text protocol at `/iclock` on the server they are pointed at:

| Call | Meaning | Answer |
|---|---|---|
| `GET /iclock/cdata?SN=…` | The device starts and asks for its settings | `GET OPTION FROM: SN`, `ATTLOGStamp=` (where it got to), attendance only, real time |
| `POST /iclock/cdata?SN=…&table=ATTLOG&Stamp=…` | Part of its log, one punch a line: user id TAB `yyyy-MM-dd HH:mm:ss` TAB status … | `OK: n` |
| `POST /iclock/cdata?…&table=OPERLOG` (or photos, user lists) | Other tables | `OK: 0`, dropped |
| `GET /iclock/getrequest`, `POST /iclock/devicecmd` | Asks for commands | `OK` (there are none) |

`DevicePushController` reads the query string itself: a device may label its body as a form, and asking the servlet for parameters would then swallow the punches.

**Who may call.** A device proves nothing but its serial number, so:

1. HR registers the device first (name, serial number, work location). A serial number belongs to one device on the whole platform (`attendance_device_serial_uq`); registering it again is refused, in any Organization. `auth.device_for_serial()` finds the Organization for an active device of an active Organization, and nothing else; everything after runs inside that Organization with row-level security.
2. When the location lists office network addresses, calls from anywhere else are refused (403), and the device row records when and why ("Called from 198.51.100.9, which is not an office network address of Hosur warehouse") for HR to see.
3. A switched-off device is a stranger (404). Bodies are capped at 2 MB and 20,000 lines.

**What becomes of each line.** The user id is matched to the employee code, as with device files. Each line is logged in `app.device_punch_log` with its outcome: taken, already here, nobody has this employee code, in the future (more than 10 minutes ahead: the device clock is fast), could not be read. Times are read in the location's time zone. A punch is stored once (`punch_device_uq`), with source `device`. Unknown codes and bad lines are still answered `OK` so the device moves on; they wait in the log for HR.

**In and out.** Few people press the device's In/Out keys, so its status column is ignored. The calculation takes device punches in and out by their order through the work day (`DayCalculator.byOrder`); a punch HR enters keeps its direction and the device carries on from it. A late upload of an earlier punch therefore still comes out right.

**The employee** at a device location sees "Punch at the attendance device" and their punches marked "from the attendance device". The app refuses a punch there (`attendance.device_location`) unless they work from home that day.

**HR's view.** *Attendance → Devices*: each device with its location, when it last called and from where, punches in the last day, and what needs a look (not heard from yet, silent for a day, turned away, user ids nobody has), with the last 50 lines and what became of each. The register form carries the set-up steps:

1. Register the device with its serial number (System info, or the label at the back).
2. On the device: Comm. → Cloud Server Setting (ADMS). Enable domain name; server address = the HRMS address.
3. Port 443 with HTTPS on if the device offers it; otherwise port 8081 with HTTPS off.
4. Each person's user ID on the device = their employee code.
5. Set the location's mode to Biometric device.

**Hosting.** Caddy passes `/iclock/*` to the app on the main site (HTTPS), and also listens on a plain-HTTP port for devices that cannot do HTTPS: `DEVICE_PORT`, 8081 unless set, which answers nothing but `/iclock/*`. On Lightsail, open that port in the instance firewall if such a device is used. Device file uploads (Attendance → Devices → Upload a device file) still work for devices without cloud push.

## API

| Method and path | Who | What |
|---|---|---|
| `POST /api/org/attendance/locations/{id}/mode` `{mode, effectiveFrom}` | `attendance.manage` | Change a location's mode from a date |
| `GET /api/org/attendance/locations` | as before | Now also `mode`, `nextMode`, `nextModeFrom`, `devices` |
| `GET /api/org/attendance/settings` | as before | Now also `modeFrom`, the earliest date a mode can change |
| `POST /api/org/attendance/users/{id}/marks` `{date, status, reason}` | `attendance.approve` over the person | Mark an exception |
| `GET /api/org/attendance/days` | as before | Each row now has `mode` and `mark` |
| `GET/POST /api/org/attendance/devices`, `PUT …/{id}`, `GET …/{id}/log` | `attendance.manage` | Devices |
| `/iclock/*` | a registered device | The push protocol |

## Audit events and errors

Audit: `attendance.mode_changed`, `attendance.marked`, `attendance.device_registered`, `attendance.device_changed`.

Errors: `attendance.present_by_default`, `attendance.device_location`, `attendance.mode_date`, `attendance.mode_unchanged`, `attendance.own_mark`, `attendance.not_present_by_default`, `attendance.day_off`, `attendance.already_marked`, `attendance.nothing_to_undo`, `device.serial`, `device.unknown`, `device.outside_network`, `device.serial_taken`, `device.too_many_lines`, `device.too_large`.

## Tests

- `DayCalculatorTest`: present by default with and without marks, half-day leave meeting a mark, punches that do not decide, device punches by order and mixed with a manual punch.
- `AttendanceTest`: marks and their rules, mode dates, the device handshake, an upload with every outcome, the same upload twice, the office network check, serial numbers once across Organizations, switching a device off, lenient line reading.
- `e2e/tests/18-attendance-modes.spec.ts`, in an Organization of its own: the Org Admin sets both modes on screen (and is refused a date in a closed month); an employee has nothing to punch; her manager marks her absent with a reason and she sees who did it; a device is registered (twice refused), calls in from the test as a real device would, sends a log with every kind of line, and is shown with its problems and log; the employee at the device location sees the device's punches; a device off its office network is turned away.
- The deployment check in CI asks both device routes for an unregistered serial and expects the app's own refusal.

## Not built yet

- Commands to the device (pushing the user list from the HRMS, setting its clock).
- Device photos and face templates; only the attendance log is read.
- Mobile push reminders for people who still punch in the app.
