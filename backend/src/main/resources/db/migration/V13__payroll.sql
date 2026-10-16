-- Payroll: the configuration an Organization builds (components, structures, pay groups, statutory
-- choices), each employee's pay, and the monthly run with its payslips. The statutory rules are data
-- shared by every Organization, versioned by date and state, and maintained by us.
--
-- Money is numeric(12,2) rupees: exact, never floating point. Every line keeps its value before
-- rounding. Rows of an approved run never change (triggers below).

-- ── who may do what ──────────────────────────────────────────────────

-- HR Admins enter salaries (decided 17 Oct 2026); runs and payslips stay with the payroll roles.
insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('hr_admin','compensation.read','tenant'), ('hr_admin','compensation.write','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null
on conflict do nothing;

-- ── statutory rules: platform data, the same for every Organization ──

create table app.payroll_rule (
  id            uuid primary key default gen_random_uuid(),
  statute       text not null check (statute in ('pf','esi','pt','lwf','income_tax')),
  jurisdiction  text not null check (jurisdiction ~ '^[A-Z]{2}$'),   -- IN for national rules, else the state code
  valid_from    date not null,
  valid_to      date,                                                 -- exclusive; null while in force
  data          jsonb not null,
  status        text not null default 'draft' check (status in ('draft','signed_off')),
  source        text not null,
  signed_off_by text,
  signed_off_on date,
  check (valid_to is null or valid_to > valid_from),
  check ((status = 'signed_off') = (signed_off_by is not null and signed_off_on is not null)),
  constraint payroll_rule_no_overlap exclude using gist
    (statute with =, jurisdiction with =, daterange(valid_from, valid_to, '[)') with &&)
);
grant select on app.payroll_rule to app_tenant;
grant select, insert, update on app.payroll_rule to app_definer;

-- Draft figures from public summaries, to be signed off by the payroll consultant before real use.
insert into app.payroll_rule (statute, jurisdiction, valid_from, data, source) values
('pf', 'IN', '2025-04-01',
 '{"employeeRate":12,"employerRate":12,"epsRate":8.33,"ceiling":15000,"edliRate":0.5,"adminRate":0.5}',
 'EPF scheme rates as summarised in the payroll feature checklist (30 Aug 2026); awaiting consultant sign-off'),
('esi', 'IN', '2025-04-01',
 '{"employeeRate":0.75,"employerRate":3.25,"threshold":21000,"thresholdDisability":25000,"lowWageDaily":176,"periodStartMonths":[4,10]}',
 'ESI rates and thresholds as summarised in the payroll feature checklist (30 Aug 2026); awaiting consultant sign-off'),
('pt', 'MH', '2023-04-01',
 '{"basis":"gross","men":[{"from":0,"to":7500,"monthly":0},{"from":7500.01,"to":10000,"monthly":175},{"from":10000.01,"to":null,"monthly":200,"february":300}],"women":[{"from":0,"to":25000,"monthly":0},{"from":25000.01,"to":null,"monthly":200,"february":300}],"yearlyCeiling":2500}',
 'Zoho Payroll academy, Maharashtra professional tax (read 17 Oct 2026); awaiting consultant sign-off'),
('pt', 'KA', '2025-04-01',
 '{"basis":"gross","men":[{"from":0,"to":24999.99,"monthly":0},{"from":25000,"to":null,"monthly":200,"february":300}],"yearlyCeiling":2500}',
 'Akrivia HCM alert on the Karnataka amendment from 1 Apr 2025 (read 17 Oct 2026); awaiting consultant sign-off'),
('pt', 'GA', '2018-04-01',
 '{"basis":"gross","men":[{"from":0,"to":15000,"monthly":0},{"from":15000.01,"to":25000,"monthly":150},{"from":25000.01,"to":null,"monthly":200}],"yearlyCeiling":2500}',
 'HROne summary of Goa slabs, titled 2018-19 (read 17 Oct 2026); awaiting consultant sign-off'),
('lwf', 'MH', '2025-04-01', '{"employee":25,"employer":75,"months":[6,12]}',
 'Patron Accounting LWF table (Apr 2026); awaiting consultant sign-off'),
('lwf', 'KA', '2025-04-01', '{"employee":50,"employer":100,"months":[12]}',
 'Patron Accounting LWF table (Apr 2026); deduction month to confirm; awaiting consultant sign-off'),
('lwf', 'GA', '2025-04-01', '{"employee":60,"employer":120,"months":[6,12]}',
 'Patron Accounting LWF table (Apr 2026); deduction months to confirm; awaiting consultant sign-off'),
('income_tax', 'IN', '2025-04-01',
 '{"cess":4,
   "new":{"standardDeduction":75000,"slabs":[{"upTo":400000,"rate":0},{"upTo":800000,"rate":5},{"upTo":1200000,"rate":10},{"upTo":1600000,"rate":15},{"upTo":2000000,"rate":20},{"upTo":2400000,"rate":25},{"upTo":null,"rate":30}],
          "rebateLimit":1200000,"rebateMax":60000,"rebateMarginalRelief":true,
          "surcharge":[{"above":5000000,"rate":10},{"above":10000000,"rate":15},{"above":20000000,"rate":25}]},
   "old":{"standardDeduction":50000,"slabs":[{"upTo":250000,"rate":0},{"upTo":500000,"rate":5},{"upTo":1000000,"rate":20},{"upTo":null,"rate":30}],
          "rebateLimit":500000,"rebateMax":12500,"section80cMax":150000,
          "surcharge":[{"above":5000000,"rate":10},{"above":10000000,"rate":15},{"above":20000000,"rate":25},{"above":50000000,"rate":37}]}}',
 'Slabs as summarised in the payroll feature checklist (30 Aug 2026); awaiting consultant sign-off');

