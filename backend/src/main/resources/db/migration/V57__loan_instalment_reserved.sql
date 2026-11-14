-- V57: an instalment taken up by a payroll run is marked as such (code review of the Expenses work).
--   reserved_run_id: set when a run is frozen, cleared when it is reopened or approved. While it is set, finance cannot skip, repay
--   early or write off the loan (the payslip would deduct an amount the schedule no longer has), and no other run takes the instalment.
-- Additive only. By hand: drop the column.
set lock_timeout = '5s';
alter table app.loan_instalment add column reserved_run_id uuid references app.payroll_run(id);
create index loan_instalment_reserved on app.loan_instalment (reserved_run_id) where reserved_run_id is not null;
