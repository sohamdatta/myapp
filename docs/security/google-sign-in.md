# Sign in with Google: setting it up

For the person running the installation (not for Organizations).

## What it does

People of an Organization can sign in with their company Google account instead of typing a password. Nothing replaces
the password: it is an addition, off until you set the two values below, and off for an Organization until its Org Admin
enters the company's Google Workspace domain.

- The account is matched by Google's permanent id for it, never by email.
- The account must belong to the Google Workspace domain the Organization entered (a personal Gmail account never does).
- A login that uses an authenticator app is still asked for its code: a company account is one factor, not two.
- The platform console always needs the password and a code.
- Turning the setting off, or a password reset, ends the connection.

## Get the client id and secret

1. In Google Cloud Console, create or choose a project, then **APIs & Services, OAuth consent screen**: user type
   **Internal** is enough if everyone is in your own Workspace; use **External** if customers use their own, and publish the app.
2. **Credentials, Create credentials, OAuth client ID**, type **Web application**.
3. **Authorised redirect URI**: `<APP_PUBLIC_URL>/api/auth/oidc/google/callback`, for example
   `https://hr.example.com/api/auth/oidc/google/callback`. One per installation (UAT and production each have their own).
4. Copy the client id and secret into `APP_GOOGLE_CLIENT_ID` and `APP_GOOGLE_CLIENT_SECRET` and restart.

The sign-in page then shows **Sign in with Google**. Scopes asked for: `openid email profile`, nothing else.

## What is kept

For each connection: the Google account's id, its company domain and email address as Google gave them, when it was
connected and last used. No Google token is stored. Caddy blanks the `code` and `state` Google sends back in its access log.

## If something goes wrong

The page shows a short sentence for each refusal. In the server log, look for lines starting `Sign-in with google`:
the token endpoint being unreachable or refusing the code is logged there. Keys used to check Google's tokens are
fetched from Google and cached; if Google cannot be reached the person is told to try again shortly.
