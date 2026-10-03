-- Attendance module schema. Builds on V1 (user module). Same rules as V1:
-- every table carries tenant_id, has row-level security forced, and is
-- reachable only by the app_tenant role inside one Organization's transaction.

-- An employee code, so device files can be matched to users.
alter table app.membership add column employee_code text;
create unique index membership_employee_code_uq
  on app.membership (tenant_id, lower(employee_code)) where employee_code is not null;

create table app.work_location (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  name          text not null,
  timezone      text not null default 'Asia/Kolkata',
  state_code    text,                                  -- drives state rules later
  latitude      double precision check (latitude between -90 and 90),
  longitude     double precision check (longitude between -180 and 180),
  radius_m      int check (radius_m between 25 and 5000),
  allowed_ips   cidr[] not null default '{}',
  punch_check   text not null default 'either'
                check (punch_check in ('none','ip','location','either')),
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  check ((latitude is null) = (longitude is null) and (latitude is null) = (radius_m is null))
);
create unique index work_location_name_uq on app.work_location (tenant_id, lower(name));

create table app.shift (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  name               text not null,
  start_time         time not null,
  end_time           time not null,                    -- earlier than start_time = ends next day
  grace_minutes      int not null default 10 check (grace_minutes between 0 and 120),
  full_day_minutes   int not null default 480 check (full_day_minutes between 60 and 1440),
  half_day_minutes   int not null default 240 check (half_day_minutes between 30 and 720),
  active             boolean not null default true,
  created_at         timestamptz not null default now(),
  unique (tenant_id, id),
  check (half_day_minutes < full_day_minutes)
);
create unique index shift_name_uq on app.shift (tenant_id, lower(name));

create table app.holiday (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  work_location_id uuid,                               -- null = every location
  on_date          date not null,
  name             text not null,
  foreign key (tenant_id, work_location_id) references app.work_location (tenant_id, id)
);
create unique index holiday_uq on app.holiday
  (tenant_id, coalesce(work_location_id, '00000000-0000-0000-0000-000000000000'::uuid), on_date);

-- Which location, shift and weekly offs apply to a user, and from when.
-- Dated rows are added, never edited. A correction is a new row for the same
-- date; the newest row for a date is the one in force.
create table app.attendance_assignment (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null,
  membership_id    uuid not null,
  work_location_id uuid not null,
  shift_id         uuid not null,
  weekly_offs      smallint[] not null default '{0}',  -- 0 = Sunday ... 6 = Saturday
  effective_from   date not null,
  created_by       uuid references app.membership(id),
  created_at       timestamptz not null default now(),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  foreign key (tenant_id, work_location_id) references app.work_location (tenant_id, id),
  foreign key (tenant_id, shift_id) references app.shift (tenant_id, id),
  check (weekly_offs <@ array[0,1,2,3,4,5,6]::smallint[])
);

create index attendance_assignment_member on app.attendance_assignment (membership_id, effective_from desc, created_at desc);

create table app.import_batch (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  device_label    text not null,
  file_name       text not null,
  mapping         jsonb not null,                      -- which column holds what
  rows_total      int not null,
  rows_imported   int not null,
  rows_duplicate  int not null,
  rows_rejected   int not null,
  rejected_sample jsonb not null default '[]',
  first_punch_at  timestamptz,
  last_punch_at   timestamptz,
  uploaded_by     uuid references app.membership(id),
  uploaded_at     timestamptz not null default now(),
  unique (tenant_id, id)
);

-- Every punch, as it happened. Rows are only ever added.
create table app.punch (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null,
  membership_id    uuid not null,
  at               timestamptz not null default now(),
  direction        text not null check (direction in ('in','out')),
  source           text not null check (source in ('web','import','manual','request')),
  work_location_id uuid,
  latitude         double precision,
  longitude        double precision,
  accuracy_m       int,
  ip               inet,
  check_result     text not null default 'not_checked'
                   check (check_result in ('not_checked','ip_ok','location_ok')),
  import_batch_id  uuid,
  request_id       uuid,
  entered_by       uuid references app.membership(id), -- HR for manual entries
  reason           text,
  created_at       timestamptz not null default now(),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  foreign key (tenant_id, work_location_id) references app.work_location (tenant_id, id),
  foreign key (tenant_id, import_batch_id) references app.import_batch (tenant_id, id),
  check (source <> 'manual' or (entered_by is not null and length(btrim(reason)) >= 5))
);
create index punch_member_at on app.punch (membership_id, at);
create unique index punch_import_uq on app.punch (membership_id, at, direction) where source = 'import';

