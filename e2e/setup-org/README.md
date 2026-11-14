# Set up an Organization through the screens

Opens the app in a browser and does, by clicking and typing, what a new customer would do: the Super Admin
creates the Organization, its Org Admin accepts the invitation and then configures every module. No API calls.

| Section | What it does |
| --- | --- |
| Organization | Super Admin creates it and invites the first Org Admin, who sets a password |
| Getting started | Applies a starting setup (Office, Factory with shifts, or Retail): location, shifts, leave types, holidays |
| Users | Invites each person in `users` with their roles; links go to `setup-report/invitations.md` (no email is sent) |
| Attendance | Extra work locations, shifts and holidays, and the work-from-home rules |
| Leave | Extra leave types |
| People | Departments and sub-departments |
| Payroll | Employer details, PF/ESI registrations, a pay group |
| Performance | Competencies and probation check-in days |
| Exits | Notice period, response days and how long a former employee keeps access |

## Run it

```powershell
cd e2e
npm.cmd ci
npx.cmd playwright install chromium

$env:APP_URL = "https://your-app-url"
$env:SETUP_CONFIG = "D:\soham\my-org.json"     # optional: your values, merged over setup-org/config.ts
npm.cmd run setup-org
```

Everything it sets is in `setup-org/config.ts`, with comments. To change values, copy only the parts you want
to change into your own JSON file (see the shape of `defaults`) and point `SETUP_CONFIG` at it.

| Setting | |
| --- | --- |
| `APP_URL` | Where the app runs. Defaults to http://localhost:5173 |
| `SETUP_CONFIG` | A JSON file merged over `config.ts` |
| `SETUP_EXISTING=1` | The Organization already exists: sign in as its Org Admin instead of creating it |
| `SETUP_ONLY` | Only these sections, e.g. `attendance,leave` |

The result is `setup-report/report.md` (one line per section), a screenshot of each section in
`setup-report/shots/`, and `setup-report/invitations.md`. A section that fails is recorded and the rest still run.
Running it again on an Organization that already has the items skips them ("already there").

## Before real use

- The PAN, TAN, bank account, IFSC and registration numbers in `config.ts` are made-up examples. Replace them.
- Use a strong `org.adminPassword` and change the Super Admin password of the platform first.
- Not covered, because they need a decision or a person: the logo, two-step sign-in, salary components and
  structures (defaults are supplied), review cycles, onboarding checklists (no screens yet), and the payroll
  consultant's sign-off of the statutory rules.
