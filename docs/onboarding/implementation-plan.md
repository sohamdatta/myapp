# Employee Onboarding — Implementation Plan

Oct 11, 2026

## Summary

The onboarding design is built in six milestones totalling 132 hours. At 15 hours a week, with a quarter added for ordinary life, that is about 12 weeks: start Monday 12 Oct 2026, first usable release on 27 Nov, everything by 5 Jan 2027. The plan follows the [implementation design](design.md) and builds on commit `ebbeff0`.

| Milestone | Hours | Running total | What works when it is done |
| --- | --- | --- | --- |
| 1. Foundations | 22 | 22 | Employee records with departments and reporting lines; managers finally see their team in attendance and leave |
| 2. Configuration core | 16 | 38 | Each Organization has working defaults for flows, sections and fields, and can add its own fields (through the API) |
| 3. Joiner flow | 34 | 72 | **First release.** HR adds and invites a joiner, the joiner fills the form on a phone, HR verifies, the joiner becomes an employee on the joining date |
| 4. Documents and checklists | 16 | 88 | Uploads with verification; checklist tasks for HR, IT, manager, buddy and joiner with due dates and reminders |
| 5. Letters, policies, codes, messages | 22 | 110 | Offer and appointment letters, policy acknowledgement, automatic employee codes, in-app notifications |
| 6. Setup screens and import | 22 | 132 | HR changes every setting from the screen with a preview; existing employees come in from a spreadsheet |

The order puts the record first because everything else hangs off it, and the configuration core before the joiner flow so the joiner screens are drawn from configuration from day one rather than rewritten later. Milestones 4 to 6 are independent of each other and can be reordered to follow what the first customers ask for. Each milestone ends with a push to `main` with its tests passing.

## Schedule

At 15 hours a week, counted as 12 to leave room for ordinary life, the first release falls on 27 Nov 2026 and the last milestone on 5 Jan 2027.

| Period | Milestones | Hours |
| --- | --- | --- |
| 12 Oct to 27 Nov 2026 | 1. Foundations, 2. Configuration core, 3. Joiner flow (**first release on 27 Nov**) | 72 |
| 28 Nov 2026 to 5 Jan 2027 | 4. Documents and checklists, 5. Letters, policies, codes, messages, 6. Setup screens and import | 60 |

