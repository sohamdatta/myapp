# Onboarding: user guide

For customers. How a new joiner goes from an offer to an active employee: HR adds and invites them, they fill in their own details and documents (on a phone or a computer), HR checks each part, the checklist is worked through, and on the joining date they become an employee. The screenshots come from a demo Organization (Northwind Pharma); your names will differ.

## Overview

A joiner moves through: **Added → (Offered) → Invited → Filling in → Being checked → Ready → Joined**. Each of the nine sections is sent and checked on its own, so a wrong bank account does not hold up the rest. HR can withdraw a joiner at any point before they join.

| Role | Where | What you can do |
| --- | --- | --- |
| HR Admin / Org Admin | **People → Joiners**, **People → Setup** | Set onboarding up, add and invite joiners, check sections and documents, issue letters, work the checklist, activate, withdraw |
| Manager, IT Admin, anyone given a task | **My tasks** | Tick off their checklist tasks |
| Joiner | **My onboarding** (the only page they see) | Read the notice, fill in each section, upload documents, accept letters, acknowledge policies |
| Auditor | **People → Setup** | Read how onboarding is set up; change nothing |

Nobody checks their own onboarding.

## 1. Setting onboarding up (People → Setup)

Every Organization starts with working defaults, so you can add a joiner on day one and change things later. Changes apply to joiners added afterwards. Each tab shows who changed it last.

| Tab | What you set | Default |
| --- | --- | --- |
| Flows | Named flows chosen by rules (department, location, employment type, designation), tried from the top; welcome message, first-day note, HR contact, levels of checking (0 to 3) | One flow, **Everyone**, one level of checking |
| Form | **Built-in fields**: hidden, optional or required, label and help, for the Organization or one flow. **Your own fields**: text, number, date, choices, yes or no, email, phone, in any section. **Preview as joiner** | Every section on |
| Documents | Document types, required before joining, within N days, or optional, with instructions | PAN card, address proof (before joining); education (within 30 days); relieving letter, photo (optional) |
| Checklists | Tasks for HR, IT, the manager, a buddy or the joiner, due relative to an event, required or not | Joining (8 tasks), Leaving (9 tasks) |
| Letters | Offer, appointment and other letters with placeholders such as `{{person.legal_name}}`, versioned | An appointment letter with the Labour Code contents |
| Policies | Policies the joiner reads and acknowledges, before joining or within N days | None |
| Messages | The wording of each message (invited, returned, reminders, welcome) | Plain defaults |
| Employee codes | Series with a prefix (tokens `{YYYY}`, `{YY}`, `{MM}`, `{LOC}`, `{DEPT}`, `{TYPE}`), digits, start number and reset; the next code is shown as you type | Typed by hand |
| Settings | When the invitation goes, how long the link works (1 to 120 days), automatic or manual activation, the offer stage, activation without PAN, retention, the rehire check, and the privacy notice | Invite by hand; 14 days; automatic; no offer stage |

![Flows](img/onboarding/01-setup-flows.png)

![Built-in fields](img/onboarding/02-setup-form.png)

![Documents joiners give](img/onboarding/03-setup-documents.png)

![Checklists](img/onboarding/04-setup-checklists.png)

![An employee code series](img/onboarding/05-setup-codes.png)

![Joining settings and the privacy notice](img/onboarding/06-setup-settings.png)

Legal name, date of birth, joining date and PAN can never be hidden, and the PAN, IFSC, Aadhaar and UAN checks always run.

## 2. Adding a joiner (HR)

Open **People → Joiners → Add joiner**: full name, personal email and mobile, joining date (no more than 90 days back), employment type, designation, department, manager, employee code (leave empty to use the series), work location, shift and weekly offs. Choose **Save and invite**, or **Save, invite later** (a joiner without a personal email cannot be invited).

![Adding a joiner](img/onboarding/07-add-joiner.png)

The joiner's page shows the invitation link to copy and send, and **Before activation**: everything still needed. **New invitation link**, **Activate** and **Withdraw** are at the top.

![A joiner just invited](img/onboarding/08-joiner-invited.png)

## 3. What the joiner does (My onboarding)

