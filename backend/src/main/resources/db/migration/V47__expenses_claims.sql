-- V47: Expenses, stages X1 and X2 (docs/expenses/design.md): the module, its permissions and Finance role,
-- categories with policy, claims, lines, and receipts with per-item review.
-- Not here yet: payout through payroll, advances and loans (X3 to X5). They add their own migrations, so
-- this file creates no table that nothing reads. Claims belong to a membership, as leave requests do.
-- Additive only, so an older build keeps running against it. By hand: drop the tables below.
set lock_timeout = '5s';

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table platform.tenant_module drop constraint tenant_module_module_check;
-- constraint-missing-not-valid: the table is new in this release or small (per-Organization rows), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table platform.tenant_module add constraint tenant_module_module_check
  check (module in ('attendance','leave','payroll','expenses'));

-- Existing Organizations get the module off. Turning it on is a platform decision.
insert into platform.tenant_module (tenant_id, module, status)
select id, 'expenses', 'off' from platform.tenant
on conflict (tenant_id, module) do nothing;

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table platform.usage_metric drop constraint usage_metric_module_check;
-- constraint-missing-not-valid: the table is new in this release or small (per-Organization rows), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table platform.usage_metric add constraint usage_metric_module_check
  check (module in ('core', 'attendance', 'leave', 'payroll', 'expenses'));

-- ── categories ───────────────────────────────────────────────────────

create table app.expense_category (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  name             text not null check (length(btrim(name)) between 1 and 60),
  limit_per_expense numeric(12,2) check (limit_per_expense is null or limit_per_expense > 0),
  limit_per_month  numeric(12,2) check (limit_per_month is null or limit_per_month > 0),
  receipt_above    numeric(12,2) not null default 500 check (receipt_above >= 0),
  per_km_rate      numeric(12,2) check (per_km_rate is null or per_km_rate > 0),   -- distance based: the amount is km x rate
  needs_travel_proof boolean not null default false,
  needs_approval_first boolean not null default false,                              -- over the limit needs a pre-approval file
  ledger           text check (ledger is null or length(btrim(ledger)) between 1 and 60),
  active           boolean not null default true,
  created_at       timestamptz not null default now(),
  unique (tenant_id, id)
);
create unique index expense_category_name on app.expense_category (tenant_id, lower(name));

-- ── claims and lines ─────────────────────────────────────────────────

create table app.expense_counter (
  tenant_id uuid not null references platform.tenant(id),
  year      int  not null,
  last      int  not null default 0,
  primary key (tenant_id, year)
);

create table app.expense_claim (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  membership_id  uuid not null,
  number         text not null,
  title          text not null check (length(btrim(title)) between 1 and 120),
  status         text not null default 'draft' check (status in
                   ('draft','submitted','returned','evidence_requested','approved','verified','scheduled','paid','rejected','withdrawn')),
  submitted_at   timestamptz,
  manager_by     uuid references app.membership(id),
  manager_at     timestamptz,
  manager_note   text,
  finance_by     uuid references app.membership(id),
  finance_at     timestamptz,
  finance_note   text,
  decision_note  text,                                  -- why it was returned or rejected
  limits         jsonb not null default '{}'::jsonb,    -- the policy as it stood when the claim was submitted
  version        int not null default 1,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, number),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  -- Nobody approves or verifies their own claim.
  constraint expense_claim_not_own check ((manager_by is null or manager_by <> membership_id)
                                      and (finance_by is null or finance_by <> membership_id)),
  -- A rejected or returned claim says why.
  constraint expense_claim_says_why check (status not in ('rejected','returned') or decision_note is not null)
);
create index expense_claim_status on app.expense_claim (tenant_id, status);
create index expense_claim_member on app.expense_claim (tenant_id, membership_id, created_at desc);

create table app.expense_line (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  claim_id         uuid not null,
  category_id      uuid not null,
  spent_on         date not null,
  amount           numeric(12,2) not null check (amount > 0),
  approved_amount  numeric(12,2) check (approved_amount is null or approved_amount >= 0),
  km               numeric(8,1) check (km is null or km > 0),
  merchant         text check (merchant is null or length(merchant) <= 120),
  note             text check (note is null or length(note) <= 500),
  over_limit_reason text,
  cut_reason       text,                                -- why a reviewer approved less than was claimed
  flags            jsonb not null default '[]'::jsonb,  -- warnings raised at submission, with any approver comment
  evidence_required boolean not null default false,     -- fixed at submission from the category's rules then, so a later change never alters it
  created_at       timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, claim_id) references app.expense_claim (tenant_id, id) on delete cascade,
  foreign key (tenant_id, category_id) references app.expense_category (tenant_id, id),
  -- Approved amount never above what was claimed; a cut says why.
  constraint expense_line_approved_le check (approved_amount is null or approved_amount <= amount),
  constraint expense_line_cut_says_why check (approved_amount is null or approved_amount = amount or cut_reason is not null)
);
create index expense_line_claim on app.expense_line (claim_id);
create index expense_line_spent on app.expense_line (tenant_id, spent_on);

