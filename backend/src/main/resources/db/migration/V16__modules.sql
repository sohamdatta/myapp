-- V16: what each Organization has paid for. Core HR (people, org chart, users, onboarding,
-- exit, and the shared work calendar: locations, shifts, holidays, who works where) is always
-- included. Attendance (with leave and work from home) and Payroll are switched on per
-- Organization by the platform, optionally until a trial ends. Switching off hides; it deletes nothing.

create table platform.tenant_module (
  tenant_id      uuid not null references platform.tenant(id),
  module         text not null check (module in ('attendance','payroll')),
  status         text not null check (status in ('on','off')),
  trial_ends_on  date,                                   -- null: no end; otherwise on through this date (India time)
  changed_by     uuid references platform.platform_user(id),
  changed_at     timestamptz not null default now(),
  primary key (tenant_id, module)
);

-- Organizations that exist already keep everything they have today.
insert into platform.tenant_module (tenant_id, module, status)
select t.id, m.module, 'on' from platform.tenant t cross join (values ('attendance'), ('payroll')) as m(module);

grant select, insert, update on platform.tenant_module to app_platform;
grant select on platform.tenant_module to app_auth, app_definer;

-- Whether a module is on for the Organization of the current transaction, for code inside it
-- (payroll asks whether to read attendance). The tenant role cannot read the platform plane.
create function app.module_on(p_module text)
returns boolean
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select exists (
    select 1 from platform.tenant_module m
     where m.tenant_id = app.current_tenant() and m.module = p_module and m.status = 'on'
       and (m.trial_ends_on is null or m.trial_ends_on >= (now() at time zone 'Asia/Kolkata')::date))
$$;

grant create on schema app to app_definer;   -- needed to own a function there; see V1 and V9
alter function app.module_on(text) owner to app_definer;
revoke create on schema app from app_definer;
revoke all on function app.module_on(text) from public;
grant execute on function app.module_on(text) to app_tenant;

-- A device belongs to an Organization with Attendance on; otherwise it is a stranger.
create or replace function auth.device_for_serial(p_serial text)
returns table (tenant_id uuid, device_id uuid)
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select d.tenant_id, d.id
    from app.attendance_device d
    join platform.tenant t on t.id = d.tenant_id
    join platform.tenant_module m on m.tenant_id = d.tenant_id and m.module = 'attendance' and m.status = 'on'
         and (m.trial_ends_on is null or m.trial_ends_on >= (now() at time zone 'Asia/Kolkata')::date)
   where upper(d.serial_number) = upper(p_serial)
     and d.active
     and t.status = 'active'
$$;