-- A correction or a day worked away from the office, raised by the user.
create table app.attendance_request (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  membership_id  uuid not null,
  kind           text not null check (kind in ('regularization','on_duty','work_from_home')),
  work_date      date not null,
  requested_in   timestamptz,
  requested_out  timestamptz,
  reason         text not null check (length(btrim(reason)) >= 5),
  status         text not null default 'pending'
                 check (status in ('pending','approved','rejected','cancelled')),
  decided_by     uuid references app.membership(id),
  decided_at     timestamptz,
  decision_note  text,
  created_at     timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  check (kind <> 'regularization' or (requested_in is not null and requested_out is not null and requested_out > requested_in)),
  constraint attendance_request_not_own check (decided_by is null or decided_by <> membership_id),  -- nobody approves their own
  check ((status in ('approved','rejected')) = (decided_by is not null and decided_at is not null))
);
create unique index attendance_request_open_uq
  on app.attendance_request (membership_id, work_date, kind) where status = 'pending';
alter table app.punch add foreign key (tenant_id, request_id) references app.attendance_request (tenant_id, id);

-- The worked-out result for one user on one day. Always derived from the
-- punches, the assignment, holidays and approved requests; safe to rebuild.
create table app.attendance_day (
  tenant_id        uuid not null,
  membership_id    uuid not null,
  work_date        date not null,
  status           text not null check (status in
                   ('present','half_day','absent','holiday','weekly_off','on_duty','work_from_home','not_assigned')),
  shift_id         uuid,
  first_in         timestamptz,
  last_out         timestamptz,
  worked_minutes   int not null default 0,
  late_minutes     int not null default 0,
  early_minutes    int not null default 0,
  overtime_minutes int not null default 0,
  incomplete       boolean not null default false,     -- an in without an out, or the reverse
  trail            text[] not null default '{}',       -- the steps that led to this result
  computed_at      timestamptz not null default now(),
  primary key (membership_id, work_date),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id)
);
create index attendance_day_tenant_date on app.attendance_day (tenant_id, work_date);

-- row-level security, exactly as in V1
do $$
declare t text;
begin
  foreach t in array array['work_location','shift','holiday','attendance_assignment','import_batch',
                           'punch','attendance_request','attendance_day'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

-- grants: settings can be edited; punches and import batches are add-only;
-- requests are added and decided; day results are rebuilt freely.
grant select, insert, update on app.work_location, app.shift to app_tenant;
grant select, insert, delete on app.holiday to app_tenant;
grant select, insert on app.attendance_assignment, app.import_batch, app.punch to app_tenant;
grant select, insert, update on app.attendance_request to app_tenant;
grant select, insert, update, delete on app.attendance_day to app_tenant;
grant select, insert, update, delete on all tables in schema app to app_definer;

-- permissions and which role holds each
insert into app.permission (code, description) values
  ('attendance.punch',   'Punch in and out, and raise correction requests'),
  ('attendance.read',    'View attendance records'),
  ('attendance.approve', 'Approve or reject attendance requests'),
  ('attendance.manage',  'Manage locations, shifts, holidays, assignments, manual entries and device imports');

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','attendance.punch','tenant'), ('owner','attendance.read','tenant'),
  ('owner','attendance.approve','tenant'), ('owner','attendance.manage','tenant'),
  ('hr_admin','attendance.read','tenant'), ('hr_admin','attendance.approve','tenant'),
  ('hr_admin','attendance.manage','tenant'),
  ('manager','attendance.read','team'), ('manager','attendance.approve','team'),
  ('employee','attendance.punch','self'), ('employee','attendance.read','self'),
  ('payroll_operator','attendance.read','assigned'), ('payroll_approver','attendance.read','assigned'),
  ('auditor','attendance.read','tenant'),
  ('ex_employee','attendance.read','self'),
  ('support_read','attendance.read','tenant'),
  ('support_write','attendance.read','tenant'), ('support_write','attendance.manage','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null;
