# Login App

A simple application with a login screen: a React frontend and a Java (Spring Boot) backend.

## Run it in GitHub Codespaces

1. Open this repository in a codespace. Java 21, Maven and Node are installed automatically.
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
| `.devcontainer/` | Codespaces configuration. |

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/login` | Checks the username and password, returns a token |
| GET | `/api/auth/me` | Returns the signed-in user for a token |
| POST | `/api/auth/logout` | Ends the session |

The frontend sends `/api` requests through the Vite dev server to the backend on port 8080.

## Useful commands

```bash
cd backend && mvn test              # backend tests
cd backend && mvn spring-boot:run   # backend only
cd frontend && npm run dev          # frontend only
```

## Before real use

This is a starter. There is one demo account, set in
`backend/src/main/resources/application.properties` (or with the
`APP_DEMO_USERNAME` and `APP_DEMO_PASSWORD` environment variables), and sessions
are held in memory, so they end when the backend restarts. For a real
application, store users in a database with hashed passwords.
