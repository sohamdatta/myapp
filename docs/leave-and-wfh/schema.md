# Schema

This is `V4__leave_and_wfh.sql` as loaded on PostgreSQL 16 on top of the `V1`, `V2` and `V3` migrations. It is not in the repository yet.

```sql
-- Leave and work-from-home schema. Builds on V1 (users) and V3 (attendance).
-- Same rules: every table carries tenant_id, has row-level security forced,
-- and is reachable only by the app_tenant role inside one Organization's transaction.

create extension if not exists btree_gist;   -- lets a constraint forbid overlapping leave for one user

-- One row of attendance and leave settings per Organization.
create table app.attendance_settings (
  tenant_id              uuid primary key references platform.tenant(id),
  leave_year_start_month smallint not null default 1 check (leave_year_start_month in (1, 4)),
  wfh_enabled            boolean not null default true,
  wfh_requires_punch     boolean not null default false,
  wfh_limit_per_week     smallint check (wfh_limit_per_week between 1 and 7),
  wfh_limit_per_month    smallint check (wfh_limit_per_month between 1 and 31),
  updated_at             timestamptz not null default now()
);

create table app.leave_type (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  name               text not null,
  kind               text not null default 'regular' check (kind in ('regular','comp_off')),
  paid               boolean not null default true,
  accrual            text not null default 'yearly' check (accrual in ('none','monthly','yearly')),
  yearly_quota       numeric(5,1) not null default 0 check (yearly_quota between 0 and 366),
  prorate_joiners    boolean not null default true,
  carry_forward_cap  numeric(5,1) check (carry_forward_cap >= 0),   -- null = everything carries; 0 = nothing does
  excess_at_year_end text not null default 'lapse' check (excess_at_year_end in ('lapse','encash')),
  when_short         text not null default 'refuse' check (when_short in ('refuse','unpaid')),
  count_days_off     boolean not null default false,                -- count weekly offs and holidays inside a leave
  expiry_days        int check (expiry_days between 1 and 366),      -- comp-off only
  active             boolean not null default true,
  created_at         timestamptz not null default now(),
  unique (tenant_id, id),
  check (kind <> 'comp_off' or (accrual = 'none' and yearly_quota = 0)),
  check (paid or accrual = 'none')                                   -- unpaid leave has no balance
);
create unique index leave_type_name_uq on app.leave_type (tenant_id, lower(name));

create table app.leave_request (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  membership_id  uuid not null,
  leave_type_id  uuid not null,
  from_date      date not null,
  to_date        date not null,
  half           text check (half in ('first','second')),            -- a half day; only for a single date
  days           numeric(5,1) not null check (days > 0),             -- as counted when applied
  unpaid_days    numeric(5,1) not null default 0 check (unpaid_days >= 0),
  reason         text not null check (length(btrim(reason)) >= 5),
  status         text not null default 'pending'
                 check (status in ('pending','approved','rejected','cancelled')),
  applied_by     uuid references app.membership(id),                 -- set when HR applies for someone
  decided_by     uuid references app.membership(id),
  decided_at     timestamptz,
  decision_note  text,
  cancelled_at   timestamptz,
  created_at     timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  foreign key (tenant_id, leave_type_id) references app.leave_type (tenant_id, id),
  check (to_date >= from_date),
  check (half is null or from_date = to_date),
  check (unpaid_days <= days),
  constraint leave_request_not_own check (decided_by is null or decided_by <> membership_id),
  check (status not in ('approved','rejected') or (decided_by is not null and decided_at is not null)),
  check (status <> 'pending' or decided_by is null),
  -- one user cannot have two live requests covering the same date
  constraint leave_request_no_overlap exclude using gist
    (membership_id with =, daterange(from_date, to_date, '[]') with &&)
    where (status in ('pending','approved'))
);
create index leave_request_member on app.leave_request (membership_id, from_date);

create table app.leave_year_end_run (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  leave_year  int not null,                                           -- the year that was closed
  run_by      uuid references app.membership(id),
  run_at      timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, leave_year)                                      -- a year is closed once
);

-- Every change to a balance, as it happened. Rows are only ever added;
-- a balance is the sum of its entries.
create table app.leave_ledger (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null,
  membership_id    uuid not null,
  leave_type_id    uuid not null,
  on_date          date not null,
  days             numeric(6,2) not null check (days <> 0),           -- positive adds, negative takes
  kind             text not null check (kind in
                   ('accrual','taken','returned','adjustment','lapse','encash','comp_off_earned','comp_off_expired')),
  leave_request_id uuid,
  year_end_run_id  uuid,
  expires_on       date,                                              -- comp-off earned only
  source_ledger_id uuid references app.leave_ledger(id),              -- comp-off expired: the earned entry it closes
  entered_by       uuid references app.membership(id),
  reason           text,
  created_at       timestamptz not null default now(),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  foreign key (tenant_id, leave_type_id) references app.leave_type (tenant_id, id),
  foreign key (tenant_id, leave_request_id) references app.leave_request (tenant_id, id),
  foreign key (tenant_id, year_end_run_id) references app.leave_year_end_run (tenant_id, id),
  check (kind <> 'adjustment' or (entered_by is not null and length(btrim(reason)) >= 5)),
  check (kind not in ('taken','returned') or leave_request_id is not null),
  check (kind not in ('lapse','encash') or year_end_run_id is not null),
  check (kind <> 'comp_off_earned' or (entered_by is not null and expires_on is not null)),
  check (kind <> 'comp_off_expired' or source_ledger_id is not null)
);
create index leave_ledger_member on app.leave_ledger (membership_id, leave_type_id, on_date);
-- an accrual for a period is written once, however often it is attempted
create unique index leave_ledger_accrual_uq on app.leave_ledger (membership_id, leave_type_id, on_date) where kind = 'accrual';
create unique index leave_ledger_expired_uq on app.leave_ledger (source_ledger_id) where kind = 'comp_off_expired';
create unique index leave_ledger_taken_uq on app.leave_ledger (leave_request_id, kind) where kind in ('taken','returned');

-- work from home
alter table app.membership add column wfh_allowed boolean not null default true;
alter table app.attendance_assignment add column remote_days smallint[] not null default '{}'
  check (remote_days <@ array[0,1,2,3,4,5,6]::smallint[]);
alter table app.attendance_request add column half text check (half in ('first','second'));
alter table app.attendance_request add column cancelled_at timestamptz;
-- an approved request can now be cancelled, so "decided" no longer means "approved or rejected" exactly
alter table app.attendance_request drop constraint attendance_request_check1;
alter table app.attendance_request add constraint attendance_request_decided
  check ((status not in ('approved','rejected') or (decided_by is not null and decided_at is not null))
     and (status <> 'pending' or decided_by is null));
alter table app.punch drop constraint punch_check_result_check;
alter table app.punch add constraint punch_check_result_check
  check (check_result in ('not_checked','ip_ok','location_ok','remote'));

-- the day result learns about leave
alter table app.attendance_day drop constraint attendance_day_status_check;
alter table app.attendance_day add constraint attendance_day_status_check check (status in
  ('present','half_day','absent','holiday','weekly_off','on_duty','work_from_home','on_leave','not_assigned'));
alter table app.attendance_day add column leave_days numeric(2,1) not null default 0 check (leave_days in (0, 0.5, 1));
alter table app.attendance_day add column leave_unpaid boolean not null default false;
alter table app.attendance_day add column remote boolean not null default false;

-- row-level security, exactly as in V1 and V3
do $$
declare t text;
begin
  foreach t in array array['attendance_settings','leave_type','leave_request','leave_year_end_run','leave_ledger'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

-- grants: settings and types can be edited; requests are added and decided;
-- ledger entries and year-end runs are add-only.
grant select, insert, update on app.attendance_settings, app.leave_type, app.leave_request to app_tenant;
grant select, insert on app.leave_ledger, app.leave_year_end_run to app_tenant;
grant select, insert, update, delete on all tables in schema app to app_definer;

insert into app.permission (code, description) values
  ('leave.apply',   'Apply for leave and cancel own requests'),
  ('leave.read',    'View leave balances and requests'),
  ('leave.approve', 'Approve or reject leave requests'),
  ('leave.manage',  'Manage leave types, apply on behalf, adjust balances and run the year end');

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','leave.apply','tenant'), ('owner','leave.read','tenant'),
  ('owner','leave.approve','tenant'), ('owner','leave.manage','tenant'),
  ('hr_admin','leave.read','tenant'), ('hr_admin','leave.approve','tenant'), ('hr_admin','leave.manage','tenant'),
  ('manager','leave.read','team'), ('manager','leave.approve','team'),
  ('employee','leave.apply','self'), ('employee','leave.read','self'),
  ('payroll_operator','leave.read','assigned'), ('payroll_approver','leave.read','assigned'),
  ('auditor','leave.read','tenant'),
  ('ex_employee','leave.read','self'),
  ('support_read','leave.read','tenant'),
  ('support_write','leave.read','tenant'), ('support_write','leave.manage','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null;
```
