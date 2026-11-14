-- V48: who approves an expense claim, configurable per Organization (docs/expenses/design.md).
--   * a designated approver for a person or a department, instead of the reporting manager;
--   * whether the manager step exists at all, and a second finance approver above an amount (both in Settings);
--   * the claim remembers who the approver was and how many finance approvals it needs, as of submission.
-- Additive only. By hand: drop the table and the four columns.
set lock_timeout = '5s';

create table app.expense_approver (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  membership_id         uuid,             -- a person's claims go to the approver ...
  department_id         uuid,             -- ... or a department's do (a person's own row wins)
  approver_membership_id uuid not null,
  created_by            uuid references app.membership(id),
  created_at            timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  foreign key (tenant_id, department_id) references app.department (tenant_id, id),
  foreign key (tenant_id, approver_membership_id) references app.membership (tenant_id, id),
  check ((membership_id is null) <> (department_id is null)),
  check (membership_id is null or membership_id <> approver_membership_id)
);
create unique index expense_approver_person on app.expense_approver (tenant_id, membership_id) where membership_id is not null;
create unique index expense_approver_department on app.expense_approver (tenant_id, department_id) where department_id is not null;

alter table app.expense_approver enable row level security;
alter table app.expense_approver force row level security;
create policy tenant_isolation on app.expense_approver
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update, delete on app.expense_approver to app_tenant, app_definer;

alter table app.expense_claim
  add column approver_membership_id uuid references app.membership(id),
  add column finance_approvals int not null default 1 check (finance_approvals in (1, 2)),
  add column finance2_by uuid references app.membership(id),
  add column finance2_at timestamptz;

alter table app.expense_claim drop constraint expense_claim_not_own;
alter table app.expense_claim add constraint expense_claim_not_own check (
  (manager_by is null or manager_by <> membership_id)
  and (finance_by is null or finance_by <> membership_id)
  and (finance2_by is null or finance2_by <> membership_id)
  and (finance2_by is null or finance2_by <> finance_by));
