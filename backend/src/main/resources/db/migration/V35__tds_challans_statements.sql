-- Income tax filings (release R3), first part: other employers' salary (Form 122 Part B), the monthly TDS deposit
-- (challan), and the quarterly TDS statement (Form 138) built from approved payroll and approved challans.
-- A challan is recorded by one person and approved by another. A filed statement, and the rows behind it,
-- cannot change; a correction is a new version that holds only what changed.
set lock_timeout = '5s';

-- ── other employers (Form 122, Part B) ───────────────────────────────

-- Salary an employee earned from a previous employer in the same tax year, and the tax already taken from it.
-- Payroll counts it as income so far and tax paid so far (and the PF and professional tax as already paid), so the
-- months left are not under-deducted. Declared amounts count until the window's cut-over month, accepted ones after.
create table app.tax_other_employer (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  employment_id   uuid not null,
  declared_by     uuid references app.membership(id),                      -- the employee who entered it
  tax_year        text not null check (tax_year ~ '^[0-9]{4}-[0-9]{2}$'),
  employer_name   text not null check (length(btrim(employer_name)) between 2 and 160),
  employer_tan    text check (employer_tan is null or employer_tan ~ '^[A-Z]{4}[0-9]{5}[A-Z]$'),
  months_worked   smallint not null check (months_worked between 1 and 12),
  taxable_salary  numeric(12, 2) not null check (taxable_salary >= 0),
  pf              numeric(12, 2) not null default 0 check (pf >= 0),
  professional_tax numeric(12, 2) not null default 0 check (professional_tax >= 0),
  tds             numeric(12, 2) not null default 0 check (tds >= 0),
  status          text not null default 'declared' check (status in ('declared', 'accepted', 'returned')),
  review_note     text check (review_note is null or length(review_note) between 3 and 400),
  reviewed_by     uuid references app.membership(id),
  reviewed_at     timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  check ((status = 'returned') = (review_note is not null)),
  constraint tax_other_employer_not_own check (reviewed_by is null or reviewed_by is distinct from declared_by),
  check (tds <= taxable_salary)
);
create index tax_other_employer_person on app.tax_other_employer (employment_id, tax_year);
create unique index tax_other_employer_once on app.tax_other_employer (employment_id, tax_year, employer_tan) where employer_tan is not null;

-- ── TDS deposits (challans) ──────────────────────────────────────────

create table app.tds_challan (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  period         date not null check (extract(day from period) = 1),     -- the month the tax was deducted
  deductor_type  text not null default 'company' check (deductor_type in ('company', 'other')),
  minor_head     text not null default '200' check (minor_head in ('200', '400')),
  bsr_code       text not null check (bsr_code ~ '^[0-9]{7}$'),
  serial         text not null check (serial ~ '^[0-9]{5}$'),
  deposited_on   date not null,
  tax            numeric(12, 2) not null check (tax >= 0),
  surcharge      numeric(12, 2) not null default 0 check (surcharge >= 0),
  cess           numeric(12, 2) not null default 0 check (cess >= 0),
  interest       numeric(12, 2) not null default 0 check (interest >= 0),
  fee            numeric(12, 2) not null default 0 check (fee >= 0),
  total          numeric(12, 2) generated always as (tax + surcharge + cess + interest + fee) stored,
  status         text not null default 'recorded' check (status in ('recorded', 'approved')),
  recorded_by    uuid references app.membership(id),
  recorded_at    timestamptz not null default now(),
  approved_by    uuid references app.membership(id),
  approved_at    timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, bsr_code, serial, deposited_on),
  constraint tds_challan_maker_checker check (approved_by is null or approved_by is distinct from recorded_by),
  check ((status = 'approved') = (approved_by is not null and approved_at is not null)),
  check (deposited_on >= period)
);

-- The payroll runs a challan pays for. A run is on one challan only.
create table app.tds_challan_run (
  tenant_id  uuid not null,
  challan_id uuid not null,
  run_id     uuid not null,
  primary key (challan_id, run_id),
  unique (tenant_id, run_id),
  foreign key (tenant_id, challan_id) references app.tds_challan (tenant_id, id) on delete cascade,
  foreign key (tenant_id, run_id) references app.payroll_run (tenant_id, id)
);

