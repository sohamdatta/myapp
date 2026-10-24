-- V17: income tax declarations (Form 124, which replaced Form 12BB from tax year 2026-27) and the
-- proofs behind them. An employee declares what they expect to claim for the tax year; payroll
-- deducts TDS on the declared amounts until the Organization's cut-over month, and on the amounts
-- HR has verified from then on. Only the old regime takes these claims; under the new regime they
-- change nothing, and the screens say so.
--
-- The limits (the ₹1.5 lakh group, NPS, health insurance, home-loan interest, HRA rates and the
-- cities taking 50%) are statutory rule data, versioned with the slabs, not code.

-- ── the Organization's window for a tax year ─────────────────────────

create table app.tax_window (
  tenant_id        uuid not null references platform.tenant(id),
  tax_year         text not null check (tax_year ~ '^[0-9]{4}-[0-9]{2}$'),      -- 2026-27
  declare_from     date not null,
  declare_until    date not null,
  proofs_from      date,
  proofs_until     date,
  verified_from    date,                -- the first payroll month taking verified amounts only; before it, declared ones
  updated_by       uuid references app.membership(id),
  updated_at       timestamptz not null default now(),
  primary key (tenant_id, tax_year),
  check (declare_until >= declare_from),
  check ((proofs_from is null) = (proofs_until is null)),
  check (proofs_until is null or proofs_until >= proofs_from),
  check (verified_from is null or verified_from = date_trunc('month', verified_from)::date)
);

-- ── an employee's declaration for a tax year ─────────────────────────

create table app.tax_declaration (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  employment_id   uuid not null,
  tax_year        text not null check (tax_year ~ '^[0-9]{4}-[0-9]{2}$'),
  submitted_at    timestamptz,           -- the employee's certificate; payroll reads nothing before it
  submitted_by    uuid references app.membership(id),
  created_at      timestamptz not null default now(),
  unique (tenant_id, id),
  unique (employment_id, tax_year),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id)
);

-- One claim. Rent is a monthly amount over a span of months; everything else is for the year.
create table app.tax_declaration_item (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  declaration_id  uuid not null,
  head            text not null check (head in ('rent','home_loan','sec80c','nps','health_self','health_parents','edu_loan')),
  kind            text check (kind is null or kind in ('life_insurance','ppf','elss','nsc','ssy','tuition','home_loan_principal',
                                                         'tax_saver_fd','epf_voluntary','other')),
  amount          numeric(12,2) not null check (amount > 0 and amount <= 99999999),
  details         jsonb not null default '{}' check (jsonb_typeof(details) = 'object'),
  proof_status    text not null default 'none' check (proof_status in ('none','submitted','accepted','rejected')),
  accepted_amount numeric(12,2) check (accepted_amount is null or accepted_amount >= 0),
  review_note     text check (review_note is null or length(review_note) between 3 and 400),
  reviewed_by     uuid references app.membership(id),
  reviewed_at     timestamptz,
  position        smallint not null default 0,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, declaration_id) references app.tax_declaration (tenant_id, id),
  check ((head = 'sec80c') = (kind is not null)),
  check ((proof_status = 'accepted') = (accepted_amount is not null)),
  check (accepted_amount is null or accepted_amount <= amount),
  check (proof_status <> 'rejected' or review_note is not null)
);
create index tax_declaration_item_declaration on app.tax_declaration_item (declaration_id, position);

-- The files behind a claim. The file itself is an ordinary document of the person, encrypted as all are.
create table app.tax_proof (
  item_id      uuid not null,
  tenant_id    uuid not null,
  document_id  uuid not null,
  created_at   timestamptz not null default now(),
  primary key (item_id, document_id),
  foreign key (tenant_id, item_id) references app.tax_declaration_item (tenant_id, id),
  foreign key (tenant_id, document_id) references app.document (tenant_id, id)
);
create index tax_proof_document on app.tax_proof (document_id);

-- ── the limits, alongside the slabs they belong with ─────────────────

-- Added to the draft rule only: a row the consultant has signed off is never changed by a migration.
update app.payroll_rule
   set data = jsonb_set(data, '{old}', (data->'old') || '{
         "npsMax": 50000,
         "healthSelfMax": 25000, "healthSelfSeniorMax": 50000,
         "healthParentsMax": 25000, "healthParentsSeniorMax": 50000,
         "homeLoanInterestMax": 200000,
         "hraRentFloorPct": 10, "hraMetroPct": 50, "hraOtherPct": 40,
         "hraMetros": ["Delhi","Mumbai","Kolkata","Chennai","Bengaluru","Hyderabad","Pune","Ahmedabad"],
         "landlordPanAbove": 100000}'::jsonb),
       source = source || '; declaration limits from public summaries of Form 124 (read 24 Oct 2026), awaiting consultant sign-off'
 where statute = 'income_tax' and jurisdiction = 'IN' and status = 'draft';

-- ── messages ─────────────────────────────────────────────────────────

alter table app.message_template drop constraint message_template_event_check;
alter table app.message_template add constraint message_template_event_check
  check (event in ('joiner_invited','offer_issued','section_returned','reminder_joiner','task_assigned','task_due','task_overdue',
                   'joiner_ready','activated','welcome_team',
                   'resignation_submitted','resignation_recommended','resignation_accepted','resignation_withdrawn',
                   'withdrawal_requested','withdrawal_decided','exit_started','exit_proposed','exit_task_assigned',
                   'exit_task_overdue','last_day_tomorrow','settlement_due','exit_letter_issued','interview_sent',
                   'former_access_ending','payroll_ready','payroll_sent_back','payslip_released',
                   'tax_proof_returned','tax_window_open'));

-- Organizations that already have payroll get the two messages; new ones get them with the payroll defaults.
insert into app.message_template (tenant_id, event, subject, body)
select s.tenant_id, m.event, m.subject, m.body
  from app.payroll_settings s
 cross join (values
   ('tax_proof_returned', 'A tax proof needs another look',
    'Hello {{person.first_name}}, HR returned your proof for {{claim}}: {{note}}. Open My pay → Tax declaration to send another.'),
   ('tax_window_open', 'Tax declarations for {{tax_year}} are open',
    'Hello {{person.first_name}}, declare what you expect to claim for {{tax_year}} by {{until}}, under My pay → Tax declaration.')
 ) as m(event, subject, body)
on conflict (tenant_id, event, channel) do nothing;

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['tax_window','tax_declaration','tax_declaration_item','tax_proof'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;

grant delete on app.tax_declaration_item, app.tax_proof to app_tenant;
-- Removing a person's data after the retention period, or a withdrawn joiner, clears these too.
grant delete on app.tax_declaration to app_tenant;