-- ── the Organization's payroll settings and registrations ────────────

create table app.payroll_settings (
  tenant_id        uuid primary key references platform.tenant(id),
  entity_name      text check (entity_name is null or length(btrim(entity_name)) between 1 and 160),
  pan              text check (pan is null or pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
  tan              text check (tan is null or tan ~ '^[A-Z]{4}[0-9]{5}[A-Z]$'),
  address          text check (address is null or length(address) <= 400),
  debit_account    text check (debit_account is null or debit_account ~ '^[0-9]{6,20}$'),
  debit_ifsc       text check (debit_ifsc is null or debit_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
  proration        text not null default 'calendar' check (proration in ('calendar','working','fixed_30')),
  absence_is_lop   boolean not null default false,       -- absence without leave or a request counts as loss of pay
  pf_restrict      boolean not null default true,        -- PF on wages up to the ceiling, unless the employee's profile says full
  metro_locations  uuid[] not null default '{}',         -- work locations treated as metro (HRA)
  variance_pct     numeric(5,2) not null default 10 check (variance_pct between 0 and 100),
  large_one_time   numeric(12,2) not null default 25000 check (large_one_time >= 0),
  release_auto     boolean not null default false,       -- release payslips as soon as a run is marked paid
  updated_by       uuid references app.membership(id),
  updated_at       timestamptz not null default now()
);

create table app.payroll_registration (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  statute     text not null check (statute in ('pf','esi','pt','lwf')),
  state       text check (state is null or state ~ '^[A-Z]{2}$'),
  number      text not null check (length(btrim(number)) between 3 and 40),
  created_at  timestamptz not null default now(),
  check ((statute in ('pt','lwf')) = (state is not null))
);
create unique index payroll_registration_uq on app.payroll_registration (tenant_id, statute, coalesce(state, ''));

-- ── components and their dated versions ──────────────────────────────

create table app.pay_component (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  code        text not null check (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
  name        text not null check (length(btrim(name)) between 1 and 60),
  kind        text not null check (kind in ('earning','deduction','employer','info')),
  statutory   boolean not null default false,      -- worked out by a rule pack, not a formula
  position    smallint not null default 100,       -- order on the payslip
  retired_at  timestamptz,
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code)
);

create table app.pay_component_version (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  component_id  uuid not null,
  valid_from    date not null,
  amount_type   text not null check (amount_type in ('fixed','formula','input','statutory')),
  formula       text check (formula is null or length(formula) <= 2000),
  wage          text not null default 'exclude' check (wage in ('include','exclude','conditional')),
  esi_wage      boolean not null default true,
  taxable       boolean not null default true,
  prorate       boolean not null default true,
  rounding      text not null default 'nearest' check (rounding in ('nearest','up','down','none')),
  one_time      boolean not null default false,    -- inputs paid once, left out of the tax projection's later months
  show_zero     boolean not null default false,
  ledger_code   text check (ledger_code is null or length(ledger_code) <= 40),
  created_by    uuid references app.membership(id),
  created_at    timestamptz not null default now(),
  unique (component_id, valid_from),
  foreign key (tenant_id, component_id) references app.pay_component (tenant_id, id),
  check (amount_type <> 'formula' or formula is not null)
);

-- The Organization's own lookup tables, read by table("name", key) in formulas.
create table app.value_table (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  name        text not null check (name ~ '^[a-z][a-z0-9_]{1,39}$'),
  key_label   text not null default 'Key' check (length(btrim(key_label)) between 1 and 40),
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, name)
);
create table app.value_table_row (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null,
  table_id    uuid not null,
  key         text not null check (length(btrim(key)) between 1 and 80),
  value       numeric(14,4) not null,
  foreign key (tenant_id, table_id) references app.value_table (tenant_id, id) on delete cascade,
  unique (table_id, key)
);

-- ── structures, pay groups ───────────────────────────────────────────

create table app.salary_structure (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  name        text not null check (length(btrim(name)) between 1 and 60),
  basis       text not null default 'ctc' check (basis in ('ctc','gross')),
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (tenant_id, id)
);
create unique index salary_structure_name_uq on app.salary_structure (tenant_id, lower(name));

create table app.salary_structure_version (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  structure_id  uuid not null,
  valid_from    date not null,
  lines         jsonb not null check (jsonb_typeof(lines) = 'array'),   -- [{code, formula}] in the order shown
  balancing     text,                                                   -- the component that takes up what is left
  created_by    uuid references app.membership(id),
  created_at    timestamptz not null default now(),
  unique (structure_id, valid_from),
  foreign key (tenant_id, structure_id) references app.salary_structure (tenant_id, id)
);

create table app.pay_group (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references platform.tenant(id),
  name                 text not null check (length(btrim(name)) between 1 and 60),
  cutoff_day           smallint not null default 25 check (cutoff_day between 1 and 28),
  pay_day              smallint not null default 30 check (pay_day between 1 and 31),
  default_structure_id uuid,
  created_at           timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, default_structure_id) references app.salary_structure (tenant_id, id)
);
create unique index pay_group_name_uq on app.pay_group (tenant_id, lower(name));