-- ── evidence ─────────────────────────────────────────────────────────

create table app.evidence_file (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  line_id      uuid not null,
  kind         text not null check (kind in ('receipt','travel_proof','pre_approval','missing_receipt_declaration')),
  file_name    text not null check (length(file_name) between 1 and 200),
  content_type text not null check (content_type in ('application/pdf','image/jpeg','image/png')),
  size_bytes   int  not null check (size_bytes between 1 and 10485760),
  sha256       bytea not null check (length(sha256) = 32),
  sealed       bytea not null,                           -- the file, encrypted as documents are
  declaration  text,                                     -- for a missing-receipt declaration: the employee's statement
  uploaded_by  uuid references app.membership(id),
  created_at   timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, line_id) references app.expense_line (tenant_id, id) on delete cascade,
  constraint evidence_declaration_text check (kind <> 'missing_receipt_declaration' or length(btrim(coalesce(declaration, ''))) >= 10)
);
create index evidence_file_line on app.evidence_file (line_id);
create index evidence_file_hash on app.evidence_file (tenant_id, sha256);

-- A decision on a line's evidence, or a request for more. A later row supersedes an earlier one, so the history stays.
create table app.evidence_review (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  line_id      uuid not null,
  status       text not null check (status in ('accepted','rejected','more_needed')),
  note         text,
  reviewed_by  uuid not null references app.membership(id),
  reviewed_at  timestamptz not null default now(),
  foreign key (tenant_id, line_id) references app.expense_line (tenant_id, id) on delete cascade,
  constraint evidence_review_says_why check (status = 'accepted' or length(btrim(coalesce(note, ''))) >= 3)
);
create index evidence_review_line on app.evidence_review (line_id, reviewed_at desc);

do $$
declare t text;
begin
  foreach t in array array['expense_category','expense_counter','expense_claim','expense_line','evidence_file','evidence_review'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;

-- A draft's lines and files can be removed; a submitted claim's are never deleted (the design keeps them 8 years).
grant delete on app.expense_line, app.evidence_file, app.expense_claim to app_tenant;
-- Reviews are a history: added, never changed. Files are added and removed, never changed.
revoke update on app.evidence_review, app.evidence_file from app_tenant;

create function app.expense_keep_submitted() returns trigger language plpgsql as $$
declare st text;
begin
  if tg_table_name = 'expense_claim' then
    st := old.status;
  elsif tg_table_name = 'expense_line' then
    select status into st from app.expense_claim where id = old.claim_id;
  else
    select k.status into st from app.expense_line l join app.expense_claim k on k.id = l.claim_id where l.id = old.line_id;
  end if;
  -- st is null when the parent is already gone: this delete is the cascade of a draft's own deletion.
  if st is not null and st <> 'draft' then
    raise exception 'A submitted expense claim, its lines and its files are kept' using errcode = 'check_violation';
  end if;
  return old;
end $$;
create trigger expense_claim_keep before delete on app.expense_claim for each row execute function app.expense_keep_submitted();
create trigger expense_line_keep  before delete on app.expense_line  for each row execute function app.expense_keep_submitted();
create trigger evidence_file_keep before delete on app.evidence_file for each row execute function app.expense_keep_submitted();

-- ── permissions and the Finance role ─────────────────────────────────

insert into app.permission (code, description) values
  ('expense.claim',   'Submit expense claims and withdraw own claims'),
  ('expense.approve', 'Approve, return or reject the expense claims of reports'),
  ('expense.finance', 'Verify evidence and claims'),
  ('expense.setup',   'Set up expense categories and policy');

insert into app.role (code, name, is_system, assignable) values ('finance', 'Finance', true, true);

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','expense.claim','tenant'), ('owner','expense.approve','tenant'),
  ('owner','expense.finance','tenant'), ('owner','expense.setup','tenant'),
  ('finance','expense.claim','self'), ('finance','expense.finance','tenant'), ('finance','expense.setup','tenant'),
  ('hr_admin','expense.approve','tenant'),   -- as for leave: a role can only be granted by someone who holds all of its permissions
  ('manager','expense.approve','team'),
  ('employee','expense.claim','self'),
  ('ex_employee','expense.claim','self')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null;
