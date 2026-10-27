-- The email outbox: mail is written in the same transaction as the thing that caused it (a reset, an invitation),
-- then a worker sends it, retrying with growing waits. The body holds links that act as passwords, so it is
-- stored encrypted and emptied once sent. Nothing here is per Organization-readable: tenant sessions can only
-- add rows, never read them.
set lock_timeout = '5s';

create schema mail;

create table mail.outbox (
  id              bigint generated always as identity primary key,
  tenant_id       uuid,
  to_email        citext not null,
  template        text not null,
  subject         text not null,
  body_enc        bytea,
  status          text not null default 'queued' check (status in ('queued', 'sending', 'sent', 'failed', 'suppressed')),
  attempts        integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  locked_until    timestamptz,
  last_error      text,
  dedupe_key      text unique,
  created_at      timestamptz not null default now(),
  sent_at         timestamptz
);

create index outbox_due on mail.outbox (next_attempt_at) where status in ('queued', 'sending');
create index outbox_status_created on mail.outbox (status, created_at desc);

-- Addresses we will not mail: bounces, complaints, or a person's own request.
create table mail.suppression (
  email  citext primary key,
  reason text not null check (reason in ('bounce', 'complaint', 'manual')),
  at     timestamptz not null default now(),
  by     text
);

grant usage on schema mail to app_platform, app_auth, app_tenant;
grant insert on mail.outbox to app_auth, app_tenant;
-- "on conflict (dedupe_key)" needs to read that one column; nothing else of a row is readable from these connections.
grant select (dedupe_key) on mail.outbox to app_auth, app_tenant;
grant select, insert, update on mail.outbox to app_platform;
grant select on mail.suppression to app_auth, app_tenant;
grant select, insert, update, delete on mail.suppression to app_platform;
