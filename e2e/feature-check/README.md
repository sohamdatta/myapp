# Feature check

Signs in to any running copy of the app with an account you give it, then opens every
feature that account sees in the sidebar, one test per feature. On each feature it also
clicks every tab. A feature fails when:

- the page crashes ("Something went wrong on this page"),
- an error message is shown on screen,
- the server answers any request with a 5xx error, or
- the browser console shows an error.

Requests the server refuses (4xx) don't fail the test. They're listed on the test in the
report, because a 403 can be correct for that account.

It is **read-only**: it never fills in a form or presses a button that saves. So it is
safe to run against UAT or production. (The full suite in `e2e/tests` creates
Organizations and people, so run that only against a fresh test database.)

## Run it from your computer

```bash
cd e2e
npm ci
npx playwright install chromium

APP_URL=https://uat.hr.example.com \
APP_EMAIL=you@company.com \
APP_PASSWORD='your-password' \
npm run check

npm run check:report      # opens the HTML report: a screenshot of every page and tab
```

| Setting | |
|---|---|
| `APP_URL` | Where the app runs (required) |
| `APP_EMAIL`, `APP_PASSWORD` | The account to sign in with (required) |
| `APP_TOTP_SECRET` | The authenticator key, if the account uses two-step sign-in |
| `APP_ORG` | The Organization to open, if the account belongs to more than one |
| `APP_FEATURES` | Only check these, e.g. `People,Leave,My attendance` |

Features the account can't see are marked **skipped**. To cover everything, run it once
with an Org Admin and once with an ordinary employee. A Super Admin covers the
platform console.

## Run it from GitHub

Actions → **Feature check** → **Run workflow**, then paste the URL. Add the
`CHECK_EMAIL` and `CHECK_PASSWORD` secrets first (and `CHECK_TOTP_SECRET` if needed).
The report is attached to the run as `feature-check-report`.
