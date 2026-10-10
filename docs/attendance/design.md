# Attendance Module: Implementation Design

Oct 3, 2026

## Status: built

All six build steps are implemented and pushed to `main` of sohamdatta/myapp (commit `3aeabe4`). 59 backend tests pass against PostgreSQL 16 (22 user module, 37 attendance), and the screens were exercised in a browser. The project has still not been built with Maven, and the office-network check has not been tried behind a real proxy.

Where the build differs from this design:

- Three schema changes, listed on the Schema tab.
- The services take a clock, so tests punch at chosen moments.
- Work locations and shifts are changed with `PUT /locations/{id}` and `PUT /shifts/{id}`.
- The import takes the file as text in a JSON body (up to 5 MB or 50,000 rows), not a multipart upload.
- The register writes times as the work location's clock shows them and has a missing-punch column.
- On screen, today's unfinished day reads "working" or "not in yet" rather than "absent". The stored result is unchanged.

## Scope

This design covers the part of attendance that can be built on the user module as it stands today: punching, work locations, shifts and holidays, the daily result, correction requests, device file import and the day-level trail. It is about 68 of the 100 hours in the attendance feature document.

| In this build | Waits for |
| --- | --- |
| Web and phone-browser punch, checked against office network or location |  |
| Work locations, shifts, weekly offs, holiday calendars |  |
| Daily result: present, half day, absent, holiday, weekly off, on duty, work from home | Leave module, for "on leave" |
| Late, early-exit and short-hours rules, and overtime hours | Payroll and consultant sign-off, for overtime pay and legal limits |
| Regularization, on-duty and work-from-home requests, approved by HR | Employee records with reporting lines, for manager approval |
| Device file import with saved column mapping and a health view |  |
| Day-level trail and attendance register | Payroll, for paid days, cut-off and arrears |

Attendance attaches to the user's membership in the Organization, because the app has no employee record yet. The membership table already carries an empty link to the future employee record, so nothing in this design has to change when that arrives.

## Fit with the user module

Attendance adds no new mechanism. It uses the ones the user module already has, and the schema was loaded on top of the user module's two migrations to confirm that.

| Concern | What the user module does | What attendance does |
| --- | --- | --- |
| Isolation between Organizations | Every table has `tenant_id` and forced row-level security with the policy `tenant_id = app.current_tenant()` | The same policy, by the same loop, on all eight new tables |
| Database roles | `app_tenant` works inside one Organization; `app_platform` and `app_auth` cannot reach the `app` schema | Attendance runs only on `app_tenant`. The console gets no attendance access |
| Cross-Organization references | Composite foreign keys on `(tenant_id, id)` | Every link to a membership, location, shift, import or request uses the same composite key |
| Who the request is | `SessionCtx` built from the session row on each request; Organization never read from the client | Unchanged. Attendance services take the same `SessionCtx` |
| Permission checks | Permission codes in a seeded table; `ctx.require(...)`; roles are bundles | Four new codes seeded the same way; no role names in code |
| Scopes | `self`, `team`, `assigned`, `tenant` on each role permission | `self` and `tenant` enforced now; `team` is seeded for Manager and returns nothing until reporting lines exist |
| Role-granting rule | A role can be granted only by someone holding its non-self permissions | Verified unchanged: an HR Admin can still grant Employee, Manager and HR Admin, and nothing else |
| Records are kept | Memberships are never deleted; the audit table is add-only | Punches and import batches are add-only; corrections are new rows, not edits |
| Own actions | Nobody changes their own roles or status | Nobody approves their own attendance request, enforced by a database check |
| Support sessions | Read-only by default; shown in the Organization's audit log | Support can read attendance. In write mode it can manage settings but cannot punch, because it has no membership |
| Audit log | `app.audit_event`, written in the same transaction as the change | Same table and helper, with new `attendance.*` actions |
| Migrations | Numbered SQL files run by `Migrator` at startup | One new file, `V3__attendance.sql`, added to the list |
| Code layout | Plain-Java services in `core/`, thin Spring controllers in `web/`, React pages in `src/pages/` | Same three places, same naming |
| Errors | `ApiException` with a status, a code and a message; database rule violations translated in `Db` | Same, with new codes and three new translations |
| Time | Server runs in UTC; timestamps stored with time zone | Same. Each work location carries its own time zone for deciding which day a punch belongs to |

## Data model

Eight new tables in the `app` schema and one new column on `membership`. The full SQL is on the Schema tab; it loaded cleanly on PostgreSQL 16 after the user module's migrations and passed 15 access and integrity checks (see Tests).

