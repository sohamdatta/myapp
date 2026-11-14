-- V52: the loan interest perquisite (docs/expenses/design.md, stage X5).
--   A loan may be marked exempt (a loan for treating a specified illness), with the reason and where the evidence is kept.
-- Additive only. By hand: drop the column.
set lock_timeout = '5s';
alter table app.loan add column perq_exempt_reason text check (perq_exempt_reason is null or length(btrim(perq_exempt_reason)) >= 10);
