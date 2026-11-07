# Smoke sweep

Signs in to an app that is already running and has data, opens **every screen the person can reach, every tab on it, and the first
few employees** (each of their tabs, and Pay → Set pay / Revise pay → **Preview**), and lists anything that goes wrong:

- an error note on screen ("Something went wrong…", "This page did not load");
- an API call that answers 4xx or 5xx (with the response text);
- an error thrown in the browser, or a screen stuck on "Loading…".

It **reads only**: Preview saves nothing. The result is `smoke-report/findings.md`, and the run fails if there is any problem.

## Run it

```bash
cd e2e && npm ci
npx playwright install chromium          # once; or set E2E_CHROMIUM_PATH to an installed Chrome

SMOKE_BASE_URL=https://your-host \
SMOKE_EMAIL=hr@yourdemo.org SMOKE_PASSWORD='...' \
npx playwright test -c playwright.smoke.config.ts
```

| Setting | What it does |
| --- | --- |
| `SMOKE_BASE_URL` | The app to test (default `http://localhost:5173`) |
| `SMOKE_EMAIL`, `SMOKE_PASSWORD` | The login to use. Run it once per role (HR, owner, manager, employee) to cover what each can see |
| `SMOKE_ORG` | Organization name, when the login has several |
| `SMOKE_EMPLOYEES` | How many employees from the top of the People list to open (default 5) |
| `SMOKE_PEOPLE` | Names to open as well, e.g. `Sanjay,Diya` |
| `SMOKE_SELFTEST=1` | Forces Set pay → Preview to fail, to prove the sweep notices. The run should then fail |

Use it after every deploy, on a demo or UAT Organization. On production, use a login that can only read.

## The sweep that writes: `actions.spec.ts`

For a **demo Organization only**. It sets pay for one employee (Set pay → Preview → Save), saves and restores their statutory
profile, then opens or starts a payroll run and goes as far as **Freeze inputs → Calculate**. It never approves or pays.
It does nothing unless `SMOKE_ALLOW_WRITES=1`, so it cannot run against production by accident. The result is `smoke-report/actions.md`.

```bash
SMOKE_ALLOW_WRITES=1 SMOKE_EMPLOYEE="Nora Nopay" \
SMOKE_BASE_URL=https://your-demo-host SMOKE_EMAIL=hr@yourdemo.org SMOKE_PASSWORD='...' \
npx playwright test -c playwright.smoke.config.ts actions
```

`SMOKE_EMPLOYEE` names the employee to give pay to (default: the first on the People list). Use someone you do not mind changing.
