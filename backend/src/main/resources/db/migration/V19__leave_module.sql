-- V19: Leave becomes a module of its own, so it can be sold (or given free) without Attendance.
-- Leave covers balances, leave requests and approvals, who is away, adjustments, comp-off grants,
-- leave types and the year end. Work from home stays in Attendance: it only changes how punching
-- is checked, and its requests share a form with attendance corrections and on-duty days.

alter table platform.tenant_module drop constraint tenant_module_module_check;
alter table platform.tenant_module add constraint tenant_module_module_check
  check (module in ('attendance','leave','payroll'));

-- Nothing changes for an Organization that exists already: it has Leave exactly when, and for
-- exactly as long as, it has Attendance today.
insert into platform.tenant_module (tenant_id, module, status, trial_ends_on, changed_by, changed_at)
select tenant_id, 'leave', status, trial_ends_on, changed_by, changed_at
  from platform.tenant_module
 where module = 'attendance'
on conflict (tenant_id, module) do nothing;