The week-by-week chart is in the [claude.ai version of this plan](https://claude.ai/code/artifact/ae6f7b7c-452b-48f9-9196-c73995da3613).

The dates move with the hours actually spent; the order and the release point do not. Milestones 4, 5 and 6 can swap places if a customer needs one of them sooner.

## Ground rules

New code follows the shape attendance and leave already use, so nothing here needs a new framework or library.

**Where code goes**

- `backend/.../core/`: services in plain Java and JDBC through `Db.inTenant` and `Sql`; pure rules (validators, rule matcher, number series, template filling) in classes with no database access, tested on their own.
- `backend/.../web/`: thin controllers that take the session with `Requests.session`, call one service method and return its map; `AppConfig` wires each service with a `Clock`.
- `backend/src/main/resources/db/migration/V5__employee_onboarding.sql`, added to `Migrator.FILES`; later changes go in V6, V7, never edits to V5 once pushed.
- `frontend/src/api.js` gets a `people` group and an `onboarding` group; screens in `frontend/src/pages/`; navigation in `nav.js` decided by permissions, as today.

**Patterns to reuse**

- Dates in India time from the service's `Clock`, never the database clock (the lesson from attendance).
- Things that happen on a date (activation, reminders, overdue tasks) are worked out when someone reads, like leave accrual. No background jobs.
- Every change by a person goes through `UserService.audit` with the before and after values that matter.
- Errors are `ApiException` with a code and a sentence a user can act on; constraint names are translated in `Db`.
- History is added, not edited: new rows for addresses, bank accounts, job changes, letters and policy versions.

**Done means**

1. The step's tests pass with the JUnit console, alongside all earlier tests.
2. The browser flow for the step runs on desktop and at 390 px width with no console errors and no sideways scroll.
3. A second Organization in the same database sees nothing of the first (the cross-tenant test is extended for every new table).
4. README updated: what was added, the API table, the test count, the status section.
5. Committed and pushed to `main` with the attribution lines.

## Milestone 1: foundations, 22 hours

The employee record goes in first, with encryption and the identity checks, so every later step has a person to attach to. Migration V5 is Part 1 of the tested schema (the record tables, permissions and the Joiner role).

| # | Task | Files | Hours |
| --- | --- | --- | --- |
| 1.1 | Migration V5: department, person, addresses, contacts, family, nominations, employment, job assignment, bank account, statutory ids, tax regime, document tables; RLS; `employee.sensitive` permission; Joiner role | `V5__employee_record.sql`, `Migrator` | 3 |
| 1.2 | Encryption: AES-256-GCM with a key id byte and the tenant id as associated data; HMAC for duplicate checks; `APP_DATA_KEY` read at start, the app refuses to start without it outside tests | `core/Crypto.java`, `application.properties`, `AppConfig` | 3 |
| 1.3 | Identity checks as pure functions: PAN (format and 4th letter), Aadhaar (12 digits, Verhoeff), IFSC, UAN, PIN, Indian mobile, email | `core/IdChecks.java` | 2 |
| 1.4 | `employee.sensitive` in the session: masked by default, a reveal call that asks for a reason and is audited | `SessionCtx`, `core/Reveal.java` | 1 |
| 1.5 | Employee service: add a person with an employment directly (for HR and later for import), read a record (masked), dated job change with the manager-loop check, departments with the tree-loop check, exit that ends the membership on the exit date | `core/EmployeeService.java`, `web/PeopleController.java` | 6 |
| 1.6 | Team scope from reporting lines: a manager's team is everyone whose current job assignment names them; attendance board, attendance requests and leave requests use it where they return nothing today | `core/Team.java`, `AttendanceService`, `LeaveService` | 3 |
| 1.7 | Screens: People → Employees list with search and filters; employee page with Details (read only), Job history with Change job, Exit; Departments under Setup | `pages/People.jsx`, `pages/Employee.jsx`, `api.js`, `nav.js` | 4 |

**Endpoints:** `GET, POST /api/org/people/employees`, `GET /people/employees/{id}`, `POST /people/employees/{id}/job`, `POST /people/employees/{id}/exit`, `GET /people/employees/{id}/reveal?field=&reason=`, `GET, POST, PUT /people/departments`.

**Tests:** `CryptoTest` (round trip, wrong tenant fails, wrong key id fails, tampered byte fails), `IdChecksTest` (good and bad values for each check, including Verhoeff and a PAN with C as 4th letter), `EmployeeTest` (dated job change, manager loop refused at depth 3, a manager sees a report's attendance and leave request, exit ends access on the date not before, support session reads only masked values, a second Organization sees nothing).

**Accepted when** the Org Admin creates two employees with one reporting to the other, the manager signs in and sees the report on Attendance and Leave, and a database dump shows no full PAN or account number.

## Milestone 2: configuration core, 16 hours

The configuration engine comes before any joiner screen, so the joiner form is always drawn from what the Organization set. Migration V6 is Part 2 of the tested schema: every configuration table and the onboarding tables, created now and used from milestone 3 on.

| # | Task | Files | Hours |
| --- | --- | --- | --- |
| 2.1 | Migration V6: settings, notice, flows, verification steps, section and field settings, custom fields, number series, letters, policies, messages, checklists, onboarding tables; `onboarding.configure` permission | `V6__onboarding.sql`, `Migrator` | 2 |
| 2.2 | Defaults: a settings row, the flow Everyone with all sections, five document types, a checklist of eight tasks, a notice, message wording and an appointment letter. Created when an Organization is created, and for existing Organizations the first time anyone reads their configuration; running it twice changes nothing | `core/OnboardingDefaults.java`, `PlatformService` | 3 |
| 2.3 | Rule matcher as a pure class: read and validate an `applies_to` object, check that ids belong to the Organization, decide whether a joiner matches, pick the first matching flow | `core/AppliesTo.java` | 2 |
| 2.4 | Field catalogue and form builder: every built-in field with its key, section, type and whether it is fixed; the effective form for a flow is the catalogue, then the Organization's field settings, then the flow's overrides, then matching custom fields; returned as one form description the screens draw | `core/FieldCatalog.java`, `core/FormSpec.java` | 4 |
| 2.5 | Configuration services: read and change settings, flows (with order), section settings, verification steps, field settings and custom fields; each change needs `onboarding.configure` and is audited with before and after | `core/OnboardingConfigService.java`, `web/PeopleSetupController.java` | 3 |
| 2.6 | Custom field values: check against type, choices, pattern and limits; store encrypted when the field is sensitive | `core/CustomValues.java` | 2 |

**Endpoints:** `GET, PUT /api/org/people/setup/settings`, `GET, POST, PUT /setup/flows`, `PUT /setup/flows/order`, `GET, PUT /setup/fields` and `/setup/flows/{id}/fields`, `GET, POST, PUT /setup/custom-fields`, `GET /setup/preview?flow=`.

**Tests:** `AppliesToTest` (each attribute, empty rules match everyone, unknown attribute refused, first match by position, fallback to the default flow), `FieldCatalogTest` (a flow override beats the Organization default, legal name, date of birth, joining date and PAN cannot be hidden, a custom field appears only for matching joiners), `OnboardingConfigTest` (defaults created once, two Organizations with opposite settings get different previews, every change audited, a user without `onboarding.configure` is refused).

**Accepted when** the preview for an intern differs between two Organizations exactly as their settings say, with nothing configured by hand beforehand except the differences.

## Milestone 3: joiner flow, 34 hours (first release)

This is the milestone that makes onboarding usable: a joiner goes from added to active employee on the defaults, with every section verified. Documents, checklists and letters arrive in later milestones; until then their sections are switched off in the default flow.

| # | Task | Files | Hours |
| --- | --- | --- | --- |
| 3.1 | Add a joiner: person, employment in `onboarding`, onboarding with the flow picked by the matcher or by HR; the rehire check matches on the Organization's chosen fields and reuses the earlier person when HR confirms | `core/JoinerService.java`, `web/JoinerController.java` | 3 |
| 3.2 | Invitation by HR's click or by the Organization's rule (when added, or a number of days before joining, worked out on read); reuses the existing invitation tables with the Joiner role and the configured link validity; a joiner with a login elsewhere signs in with it | `JoinerService`, `InvitationService` | 3 |
| 3.3 | Sections: save a draft, submit, HR fills a section (audited), each built-in field written to its record table and custom fields to their values, all checked against the flow's form description | `core/SectionService.java` | 4 |
| 3.4 | Verification at the flow's levels: who may verify each level (role, named user, manager), verify or return with a note, a review row per decision, a resubmitted section starts again at level 1, nobody verifies their own | `core/Verification.java` | 3 |
| 3.5 | The joiner's own API: their onboarding, the notice to accept, sections with status and notes | `web/MyOnboardingController.java` | 1 |
| 3.6 | Joiner screens: welcome and notice, progress list, one form per section drawn from the form description (every field type, phone first), returned notes at the top, submit | `pages/MyOnboarding.jsx`, `components/FormSection.jsx` | 8 |
| 3.7 | HR screens: Joiners list with tiles and filters, Add joiner form, joiner page with Details cards, Verify and Return, masked values with Reveal | `pages/Joiners.jsx`, `pages/Joiner.jsx` | 4 |
| 3.8 | Activation: the reasons it is blocked, automatic on the joining date (checked on read and at sign-in) or by HR; swaps Joiner for Employee, starts the attendance assignment and leave from the joining date; withdraw with a reason ends the membership and the link | `core/Activation.java`, `AuthService`, `AttendanceSettingsService` | 5 |
| 3.9 | Exit and retention: exit ends the membership on the date; withdrawn joiners past the Organization's retention are deleted when HR opens the Joiners list, logged without their values | `EmployeeService`, `core/Retention.java` | 3 |

**Endpoints:** `GET /api/org/onboarding/me`, `POST /onboarding/me/notice`, `GET, PUT /onboarding/me/sections/{section}`, `POST /onboarding/me/sections/{section}/submit`, `GET, POST /people/joiners`, `GET /people/joiners/{id}`, `PUT /people/joiners/{id}/sections/{section}`, `POST /people/joiners/{id}/sections/{section}/verify` and `/return`, `POST /people/joiners/{id}/invite`, `/activate`, `/withdraw`.

**Tests:** `JoinerFlowTest` (the whole path on defaults; a return and resubmit; two levels with the manager at level 2; own verification refused; activation blocked until required sections are verified; automatic activation on the date with a fixed clock, not a day earlier; the new employee punches and has leave from the joining date; a joiner cannot read another joiner; a withdrawn link stops working; a rehire reuses the person), plus a browser flow `onboarding_e2e.py` run at 1200 px and 390 px.

**Accepted when** HR adds a joiner, the joiner completes every section on a phone, HR returns one and verifies the rest, and on the joining date the joiner signs in as an employee who can punch and apply for leave.

## Milestone 4: documents and checklists, 16 hours

Files and tasks are the two things HR now tracks in spreadsheets and email; this milestone moves both into the joiner's page. The Documents section is switched on in the default flow when it lands.

| # | Task | Files | Hours |
| --- | --- | --- | --- |
| 4.1 | Upload: multipart endpoint, type taken from the file's first bytes (PDF, JPEG, PNG), 10 MB limit, safe file name, images re-encoded to drop location data, SHA-256, bytes encrypted into `document_content` | `core/DocumentService.java`, `core/FileSniffer.java`, `web/DocumentController.java` | 3 |
| 4.2 | Document types with rules, the list each joiner must provide, verify or reject with a note, a replacement marks the old one superseded, the Documents section's status worked out from them | `DocumentService`, `OnboardingConfigService` | 2 |
| 4.3 | Serving a file: only to the person or `employee.sensitive`, sent as an attachment with `nosniff`, every view audited | `DocumentController` | 1 |
| 4.4 | Screens: upload from the phone camera or files with progress and status; HR's Documents tab with a viewer and Verify or Reject | `MyOnboarding.jsx`, `Joiner.jsx`, `components/DocumentList.jsx` | 2 |
| 4.5 | Checklist: tasks copied at onboarding creation from every template whose rules match; due dates set when the trigger happens (added, offer accepted, invited, joining date, activated); assignee from the owner (HR contact, manager, buddy, joiner, a role's first member, a named user), changeable by HR | `core/ChecklistService.java` | 3 |
| 4.6 | Task actions: done (refused without a file when one is required), skip with a reason, reassign, approve; the assignee may act without `employee.write`; overdue worked out on read | `ChecklistService`, `web/TaskController.java` | 2 |
| 4.7 | Screens: the Checklist tab grouped by owner with overdue in amber; My tasks for anyone with open tasks; the Manager's Joining soon list | `Joiner.jsx`, `pages/MyTasks.jsx`, `nav.js` | 3 |

**Endpoints:** `GET, POST /api/org/onboarding/me/documents`, `GET /onboarding/me/documents/{id}/content`, `POST /people/joiners/{id}/documents/{docId}/verify` and `/reject`, `GET /people/documents/{id}/content`, `GET /people/tasks/mine`, `POST /people/tasks/{id}/done`, `/skip`, `/reassign`, `/approve`, `GET, POST, PUT /setup/document-types` and `/setup/checklists`.

**Tests:** `FileSnifferTest` (a PDF renamed .png is read as PDF, an HTML file named .pdf is refused, 10 MB plus one byte refused), `DocumentTest` (bytes in the database are not the file, a support session cannot open a document, a replacement supersedes), `ChecklistTest` (rules pick templates, a task waits for its trigger, a required attachment blocks done, an IT assignee with only the Employee role can tick their task, overdue on the day after due with a fixed clock).

**Accepted when** an intern's checklist differs from a permanent employee's as the templates say, IT ticks Laptop issued from My tasks, and activation waits until the required documents are verified.

## Milestone 5: letters, policies, employee codes, messages, 22 hours

These four share one piece, a small template engine, so they are built together. This is the only milestone that adds a Java dependency (for PDF), so its first task is to prove that dependency builds.

| # | Task | Files | Hours |
| --- | --- | --- | --- |
| 5.1 | Template engine as a pure class: placeholders such as `{{person.legal_name}}` from a fixed list (person, employment, job, Organization, today), an unknown placeholder refused when the template is saved, values HTML-escaped | `core/Templates.java` | 2 |
| 5.2 | PDF from HTML with a library that runs inside the app (openhtmltopdf is the candidate); the build is proven with Maven before anything depends on it | `core/Pdf.java`, `pom.xml` | 4 |
| 5.3 | Letters: issue from a template or upload, store as a document, offer stage with expiry (the joiner sees only the offer until they accept), accept or decline, a declined offer closes the onboarding; the Letters section | `core/LetterService.java` | 3 |
| 5.4 | Policies: versions, acknowledge, due before activation or within days, the Policies section | `core/PolicyService.java` | 2 |
| 5.5 | Screens for letters and policies on both sides | `MyOnboarding.jsx`, `Joiner.jsx` | 1 |
| 5.6 | Employee codes: token expansion as a pure function; the next number taken under a row lock so two activations at once never share a code; drawn at activation, or typed by hand when no series matches | `core/NumberSeries.java` | 4 |
| 5.7 | Messages: wording per event from the template engine; a notification row per person at each event; reminders for due and overdue tasks and for joiners who have not started, worked out on read with `last_reminded_at` so nobody is reminded twice in a day; a bell with the unread count | `core/Notifications.java`, `components/Bell.jsx` | 6 |

**Endpoints:** `GET, POST, PUT /api/org/people/setup/letters`, `/setup/policies`, `/setup/messages`, `/setup/number-series`; `POST /setup/number-series/{id}/example`; `POST /people/joiners/{id}/letters`; `POST /onboarding/me/letters/{id}/accept` and `/decline`; `POST /onboarding/me/policies/{id}/acknowledge`; `GET /notifications`, `POST /notifications/read`.

**Tests:** `TemplatesTest` (every placeholder, unknown placeholder refused, script in a name is escaped), `NumberSeriesTest` (each token, yearly reset, padding; 20 parallel draws give 20 distinct codes), `LetterTest` (offer expires, decline closes the onboarding, an accepted letter cannot be changed, only a new version issued), `NotificationTest` (one reminder a day at most, reminders stop when the task is done).

**Accepted when** an Organization with the offer stage on sends an offer, the joiner accepts it on a phone, acknowledges two policies, and becomes GLX-26-0001 on the joining date.

## Milestone 6: setup screens and bulk import, 22 hours

Until this milestone the configuration is changed through the API or kept at its defaults; now HR changes everything from the screen, and a new customer brings in its existing staff in one upload.

| # | Task | Files | Hours |
| --- | --- | --- | --- |
| 6.1 | Setup → Flows: list in order with rules in words, drag to reorder, a flow's welcome content, sections (switch, order, title, verified) and verification levels | `pages/PeopleSetup.jsx`, `components/RuleEditor.jsx` | 4 |
| 6.2 | Setup → Form: every section with its fields, a three-way switch hidden, optional, required, label and help; Add field for custom fields with type, choices and rules; fixed fields shown locked with the reason | `components/FieldSettings.jsx` | 4 |
| 6.3 | Preview as joiner: the form for a chosen flow in a phone frame, built from the same form description the joiner gets | `components/Preview.jsx` | 2 |
| 6.4 | Setup → Documents, Checklists, Letters, Policies, Messages, Employee codes, Settings: one editor each; letters and messages show the placeholders and a sample filled from a test joiner; codes show the next code live; every screen shows its last change | `PeopleSetup.jsx` | 4 |
| 6.5 | Import: CSV upload, column mapping remembered per Organization (as device import does), custom fields included, row-by-row preview with the reason a row cannot be used, import as active employees without onboarding, PAN and bank encrypted on the way in | `core/EmployeeImport.java`, `pages/PeopleImport.jsx` | 6 |
| 6.6 | Bulk invitations for imported employees, with the links listed to copy until email exists | `JoinerService`, `PeopleImport.jsx` | 2 |

**Tests:** `EmployeeImportTest` (a mapping is remembered, a duplicate PAN or employee code is refused with its row number, a bad IFSC is refused, the same file twice adds nothing, imported PAN is encrypted), and a browser flow `setup_e2e.py` that changes a field to required, adds a custom field, previews it, and sees it on a new joiner's form.

**Accepted when** HR configures a second flow for interns entirely from the screen, and imports 200 existing employees from a spreadsheet with every refused row explained.

## Testing plan

Onboarding adds about 90 tests to the 88 that pass today, in three layers, and two suites guard every push: the cross-tenant suite and the full joiner path.

| Layer | What it covers | Where | Added |
| --- | --- | --- | --- |
| Pure | Identity checks, encryption, rule matcher, field catalogue, template engine, number series, file sniffing | `CryptoTest`, `IdChecksTest`, `AppliesToTest`, `FieldCatalogTest`, `TemplatesTest`, `NumberSeriesTest`, `FileSnifferTest` | about 45 |
| Database | Each service against PostgreSQL 16 with a fixed clock: the record, configuration, joiner flow, documents, checklists, letters, notifications, import; every new table in the cross-tenant test | `EmployeeTest`, `OnboardingConfigTest`, `JoinerFlowTest`, `DocumentTest`, `ChecklistTest`, `LetterTest`, `NotificationTest`, `EmployeeImportTest` | about 45 |
| Browser | HR and joiner paths on desktop and at 390 px, no console errors, no sideways scroll | `onboarding_e2e.py`, `setup_e2e.py`, the existing `e2e.py`, `att_e2e.py`, `leave_e2e.py` | 2 flows |

**Before each push**

1. All JUnit tests pass, including the 88 existing ones.
2. All five browser flows pass on a fresh database.
3. `npm run lint` and `npm run build` are clean.
4. A dump of the test database is searched for a known PAN and account number; neither may appear in clear.
5. The README's status section says what has and has not been checked, including that the Maven build is not proven where it was not run.

## Risks

The largest risk is not in onboarding itself: the backend has never been built with Maven since the move to Spring Boot 4.1.1, and onboarding adds file uploads and a PDF library on top.

| Risk | What happens | What to do |
| --- | --- | --- |
| Maven build of Spring Boot 4.1.1 never run | The app may not start in Codespaces at all | Run `./restart.sh` in Codespaces before milestone 1; fix the build first. Prove the PDF library builds before task 5.3 |
| Encryption key lost or leaked | Lost: PAN and bank details unreadable. Leaked: they are readable from a dump | Keep `APP_DATA_KEY` in the host's secret store with a written backup; the key id allows rotation without downtime |
| Configuration too flexible | An Organization hides something payroll needs, or builds a form nobody can complete | The fixed fields and checks in the design; the preview; defaults that work untouched |
| Joiners fill the form on old phones and slow networks | Drafts lost, uploads fail | Save on every field change; uploads resumable by retry; test at 390 px and with the network throttled to 3G |
| No email | HR copies every link by hand, and reminders only show inside the app | Treat email sending as the first task after the first release; the message templates are already its wording |
| Indian rules move | PF ceiling, labour code forms, DPDP deadlines change | Thresholds are dated data; the consultant reviews the rules table before launch and each quarter |
| Scope grows mid-way | E-sign, background checks and DigiLocker get pulled forward | Each is a hook per section in the design; add one only when a paying customer asks |

## Before starting, and before release

Five decisions shape milestone 1 and should be settled before it starts; the release checklist applies to the first release after milestone 3 and to every milestone after it.

**Decide before milestone 1**

- [ ] Activation without a PAN: allowed with a reason (proposed) or blocked.
- [ ] Documents in PostgreSQL first (proposed) or object storage from the start.
- [ ] Invitation only after HR records the offer as accepted (proposed), or as soon as the joiner is added.
- [ ] The PDF library for letters: openhtmltopdf (proposed) or letters uploaded by HR only.
- [ ] Where `APP_DATA_KEY` lives in Codespaces and in production, and who holds the backup.

**Release checklist**

- [ ] `./restart.sh` in Codespaces builds with Maven and starts; `mvn test` passes.
- [ ] All JUnit tests and the five browser flows pass on a fresh database.
- [ ] The database dump check finds no clear PAN or account number.
- [ ] Migrations V5 and V6 run on a copy of a database that already has users, attendance and leave, and the defaults are created for each existing Organization.
- [ ] README: onboarding section, API table, test count, status.
- [ ] The onboarding design and this plan record what was built and what changed.
- [ ] The consultant has reviewed the India rules table, with the date of review written in the design.
