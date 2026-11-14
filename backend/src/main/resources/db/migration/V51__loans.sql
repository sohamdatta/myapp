-- V51: salary advances and loans (docs/expenses/design.md, stage X4).
--   loan_type        what an Organization offers: a ceiling, a term, a rate, the service needed, how many may be open.
--   loan             one request, through manager and finance approval to disbursal and repayment.
--   loan_instalment  the schedule, fixed when the loan is disbursed. Payroll takes the instalments that are due.
--   loan_settlement  repayments made outside payroll: a prepayment, a write-off.
-- Additive only. By hand: drop the four tables and the permission and role rows.
set lock_timeout = '5s';

create table app.loan_type (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references platform.tenant(id),
  name                 text not null check (length(btrim(name)) between 1 and 60),
  kind                 text not null check (kind in ('salary_advance','loan')),
  max_amount           numeric(12,2) not null check (max_amount > 0),
  max_months           int not null check (max_months between 1 and 60),
  annual_rate          numeric(5,2) not null default 0 check (annual_rate between 0 and 30),
  min_service_months   int not null default 0 check (min_service_months between 0 and 120),
  max_open             int not null default 1 check (max_open between 1 and 10),
  active               boolean not null default true,
  created_at           timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, name)
);

create table app.loan_counter (
  tenant_id uuid not null references platform.tenant(id),
  year      int  not null,
  last      int  not null,
  primary key (tenant_id, year)
);

create table app.loan (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  number           text not null,
  membership_id    uuid not null,
  type_id          uuid not null,
  amount           numeric(12,2) not null check (amount > 0),
  months           int not null check (months between 1 and 60),
  annual_rate      numeric(5,2) not null check (annual_rate between 0 and 30),   -- the rate as it was when the loan was asked for
  purpose          text not null check (length(btrim(purpose)) between 3 and 400),
  status           text not null default 'requested' check (status in
                     ('requested','manager_approved','approved','active','closed','rejected','withdrawn','written_off')),
  manager_by       uuid references app.membership(id),
  manager_at       timestamptz,
  finance_by       uuid references app.membership(id),
  finance_at       timestamptz,
  approver_membership_id uuid references app.membership(id),   -- who the manager step was routed to, as of the request
  decision_note    text,
  disbursed_on     date,
  disbursed_by     uuid references app.membership(id),
  disbursal_ref    text,
  first_month      date check (first_month is null or first_month = date_trunc('month', first_month)::date),
  version          int not null default 1,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, number),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  foreign key (tenant_id, type_id) references app.loan_type (tenant_id, id),
  -- Nobody approves their own loan.
  constraint loan_not_own check ((manager_by is null or manager_by <> membership_id) and (finance_by is null or finance_by <> membership_id)),
  constraint loan_says_why check (status <> 'rejected' or decision_note is not null),
  check (status not in ('active','closed') or (disbursed_on is not null and first_month is not null))
);
create index loan_person on app.loan (tenant_id, membership_id, status);

create table app.loan_instalment (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  loan_id       uuid not null,
  month         date not null check (month = date_trunc('month', month)::date),
  principal     numeric(12,2) not null check (principal >= 0),
  interest      numeric(12,2) not null default 0 check (interest >= 0),
  status        text not null default 'due' check (status in ('due','taken','skipped','cancelled')),
  taken_run_id  uuid,
  taken_amount  numeric(12,2),
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, loan_id) references app.loan (tenant_id, id),
  check (status <> 'taken' or taken_amount is not null)
);
-- One live instalment for a loan in a month; a skipped or cancelled one steps aside.
create unique index loan_instalment_month on app.loan_instalment (loan_id, month) where status in ('due','taken');
create index loan_instalment_due on app.loan_instalment (tenant_id, month) where status = 'due';

create table app.loan_settlement (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references platform.tenant(id),
  loan_id    uuid not null,
  kind       text not null check (kind in ('prepayment','write_off')),
  amount     numeric(12,2) not null check (amount > 0),
  note       text not null check (length(btrim(note)) >= 3),
  by_id      uuid not null references app.membership(id),
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, loan_id) references app.loan (tenant_id, id)
);

do $$
declare t text;
begin
  foreach t in array array['loan_type','loan_counter','loan','loan_instalment','loan_settlement'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format('create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;
-- A loan and what was recorded against it are kept.
revoke update on app.loan_settlement from app_tenant;

insert into app.permission (code, description) values
  ('loan.manage', 'Approve, pay out and manage advances and loans');

insert into app.role_permission (role_id, permission_code, scope)
select r.id, 'loan.manage', 'tenant' from app.role r where r.code in ('owner','finance') and r.tenant_id is null;
