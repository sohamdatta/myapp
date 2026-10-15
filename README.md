# HRMS

[![CI](https://github.com/sohamdatta/myapp/actions/workflows/ci.yml/badge.svg)](https://github.com/sohamdatta/myapp/actions/workflows/ci.yml)

A multi-tenant HRMS: a React frontend and a Java (Spring Boot) backend on
PostgreSQL. Built so far: **users and access**, **attendance**, **leave and
work from home**, the **employee record**, and each Organization's
**onboarding configuration** (milestones 1 and 2 of onboarding). Design documents are in [`docs/`](docs/README.md).

There are three levels:

```
Super Admin                 runs the platform; creates and manages Organizations
└── Organization            one customer account; all its data is isolated
    ├── Org Admin           full control of that Organization
    └── User                Employee, plus any roles an admin assigns
```

The Super Admin manages Organizations but has **no standing access to the data
inside them**. To look inside one, a platform user requests support access to
that Organization with a reason; the access lasts 60 minutes, and it is
recorded in the Organization's own audit log, where its Org Admin can see it.

## Status

Read this before relying on it.

- **Every push is built and tested automatically** (see "Automated tests"
  below). The first run, on commit `6f303d5`, built the backend with Maven on
  Spring Boot 4.1.1, passed all 88 backend tests against PostgreSQL 16, and
  passed the 23 browser tests against the app started from that build.
  The badge at the top shows the latest result.
- **Set `APP_DATA_KEY` before storing real employee data.** Without it, PAN
  and bank account numbers are encrypted with a fixed development key that is
  public in this repository. See "Configuration".
- **Leave needs the PostgreSQL `btree_gist` extension.** It ships with
  PostgreSQL and the migration creates it.
- **The office-network punch check has not been tried behind a real proxy.**
  It was tested with the address passed in directly. See "Attendance" below
  before relying on it.
- **No email is sent.** Invitations produce a link that the screen shows for
  you to copy and send.
- **Not built yet:** multi-factor sign-in, password reset, email change, seat
  limits, custom roles, and limiting a user to particular legal entities or
  departments. In attendance and leave: overtime pay, approval chains beyond
  one approver, leave or work-from-home requests raised by HR for someone
  else's future days at home, comp-off claims raised by the employee, and
  anything for payroll. Leave that is unpaid or marked for encashment is
  recorded; nothing pays or deducts it yet. Onboarding of new joiners
  (milestones 3 to 6 of `docs/onboarding/implementation-plan.md`); the
  onboarding configuration exists but has no screens yet, only the API.

## Run it in GitHub Codespaces

1. Open this repository in a codespace. Java 21, Maven, Node and PostgreSQL 16
   are set up automatically. If you had a codespace from before this change,
   rebuild the container so that PostgreSQL is added.
2. In the terminal, run:

   ```bash
   ./start.sh
   ```

3. Open the forwarded port **5173** (the "App (React)" entry in the Ports tab).
4. Sign in as the first Super Admin: `admin@example.com` / `ChangeMe-12345`.

The first start takes a few minutes while Maven downloads Spring Boot. On
startup the backend creates the database schema and, if the platform has no
platform users, the first Super Admin.

### Try the whole flow

1. Sign in as the Super Admin. You land in the platform console. Create an
   Organization and give the email of its first Org Admin. Copy the invitation
   link.
2. Sign out, open the link, and set a password. You are now the Org Admin.
3. Under **Invitations**, invite a user with the HR Admin role, and open that
   link in a private window. The HR Admin can invite employees and managers,
   but the payroll and admin roles are greyed out for them.
4. Back in the console, request **Support access** to the Organization and
   start a session. Then look at the Organization's **Audit log** as its Org
   Admin: the support session is there, with its reason.

### Try attendance

1. As the Org Admin, open **Attendance → Setup**. Add
   a work location (choose "No check" to try it from anywhere) and a shift.
2. Under **People**, tick the users, choose the location and shift, and assign.
3. Open **My attendance** and punch in and out. The month below shows each
   day's result; **Why** shows how it was worked out.
4. Raise a request (a punch correction, on duty, or work from home) as one
   user and decide it as another under **Attendance → Requests**. Nobody can
   decide their own.
5. Under **Device import**, give each user an employee code on the People
   view first, then upload a file exported from a biometric device.

### Try leave and work from home

1. As the Org Admin, open **Leave → Setup** and add leave types, for example
   Casual leave with 12 days a year. Users need a work location and shift
   (step 2 above) before leave days can be counted.
2. As a user with the Employee role, open **My leave**, apply, and watch the
   form say how many days it takes. Approve it as someone else under
   **Leave → Requests**.
3. Under **Leave → Balances**, add an opening balance with **Adjust**, or
   record leave for someone with **Record leave**. **History** shows every
   entry behind a balance.
4. Under **Attendance → Setup → Work from home**, set the limits. Under
   **People**, choose who may ask and assign regular days at home. A user
   then asks under **My attendance → New request**.

Signing in takes each person straight to where they work: their Organization,
or the console for a platform user. Nobody is asked to choose. Someone who
belongs to more than one Organization goes to the one they used last on that
browser, and has a **Switch** button in the header.

## Run it elsewhere

You need Java 21, Maven, Node and a PostgreSQL 16:

```bash
docker run -d --name hrms-db -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=hrms postgres:16
./start.sh
```

## Deploying

To run it on a server, see [Deploying on AWS Lightsail](docs/deploy/aws-lightsail.md):
one server running the app behind Caddy (which handles HTTPS), and a managed
PostgreSQL 16, in the Mumbai region. The files are in [`deploy/`](deploy).

The database login the app is given needs to create roles and databases, but not be a
superuser, which managed databases never give. Where it cannot create a role that
bypasses row-level security, the app's function owner (`app_definer`) gets an equivalent
policy on every protected table instead.

## Configuration

Set these environment variables for anything beyond local development.

| Variable | Default | Purpose |
|---|---|---|
| `APP_DB_URL` | `jdbc:postgresql://localhost:5432/hrms` | The database |
| `APP_DB_OWNER_USER` / `APP_DB_OWNER_PASSWORD` | `postgres` / `postgres` | Runs the migrations at startup and creates the application's database roles. Needs permission to create roles |
| `APP_DB_ROLE_PASSWORD` | `dev-only-change-me` | Password given to the three application roles |
| `APP_SUPERADMIN_EMAIL` / `APP_SUPERADMIN_PASSWORD` | `admin@example.com` / `ChangeMe-12345` | The first Super Admin, created only when there are no platform users |
| `APP_DATA_KEY` | none: a fixed development key, with a warning in the log | Encrypts PAN, UAN, bank account numbers and, later, documents. 32 random bytes, base64: `openssl rand -base64 32`. Keep a copy somewhere safe; values encrypted with a lost key cannot be read again |
| `APP_REQUIRE_DATA_KEY` | `false` | Set to `true` in production, so the app refuses to start without `APP_DATA_KEY` rather than use the development key |
| `APP_DATA_KEY_ID`, `APP_DATA_KEY_PREVIOUS` | `1`, none | Rotating the key: give the new key a new id, and put the old one in `APP_DATA_KEY_PREVIOUS` as `id:key` so existing values can still be read |
| `APP_TRUST_FORWARDED_FOR` | `false` | Set to `true` only when the backend sits behind exactly one proxy that sets `X-Forwarded-For`. The office-network punch check then uses the address that proxy reports |

## How isolation works

The database enforces the separation, not the application.

| Database role | Used for | Can reach |
|---|---|---|
| `app_platform` | The console | Platform tables only. It has no access to the schema that holds Organization data |
| `app_auth` | Sign-in, sessions, invitations | Logins and sessions only |
| `app_tenant` | Everything inside an Organization | Only the rows of the Organization set for the current transaction, through row-level security |

A few database functions are the only crossings between these, such as
listing the Organizations a login may enter and accepting an invitation.
They are listed, with the rest of the schema, in
`backend/src/main/resources/db/migration/V1__user_module.sql`.

The database also enforces these rules itself:

- An Organization always keeps at least one active Org Admin.
- One user cannot be both Payroll Operator and Payroll Approver.
- A support access request cannot be approved by the person who made it,
  unless nobody else can approve and it is flagged as auto-approved.
- Users are suspended or ended, never deleted.

## Attendance

- **Punching.** A user punches in and out from the browser. The server sets
  the time and the direction. Each work location says what a punch needs:
  the office network, a position within a radius of the office, either, or
  nothing. A punch that fails the check is refused and not stored.
- **The office-network check and proxies.** The backend compares the address
  the request came from with the location's list. Behind a proxy that address
  is the proxy's, so set `APP_TRUST_FORWARDED_FOR=true` there. With the Vite
  dev server in front (as `./start.sh` runs it) and in Codespaces, the backend
  does not see the user's real address, so use "No check" or the location
  check when trying it out.
- **Day results.** Each day is worked out from the punches, the shift, weekly
  offs, holidays and approved requests: present, half day, absent, holiday,
  weekly off, on duty or work from home, with late, early and overtime
  minutes. The steps are kept with the result and shown under **Why**.
  Results are recalculated whenever something they depend on changes; there
  is no nightly job.
- **Punches are never edited or deleted.** A correction is a request; when it
  is approved, its two times are added and used instead, and the originals
  stay. HR can add a punch for someone with a reason, which is audited. The
  database role the application uses cannot update or delete a punch.
- **Assignments are dated.** A user's location, shift and weekly offs apply
  from a date. To correct one, assign again from the same date.
- **Night shifts** count to the day they start. A punch up to four hours
  after the shift ends still belongs to that day.
- **Device files.** CSV, semicolon or tab-separated. You say once which
  column holds the employee code, the date and time, and in/out (or none:
  punches then alternate through the day); this is remembered per device.
  Uploading the same file again adds nothing. Rows that could not be used are
  listed with the reason.
- **Who sees what.** Employees see their own days. HR Admin, Org Admin and
  Auditor see everyone. A Manager's team is empty until employee records
  carry reporting lines.

The schema is in `backend/src/main/resources/db/migration/V3__attendance.sql`.

## Leave

- **Leave types** are set per Organization: paid with a yearly quota credited
  at the start of the leave year or month by month, paid with days added only
  by HR, unpaid, or comp-off. Each paid type says how much carries forward and
  whether the rest lapses or is marked for encashment.
- **A balance is a sum.** Every credit, leave taken, leave returned,
  adjustment, lapse and expiry is one entry that is never changed. The
  balance is their total, and **History** lists them. Credits that are due
  are written the next time the balance is read; there is no nightly job.
- **Counting days.** The server counts the days of a request from the user's
  shift, weekly offs and holidays on each date. A leave type can say that
  days off inside a leave count too. A half day is the first or second half
  of a single date.
- **Applying.** A request can start up to 31 days back and end up to 365 days
  ahead. Days already asked for are held against the balance. When the
  balance is short the request is refused, or the rest is taken as unpaid if
  the leave type allows it. Two leaves cannot overlap.
- **Deciding.** Nobody decides their own request. The user can cancel a
  pending request, or an approved one before it starts; HR can cancel
  any approved leave in an open leave year. Cancelling returns the days.
- **On someone's behalf.** HR can record leave as approved for any date in an
  open leave year, and add to or take from a balance with a reason.
- **Comp-off.** Days off that were worked for at least a half day are listed
  under Balances; HR grants a half or whole day, which expires after the
  number of days the comp-off type gives.
- **Year end.** The leave year runs January to December or April to March.
  Closing a year is previewed first and run once: whatever each user holds
  above the carry-forward limit is written off as a lapse or for encashment.
  A closed year accepts no more changes; corrections are adjustments in the
  current year.
- **Attendance.** A day of approved leave shows as on leave; a half day of
  leave needs only a half day of work to be complete.

## Work from home

- **Who may ask** is set per user under Attendance → People, and the whole
  feature can be switched off under Setup.
- **A request** covers a date range, one request per working day, or a half
  day on a single date. Limits per week (Monday to Sunday) and per month can
  be set; pending and approved days both count. An approved day can be
  cancelled by the user until it has passed.
- **Regular days at home** are part of a user's assignment. They need no
  request and do not count towards the limits.
- **Punching from home.** On an approved or regular day at home the punch is
  not checked against the office network or position, and no position is
  stored. Whether such a day still needs punches is a setting; when it does
  not, the day is complete without them.

The schema for both is in
`backend/src/main/resources/db/migration/V4__leave_and_wfh.sql`.

## People

HR keeps a record for each employee under **People**: the person (legal and
preferred name, date of birth, gender, contact), their employment (joining
date, type, employee code), their job (designation, department, reporting
manager), identifiers and bank account.

- **Existing staff are added directly**, with "Add employee". New joiners will
  go through onboarding instead, once it is built. An employee record can be
  linked to a user account; the employee code then also becomes the code
  attendance uses to match device files.
- **Job changes are dated.** A change of designation, department or manager
  adds a row from a date; the earlier ones stay as history, and a future date
  takes effect on that day. Nobody can report to themselves, directly or
  through others, and a department cannot sit inside its own sub-department.
- **Reporting lines make the Manager role work.** A Manager sees and decides
  the attendance and leave requests of the people who report to them, sees
  them on the daily board and the leave calendar, and sees their records
  under People → My team. Nobody else's.
- **Identifiers are encrypted.** PAN, UAN, previous PF member id, ESI number
  and bank account numbers are encrypted with AES-256-GCM before they reach
  the database, with the Organization's id bound in, so a value copied to
  another Organization's row cannot be read. Screens and API responses carry
  the last four characters only. The full value is shown to someone with
  `employee.sensitive` (Org Admin, HR Admin, Payroll Operator, and each
  employee for their own) only on request, with a reason, and every such view
  is in the audit log. Support sessions never see full values.
- **Aadhaar is optional** and only its last four digits are kept; the full
  number is checked (12 digits, Verhoeff check digit) and discarded. A PAN
  must be a person's (fourth letter P) and is unique within an Organization;
  it can be recorded as pending with a reason.
- **The org chart is for everyone.** "Org chart" shows everyone working here
  in their reporting lines: name, designation, department and work email,
  nothing else. Any employee, manager, HR or auditor can open it (the
  `orgchart.read` permission, `V12__org_chart.sql`); joiners and former
  employees cannot. Find someone by name, role or department, see who they
  report to and how big their team is, and open the record when your access to
  employee records allows it (your own, your team's, or everyone's for HR).
  People who report to nobody and lead nobody are listed under the chart, and
  on a phone the chart reads top to bottom.
- **Leaving.** "Start an exit…" on the record opens Exits with the employee
  chosen; see "Exits" below. The day after it, the person's roles are replaced by
  Ex-employee. The record is kept. The only Org Admin cannot be exited.

The schema is `backend/src/main/resources/db/migration/V5__employee_record.sql`.
It also adds the `employee.sensitive` permission and the Joiner role that
onboarding will use.

## Onboarding configuration

Every Organization configures onboarding its own way; nothing in the code
names a customer. HR changes it under **People → Setup**, one tab per area:
flows (in the order they are tried, with their rules in words, welcome,
sections and who checks them), the form (each field hidden, optional or
required, relabelled, for the Organization or one flow; fields of their own;
a preview of the form on a phone), documents, checklists, letters, policies,
messages, employee codes and settings. Letters and messages list the
placeholders they can use and show a sample; employee codes show the next
code as it is typed. Each tab says who changed it last. Everyone with
`employee.read` across the Organization can look; changing needs
`onboarding.configure`. The same configuration is available through the API
under `/api/org/people/setup`.

- **Defaults.** The first time anything reads an Organization's configuration,
  it gets working defaults: settings (invite by hand, links valid 14 days,
  automatic activation, activation without a PAN allowed with a reason), a
  privacy notice to edit, one flow called Everyone with every section and one
  level of verification by HR Admin, five document types, a joining
  checklist of eight tasks, plain message wording and an appointment letter.
- **Flows.** Named flows, each chosen by rules on department, work location,
  employment type and designation. The first active flow, in order, whose
  rules match a joiner is theirs. A new flow with rules goes before the first
  flow for everyone; one flow for everyone must always stay active.
- **Rules** use one syntax everywhere: `{"employment_type": ["intern"],
  "department": ["<id>"]}`. Every attribute listed must match; values within
  one are alternatives; `{}` means everyone.
- **Sections and verification** per flow: which sections show, their order,
  title and introduction, whether each is verified, and 0 to 3 levels of
  verifiers (a role, a named user, or the joiner's manager). Employment,
  Personal and Statutory cannot be switched off.
- **Fields.** Each built-in field can be hidden, optional or required,
  relabelled and given help, for the Organization and overridden per flow.
  Legal name, date of birth, joining date and PAN are always required.
- **Custom fields** of any of nine types (text, long text, number, date, one
  or several choices, yes or no, email, phone), in any section, with rules,
  limits and a pattern. Sensitive ones are encrypted like PAN and shown only
  on request. Their values can already be set on employee records.
- **Preview.** `GET /setup/preview?employmentType=intern` returns the form an
  intern would see: the flow the rules pick and every visible field.

Changing configuration needs the `onboarding.configure` permission (Org Admin
and HR Admin); reading it needs `employee.read` across the Organization. Every
change is in the audit log with its before and after values.

## Onboarding joiners

HR adds a joiner under **People → Joiners** with what they know (name,
personal email, mobile, joining date, job, and the location, shift and weekly
offs attendance will use) and invites them. No email is sent yet: HR copies
the link, which stays valid until at least a week after the joining date.

- **The joiner** signs in with the Joiner role and sees only **My
  onboarding**: a welcome, the privacy notice to accept first, and one form per
  section (personal, contact, addresses, emergency contact, family and
  nominees, bank, PAN and tax). Each section is saved as they go and sent for
  checking; required fields are checked on sending. PAN, Aadhaar and the bank
  account are stored encrypted and shown as the last four.
- **Verification.** Each sent section is verified, or returned with a note, at
  each level the flow sets; the joiner fixes a returned section and sends it
  again. Nobody verifies their own details. HR can also fill or correct any
  section; correcting a verified one sends it back for checking.
- **Activation.** Once every section is verified (and the PAN is given, if the
  Organization insists), the joiner becomes an employee on the joining date:
  the Joiner role becomes Employee, the employee code and joining date carry
  over (leave accrues from that date), and attendance is assigned from that
  day. With automatic activation this happens by itself; otherwise HR presses
  Activate.
- **Rehires.** A joiner whose email or mobile matches an earlier person is
  offered to HR as a possible rehire before anything is created.
- **Withdrawal** ends the link and login; the joiner's data is deleted after
  the Organization's retention period (90 days by default).
- **Documents.** Each joiner is asked for the document types whose rules
  match them (by default: PAN card and address proof before joining,
  education certificates within 30 days, a relieving letter and a photo
  optional). The joiner uploads a PDF or a photo from the phone; the server
  reads the type from the file's bytes (PDF, JPEG or PNG, up to 10 MB),
  redraws photos to drop their location and camera data, and stores the bytes
  encrypted. HR views, verifies or rejects each with a note; a new upload
  replaces the old one. A file is shown only to its owner and to people with
  `employee.sensitive`, and every view is in the audit log.
- **Checklists.** Every joiner gets the tasks of each checklist template whose
  rules match them, for HR, IT, the manager, a buddy or the joiner. Due dates
  follow the task's trigger (added, invited, joining date, activated). Whoever
  a task is assigned to sees it under **My tasks** and can mark it done,
  without any HR permission; give IT tasks a home with the new **IT Admin**
  role. HR can skip with a reason, reassign, or add a task. A task can need a
  file before it is done, or an approver.
- **What blocks activation:** sections not verified, documents needed before
  joining not verified, required tasks due before the joining date that are
  not done or skipped, letters waiting for an answer, and policies due before
  joining not acknowledged.
- **Letters.** HR issues a letter from the Organization's templates (an
  offer and an appointment letter to start with), filled for the joiner with
  placeholders like `{{person.legal_name}}` and written as a PDF, or uploads
  a PDF of their own. A letter can ask the joiner to accept it, or to accept
  or decline it. An unknown placeholder is refused when a template is saved.
  The PDF writer is built in and uses the standard PDF fonts, so characters
  outside Latin-1 (Devanagari, say) show as "?" and the rupee sign as "Rs.";
  upload a PDF when a letter needs them.
- **The offer stage.** An offer letter puts the joiner in the offered state:
  they see only the offer until they answer it. Accepting moves them on;
  declining ends the onboarding and the login. An offer expires after the
  days HR chose (7 by default).
- **Policies** to read and acknowledge, with rules for whom they apply to,
  due before joining or within days. New wording is a new version, which is
  acknowledged again.
- **Employee codes** from series such as `GLX-{YY}-` with 4 digits, using
  `{YYYY} {YY} {MM} {LOC} {DEPT} {TYPE}`, resetting yearly if wanted. A joiner
  with no code typed gets the next one from the first matching series when
  they are activated; two activations at once never share a code.
- **The bell.** People are told, in the app, when a task is given to them, a
  section or document is returned, an offer arrives, a joiner is ready, a
  joiner becomes active, and when a task is coming due or overdue or a
  joiner has a week left with sections to do (each at most once a day). The
  wording is the Organization's own; email will use the same wording later.

## Importing existing employees

A new customer brings in their staff under **People → Import**: a CSV saved
from Excel or Google Sheets, one person per row.

- **Columns.** The first look guesses which column holds what from the
  column names (Emp Code, DOJ, Reports To and the like, and the
  Organization's own fields). The mapping is remembered by column name, so
  the next file from the same spreadsheet maps itself.
- **Checking.** Every row is checked before anything is written, with the
  reason a row cannot be used: a date that is not a date, an unknown gender
  or employment type, a missing designation, a bad PAN or IFSC, a PAN,
  employee code, personal email, or name and joining date already recorded or
  repeated in the file, a manager who is not found, reporting lines that go
  round in a circle. Dates may be written 2023-05-01, 01/05/2023 or
  1-May-2023.
- **Importing** writes every usable row at once: departments named in the
  file are added, managers are found among existing staff (by employee code
  or personal email) or further up the file, the Organization's own fields
  are set, and PAN, UAN and bank accounts are encrypted. The same file
  imported twice adds nothing.
- **Invitations.** Imported employees with a personal email are listed to
  invite in bulk; each gets a link to copy (no email yet), and when they
  accept, their login is linked to their record and takes their employee
  code. Their joining date is left as it was, so leave is not accrued again.

## Exits

Under People → Exits for HR and managers, and My exit for each employee.
The design, as built, is `docs/exit/design.md`; the schema is
`V11__employee_exit.sql`.

- **Resignation.** The employee resigns in My exit, with a reason and the
  last day they would like; their notice comes from Setup → Exits (by
  employment type, the probation notice while on probation). Their manager
  recommends a last day, then HR accepts it (the manager's step can be turned
  off). Notice not served is waived, set against earned leave, recorded as
  owed by the employee, or (for terminations) paid in lieu; it is never
  deducted from wages. Before acceptance the employee withdraws in one click;
  after it, HR decides. The employee keeps working, and applying for leave,
  until the last day; Setup decides which leave types are allowed then.
- **Other exits.** HR starts a termination, end of contract, retirement,
  death or absconding. The app proposes an end of contract 30 days ahead and
  a retirement 60 days ahead (the last day of the month the retirement age is
  reached); HR confirms or cancels. On death the employment ends at once, the
  nominees are shown, and gratuity is due whatever the service. Absconding
  opens once someone has been away without word for the days set, and is
  confirmed only after the show-cause notice and then the final notice have
  each had their days to reply.
- **Clearance.** Acceptance starts the exit checklist (Setup → Checklists,
  "People leaving"): handover, equipment, accounts, ID card, dues, the exit
  interview, the PF date of exit. Tasks fall to the manager, IT, Finance or
  HR, due from the acceptance or the last day, and show in My tasks. A task
  can be done with an amount owed by or to the employee.
- **The full and final settlement.** HR has the app work out the lines:
  salary days to the last day less unpaid leave, leave to encash, notice pay
  or the notice owed, gratuity (five years' service, one for fixed term, any
  on death; part years over six months count), claims and recoveries from
  clearance, and income tax. Each line has its legal due date: wages within
  two working days of the last day, gratuity within thirty. HR types the
  amounts (payroll will fill them later); a second person approves, which
  sends the employee a PDF statement; each line is then marked paid with its
  date and bank reference, and a paid line never changes. HR is reminded the
  day before a line falls due.
- **Letters.** The acceptance letter goes out when a resignation is accepted.
  HR issues termination, retirement, end-of-contract, show-cause, final
  notice, experience and relieving letters from the exit; the relieving letter
  waits for the last day and the required clearance. Their wording is in
  Setup → Letters, under "Leaving".
- **The exit interview.** HR sends the questions (Setup → Exits); the
  employee answers or declines in My exit. Answers are encrypted and only HR
  reads them; the manager sees only whether it happened. HR can write notes
  from one held in person.
- **Afterwards.** The day after the last day, the employment ends and the
  reports move to whoever HR named. A former employee signs in to My exit
  only, for their letters and settlement, for 12 months by default (Setup →
  Exits; none on death), and is told two weeks before that ends. HR closes the
  exit once clearance and the settlement are done, saying whether the person
  could be hired again; the rehire check shows it if they apply again. After
  the retention period (Setup → Onboarding), a former employee's personal data
  is removed, keeping only what other records need, unless an exit is under
  legal hold.

## Roles

| Role | Can |
|---|---|
| Org Admin | Everything in the Organization, including users, roles and billing |
| HR Admin | Manage people and invite users. No access to other people's pay |
| Payroll Operator | Prepare payroll. Cannot approve it |
| Payroll Approver | Approve payroll and release payment. Cannot prepare it |
| Manager | See their team (the people who report to them): records, attendance and leave, and decide their requests |
| Employee | See their own record, and the org chart |
| Auditor | Read-only, including the audit log |

A user can grant a role only if they already hold every permission in it, so
an HR Admin can invite employees and managers but cannot hand out payroll or
admin roles, and cannot suspend or remove an Org Admin. Nobody can change
their own roles or status.

The user-management, attendance, leave and employee permissions are used by screens today. The
compensation and payroll permissions are defined and assigned to
roles, ready for those modules. An HR Admin who should punch or apply for
leave also needs the Employee role.

## Layout

| Folder | What it holds |
|---|---|
| `frontend/` | React app (Vite). Screens are in `src/pages/`, the API client in `src/api.js` |
| `backend/src/main/java/.../core/` | The services and database access. Plain Java and JDBC, no Spring |
| `backend/src/main/java/.../web/` | Spring controllers and configuration |
| `backend/src/main/resources/db/migration/` | The schema and seed data, applied at startup |
| `.devcontainer/` | Codespaces configuration, including PostgreSQL |
| `docs/` | Design documents, schemas and plans for each module; start at `docs/README.md` |

## API

Every call except sign-in and the invitation page needs
`Authorization: Bearer <token>`. Errors are `{ "code", "message" }`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/login` | Checks email and password, returns a token |
| GET | `/api/auth/me` | The signed-in person, their permissions, and where they can go |
| POST | `/api/auth/enter` | Enters an Organization or the console; returns a new token |
| POST | `/api/auth/logout` | Ends the session |
| GET | `/api/invitations/preview?token=` | What an invitation offers |
| POST | `/api/invitations/accept` | Accepts it; returns a token |
| GET | `/api/org/users`, `/roles`, `/invitations`, `/audit` | Lists for the current Organization |
| POST | `/api/org/invitations` | Invites a user |
| POST | `/api/org/invitations/{id}/resend`, `/revoke` | New link, or withdraw |
| PUT | `/api/org/users/{id}/roles` | Sets a user's roles |
| POST | `/api/org/users/{id}/suspend`, `/reactivate`, `/end` | Changes a user's status |
| GET, POST | `/api/platform/organizations` | Lists and creates Organizations |
| POST | `/api/platform/organizations/{id}/invite-admin`, `/suspend`, `/reactivate`, `/close` | Manages one |
| GET, POST | `/api/platform/users` | Platform users |
| GET, POST | `/api/platform/grants` | Support access requests |
| POST | `/api/platform/grants/{id}/approve`, `/reject`, `/revoke`, `/start` | Decides one, or starts the session |

Attendance, all under `/api/org/attendance`:

| Method | Path | Purpose |
|---|---|---|
| GET | `/me/today` | Today's punches, what the next punch will be, and what the location checks |
| POST | `/me/punch` | Punches in or out; the body may carry `latitude`, `longitude`, `accuracyM` |
| GET | `/me/days?month=` | The caller's days for a month, each with its trail |
| GET, POST | `/me/requests` | The caller's requests; raises one, or one per working day of `fromDate` to `toDate` |
| POST | `/me/requests/{id}/cancel` | Withdraws a pending request, or an approved day away that has not passed |
| GET | `/me/wfh` | Whether the caller may work from home, the limits, and what is used |
| GET | `/days?date=` | Everyone the caller can see, on one date |
| GET | `/users/{id}/days?month=` | One user's month |
| POST | `/users/{id}/punches` | HR adds a punch, with a reason |
| GET | `/requests?status=` | Requests to decide |
| POST | `/requests/{id}/approve`, `/reject` | Decides one |
| GET | `/register?month=` | The month's register as CSV text |
| GET, POST | `/locations`, `/shifts` | Lists and creates; `PUT /{id}` changes one |
| GET, POST | `/holidays` | Lists a year's holidays, adds one; `POST /{id}/delete` removes one |
| GET, POST | `/assignments` | Users with what is assigned to them; assigns a location and shift |
| PUT | `/users/{id}/employee-code` | Sets the code device files use |
| PUT | `/users/{id}/wfh` | Whether a user may ask to work from home |
| GET, PUT | `/settings` | The leave year and the work-from-home rules |
| POST | `/imports/preview` | Shows a file's columns and the remembered mapping |
| GET, POST | `/imports` | Device health and recent files; imports a file |

Leave, all under `/api/org/leave`:

| Method | Path | Purpose |
|---|---|---|
| GET | `/types` | The Organization's leave types |
| GET | `/me/balances`, `/me/ledger?type=` | The caller's balances, and the entries behind one |
| GET, POST | `/me/requests` | The caller's leave requests; applies for leave |
| POST | `/me/requests/preview` | Counts the days of a request without making it |
| POST | `/me/requests/{id}/cancel` | Withdraws a pending request, or approved leave that has not started |
| GET | `/requests?status=` | Requests to decide |
| POST | `/requests/{id}/approve`, `/reject`, `/cancel` | Decides one; HR cancels approved leave |
| GET | `/calendar?from=&to=` | Who is away: leave, on duty and work from home |
| GET | `/balances` | Every user's balances |
| GET | `/users/{id}/balances`, `/users/{id}/ledger?type=` | One user's balances and entries |
| POST | `/users/{id}/requests` | HR records leave for a user, approved |
| POST | `/users/{id}/adjustments` | Adds to or takes from a balance, with a reason |
| GET | `/comp-off/candidates` | Days off that were worked and have no comp-off yet |
| POST | `/users/{id}/comp-off` | Grants comp-off for one of them |
| POST | `/types` | Adds a leave type; `PUT /types/{id}` changes one |
| GET | `/year-end/preview?year=` | What closing a leave year would write |
| POST | `/year-end` | Closes a leave year |

People, under `/api/org/people`:

| Method | Path | Purpose |
|---|---|---|
| GET, POST | `/employees` | Lists employees (`?status=active\|exited\|all`, `q`, `department`); adds one |
| GET | `/employees/{id}` | One employee, with job history; identifiers as last four only |
| GET | `/org-chart` | Everyone working here with designation, department, work email and manager; `me` and, per person, `canOpen` |
| POST | `/employees/{id}/reveal` | `{field, reason}`: one full value, audited. Fields `pan`, `uan`, `pf_member`, `esi_ip`, `bank_account` |
| POST | `/employees/{id}/job` | A dated change of designation, department or manager |
| POST | `/employees/{id}/exit` | `{lastWorkingDay, reason}` |
| POST | `/employees/{id}/link` | Links the record to a user account |
| PUT | `/employees/{id}/identifiers`, `/employees/{id}/bank` | Sets or corrects an identifier; records a new bank account from a date |
| GET | `/unlinked-users` | Users with no employee record yet |
| GET, POST | `/departments`; PUT `/departments/{id}` | Departments |
| GET | `/states` | States and union territories |
| PUT | `/employees/{id}/custom-fields` | `{values: {key: value}}`: sets the Organization's own fields on an employee |

Onboarding configuration, under `/api/org/people/setup`:

| Method | Path | Purpose |
|---|---|---|
| GET, PUT | `/settings` | Invitation, activation, rehire matching, retention, offer stage |
| POST | `/notice` | Publishes a new version of the privacy notice |
| GET, POST | `/flows`; PUT `/flows/{id}` | Flows with rules, welcome content, sections and verification steps |
| PUT | `/flows/order` | The order flows are tried in |
| GET, PUT | `/fields`, `/flows/{id}/fields` | Built-in field settings for the Organization, or one flow's overrides |
| GET, POST | `/custom-fields`; PUT `/custom-fields/{id}` | The Organization's own fields |
| GET | `/preview?flow=&employmentType=&department=&workLocation=&designation=` | The form a joiner would see |
| GET, POST | `/document-types`; PUT `/document-types/{id}` | Documents asked for: before joining, within days, or optional, with rules |
| GET, POST | `/checklists`; PUT `/checklists/{id}` | Checklist templates with rules and their tasks (owner, trigger, offset, file needed, approver) |
| GET, POST | `/letters`; PUT `/letters/{id}` | Letter templates: kind, wording with placeholders, the answer asked for, rules |
| GET, POST | `/policies`; PUT `/policies/{id}` | Policies: wording, due point, rules |
| GET, POST | `/number-series`; PUT `/number-series/{id}` | Employee code series |
| POST | `/number-series/{id}/example` | The next code a series would give, without taking it |
| GET | `/messages`; PUT `/messages/{id}` | The wording of each in-app message, or switching one off |

Joiners (HR), under `/api/org/people/joiners`:

| Method | Path | Purpose |
|---|---|---|
| GET | `?status=open\|active\|withdrawn\|all` | Joiners, with tiles for the week |
| POST | `/` | Adds a joiner; may answer `{possibleRehire: [...]}` first |
| GET | `/{id}`, `/{id}/history` | One joiner with sections and blockers; their history |
| GET, PUT | `/{id}/sections/{section}` | A section's form and values; HR fills or corrects it |
| POST | `/{id}/sections/{section}/submit`, `/verify`, `/return` | Sends, verifies, or returns with `{note}` |
| POST | `/{id}/invite`, `/{id}/activate`, `/{id}/withdraw` | Invitation link, activation, withdrawal with `{reason}` |
| GET, POST | `/{id}/documents` | The documents asked for and uploaded; HR uploads one (the file as the body, `?type=&name=`) |
| POST | `/{id}/documents/{docId}/verify`, `/reject` | Decides a document; a rejection needs `{note}` |
| GET, POST | `/{id}/tasks` | The checklist; HR adds a one-off task |
| GET, POST | `/{id}/letters` | Letters issued and the templates that apply; issue one with `{templateId, expiresInDays}` |
| GET | `/{id}/letters/preview?template=` | A template filled for this joiner, as text |
| POST | `/{id}/letters/upload` | Issue a PDF (the body) with `?title=&kind=&response=` |
| GET | `/{id}/policies` | The policies that apply, and what was acknowledged |

The import, under `/api/org/people/import`: POST `/read` `{content}` (columns,
first rows and a mapping), POST `/preview` and `/run` `{content, mapping}`,
GET `/uninvited`, POST `/invite` `{employmentIds}`. And GET
`/api/org/people/setup/changes`, the last change to each area of setup.

Exits, under `/api/org`: GET and POST `/me/exit` (resign `{reasonCategory,
note, requestedLastDay}`), POST `/me/exit/withdraw` `{note}`, POST
`/me/exit/interview` `{answers}` and `/me/exit/interview/decline`. For HR and
managers, under `/api/org/people/exits`: GET (`?status=open|closed|all`) and
POST (start `{employmentId, kind, reasonCategory, note, lastWorkingDay,
shortfallAction, newManagerId}`); GET `/{id}`; POST `/{id}/recommend`,
`/accept`, `/withdrawal` `{approve, note}`, `/cancel` `{note}`, `/close`
`{rehire, rehireNote}`, `/tasks`; PUT `/{id}/legal-hold` `{hold, note}`; GET
`/{id}/settlement`, POST `/{id}/settlement/prepare`, `/settlement/lines`,
`/settlement/approve`, `/settlement/lines/{lineId}/paid` `{paidOn,
reference}`, PUT and DELETE `/settlement/lines/{lineId}`; GET
`/{id}/letters/preview?template=`, POST `/{id}/letters` `{templateId}`; GET
and POST `/{id}/interview`, PUT `/{id}/interview/notes` `{notes}`. Clearance
tasks: GET `/api/org/people/exit-tasks/mine`, POST
`/api/org/people/exit-tasks/{id}/done` `{amount, direction, note}`, `/skip`,
`/reassign`. Setup: GET and PUT `/api/org/people/setup/exit`.

Files and tasks, under `/api/org/people`: GET `/documents/{id}/content` (the
file, for `employee.sensitive`, audited); GET `/tasks/mine`; POST
`/tasks/{id}/done`, `/skip` `{reason}`, `/reassign` `{membershipId}`,
`/approve`, `/attachment` (the file as the body).

The joiner's own onboarding, under `/api/org/onboarding/me`: GET for the
welcome and sections, POST `/notice` with `{noticeId}`, GET and PUT
`/sections/{section}`, POST `/sections/{section}/submit`, GET and POST
`/documents` (upload: the file as the body, `?type=&name=`), GET
`/documents/{id}/content`, GET `/letters`, POST `/letters/{id}/accept` or
`/decline`, GET `/policies`, POST `/policies/{id}/acknowledge` `{version}`.
The bell: GET `/api/org/notifications`, POST `/api/org/notifications/read`
`{ids}` (no ids marks all read).

The Organization a request acts on always comes from the server-side session.
It is never read from the URL, a header or the request body.

## Automated tests

Three kinds of test, all run by GitHub Actions on every push to `main` and on
every pull request (`.github/workflows/ci.yml`). A failure turns the commit red
on GitHub and emails whoever pushed it.

| Job | What it checks | Where the tests are |
|---|---|---|
| Backend build and tests | Maven builds the backend; 191 JUnit tests run against a real PostgreSQL 16: business rules, permissions, and isolation between Organizations | `backend/src/test/` |
| Frontend lint and build | `oxlint` finds no problems and the production build succeeds | `frontend/` |
| Browser tests | The app is started from the backend jar, and Playwright clicks through it as the Super Admin, an Org Admin and an HR Admin | `e2e/tests/` |
| Deployment images | The two images in `deploy/` are built and started as on a server, behind Caddy, against a PostgreSQL whose admin login is not a superuser; the API, the frontend and a sign-in answer through it | `deploy/`, `.github/workflows/ci.yml` |

The browser tests tell one story in order, each step depending on the one before:

1. `01-users`: create an Organization, accept invitations, the grant rule, suspend and reactivate, support access shown in the audit log.
2. `02-attendance`: locations, shifts, holidays and assignments; punches refused and accepted by the office-network check; requests and approval; a manual punch; the register download; a device import, twice.
3. `03-leave`: leave types, applying with the day preview, approval, the who-is-away calendar, balance adjustment, leave recorded on someone's behalf, cancelling, closing last year.
4. `04-work-from-home`: a range of future days, the weekly limit, cancelling an approved day.
5. `05-people`: departments, adding employees with identifiers, last four on screen and the full PAN only with a reason, a dated job change, a duplicate PAN refused, an exit.
6. `06-phone`: the main screens at 390 px wide, with no sideways scrolling.
7. `07-onboarding`: HR adds and invites a joiner; the joiner accepts on a phone, reads the notice, fills every section and uploads documents; HR returns a section and rejects a document, the joiner fixes both; HR works the checklist (a task reassigned and done from My tasks, one skipped); HR verifies all, and the joiner becomes an employee; a joiner is withdrawn.

8. `08-offer-and-codes`: a code series and two policies set up; an offer letter previewed and issued; the joiner reads it on a phone, accepts it and acknowledges the policies; the bell takes HR to their tasks; the joiner becomes an employee with the next code from the series.
9. `09-setup-and-import`: a flow for interns set up on screen, a field made required for it and a field of HR's own, previewed on a phone and seen on a new intern; a code series and a message reworded; existing staff imported from a CSV with every refused row explained, the same file refused the second time; an imported employee invited and signed in.
10. `10-exit`: an employee resigns on a phone; the manager recommends a day and HR accepts it with the shortfall set against leave; clearance tasks go to their owners and the leaver records a claim; a withdrawal is asked for and refused; HR starts a termination and sets notice periods; the settlement is worked out, approved by a second person, paid and downloaded by the employee; letters wait for their moment, and the exit interview is answered and read only by HR; an absconding case waits for its show-cause notice; an exit is put under legal hold.
11. `11-org-chart`: an employee finds themselves, their manager and a colleague on the org chart, cannot open others' records, and reads it on a phone; HR opens a record from the chart.

Every browser test also fails on any error in the browser console.

**When a browser test fails in CI**, open the run on the Actions tab and download
the `playwright-report` artifact. It holds a screenshot, a video and a
step-by-step trace of the failing test, plus the backend and frontend logs.
Open the trace with `npx playwright show-trace <file>.zip`.

**To run the browser tests yourself** (in Codespaces, for example), start the
app on an empty database, then:

```bash
./start.sh                            # in one terminal; the database must be empty
cd e2e && npm install
npx playwright install --with-deps chromium   # first time only
npx playwright test                   # all suites, in order
npx playwright test --ui              # watch each step in a browser window
E2E_SHOTS=../shots npx playwright test  # also save the screenshots used in the setup and user guide
```

They create an Organization called Globex Ltd, so they need a fresh database
each time. In Codespaces, reset it with
`psql -h localhost -U postgres -c "drop database hrms with (force)" -c "create database hrms"`
(password `postgres`) and restart the backend.

**Every new feature adds its tests in the same commit**: JUnit tests for the
rules, and a step in the matching browser suite (or a new numbered suite) for
the screens.

## Useful commands

```bash
./restart.sh                        # stop the app if running, pull the latest code, start again
cd backend && mvn test              # backend tests; skipped when PostgreSQL is not reachable
cd e2e && npx playwright test       # browser tests; the app must be running on an empty database
cd backend && mvn spring-boot:run   # backend only
cd frontend && npm run dev          # frontend only
cd frontend && npm run lint && npm run build
```

## Differences from the design document

- Passwords are hashed with PBKDF2-HMAC-SHA256 from the JDK, not argon2id, to
  avoid a dependency that could not be tested here.
- Sessions are random tokens stored hashed in the database and looked up on
  every request, not signed JWTs with refresh tokens. Role changes and
  suspensions therefore apply on the next request with no version check.
- The audit log table is part of this module's schema.
- A user can only be suspended or ended by someone who could have granted all
  of their roles. The design did not state this; without it an HR Admin could
  suspend an Org Admin.
