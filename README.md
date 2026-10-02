# HRMS user module

The user and access module of a multi-tenant HRMS: a React frontend and a Java
(Spring Boot) backend on PostgreSQL.

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

- **The Java build has not been run.** The environment this was written in
  could not download from Maven Central, so `mvn` could not compile or test
  the project. The backend code was instead compiled with `javac` against
  Spring 4.3, Jackson and the PostgreSQL driver, the 22 backend tests passed
  against PostgreSQL 16 when run with the JUnit console launcher, and the
  screens were exercised in a browser through a stand-in HTTP server that
  called the real controllers. The first `mvn test` and `./start.sh` in
  Codespaces are therefore the first run on Spring Boot 3.5. HikariCP and the
  Codespaces PostgreSQL setup are the parts that have never executed.
- **No email is sent.** Invitations produce a link that the screen shows for
  you to copy and send.
- **Not built yet:** multi-factor sign-in, password reset, email change, seat
  limits, custom roles, and limiting a user to particular legal entities or
  departments.

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

1. As the Super Admin, choose **Platform console**, create an Organization and
   give the email of its first Org Admin. Copy the invitation link.
2. Sign out, open the link, and set a password. You are now the Org Admin.
3. Under **Invitations**, invite a user with the HR Admin role, and open that
   link in a private window. The HR Admin can invite employees and managers,
   but the payroll and admin roles are greyed out for them.
4. Back in the console, request **Support access** to the Organization and
   start a session. Then look at the Organization's **Audit log** as its Org
   Admin: the support session is there, with its reason.

## Run it elsewhere

You need Java 21, Maven, Node and a PostgreSQL 16:

```bash
docker run -d --name hrms-db -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=hrms postgres:16
./start.sh
```

## Configuration

Set these environment variables for anything beyond local development.

| Variable | Default | Purpose |
|---|---|---|
| `APP_DB_URL` | `jdbc:postgresql://localhost:5432/hrms` | The database |
| `APP_DB_OWNER_USER` / `APP_DB_OWNER_PASSWORD` | `postgres` / `postgres` | Runs the migrations at startup and creates the application's database roles. Needs permission to create roles |
| `APP_DB_ROLE_PASSWORD` | `dev-only-change-me` | Password given to the three application roles |
| `APP_SUPERADMIN_EMAIL` / `APP_SUPERADMIN_PASSWORD` | `admin@example.com` / `ChangeMe-12345` | The first Super Admin, created only when there are no platform users |

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

## Roles

| Role | Can |
|---|---|
| Org Admin | Everything in the Organization, including users, roles and billing |
| HR Admin | Manage people and invite users. No access to other people's pay |
| Payroll Operator | Prepare payroll. Cannot approve it |
| Payroll Approver | Approve payroll and release payment. Cannot prepare it |
| Manager | See their own team |
| Employee | See their own record |
| Auditor | Read-only, including the audit log |

A user can grant a role only if they already hold every permission in it, so
an HR Admin can invite employees and managers but cannot hand out payroll or
admin roles, and cannot suspend or remove an Org Admin. Nobody can change
their own roles or status.

Only the user-management permissions are used by screens today. The employee,
compensation and payroll permissions are defined and assigned to roles, ready
for those modules.

## Layout

| Folder | What it holds |
|---|---|
| `frontend/` | React app (Vite). Screens are in `src/pages/`, the API client in `src/api.js` |
| `backend/src/main/java/.../core/` | The services and database access. Plain Java and JDBC, no Spring |
| `backend/src/main/java/.../web/` | Spring controllers and configuration |
| `backend/src/main/resources/db/migration/` | The schema and seed data, applied at startup |
| `.devcontainer/` | Codespaces configuration, including PostgreSQL |

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

The Organization a request acts on always comes from the server-side session.
It is never read from the URL, a header or the request body.

## Useful commands

```bash
cd backend && mvn test              # backend tests; skipped when PostgreSQL is not reachable
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