| Table | Holds | Key columns | Rules |
| --- | --- | --- | --- |
| `membership` (existing) |  | New: `employee_code` | Unique within an Organization, ignoring case. Used to match device files to users |
| `work_location` | An office or site | `name`, `timezone`, `state_code`, `latitude`, `longitude`, `radius_m`, `allowed_ips`, `punch_check` | `punch_check` is `none`, `ip`, `location` or `either`. Radius 25 to 5,000 metres. `state_code` is unused now and will select state rules for overtime |
| `shift` | Working hours | `start_time`, `end_time`, `grace_minutes`, `full_day_minutes`, `half_day_minutes` | An end time earlier than the start means the shift ends the next day |
| `holiday` | A holiday date | `on_date`, `name`, `work_location_id` | No location means every location. One row per location and date |
| `attendance_assignment` | Which location, shift and weekly offs apply to a user, from a date | `membership_id`, `work_location_id`, `shift_id`, `weekly_offs`, `effective_from` | Rows are added, never edited, so a past month always recomputes the same way |
| `punch` | Every punch as it happened | `membership_id`, `at`, `direction`, `source`, `ip`, `latitude`, `longitude`, `accuracy_m`, `check_result` | Add-only: the application role has no update or delete right. `source` is `web`, `import`, `manual` or `request`. A manual punch needs who entered it and a reason. The same imported punch cannot be stored twice |
| `import_batch` | One uploaded device file | `device_label`, `file_name`, `mapping`, row counts, `rejected_sample` | Add-only. The latest batch per device label supplies the saved column mapping |
| `attendance_request` | A correction, on-duty or work-from-home request | `kind`, `work_date`, `requested_in`, `requested_out`, `reason`, `status`, `decided_by` | One pending request per user, day and kind. Cannot be decided by its author. A regularization needs both times, out after in |
| `attendance_day` | The worked-out result for one user on one day | `status`, `first_in`, `last_out`, `worked_minutes`, `late_minutes`, `early_minutes`, `overtime_minutes`, `incomplete`, `trail` | Always derived; safe to delete and rebuild. `trail` lists the steps that produced the result and is what the employee sees |

Punches and results are kept apart on purpose. A punch is a fact and is never changed. A day's result is a calculation and can always be redone from the facts, which is the same principle as the payroll run in the solution design.

## Permissions and roles

Four new permission codes join the existing 16, seeded in the same migration as the tables. All means across the Organization, Assigned means the user's assigned entities, Team means their reporting line, Self means their own records, and a blank means none.

| Role | `attendance.punch` | `attendance.read` | `attendance.approve` | `attendance.manage` |
| --- | --- | --- | --- | --- |
| Org Admin | All | All | All | All |
| HR Admin |  | All | All | All |
| Manager |  | Team | Team |  |
| Employee | Self | Self |  |  |
| Payroll Operator |  | Assigned |  |  |
| Payroll Approver |  | Assigned |  |  |
| Auditor |  | All |  |  |
| Ex-employee |  | Self |  |  |
| Support (read) |  | All |  |  |
| Support (write) |  | All |  | All |

What each code allows:

- `attendance.punch`: punch in and out, and raise correction requests, for oneself.
- `attendance.read`: view punches, day results and requests.
- `attendance.approve`: approve or reject requests, never one's own.
- `attendance.manage`: locations, shifts, holidays, assignments, employee codes, manual punches and device imports.

The user module's guard only checks for a permission across the Organization. Attendance is the first module where scope matters, so `SessionCtx` gains one method, `scopeOf(permission)`, that returns the widest scope the user holds. Services turn it into a filter:

| Scope held | Records the user can reach |
| --- | --- |
| All | Every user in the Organization |
| Assigned | Every user, until entity-level access is switched on, as in the user module design |
| Team | Nobody yet. Returns an empty list until employee records carry reporting lines |
| Self | Only rows with the user's own membership |

A user needs the Employee role to punch. An HR Admin invited without it can manage attendance but has no punch button, which is the existing behaviour of roles and needs no special case. The Org Admin is seeded with every permission at Organization scope, as in the user module; a punch is still always for oneself.

## Rules

### Accepting a punch

The server decides the time and the direction; the browser supplies only its location.

1. The user must hold `attendance.punch` and have an assignment in force today. Without one the punch is refused with `attendance.not_assigned`.
2. The time is the server's clock. A time sent by the browser is ignored.
3. The direction alternates: the first punch of the day is in, the next is out. A second punch within 60 seconds is refused as a double tap.
4. The location's `punch_check` decides what is verified. `ip` needs the request's address inside `allowed_ips`. `location` needs the browser's position within `radius_m` of the location, with a reported accuracy of 200 metres or better. `either` accepts whichever passes. `none` accepts everything.
5. A punch that fails the check is refused and not stored. The response says which check failed and by how much, for example "You are 640 m from Kolkata office; the limit is 200 m".
6. The punch row records the address, the position and which check passed, then the day is recalculated.

