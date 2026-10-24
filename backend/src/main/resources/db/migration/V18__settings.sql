-- V18: the configuration system. Every policy value is a named setting declared once in code
-- (core/Settings.java) with its type, default, range and owner; the database holds only values that
-- differ from the default, and a log of every change. See docs/configuration/design.md.
--
-- Organization plane: app.setting_value (values in force, by scope and start date), app.setting_change
-- (append-only log, including changes waiting for a second person), app.setting_epoch (bumped by
-- every applied change, so each server knows when to reload its cache).
-- Platform plane: platform.setting (platform values and defaults for every Organization),
-- platform.setting_bound (limits on Organization values), platform.setting_change (their log).

-- ── Organization plane ───────────────────────────────────────────────

create table app.setting_change (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  key          text not null check (key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
  scope_kind   text not null check (scope_kind in ('organization','location','pay_group','person')),
  scope_id     uuid,
  valid_from   date,
  old_value    jsonb,                     -- null: it was the default
  new_value    jsonb,                     -- null: back to the default
  reason       text check (reason is null or length(btrim(reason)) between 3 and 400),
  status       text not null check (status in ('pending','applied','rejected','withdrawn','undone')),
  assurance    text not null check (assurance in ('none','password','second_person')),
  requested_by uuid references app.membership(id),          -- null for the system (a migration)
  requested_at timestamptz not null default now(),
  decided_by   uuid references app.membership(id),
  decided_at   timestamptz,
  decision_note text check (decision_note is null or length(btrim(decision_note)) between 3 and 400),
  undoes       uuid references app.setting_change(id),
  unique (tenant_id, id),
  check ((scope_kind = 'organization') = (scope_id is null)),
  check (status = 'pending' or status = 'withdrawn' or decided_at is not null or requested_by is null)
);
create index setting_change_key on app.setting_change (tenant_id, key, requested_at desc);
create index setting_change_pending on app.setting_change (tenant_id) where status = 'pending';

create table app.setting_value (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  key         text not null check (key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
  scope_kind  text not null check (scope_kind in ('organization','location','pay_group','person')),
  scope_id    uuid,                       -- null for the Organization; else the location, pay group or membership
  valid_from  date,                       -- null for undated settings
  value       jsonb not null,
  change_id   uuid not null,
  foreign key (tenant_id, change_id) references app.setting_change (tenant_id, id),
  check ((scope_kind = 'organization') = (scope_id is null))
);
create unique index setting_value_one on app.setting_value
  (tenant_id, key, scope_kind, coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(valid_from, '-infinity'::date));

create table app.setting_epoch (
  tenant_id  uuid primary key references platform.tenant(id),
  epoch      bigint not null default 0
);

-- A decided change never changes again: its outcome is the record.
create function app.setting_change_final() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'setting changes are never deleted';
  end if;
  if old.status <> 'pending' and (new.status is distinct from old.status and not (old.status = 'applied' and new.status = 'undone')
      or new.new_value is distinct from old.new_value or new.old_value is distinct from old.old_value or new.key <> old.key) then
    raise exception 'a decided setting change cannot be altered';
  end if;
  return new;
end $$;
create trigger setting_change_final before update or delete on app.setting_change
  for each row execute function app.setting_change_final();

do $$
declare t text;
begin
  foreach t in array array['setting_change','setting_value','setting_epoch'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;
grant delete on app.setting_value to app_tenant;

-- ── Platform plane ───────────────────────────────────────────────────

create table platform.setting_change (
  id           uuid primary key default gen_random_uuid(),
  key          text not null,
  what         text not null check (what in ('value','bound')),
  old_value    jsonb,
  new_value    jsonb,
  reason       text check (reason is null or length(btrim(reason)) between 3 and 400),
  changed_by   uuid references platform.platform_user(id),
  changed_at   timestamptz not null default now()
);

create table platform.setting (
  key        text primary key,
  value      jsonb not null,
  change_id  uuid not null references platform.setting_change(id)
);

create table platform.setting_bound (
  key           text primary key,
  minimum       numeric,
  maximum       numeric,
  allowed       jsonb check (allowed is null or jsonb_typeof(allowed) = 'array'),
  locked        boolean not null default false,
  locked_value  jsonb,
  change_id     uuid not null references platform.setting_change(id),
  check (not locked or locked_value is not null)
);

grant select, insert, update, delete on platform.setting, platform.setting_bound to app_platform;
grant select, insert on platform.setting_change to app_platform;
-- Sign-in reads platform values (the lockout limits) before any Organization is chosen.
grant select on platform.setting to app_auth;
grant select on platform.setting, platform.setting_bound to app_definer;

-- Inside an Organization's transaction, its code reads platform values and bounds through this function:
-- the tenant role cannot read the platform plane itself.
create function app.platform_settings()
returns table (key text, value jsonb, minimum numeric, maximum numeric, allowed jsonb, locked boolean, locked_value jsonb)
language sql stable security definer set search_path = platform, pg_temp as $$
  select coalesce(s.key, b.key), s.value, b.minimum, b.maximum, b.allowed, coalesce(b.locked, false), b.locked_value
    from platform.setting s full join platform.setting_bound b on b.key = s.key
$$;

grant create on schema app to app_definer;   -- needed to own a function there; see V1, V9 and V16
alter function app.platform_settings() owner to app_definer;
revoke create on schema app from app_definer;
revoke all on function app.platform_settings() from public;
grant execute on function app.platform_settings() to app_tenant;

-- ── messages ─────────────────────────────────────────────────────────

alter table app.message_template drop constraint message_template_event_check;
alter table app.message_template add constraint message_template_event_check
  check (event in ('joiner_invited','offer_issued','section_returned','reminder_joiner','task_assigned','task_due','task_overdue',
                   'joiner_ready','activated','welcome_team',
                   'resignation_submitted','resignation_recommended','resignation_accepted','resignation_withdrawn',
                   'withdrawal_requested','withdrawal_decided','exit_started','exit_proposed','exit_task_assigned',
                   'exit_task_overdue','last_day_tomorrow','settlement_due','exit_letter_issued','interview_sent',
                   'former_access_ending','payroll_ready','payroll_sent_back','payslip_released',
                   'tax_proof_returned','tax_window_open',
                   'setting_weakened','setting_pending','setting_decided'));