create table app.pay_group_member (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  employment_id  uuid not null,
  group_id       uuid not null,
  valid_from     date not null,
  created_by     uuid references app.membership(id),
  created_at     timestamptz not null default now(),
  unique (employment_id, valid_from),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, group_id) references app.pay_group (tenant_id, id)
);

-- ── each employee's pay ──────────────────────────────────────────────

create table app.compensation (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  employment_id  uuid not null,
  valid_from     date not null,
  structure_id   uuid not null,
  annual_amount  numeric(14,2) not null check (annual_amount > 0),     -- CTC or gross a year, as the structure starts from
  reason         text not null check (length(btrim(reason)) between 3 and 200),
  recorded_by    uuid references app.membership(id),
  recorded_at    timestamptz not null default now(),
  superseded_at  timestamptz,                                        -- a correction of the same date records a new row
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, structure_id) references app.salary_structure (tenant_id, id)
);
create unique index compensation_current_uq on app.compensation (employment_id, valid_from) where superseded_at is null;

-- Nobody records their own pay.
create function app.compensation_not_own() returns trigger language plpgsql as $$
begin
  if exists (select 1 from app.employment e where e.id = new.employment_id and e.membership_id = new.recorded_by) then
    raise exception 'Nobody can change their own pay' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger compensation_not_own before insert on app.compensation for each row execute function app.compensation_not_own();

