# Login App

A simple application with a login screen: a React frontend, a Java (Spring Boot) backend and a PostgreSQL database.

## Run it in GitHub Codespaces

1. Open this repository in a codespace. Java 21, Maven, Node and PostgreSQL are set up automatically.
2. In the terminal, run:

   ```bash
   ./start.sh
   ```

3. Open the forwarded port **5173** (the "App (React)" entry in the Ports tab).
4. Sign in with the demo account: username `admin`, password `admin123`.

The first start takes a few minutes while Maven downloads Spring Boot.

## Layout

| Folder | What it holds |
|---|---|
| `frontend/` | React app (Vite). The login screen is in `src/App.jsx`. |
| `backend/` | Spring Boot API. The login logic is in `AuthService.java` and `AuthController.java`. |
| `.devcontainer/` | Codespaces configuration, including the PostgreSQL container. |

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/login` | Checks the username and password, returns a token |
| GET | `/api/auth/me` | Returns the signed-in user for a token |
| POST | `/api/auth/logout` | Ends the session |

The frontend sends `/api` requests through the Vite dev server to the backend on port 8080.

## Database

PostgreSQL 17 runs in its own container next to the app and is reachable at
`localhost:5432` (database `myapp`, user `myapp`, password `myapp`). These are
development-only credentials for the codespace.

Users are stored in the `users` table, created at startup from
`backend/src/main/resources/schema.sql`. Passwords are stored as BCrypt hashes.
The demo account is added at startup if it is not already in the table.

```bash
psql -h localhost -U myapp myapp     # open a database prompt (password: myapp)
```

## Useful commands

```bash
cd backend && mvn test              # backend tests (use an in-memory database)
cd backend && mvn spring-boot:run   # backend only
cd frontend && npm run dev          # frontend only
```

## Before real use

This is a starter. The demo account's username and password are set in
`backend/src/main/resources/application.properties` (or with the
`APP_DEMO_USERNAME` and `APP_DEMO_PASSWORD` environment variables); changing them
later adds a new account and does not remove the old one. Sign-in sessions are
held in memory, so everyone signs in again after the backend restarts. For a
real deployment, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` to your own
database and choose a strong demo password or remove the demo account.
