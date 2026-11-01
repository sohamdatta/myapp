-- Pre-run variance checks (release R9). Two small additions, both additive so an older build keeps running against them:
--   * a rupee threshold for the net pay change check, beside the percentage one (0 turns the rupee check off);
--   * a key on each run exception, so an acceptance carries over to a recalculation only when the same thing is still wrong
--     (a net pay change accepted at ₹70,400 is not accepted again at ₹52,000).
-- By hand: drop the two columns.
set lock_timeout = '5s';

alter table app.payroll_settings add column variance_amount numeric(12,2) not null default 5000;
alter table app.run_exception add column detail_key text;