The request's address is taken from the forwarded-for header only when the app is configured as running behind a known proxy. Otherwise an employee could type any address into that header and pass the office-network check.

### Which day a punch belongs to

A punch belongs to a work date in the location's time zone. For a shift that ends the next day, a punch up to four hours after the shift's end still belongs to the day the shift started.

### Working out a day

`DayCalculator` is a pure function. It takes the assignment, the shift, the holidays, the day's punches and the day's approved requests, and returns the result and its trail. It reads nothing else and has no clock, so it can be tested without a database. Steps, first match wins:

1. No assignment in force: `not_assigned`.
2. An approved on-duty or work-from-home request: that status, counted as a full day.
3. A holiday for the location: `holiday`. Punches on a holiday are kept and counted as overtime minutes.
4. A weekly off: `weekly_off`, with the same treatment of punches.
5. No punches: `absent`.
6. Otherwise pair the punches in order, in with out, and add up the worked minutes. An unpaired punch sets `incomplete` and contributes nothing.
7. Worked minutes at or above `full_day_minutes`: `present`. At or above `half_day_minutes`: `half_day`. Below that: `absent`.
8. Late minutes are the first in after shift start plus grace. Early minutes are the last out before shift end. Overtime minutes are worked minutes beyond `full_day_minutes`.

An approved regularization adds two punches with source `request`, linked to the request, and the day is recalculated. The original punches stay. Each step writes one line to the trail, for example "Shift General 09:30 to 18:30, grace 10 min", "First in 09:52, 12 min late", "Worked 7 h 05 min, below full day of 8 h", "Result: half day".

### When days are calculated

A day is recalculated whenever one of its inputs changes: a punch, an approved request, an import, or a new assignment or holiday covering it. Days with no activity are filled in when someone opens a month that contains them, up to yesterday. There is no scheduled job, which keeps the first version free of background processing.

## API

All endpoints sit under `/api/org/attendance`, beside the user module's `/api/org` endpoints, and take the same `Authorization: Bearer` header. The Organization comes from the session. Errors use the same `{code, message}` body.

| Method | Path | Requires | Rules |
| --- | --- | --- | --- |
| GET | `/me/today` | `attendance.punch` | Today's punches, result, shift and location, and whether the next punch is in or out |
| POST | `/me/punch` | `attendance.punch` | Body: optional `latitude`, `longitude`, `accuracy`. Rules under Accepting a punch |
| GET | `/me/days?month=2026-10` | `attendance.read` | Own day results for the month, each with its trail |
| GET | `/me/requests` | `attendance.punch` | Own requests |
| POST | `/me/requests` | `attendance.punch` | Body: `kind`, `workDate`, `requestedIn`, `requestedOut`, `reason`. Date within the last 31 days and not in the future |
| POST | `/me/requests/{id}/cancel` | `attendance.punch` | Pending and own only |
| GET | `/days?date=2026-10-03` | `attendance.read` | Everyone in scope on one date: the daily board |
| GET | `/users/{id}/days?month=` | `attendance.read` | One user's month, if that user is in scope |
| GET | `/requests?status=pending` | `attendance.approve` | Requests in scope |
| POST | `/requests/{id}/approve`, `/reject` | `attendance.approve` | Not one's own. Body: optional `note` |
| POST | `/users/{id}/punches` | `attendance.manage` | Manual punch. Body: `at`, `direction`, `reason` |
| GET, POST | `/locations` | read: `attendance.read`; write: `attendance.manage` |  |
| PUT | `/locations/{id}` | `attendance.manage` | Includes deactivating |
| GET, POST | `/shifts` | as locations |  |
| PUT | `/shifts/{id}` | `attendance.manage` |  |
| GET, POST | `/holidays` | as locations |  |
| POST | `/holidays/{id}/delete` | `attendance.manage` | Recalculates the affected day |
| GET | `/assignments` | `attendance.read` | Current assignment per user |
| POST | `/assignments` | `attendance.manage` | Body: `membershipIds`, `workLocationId`, `shiftId`, `weeklyOffs`, `effectiveFrom`. Several users at once |
| PUT | `/users/{id}/employee-code` | `attendance.manage` |  |
| POST | `/imports/preview` | `attendance.manage` | Body: file text and device label. Returns the first rows and the saved mapping for that label |
| POST | `/imports` | `attendance.manage` | Body: file text, device label, mapping. Returns the batch with its counts |
| GET | `/imports` | `attendance.manage` | Batches, newest first, with the health figures |
| GET | `/register?month=` | `attendance.read` at All | Attendance register as CSV |

