-- Failed sign-ins, kept per email address whether or not an account has that address, so a lock looks the same for both
-- (auth.identity.failed_login_count and locked_until only exist for real accounts, which would tell the two apart).
-- Rows older than the lock window mean nothing and are deleted by the application.
create table auth.login_failure (
  id    bigint generated always as identity primary key,
  email citext not null,
  at    timestamptz not null default now()
);

create index login_failure_email_at on auth.login_failure (email, at);
create index login_failure_at on auth.login_failure (at);

grant select, insert, delete on auth.login_failure to app_auth;