create table app.compensation_override (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  employment_id  uuid not null,
  component_code text not null,
  valid_from     date not null,
  valid_to       date,                                               -- inclusive; null while it lasts
  monthly_amount numeric(12,2) not null check (monthly_amount >= 0),
  reason         text not null check (length(btrim(reason)) between 3 and 200),
  created_by     uuid references app.membership(id),
  created_at     timestamptz not null default now(),
  removed_at     timestamptz,
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  check (valid_to is null or valid_to >= valid_from)
);
create function app.compensation_override_not_own() returns trigger language plpgsql as $$
begin
  if exists (select 1 from app.employment e where e.id = new.employment_id and e.membership_id = new.created_by) then
    raise exception 'Nobody can change their own pay' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger compensation_override_not_own before insert on app.compensation_override
  for each row execute function app.compensation_override_not_own();

create table app.pay_profile (
  employment_id  uuid primary key,
  tenant_id      uuid not null,
  pf_member      boolean not null default true,
  pf_full_wages  boolean not null default false,      -- PF on full wages above the ceiling
  eps_eligible   boolean not null default true,
  esi_exempt     boolean not null default false,
  tax_regime     text not null default 'new' check (tax_regime in ('new','old')),
  pt_exempt      boolean not null default false,
  lwf_exempt     boolean not null default false,
  updated_by     uuid references app.membership(id),
  updated_at     timestamptz not null default now(),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id)
);

-- ── runs and payslips ────────────────────────────────────────────────

create table app.payroll_run (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  group_id        uuid not null,
  period          date not null check (extract(day from period) = 1),
  state           text not null default 'draft' check (state in ('draft','frozen','calculated','approved','paid','released')),
  snapshot        jsonb,
  frozen_at       timestamptz,
  frozen_by       uuid references app.membership(id),
  attempt         int not null default 0,
  calculated_by   uuid references app.membership(id),
  calculated_at   timestamptz,
  approved_by     uuid references app.membership(id),
  approved_at     timestamptz,
  paid_on         date,
  paid_reference  text check (paid_reference is null or length(btrim(paid_reference)) between 1 and 80),
  paid_by         uuid references app.membership(id),
  released_at     timestamptz,
  released_by     uuid references app.membership(id),
  note            text check (note is null or length(note) <= 300),   -- why it was sent back or reopened
  totals          jsonb,
  created_at      timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, group_id, period),
  foreign key (tenant_id, group_id) references app.pay_group (tenant_id, id),
  constraint payroll_run_maker_checker check (approved_by is null or approved_by is distinct from calculated_by),
  check ((state in ('approved','paid','released')) = (approved_by is not null)),
  check ((state in ('paid','released')) = (paid_on is not null)),
  check ((state = 'released') = (released_at is not null)),
  check ((state = 'draft') or snapshot is not null)
);

-- A run moves only along its steps; an approved run is fixed for good.
create function app.payroll_run_transition() returns trigger language plpgsql as $$
begin
  if old.state in ('approved','paid','released') and (new.snapshot is distinct from old.snapshot
      or new.totals is distinct from old.totals or new.attempt <> old.attempt) then
    raise exception 'An approved run cannot change' using errcode = 'check_violation';
  end if;
  if new.state is distinct from old.state and not (
       (old.state = 'draft' and new.state = 'frozen')
    or (old.state = 'frozen' and new.state in ('draft','calculated'))
    or (old.state = 'calculated' and new.state in ('draft','approved'))
    or (old.state = 'approved' and new.state = 'paid')
    or (old.state = 'paid' and new.state = 'released')) then
    raise exception 'A run cannot go from % to %', old.state, new.state using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger payroll_run_transition before update on app.payroll_run
  for each row execute function app.payroll_run_transition();

create table app.run_attempt (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null,
  run_id      uuid not null,
  number      int not null,
  by_id       uuid references app.membership(id),
  at          timestamptz not null default now(),
  totals      jsonb not null,
  foreign key (tenant_id, run_id) references app.payroll_run (tenant_id, id),
  unique (run_id, number)
);

create table app.run_input (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  run_id         uuid not null,
  employment_id  uuid not null,
  component_code text not null,
  amount         numeric(12,2) not null check (amount >= 0),
  note           text check (note is null or length(note) <= 200),
  created_by     uuid references app.membership(id),
  created_at     timestamptz not null default now(),
  foreign key (tenant_id, run_id) references app.payroll_run (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id)
);