State changes use POST with the action in the path, as in the user module.

## Code structure and screens

### Backend

New files go beside the user module's, in the same two packages. Services are plain Java on `Db` and `Sql`; controllers only read the session and call a service.

| File | Package | Responsibility |
| --- | --- | --- |
| `V3__attendance.sql` | `db/migration` | Tables, policies, grants, permissions. Added to the list in `Migrator` |
| `DayCalculator` | `core` | The pure function that turns a day's inputs into a result and trail. No database access |
| `PunchChecks` | `core` | Distance between two positions, address-in-range test, work date for a punch |
| `AttendanceService` | `core` | Punch, own days, daily board, requests and their approval, manual punch, recalculation |
| `AttendanceSettingsService` | `core` | Locations, shifts, holidays, assignments, employee codes |
| `AttendanceImportService` | `core` | Parse a device file, apply the mapping, match users, store the batch and punches |
| `AttendanceController` | `web` | The `/me`, `/days`, `/requests` and `/users` endpoints |
| `AttendanceSettingsController` | `web` | Settings and import endpoints |
| `SessionCtx` (changed) | `core` | Adds `scopeOf(permission)` |
| `Db` (changed) | `core` | Translates three more database errors into API errors |
| `Requests` (changed) | `web` | Adds reading the client address |
| `AppConfig` (changed) | `web` | Creates the three new services; one new setting for trusting the forwarded-for header |

### Frontend

| File | Change |
| --- | --- |
| `src/api.js` | Adds an `attendance` group of calls, like `org` and `platform` |
| `src/pages/MyAttendance.jsx` | New. The employee's screen |
| `src/pages/Attendance.jsx` | New. The HR screens |
| `src/pages/Organization.jsx` | Adds the tabs, shown by permission as the existing tabs are |

### Screens

| Tab | Shown to | Contents |
| --- | --- | --- |
| My attendance | Holders of `attendance.punch` | Punch button showing in or out, today's punches and shift; month list of days with status, hours and a late mark; opening a day shows its trail; "Request a correction" form; own requests with their status |
| Attendance | `attendance.read` beyond Self | Daily board for a chosen date: each user's status, first in, last out, hours, late minutes, with counts by status. Opening a user shows their month |
| Requests | `attendance.approve` | Pending requests with the day's existing punches beside each, Approve and Reject with a note |
| Attendance settings | `attendance.manage` | Locations, shifts, holidays, assignments (assign several users at once), employee codes |
| Device import | `attendance.manage` | Upload, column mapping with a preview of the first rows, result counts, and the health view: last file per device, days with no punches, duplicates skipped, rows rejected and why |

With this build an Employee no longer lands on the "nothing to show for your roles" message; they land on My attendance. The punch button asks the browser for its position only when the location's check needs it.

## Audit events and errors

Changes to settings and decisions go to the Organization's audit log through the existing helper. Ordinary punches do not: the punch table is already a complete, add-only record of them.

| Action | Written when |
| --- | --- |
| `attendance.location_created`, `attendance.location_changed` | A location is added or edited |
| `attendance.shift_created`, `attendance.shift_changed` | A shift is added or edited |
| `attendance.holiday_added`, `attendance.holiday_removed` | A holiday is added or removed |
| `attendance.assigned` | Users are given a location, shift and weekly offs |
| `attendance.employee_code_set` | An employee code is set or changed |
| `attendance.manual_punch` | HR enters a punch for a user, with the reason |
| `attendance.request_approved`, `attendance.request_rejected` | A request is decided |
| `attendance.imported` | A device file is imported, with its counts |

Actions taken in a support session carry the support actor and grant, as every audit event already does.

| Error code | HTTP | When |
| --- | --- | --- |
| `attendance.not_assigned` | 409 | The user has no location and shift in force |
| `attendance.outside_location` | 403 | The position is beyond the location's radius, or too inaccurate |
| `attendance.outside_network` | 403 | The address is not in the location's allowed ranges |
| `attendance.location_required` | 400 | The location check needs a position and the browser sent none |
| `attendance.too_soon` | 409 | A second punch within 60 seconds |
| `attendance.request_exists` | 409 | A pending request already exists for that day and kind |
| `attendance.request_window` | 400 | The request date is in the future or more than 31 days back |
| `attendance.own_request` | 409 | Deciding one's own request |
| `attendance.code_taken` | 409 | The employee code is used by another user |
| `attendance.import_mapping` | 400 | The mapping does not name the user, time and direction columns |
| `permission.denied`, `not_found` | 403, 404 | As in the user module. A user outside the caller's scope is reported as not found |

