# Configuration: design, as built (stage 1)

The full design is the live document on claude.ai ([Configuration design](https://claude.ai/artifact/3wSpJUPbWwxVxE4KtNi3Ug)). This page records what stage 1 built (migration `V18__settings.sql`) and how to add a setting.

## What a setting is

A setting is declared once, in code, in `core/Settings.java`. The declaration says what it means and how it behaves. The database stores only values and the history of changes, never definitions.

| Part of the declaration | What it does |
| --- | --- |
| `key` | Dotted lower case, for example `auth.invitation.valid_days`. Never reused for a different meaning. |
| `group`, `label`, `help`, `unit` | Where the setting appears on the Settings screen, and the words shown with it. |
| type | `integer` with a range, `bool`, or `choice` from a list. |
| layer | `PLATFORM` (one value for every Organization, set in the console) or `ORGANIZATION`. A `PERSONAL` layer is reserved. |
| scopes | Where an Organization can vary the value: always the Organization; optionally by location **or** by pay group (never both, so there is no tie to break), and later by person. |
| `dated()` | Values take effect from a date. A value is read for the day it applies to, not for today. |
| `owner` | The permission that may change it, for example `tenant.manage`. Holders of `audit.read` may see it, read-only. |
| `module` | Hidden when the Organization does not have that module. |
| `weakens(direction, approval)` | Which direction lowers safety or moves money (`UP`, `DOWN`, `ANY`), and what a change in that direction needs: `PASSWORD` (the person confirms their password; this becomes an MFA code once MFA exists) or `SECOND_PERSON` (another owner approves). |

## How a value is found

`Settings.resolve` is a pure function, tested without a database. For a key, a place (Organization, location, pay group) and a date, it returns:

1. The narrowest scope that has a value in force on that date: location or pay group, then the Organization.
2. Otherwise the platform's value for every Organization.
3. Otherwise the default in the declaration.

Then the platform's bounds apply. A stored value outside a narrowed range is held at the bound and marked `clamped`. A locked key always takes the platform's fixed value.

The result carries its `source`, for example `location from 2026-09-01`. It also carries the trail of every step, so the screen can say where a value comes from and `GET /api/org/settings/{key}` can explain it.

Reading is cheap. `Settings.get` keeps a per-Organization cache that is checked against `app.setting_epoch`. Every change bumps the epoch in the same transaction, so a change is seen by the next request on any server.

## How a value changes

Every change is a row in `app.setting_change`. Its fields: key, scope, start date, old and new value, reason, assurance (`none`, `password`, `second_person`) and status (`applied`, `pending`, `rejected`, `withdrawn`, `undone`). A trigger keeps decided rows final.

1. **Checks.** The key, scope and place must exist. The value must be within the declaration's range and the platform's bounds, and the key must not be locked. The value must actually change, and there must be no change already waiting for that key and place.
2. **Weakening.** The value is resolved before and after the change. If it moves in the weakening direction, the declared approval applies:
   - With password confirmation, a missing password is refused with `setting.password_required`, which the screen answers by asking for it. A wrong password is refused with `setting.password_wrong`.
   - With a second person, the change is saved as `pending` and every other owner is notified. The requester can withdraw it. Another owner approves it, or rejects it with a note. The requester can never decide their own change.
3. **Apply.** The value is written to `app.setting_value` and the epoch is bumped. For dated attendance keys, attendance days from the start date are recalculated. Dated payroll keys may not start in a month whose payroll is already approved (`setting.dated_locked`).
4. **Notice.** A weakening change sends `setting_weakened` to the other owners. Pending and decided changes send `setting_pending` and `setting_decided`.
5. **Undo.** For 30 days, an applied change can be undone. The undo is a new change back to the old value, through the same checks (so undoing a safer change may ask for the password). It is refused if the value has moved on since.

## Settings in stage 1

| Key | Layer | Default and range | Weakens | Used by |
| --- | --- | --- | --- | --- |
| `auth.lockout.after_failures` | Platform | 5 (3 to 10) | up | Sign-in locks the identity after this many wrong passwords |
| `auth.lockout.minutes` | Platform | 15 (5 to 60) | down | How long the lock lasts; the message says how long |
| `auth.invitation.valid_days` | Organization | 7 (1 to 30) | up, password | Invitations and resends |
| `leave.apply.back_days` | Organization | 31 (0 to 90) | — | How far back leave may be applied for |
| `leave.apply.ahead_days` | Organization | 365 (30 to 730) | — | How far ahead |
| `attendance.request.back_days` | Organization, by location | 31 (0 to 90) | — | Attendance corrections and work-from-home requests |
| `attendance.request.ahead_days` | Organization, by location | 60 (0 to 365) | — | How far ahead |

Lockout is a platform setting, not an Organization one. One sign-in identity can belong to several Organizations, so no single Organization can own its lockout. The live design document has been corrected to match.

## API

| Call | What it does |
| --- | --- |
| `GET /api/org/settings?group=` | The groups the caller may see, and one group's settings: value, source, range, values per place, pending changes, and whether the caller may change it |
| `GET /api/org/settings/{key}?scope=&scopeId=&asOf=` | One value resolved for a place and date, with the trail |
| `PUT /api/org/settings/{key}` | `{scope, scopeId, validFrom, value, reset, reason, password}` returns the change (`applied` or `pending`) and the value now in force |
| `GET /api/org/settings/changes?key=` | The history, newest first |
| `POST /api/org/settings/changes/{id}/approve`, `reject`, `withdraw`, `undo` | Decide, withdraw or undo a change |
| `GET /api/platform/settings`, `PUT …/{key}`, `PUT …/{key}/bound` | The console: platform values, ranges and locks |

## Adding a setting

1. Declare it in `Settings.java` with a clear default. The default must be today's behaviour, so nothing changes until someone chooses to.
2. Replace the constant in the code with `Settings.get(c, Settings.KEY).asInt()`, inside the tenant transaction you are already in. Use `Settings.platform(c, KEY)` for platform keys, and pass a `Context` and date for scoped or dated keys.
3. Add a line to the table above and a test that the new value takes effect.

No migration is needed. Values for a key nobody has changed are not stored.

## Tests

- `SettingsTest`: the resolver, without a database; then against PostgreSQL:
  - the password and second-person flows
  - undo and history
  - platform bounds and locks
  - dated values per location
  - the leave window
  - lockout from the platform setting
- `e2e/tests/22-settings.spec.ts` (project `settings`, which brings its own Organization) covers the screen:
  - a safer change, a weaker change with the password, history and undo
  - the phone layout
  - an employee seeing nothing
