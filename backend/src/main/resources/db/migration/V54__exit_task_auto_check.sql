-- V54: an exit task can check itself against Expenses (docs/expenses/design.md, stage X5).
--   auto_check = 'expenses': the task completes itself once the leaver has no open expense claim and no advance or loan still owed,
--   and until then shows what is open. The default "Clear open advances, loans and expense claims" task carries it.
-- Additive only. By hand: drop the two columns.
set lock_timeout = '5s';
alter table app.checklist_template_task add column auto_check text check (auto_check is null or auto_check in ('expenses'));
alter table app.exit_task add column auto_check text check (auto_check is null or auto_check in ('expenses'));

-- A self-checking task that completes itself has no person who did it.
do $$
declare name text;
begin
  select conname into name from pg_constraint where conrelid = 'app.exit_task'::regclass and contype = 'c' and pg_get_constraintdef(oid) like '%done_by IS NULL%done_at IS NULL%';
  execute format('alter table app.exit_task drop constraint %I', name);
end $$;
alter table app.exit_task add constraint exit_task_done_by_check check ((done_by is null) = (done_at is null) or auto_check is not null);

update app.checklist_template_task set auto_check = 'expenses' where title = 'Clear open advances, loans and expense claims';
update app.exit_task set auto_check = 'expenses' where title = 'Clear open advances, loans and expense claims' and done_at is null;