The joiner opens the link, chooses a password and sees only **My onboarding**. First comes the welcome (joining date, manager, place) and the privacy notice; nothing opens until they choose **I have read this**.

![The welcome and notice, on a phone](img/onboarding/09-welcome.png)

They open each section, fill it in and choose **Send for checking**: personal details, contact, addresses, emergency contact, family and nominees, bank account, PAN, Aadhaar and PF. What is required is checked on sending, for example:

- joiners must be 18 or older;
- nominee shares must add up to 100%;
- the account number is typed twice and must match;
- PAN, IFSC, Aadhaar and UAN must be well formed. Aadhaar is optional and only its last four digits are kept.

![A section being filled in](img/onboarding/10-section.png)

**Documents** take PDF, JPEG or PNG files (checked from the file itself, not its name). A sent section is read-only and shows identifiers as the last four digits.

![Documents](img/onboarding/11-documents.png)

The progress bar counts sections sent and checked; **Your tasks** lists what the joiner must do (bring originals, for example). Letters to accept and policies to acknowledge appear here too.

![Progress and the joiner's tasks](img/onboarding/12-progress.png)

## 4. Checking (HR)

**People → Joiners** shows tiles (joining in 7 days, waiting on the joiner, waiting on HR, ready to activate, overdue tasks) and filters **Joining**, **Joined**, **Withdrawn** (declined offers are listed here too) and **All**.

![The joiners list](img/onboarding/13-joiners.png)

Open a joiner. Under **Sections**, open each section: **Verify**, or **Return with a note** saying what needs fixing; the joiner sees the note and sends it again. **Edit for the joiner** fills a section on their behalf (recorded with your name).

![Returning a section with a note](img/onboarding/14-return.png)

Under **Documents**, **View** a file, then **Verify** or **Reject** with a reason; the joiner uploads it again. A verified document cannot be replaced by the joiner.

![Checking documents](img/onboarding/15-check-documents.png)

## 5. The checklist and My tasks

**Checklist** groups tasks by owner (HR, IT, Manager, Joiner) with due dates. **Mark done**, **Skip** with a reason, **Reassign** to someone, or **Add a task**. Required tasks due before the joining date must be done or skipped before activation. Whoever is given a task finds it under **My tasks**, grouped by joiner.

![The checklist](img/onboarding/16-checklist.png)

![A manager's tasks](img/onboarding/18-my-tasks.png)

## 6. Letters, policies and offers

Under **Letters and policies**, choose a letter, **Preview** it filled for the joiner, and **Issue letter** (or **Upload a letter instead**). Policies show whether the joiner has acknowledged them.

![Letters and policies](img/onboarding/17-letters.png)

With **Joiners get an offer letter to accept first** ticked (Setup → Settings), HR issues the offer letter (with the days to answer) after adding the joiner. The joiner sees only the offer until they answer: **Accept** opens the welcome; **Decline** asks again (**Yes, decline** or **Keep it**) and ends the joining, which then shows as *offer declined*.

## 7. Joining and withdrawing

When every required section and document is checked, letters accepted, policies acknowledged and required tasks done, the joiner becomes an employee on the joining date: automatically, or when HR chooses **Activate**. They get the Employee role (and its menu instead of My onboarding), the next code from the series, attendance and leave from the joining date, and appear under **People → Employees**.

**Withdraw** (with a reason) ends a joining that will not happen: the link stops working and their data is deleted after the retention period.

## Common questions

| Problem | What to check |
| --- | --- |
| **Invite** is missing | Add the joiner's personal email. |
| "Still needed: …" when sending a section | Fill in the fields named. |
| "… 18 or older" | Check the date of birth. |
| "… must add up to 100%" | Make nominee shares total 100 for each scheme. |
| The account numbers are "different" | Type the same number in both boxes. |
| "Only PDF, JPEG and PNG" | The file is another kind, whatever its name says. |
| The joiner has not become an employee | Read **Before activation** on their page: what is listed still blocks it. |
| No employee code was given | Add a series under Setup → Employee codes, or type one when adding the joiner. |
| A declined joiner is not in the list | Choose **Withdrawn**. |
