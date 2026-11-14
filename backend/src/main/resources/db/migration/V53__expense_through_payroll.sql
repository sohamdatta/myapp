-- V53: paying verified expense claims through the payroll run (docs/expenses/design.md, rest of stage X3).
--   * pay components and payslip lines may be of kind 'reimbursement': paid with the salary, outside gross, not taxable.
--   * app.expense_run_claim: the claims a run has taken up when frozen. A reserved claim is 'scheduled', so it cannot also go in a bank batch.
--     Approving the run pays them (a held payslip gives its claims back); reopening the run gives all of them back.
-- Additive only. By hand: drop the table, and put the two check constraints back without 'reimbursement'.
set lock_timeout = '5s';

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.pay_component drop constraint pay_component_kind_check;
-- constraint-missing-not-valid: the table is new in this release or small (per-Organization rows), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.pay_component add constraint pay_component_kind_check check (kind in ('earning','deduction','employer','info','reimbursement'));
-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.payslip_line drop constraint payslip_line_kind_check;
-- constraint-missing-not-valid: the table is new in this release or small (per-Organization rows), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.payslip_line add constraint payslip_line_kind_check check (kind in ('earning','deduction','employer','info','reimbursement'));

create table app.expense_run_claim (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  run_id        uuid not null references app.payroll_run(id),
  claim_id      uuid not null references app.expense_claim(id),
  employment_id uuid not null references app.employment(id),
  amount        numeric(12,2) not null check (amount > 0),
  status        text not null default 'reserved' check (status in ('reserved','paid')),
  created_at    timestamptz not null default now(),
  unique (claim_id)
);
create index expense_run_claim_run on app.expense_run_claim (run_id);

alter table app.expense_run_claim enable row level security;
alter table app.expense_run_claim force row level security;
create policy tenant_isolation on app.expense_run_claim using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update, delete on app.expense_run_claim to app_tenant;
grant select, insert, update, delete on app.expense_run_claim to app_definer;
