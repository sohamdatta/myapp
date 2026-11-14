-- V55: a second finance approver for large loans (docs/expenses/design.md, stage X4).
--   finance_approvals: how many different people in finance must approve, fixed when the loan is asked for (1, or 2 above the setting).
--   finance2_by / finance2_at: the second approver. The first is finance_by, who may not be the second, nor the borrower.
-- Additive only. By hand: drop the three columns and the constraint.
set lock_timeout = '5s';
alter table app.loan add column finance_approvals int not null default 1 check (finance_approvals in (1, 2));
-- adding-foreign-key-constraint: the column is new and empty (or the table is small), so there is nothing to scan and the lock is momentary.
-- squawk-ignore adding-foreign-key-constraint
alter table app.loan add column finance2_by uuid references app.membership(id);
alter table app.loan add column finance2_at timestamptz;
-- constraint-missing-not-valid: the table is new in this release or small (per-Organization rows), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.loan add constraint loan_second_person check (finance2_by is null or (finance2_by <> membership_id and finance2_by is distinct from finance_by));
