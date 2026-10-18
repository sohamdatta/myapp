-- V15: how each work location records attendance, and the two new ways.
--   app      people punch in the app (what V3 built; still the default)
--   device   a biometric machine at the door sends each punch by itself
--   present  every working day counts as present; HR or the manager marks the exceptions
-- Same rules as V1: tenant_id on every table, row-level security forced.

-- The mode in force at a location from a date. Rows are added, never edited; the newest
-- row for the latest start date wins, as with attendance assignments. No row = 'app'.
create table app.location_mode (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null,
  work_location_id uuid not null,
  mode             text not null check (mode in ('app','device','present')),
  effective_from   date not null,
  created_by       uuid references app.membership(id),
  created_at       timestamptz not null default now(),
  foreign key (tenant_id, work_location_id) references app.work_location (tenant_id, id)
);
create index location_mode_location on app.location_mode (work_location_id, effective_from desc, created_at desc);

-- An exception marked on a present-by-default day. Rows are only added; the newest for a
-- date is the one in force, and 'present' takes back an earlier mark.
create table app.attendance_mark (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  membership_id  uuid not null,
  work_date      date not null,
  status         text not null check (status in ('absent','half_day','present')),
  reason         text not null check (length(btrim(reason)) >= 5),
  marked_by      uuid not null references app.membership(id),
  created_at     timestamptz not null default now(),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  constraint attendance_mark_not_own check (marked_by <> membership_id)   -- nobody marks their own day
);
create index attendance_mark_member on app.attendance_mark (membership_id, work_date, created_at desc);

-- A biometric machine that pushes punches (eSSL / ZKTeco "ADMS" cloud push). It names itself
-- by serial number only, so a serial belongs to one Organization across the whole platform.
create table app.attendance_device (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  work_location_id uuid not null,
  name             text not null check (length(btrim(name)) between 1 and 80),
  serial_number    text not null check (serial_number ~ '^[A-Za-z0-9-]{4,40}$'),
  active           boolean not null default true,
  attlog_stamp     text,                                  -- the device's own upload marker, handed back on connect
  last_seen_at     timestamptz,
  last_ip          inet,
  last_refused_at  timestamptz,                           -- the last call turned away, and why, for HR to see
  last_refused     text,
  created_by       uuid references app.membership(id),
  created_at       timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, work_location_id) references app.work_location (tenant_id, id)
);
create unique index attendance_device_serial_uq on app.attendance_device (upper(serial_number));

-- Every line a device sent and what became of it. Rows are only added.
create table app.device_punch_log (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  device_id      uuid not null,
  received_at    timestamptz not null default now(),
  ip             inet,
  employee_code  text,
  punched_at     timestamptz,
  outcome        text not null check (outcome in ('imported','duplicate','unknown_code','in_the_future','unreadable')),
  line           text not null,
  foreign key (tenant_id, device_id) references app.attendance_device (tenant_id, id)
);
create index device_punch_log_device on app.device_punch_log (device_id, received_at desc);

-- Punches from a device. Its In/Out keys are rarely pressed, so the calculation goes by order.
alter table app.punch drop constraint punch_source_check;
alter table app.punch add constraint punch_source_check
  check (source in ('web','import','manual','request','device'));
alter table app.punch add column device_id uuid;
alter table app.punch add foreign key (tenant_id, device_id) references app.attendance_device (tenant_id, id);
create unique index punch_device_uq on app.punch (membership_id, at) where source = 'device';

do $$
declare t text;
begin
  foreach t in array array['location_mode','attendance_mark','attendance_device','device_punch_log'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

grant select, insert on app.location_mode, app.attendance_mark, app.device_punch_log to app_tenant;
grant select, insert, update on app.attendance_device to app_tenant;
grant select, insert, update, delete on app.location_mode, app.attendance_mark, app.attendance_device, app.device_punch_log
  to app_definer;

-- A device calls in with nothing but its serial number. This finds whose it is, across
-- Organizations, and nothing more; everything after runs inside that one Organization.
create function auth.device_for_serial(p_serial text)
returns table (tenant_id uuid, device_id uuid)
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select d.tenant_id, d.id
    from app.attendance_device d
    join platform.tenant t on t.id = d.tenant_id
   where upper(d.serial_number) = upper(p_serial)
     and d.active
     and t.status = 'active'
$$;

-- Whether a serial number is taken by any Organization, so registering it twice gets a plain answer.
create function auth.device_serial_taken(p_serial text)
returns boolean
language sql stable security definer set search_path = app, pg_temp as $$
  select exists (select 1 from app.attendance_device d where upper(d.serial_number) = upper(p_serial))
$$;

grant create on schema auth to app_definer;   -- needed to own a function there; see V1, V9 and V14
alter function auth.device_for_serial(text) owner to app_definer;
alter function auth.device_serial_taken(text) owner to app_definer;
revoke create on schema auth from app_definer;
revoke all on function auth.device_for_serial(text), auth.device_serial_taken(text) from public;
grant execute on function auth.device_for_serial(text), auth.device_serial_taken(text) to app_auth;
