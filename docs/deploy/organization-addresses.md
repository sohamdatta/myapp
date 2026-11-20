# Each Organization's own sign-in address

Every Organization can sign in at its own address: its short name (slug) in front of the
app's address. With `DOMAIN=hr.example.com`, Acme signs in at `https://acme.hr.example.com`.

That page:

- shows the Organization's name and its logo (the one its Org Admin uploads under
  **Organization settings → Logo**), with "Powered by Nexvance" underneath;
- lets in **only that Organization's members**. Anyone else is refused once their password
  (and code) is right, with a message pointing them to the main address. From there they
  can enter only that Organization: not another, and not the platform console;
- has no "Sign in with Google" button: Google sends people back to the main address.

The main address (`DOMAIN`) works exactly as before: anyone signs in and goes to their
Organization, and Super Admins reach the console.

## Choosing the short name

The Super Admin sets the slug when creating the Organization. After that the Org Admin can
change it under **Organization settings → Sign-in address**. The old address stops working
at once (it shows "Nobody signs in at this address" with a link to the main page), so the
screen warns first, and the change is recorded in the Organization's audit log.

Short names are 3 to 40 lowercase letters, digits or single hyphens. A few are kept for the
platform (`www`, `api`, `admin`, `console`, `mail`, `uat`, `staging` and others; see
`SignInPage.RESERVED`).

## Turning it on for a server

1. Add a DNS **wildcard** record, `*.hr.example.com`, pointing at the server (the same
   address as `DOMAIN`'s record).
2. In the environment's settings file (`deploy/env/<environment>.env`) set
   `ORG_ADDRESSES=*.hr.example.com`.
3. `deploy/hrms.sh <environment> up -d --build`.

Caddy fetches each Organization's certificate from Let's Encrypt the first time its address
is opened (on-demand TLS), which takes a few seconds once. Before fetching it asks the app
(`/api/public/tls-check`) whether the name belongs to an active Organization, so nobody can
make it request certificates for names that lead nowhere. Let's Encrypt allows 50 new
certificates per registered domain per week; with more Organizations than that opening
their address in one week, switch to a wildcard certificate (DNS challenge) instead.

Without `ORG_ADDRESSES` only the main address is served; the app still knows each
Organization's address and shows it in settings, but it will not open.

## Trying it locally

Browsers send `*.localhost` to your own machine, so with `./start.sh` running open
`http://<slug>.localhost:5173`. The development server passes the name on to the backend
in `X-Forwarded-Host`.

## What is public

An Organization's address tells anyone who opens it the Organization's name and logo, as the
logo address already did. Since short names can be guessed, so can which Organizations use
the service.