create table app.pay_hold (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  run_id         uuid not null,
  employment_id  uuid not null,
  reason         text not null check (length(btrim(reason)) between 3 and 200),
  held_by        uuid references app.membership(id),
  held_at        timestamptz not null default now(),
  foreign key (tenant_id, run_id) references app.payroll_run (tenant_id, id),
  unique (run_id, employment_id)
);

create table app.payslip (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  run_id          uuid not null,
  employment_id   uuid not null,
  membership_id   uuid,
  days_in_period  numeric(5,2) not null,
  days_paid       numeric(5,2) not null,
  lop_days        numeric(5,2) not null default 0,
  gross           numeric(12,2) not null,
  deductions      numeric(12,2) not null,
  net             numeric(12,2) not null,
  employer_cost   numeric(12,2) not null,
  held            boolean not null default false,
  info            jsonb not null,             -- the employee as they were, the state, regime and tax projection
  unique (tenant_id, id),
  unique (run_id, employment_id),
  foreign key (tenant_id, run_id) references app.payroll_run (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id)
);

create table app.payslip_line (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null,
  payslip_id   uuid not null,
  position     smallint not null,
  code         text not null,
  name         text not null,
  kind         text not null check (kind in ('earning','deduction','employer','info')),
  amount       numeric(12,2) not null,
  unrounded    numeric(16,4) not null,
  full_month   numeric(12,2),
  rule_id      uuid references app.payroll_rule(id),
  trace        jsonb not null,
  foreign key (tenant_id, payslip_id) references app.payslip (tenant_id, id) on delete cascade
);
create index payslip_line_payslip on app.payslip_line (payslip_id, position);

create table app.run_exception (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  run_id          uuid not null,
  employment_id   uuid,
  code            text not null,
  level           text not null check (level in ('blocking','warning')),
  acceptable      boolean not null default true,
  message         text not null,
  link            text,                         -- where it is fixed
  accepted_by     uuid references app.membership(id),
  accepted_reason text check (accepted_reason is null or length(btrim(accepted_reason)) between 3 and 200),
  accepted_at     timestamptz,
  foreign key (tenant_id, run_id) references app.payroll_run (tenant_id, id),
  check (accepted_by is null or (acceptable and accepted_reason is not null and level = 'blocking'))
);

-- Payslips and exceptions of an approved run are final.
create function app.payroll_rows_final() returns trigger language plpgsql as $$
declare st text;
begin
  select state into st from app.payroll_run where id = coalesce(old.run_id, new.run_id);
  if st in ('approved','paid','released') then
    raise exception 'An approved run cannot change' using errcode = 'check_violation';
  end if;
  return case when tg_op = 'DELETE' then old else new end;
end $$;
create trigger payslip_final before insert or update or delete on app.payslip
  for each row execute function app.payroll_rows_final();
create trigger run_exception_final before insert or update or delete on app.run_exception
  for each row execute function app.payroll_rows_final();
create trigger run_input_final before insert or update or delete on app.run_input
  for each row execute function app.payroll_rows_final();

-- ── messages ─────────────────────────────────────────────────────────

alter table app.message_template drop constraint message_template_event_check;
alter table app.message_template add constraint message_template_event_check
  check (event in ('joiner_invited','offer_issued','section_returned','reminder_joiner','task_assigned','task_due','task_overdue',
                   'joiner_ready','activated','welcome_team',
                   'resignation_submitted','resignation_recommended','resignation_accepted','resignation_withdrawn',
                   'withdrawal_requested','withdrawal_decided','exit_started','exit_proposed','exit_task_assigned',
                   'exit_task_overdue','last_day_tomorrow','settlement_due','exit_letter_issued','interview_sent',
                   'former_access_ending','payroll_ready','payroll_sent_back','payslip_released'));

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['payroll_settings','payroll_registration','pay_component','pay_component_version','value_table',
                           'value_table_row','salary_structure','salary_structure_version','pay_group','pay_group_member',
                           'compensation','compensation_override','pay_profile','payroll_run','run_attempt','run_input',
                           'pay_hold','payslip','payslip_line','run_exception'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;

grant delete on app.value_table, app.value_table_row, app.payroll_registration, app.run_input, app.pay_hold, app.payslip,
  app.payslip_line, app.run_exception, app.pay_group_member to app_tenant;
