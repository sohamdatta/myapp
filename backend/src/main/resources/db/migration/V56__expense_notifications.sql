-- V56: notifications for expenses and loans (docs/expenses/design.md, stage X4).
--   New message events: an approver or finance has something waiting; the employee's claim is returned, rejected, needs more evidence,
--   is verified or paid; a loan is decided or paid out; finance hears of instalments carried. Organizations get the default texts the
--   first time one is sent (ExpenseNotices.ensure), and can edit or switch each off in Setup.
-- Additive only. By hand: delete the rows of these events and put back V25's event list.
set lock_timeout = '5s';

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.message_template drop constraint message_template_event_check;
-- constraint-missing-not-valid: the table is small (per-Organization templates), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.message_template add constraint message_template_event_check
  check (event in ('joiner_invited','offer_issued','section_returned','reminder_joiner','task_assigned','task_due','task_overdue',
                   'joiner_ready','activated','welcome_team',
                   'resignation_submitted','resignation_recommended','resignation_accepted','resignation_withdrawn',
                   'withdrawal_requested','withdrawal_decided','exit_started','exit_proposed','exit_task_assigned',
                   'exit_task_overdue','last_day_tomorrow','settlement_due','exit_letter_issued','interview_sent',
                   'former_access_ending','payroll_ready','payroll_sent_back','payslip_released',
                   'tax_window_open','tax_proof_returned','setting_pending','setting_decided','setting_weakened',
                   'probation_checkin_due','probation_checkin_written','probation_recommend_due','probation_recommended',
                   'probation_overdue','probation_decided','probation_letter_issued',
                   'goals_submitted','goals_agreed','goals_returned','goal_changed','one_to_one_nudge','feedback_received',
                   'review_self_open','review_manager_open','review_due','review_self_returned','review_released',
                   'review_calibrated','outcomes_submitted','outcomes_returned',
                   'plan_drafted','plan_started','plan_checkin_due','plan_checkin_written','plan_overdue','plan_decided',
                   'peer_review_requested','skip_level_open','skip_level_returned','feedback_requested',
                   'expense_waiting','expense_finance_waiting','expense_returned','expense_rejected','expense_evidence_needed','expense_verified','expense_paid','loan_waiting','loan_finance_waiting','loan_decided','loan_paid_out','loan_instalment_carried'));