-- ── quarterly TDS statements (Form 138) ──────────────────────────────

create table app.tds_statement (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  tax_year      text not null check (tax_year ~ '^[0-9]{4}-[0-9]{2}$'),
  quarter       smallint not null check (quarter between 1 and 4),
  version       integer not null check (version >= 1),
  correction_of uuid,                                                    -- the filed version this one corrects
  tan           text not null check (tan ~ '^[A-Z]{4}[0-9]{5}[A-Z]$'),
  status        text not null default 'draft' check (status in ('draft', 'filed')),
  file_name     text not null,
  file_enc      bytea not null,                                          -- the review spreadsheet; it holds full PANs
  totals        jsonb not null,
  checks        jsonb not null,
  accepted      jsonb not null default '[]',                             -- checks the approver accepted, with the reason
  generated_by  uuid references app.membership(id),
  generated_at  timestamptz not null default now(),
  token_number  text check (token_number is null or length(btrim(token_number)) between 6 and 40),
  filed_on      date,
  filed_by      uuid references app.membership(id),
  unique (tenant_id, id),
  unique (tenant_id, tax_year, quarter, version),
  foreign key (tenant_id, correction_of) references app.tds_statement (tenant_id, id),
  check ((status = 'filed') = (token_number is not null and filed_on is not null and filed_by is not null))
);

-- One line per employee per month, with the challan that paid its tax; frozen with the statement.
create table app.tds_statement_row (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  statement_id  uuid not null,
  employment_id uuid not null,
  month         date not null check (extract(day from month) = 1),
  challan_id    uuid,
  amount_paid   numeric(12, 2) not null,
  tax_deducted  numeric(12, 2) not null,
  higher_rate   boolean not null default false,                          -- no valid PAN
  pan_enc       bytea,                                                   -- the PAN as sent, to see a correction of it
  removed       boolean not null default false,                          -- in a correction: this line is withdrawn
  foreign key (tenant_id, statement_id) references app.tds_statement (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, challan_id) references app.tds_challan (tenant_id, id)
);
create index tds_statement_row_statement on app.tds_statement_row (statement_id);
create index tds_statement_row_challan on app.tds_statement_row (challan_id);
create index tds_challan_period on app.tds_challan (tenant_id, period);

-- A filed statement and its lines are final.
create function app.tds_statement_final() returns trigger language plpgsql as $$
begin
  if old.status = 'filed' then
    raise exception 'A filed statement cannot change' using errcode = 'check_violation';
  end if;
  return case when tg_op = 'DELETE' then old else new end;
end $$;
create trigger tds_statement_final before update or delete on app.tds_statement
  for each row execute function app.tds_statement_final();

create function app.tds_statement_row_final() returns trigger language plpgsql as $$
declare st text;
begin
  select status into st from app.tds_statement where id = coalesce(old.statement_id, new.statement_id);
  if st = 'filed' then
    raise exception 'A filed statement cannot change' using errcode = 'check_violation';
  end if;
  return case when tg_op = 'DELETE' then old else new end;
end $$;
create trigger tds_statement_row_final before insert or update or delete on app.tds_statement_row
  for each row execute function app.tds_statement_row_final();

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['tax_other_employer', 'tds_challan', 'tds_challan_run', 'tds_statement', 'tds_statement_row'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;

grant delete on app.tax_other_employer, app.tds_challan, app.tds_challan_run, app.tds_statement_row to app_tenant;

-- ── the deposit and statement rules, as draft rule data ──────────────

-- Added to the draft rule only: a row the consultant has signed off is never changed by a migration. These are the
-- consultant's open questions (deposit due dates under the Income-tax Rules 2026, statement due dates).
update app.payroll_rule
   set data = data || '{"tdsDueDay":7,"tdsDueMarch":"04-30","noPanRate":20,
                        "statementDue":{"1":"07-31","2":"10-31","3":"01-31","4":"05-31"}}'::jsonb,
       source = source || '; TDS deposit and statement due dates as assumed in the R3 design (29 Oct 2026), awaiting consultant sign-off'
 where statute = 'income_tax' and jurisdiction = 'IN' and status = 'draft';
