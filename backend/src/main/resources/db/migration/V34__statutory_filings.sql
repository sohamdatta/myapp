-- Statutory returns made from an approved payroll month: the PF electronic challan-cum-return (ECR) file and the
-- ESI monthly contribution file (release R4). Each file is a versioned row with the checks that were run on it,
-- then a record of the filing on the EPFO or ESIC portal (reference, date, amount) that a second person approves.
-- The file holds full UANs and ESI numbers, so it is stored encrypted like every other identifier.
set lock_timeout = '5s';

create table app.statutory_filing (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  kind             text not null check (kind in ('pf_ecr', 'esi')),
  registration     text not null check (length(btrim(registration)) between 3 and 40),   -- the PF or ESI code, as registered
  period           date not null check (extract(day from period) = 1),
  version          integer not null check (version >= 1),
  status           text not null default 'draft' check (status in ('draft', 'downloaded', 'recorded', 'filed')),
  file_name        text not null,
  file_enc         bytea not null,
  totals           jsonb not null,
  checks           jsonb not null,                                       -- the pre-checks as they stood when the file was made
  run_ids          uuid[] not null,
  generated_by     uuid references app.membership(id),
  generated_at     timestamptz not null default now(),
  downloaded_at    timestamptz,
  reference_number text check (reference_number is null or length(btrim(reference_number)) between 3 and 60),   -- TRRN or challan number
  paid_on          date,
  amount_paid      numeric(12, 2) check (amount_paid is null or amount_paid >= 0),
  recorded_by      uuid references app.membership(id),
  recorded_at      timestamptz,
  approved_by      uuid references app.membership(id),
  approved_at      timestamptz,
  unique (tenant_id, kind, registration, period, version),
  constraint statutory_filing_maker_checker check (approved_by is null or approved_by is distinct from recorded_by),
  check ((status in ('recorded', 'filed')) = (recorded_by is not null and reference_number is not null
                                              and paid_on is not null and amount_paid is not null)),
  check ((status = 'filed') = (approved_by is not null and approved_at is not null))
);
create index statutory_filing_period on app.statutory_filing (tenant_id, period desc, kind);

alter table app.statutory_filing enable row level security;
alter table app.statutory_filing force row level security;
create policy tenant_isolation on app.statutory_filing
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update on app.statutory_filing to app_tenant;
grant select, insert, update, delete on app.statutory_filing to app_definer;

-- ESIC's reason for a member with no wages in the month, by how the employment ended. An Organization's HR can
-- change a row; without one, the default in the ESI rule applies.
create table app.esi_reason_map (
  tenant_id  uuid not null references platform.tenant(id),
  exit_kind  text not null check (exit_kind in ('resignation', 'termination', 'end_of_contract', 'retirement', 'death', 'absconding')),
  esic_code  smallint not null check (esic_code between 0 and 13),
  primary key (tenant_id, exit_kind)
);
alter table app.esi_reason_map enable row level security;
alter table app.esi_reason_map force row level security;
create policy tenant_isolation on app.esi_reason_map
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update, delete on app.esi_reason_map to app_tenant;
grant select, insert, update, delete on app.esi_reason_map to app_definer;

-- The return rules are rule data like every other statutory figure; these are draft until the payroll consultant
-- signs them off (the separator, the ceilings, the due day and the ESIC reason codes are all on the consultant's list).
update app.payroll_rule
   set data = data || '{"epsCeiling":15000,"edliCeiling":15000,"ecrSeparator":"#~#","ncpHalfDay":"up","dueDay":15}'::jsonb,
       source = source || '; ECR layout and ceilings per EPFO ECR 2.0 introduction (read 29 Oct 2026), awaiting consultant sign-off'
 where statute = 'pf' and jurisdiction = 'IN' and status = 'draft';

update app.payroll_rule
   set data = data || '{"dueDay":15,"reasonCodes":{"onLeave":1,"leftService":2,"retired":3,"outOfCoverage":4,"expired":5,"noWork":11},
                        "exitReason":{"resignation":2,"termination":2,"end_of_contract":2,"absconding":2,"retirement":3,"death":5}}'::jsonb,
       source = source || '; return-file reason codes as published for the ESIC monthly contribution upload (read 29 Oct 2026), awaiting consultant sign-off'
 where statute = 'esi' and jurisdiction = 'IN' and status = 'draft';
