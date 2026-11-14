-- V57: an instalment taken up by a payroll run is marked as such (code review of the Expenses work).
--   reserved_run_id: set when a run is frozen, cleared when it is reopened or approved. While it is set, finance cannot skip, repay
--   early or write off the loan (the payslip would deduct an amount the schedule no longer has), and no other run takes the instalment.
-- Additive only. By hand: drop the column.
set lock_timeout = '5s';
-- adding-foreign-key-constraint: the column is new and empty (or the table is small), so there is nothing to scan and the lock is momentary.
-- squawk-ignore adding-foreign-key-constraint
alter table app.loan_instalment add column reserved_run_id uuid references app.payroll_run(id);
-- require-concurrent-index-creation: Flyway runs in a transaction, where a concurrent build is not possible; the table is small and the index is partial.
-- squawk-ignore require-concurrent-index-creation
create index loan_instalment_reserved on app.loan_instalment (reserved_run_id) where reserved_run_id is not null;
