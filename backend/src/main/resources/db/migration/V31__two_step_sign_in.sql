-- Two-step sign-in (an authenticator app's code). The secret is sealed with the application's data key; setting up writes
-- it as "pending" and it only becomes the real one when a code from it is shown to be right. mfa_last_step is the last
-- 30-second step a code was accepted for, so a code cannot be used twice. A challenge is what a right password earns:
-- five minutes, and five wrong codes end it.
set lock_timeout = '5s';

alter table auth.identity
  add column mfa_pending_secret_enc bytea,
  add column mfa_last_step bigint;

create table auth.mfa_challenge (
  token_hash  bytea primary key,
  identity_id uuid not null references auth.identity(id),
  expires_at  timestamptz not null,
  attempts    integer not null default 0,
  used_at     timestamptz
);

create index mfa_challenge_expires on auth.mfa_challenge (expires_at);

grant select, insert, update, delete on auth.mfa_challenge to app_auth;
