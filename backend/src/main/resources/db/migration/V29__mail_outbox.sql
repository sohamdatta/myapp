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
  claim           uuid,
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

-- The only way in for Organization and sign-in connections: a function that runs as its owner, so they need no access to
-- the tables at all (they cannot read dedupe keys or the suppression list, or write a row for another Organization).
create function mail.enqueue(p_tenant uuid, p_to citext, p_template text, p_subject text, p_body bytea, p_dedupe text)
returns boolean
language plpgsql
security definer
set search_path = mail, pg_temp
as $$
declare
  added integer;
begin
  if p_tenant is not null and p_tenant is distinct from nullif(current_setting('app.tenant_id', true), '')::uuid then
    raise exception 'mail for another Organization' using errcode = '42501';
  end if;
  insert into mail.outbox (tenant_id, to_email, template, subject, body_enc, status, dedupe_key)
  values (p_tenant, p_to, p_template, p_subject,
          case when exists (select 1 from mail.suppression where email = p_to) then null else p_body end,
          case when exists (select 1 from mail.suppression where email = p_to) then 'suppressed' else 'queued' end,
          p_dedupe)
  on conflict (dedupe_key) do nothing;
  get diagnostics added = row_count;
  return added > 0;
end;
$$;

revoke all on function mail.enqueue(uuid, citext, text, text, bytea, text) from public;

grant usage on schema mail to app_platform, app_auth, app_tenant;
grant execute on function mail.enqueue(uuid, citext, text, text, bytea, text) to app_platform, app_auth, app_tenant;
grant select, insert, update, delete on mail.outbox to app_platform;
grant select, insert, update, delete on mail.suppression to app_platform;
