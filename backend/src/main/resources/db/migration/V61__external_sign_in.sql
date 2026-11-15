-- V61: sign in with Google or Microsoft. external_login ties a login to one account at a provider: the provider's own
-- permanent id (Google sub; Microsoft tenant:oid), never the email, which can change hands. tenant is the Google
-- Workspace domain or Microsoft tenant the account belonged to when it was linked. oidc_handoff is the one-time code
-- the browser trades for a session after coming back from the provider: stored as a hash, 60 seconds, used once.
-- A session remembers how it was started so the platform console can refuse one that did not use a password.
set lock_timeout = '5s';

create table auth.external_login (
  id           uuid primary key default gen_random_uuid(),
  identity_id  uuid not null references auth.identity(id),
  provider     text not null check (provider in ('google','microsoft')),
  subject      text not null,
  tenant       text,
  email        text,
  created_at   timestamptz not null default now(),
  last_used_at timestamptz,
  unique (provider, subject),
  unique (identity_id, provider)
);

create table auth.oidc_handoff (
  code_hash   bytea primary key,
  identity_id uuid not null references auth.identity(id),
  provider    text not null check (provider in ('google','microsoft')),
  tenant      text,
  expires_at  timestamptz not null,
  used_at     timestamptz
);

create index oidc_handoff_expires on auth.oidc_handoff (expires_at);

grant select, insert, update, delete on auth.external_login, auth.oidc_handoff to app_auth;

alter table auth.session
  add column auth_method text not null default 'password',
  add column provider_tenant text;

alter table auth.session
  add constraint session_auth_method_check check (auth_method in ('password','google','microsoft')) not valid;
alter table auth.session validate constraint session_auth_method_check;