The pending-request, own-request and employee-code rules are enforced by the database as well. `Db` translates those three violations into the codes above, the same way it already handles the last Org Admin guard.

## Tests

Only the schema has been tested so far. It was loaded on PostgreSQL 16 after the user module's two migrations, and 15 checks were run with two Organizations. All behaved as designed:

- The console and sign-in database roles are refused on attendance tables.
- With no Organization set, the application role sees no rows.
- One Organization sees none of another's locations, punches or holidays.
- Writing a punch for another Organization's user is rejected by row-level security, and attaching one's own row to another Organization's user or location is rejected by the foreign keys.
- Punches cannot be updated or deleted.
- A manual punch without a reason and the person who entered it is rejected.
- The same imported punch cannot be stored twice.
- A second pending request for the same day and kind is rejected; a request cannot be approved by its author; a regularization without both times is rejected.
- A day's result can be written and then replaced.
- An employee code cannot be reused within an Organization, whatever the letter case.
- An Employee holds punch and read for Self; an HR Admin holds read, approve and manage across the Organization; the role-granting results from the user module are unchanged.

Tests to write with the code, none of which exist yet:

| Test class | Kind | Covers |
| --- | --- | --- |
| `DayCalculatorTest` | Plain unit test, no database | Each of the eight steps; late and early minutes around the grace boundary; a shift that ends the next day; an unpaired punch; punches on a holiday; a regularization replacing a missing out |
| `PunchChecksTest` | Plain unit test | Distance at, inside and outside the radius; address inside and outside a range; work date at midnight and across time zones |
| `AttendanceTest` | Against PostgreSQL, like `UserModuleTest` | Punch accepted and refused for each check mode; double tap; request raised, approved by HR, refused for own request; scope: an Employee cannot read another user's days, HR can; a second Organization cannot see or change any of it; import with duplicates and unmatched codes; support session can read and cannot punch |

The database tests reuse the helpers in `UserModuleTest` (a new Organization with its Org Admin, and joining a user with roles), so those move to a shared test class.

## Build steps

Six steps, 68 hours, in an order where each step can be shown working before the next begins. The hours are the estimates from the attendance feature document and have not been tested against real work.

| Step | Hours | Delivers | Done when |
| --- | --- | --- | --- |
| 1. Settings | 10 | Migration; locations, shifts, holidays, assignments, employee codes; the settings tab | HR can set up an office and assign users, and a second Organization cannot see it |
| 2. Punch | 10 | `PunchChecks`, punch endpoint, My attendance tab with the button and today's punches | An Employee punches in and out; a punch outside the office network or radius is refused with a clear reason |
| 3. Daily result | 14 | `DayCalculator` with its unit tests; month list; daily board for HR | A month shows the right status, hours and late marks for the test cases, and the board matches |
| 4. Requests | 8 | Regularization, on-duty and work-from-home requests; the Requests tab; manual punch by HR | An approved correction changes the day's result and appears in the audit log |
| 5. Device import | 20 | File parsing, column mapping with preview, user matching, health view | The same file imported twice adds nothing the second time, and unmatched rows are listed with the reason |
| 6. Day trail and register | 6 | Trail shown on each day; register download | An employee can read why a day was marked as it was without asking HR |

Steps 1 to 4 are the piece worth building first: 42 hours and no device involved. Step 5 is the largest single item and can wait until a customer has a device to import from.

## Open decisions

- **A punch that fails the location check: refuse it, or store it flagged for HR to review?** This design refuses it. Storing it flagged is kinder to an employee with a poor signal but gives HR a queue to clear.
- **Who approves requests until managers exist?** This design gives it to HR Admin and Org Admin. The alternative is to add a simple "approver" field per user now and replace it when reporting lines arrive.
- **Does an HR Admin punch?** Only if they also hold the Employee role, which the Org Admin chooses when inviting them.
- **Half-day and full-day thresholds.** Defaults are 4 and 8 hours per shift. Some employers count a half day by which half was worked, not by total hours.
- **Request window.** 31 days back is proposed. Once payroll exists this should become the payroll cut-off.
- **Overtime minutes.** They are recorded here as hours beyond a full day, with no pay and no legal limit attached. Both wait for payroll and the consultant's sign-off on state rules.
