-- V59: expense advances set against claims (docs/expenses/design.md, Advances and loans).
--   loan_type.kind gains expense_advance: money paid before a trip, settled by expense claims, the rest taken in one deduction.
--   expense_claim.advance_loan_id: the advance the employee sets the claim against. advance_applied: how much of the claim the
--   advance covered when finance verified it; only the rest is paid.
--   loan_settlement.kind gains claim: a claim that reduced an advance.
-- Additive only. By hand: drop the two columns and put back the two shorter lists.
set lock_timeout = '5s';

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.loan_type drop constraint loan_type_kind_check;
-- constraint-missing-not-valid: the table is small (an Organization's few loan types), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.loan_type add constraint loan_type_kind_check check (kind in ('expense_advance','salary_advance','loan'));

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.loan_settlement drop constraint loan_settlement_kind_check;
-- constraint-missing-not-valid: the table is new in this release and small, so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.loan_settlement add constraint loan_settlement_kind_check check (kind in ('prepayment','write_off','claim'));

-- adding-foreign-key-constraint: the column is new and empty, so there is nothing to scan and the lock is momentary.
-- squawk-ignore adding-foreign-key-constraint
alter table app.expense_claim add column advance_loan_id uuid references app.loan(id);
-- constraint-missing-not-valid: the column is new and every row takes the default, so there is nothing to check.
-- squawk-ignore constraint-missing-not-valid
alter table app.expense_claim add column advance_applied numeric(12,2) not null default 0 check (advance_applied >= 0);
