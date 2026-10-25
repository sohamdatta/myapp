-- Performance management, stage 5: improvement plans. A manager (or HR) drafts a plan for someone
-- in their team: why, what has to improve and how it is measured, the support offered, its length
-- and how often to check in. HR starts it, which issues the plan letter; the employee reads and
-- acknowledges it and may respond. The manager checks in on each objective; the employee may reply.
-- At the end HR decides: completed, extended once, or not met, which starts an exit.
-- The design is docs/performance/design.md.
--
-- Breaking: no. It only adds tables, columns, permissions and allowed values.
-- To undo by hand: drop the improvement plan tables and the improvement_plan_id column on app.issued_letter, and put back V23's letter-kind and message-event lists.

set lock_timeout = '5s';

create table app.improvement_plan (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null,
  employment_id      uuid not null,
  manager_id         uuid,                       -- their manager's employment when it was drafted
  review_id          uuid,                       -- the review that led to it, when there was one
  -- draft: written, waiting for HR; active: running; completed / not_met: decided; cancelled
  status             text not null default 'draft' check (status in ('draft','active','completed','not_met','cancelled')),
  reason_enc         bytea not null,             -- why the plan, as the employee will read it
  support_enc        bytea,                      -- what the company will do to help
  length_days        smallint not null check (length_days between 14 and 120),
  checkin_every      smallint not null check (checkin_every between 7 and 31),
  start_date         date,
  end_date           date,
  original_end       date,                       -- set when the plan is extended (once)
  created_by         uuid references app.membership(id),
  created_at         timestamptz not null default now(),
  started_by         uuid references app.membership(id),
  started_at         timestamptz,
  acknowledged_at    timestamptz,
  response_enc       bytea,                      -- the employee's response, when they wrote one
  decision_note_enc  bytea,
  decided_by         uuid references app.membership(id),
  decided_at         timestamptz,
  cancelled_reason   text check (cancelled_reason is null or length(cancelled_reason) between 5 and 500),
  exit_case_id       uuid,
  letter_id          uuid,
  hr_reminded_on     date,
  unique (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, manager_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, review_id) references app.review (tenant_id, id),
  foreign key (tenant_id, exit_case_id) references app.exit_case (tenant_id, id),
  check (manager_id is null or manager_id <> employment_id),
  check (status in ('draft','cancelled') or started_at is not null),
  check ((started_at is null) = (start_date is null)),
  check (end_date is null or end_date > start_date),
  check (original_end is null or end_date > original_end),
  check ((status in ('completed','not_met')) = (decided_at is not null)),
  check (status <> 'not_met' or exit_case_id is not null),
  check ((status = 'cancelled') = (cancelled_reason is not null)),
  check (acknowledged_at is null or started_at is not null)
);
-- One plan in draft or running at a time for each person.
create unique index improvement_plan_live on app.improvement_plan (employment_id) where status in ('draft','active');

-- What has to improve, and how it is measured.
create table app.plan_objective (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null,
  plan_id    uuid not null,
  position   smallint not null check (position between 1 and 6),
  title      text not null check (length(btrim(title)) between 3 and 200),
  measure    text not null check (length(btrim(measure)) between 3 and 500),
  unique (tenant_id, id),
  unique (plan_id, position),
  foreign key (tenant_id, plan_id) references app.improvement_plan (tenant_id, id)
);

-- The check-ins, one for each due date. ratings: {"<objective id>": "on_track"|"at_risk"|"met"|"not_met"}
create table app.plan_checkin (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null,
  plan_id      uuid not null,
  due_date     date not null,
  ratings      jsonb not null default '{}' check (jsonb_typeof(ratings) = 'object'),
  note_enc     bytea,
  written_by   uuid references app.membership(id),
  written_at   timestamptz,
  reply_enc    bytea,
  replied_at   timestamptz,
  reminded_on  date,
  unique (plan_id, due_date),
  foreign key (tenant_id, plan_id) references app.improvement_plan (tenant_id, id),
  check ((written_at is null) = (note_enc is null)),
  check (replied_at is null or written_at is not null)
);

-- Nobody drafts, starts or decides their own plan, or writes a check-in on it.
create function app.improvement_plan_not_own() returns trigger language plpgsql as $$
declare employee uuid;
begin
  select membership_id into employee from app.employment where id = new.employment_id;
  if employee is not null and employee in (new.created_by, new.started_by, new.decided_by) then
    raise exception 'Nobody manages their own improvement plan' using errcode = 'check_violation';
  end if;
  if tg_op = 'UPDATE' and old.status in ('completed','not_met','cancelled') then
    raise exception 'A decided plan cannot change' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger improvement_plan_not_own before insert or update on app.improvement_plan
  for each row execute function app.improvement_plan_not_own();

create function app.plan_checkin_not_own() returns trigger language plpgsql as $$
begin
  if new.written_by is not null and exists (select 1 from app.improvement_plan p join app.employment e on e.id = p.employment_id
      where p.id = new.plan_id and e.membership_id = new.written_by) then
    raise exception 'Nobody writes a check-in on their own plan' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger plan_checkin_not_own before insert or update on app.plan_checkin
  for each row execute function app.plan_checkin_not_own();

-- ── letters and messages ─────────────────────────────────────────────

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.letter_template drop constraint letter_template_kind_check;
-- constraint-missing-not-valid: the table is small (per-Organization templates and letters, or a column added just above), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.letter_template add constraint letter_template_kind_check
  check (kind in ('offer','appointment','confirmation','other','exit_acceptance','relieving','experience','termination',
                  'retirement','contract_end','show_cause','final_notice','probation_extension','increment','promotion',
                  'improvement_plan'));
-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.issued_letter drop constraint issued_letter_kind_check;
-- constraint-missing-not-valid: the table is small (per-Organization templates and letters, or a column added just above), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.issued_letter add constraint issued_letter_kind_check
  check (kind in ('offer','appointment','confirmation','other','exit_acceptance','relieving','experience','termination',
                  'retirement','contract_end','show_cause','final_notice','settlement','probation_extension','increment','promotion',
                  'improvement_plan'));
alter table app.issued_letter add column improvement_plan_id uuid;
-- adding-foreign-key-constraint: the column is new and empty, or the table was created by this release, so the check has no rows to scan.
-- constraint-missing-not-valid: the table is small (per-Organization templates and letters, or a column added just above), so the scan is instant.
-- squawk-ignore adding-foreign-key-constraint, constraint-missing-not-valid
alter table app.issued_letter add foreign key (tenant_id, improvement_plan_id) references app.improvement_plan (tenant_id, id);
alter table app.improvement_plan
  add foreign key (letter_id) references app.issued_letter (id);

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.message_template drop constraint message_template_event_check;
-- constraint-missing-not-valid: the table is small (per-Organization templates and letters, or a column added just above), so the scan is instant.
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
                   'plan_drafted','plan_started','plan_checkin_due','plan_checkin_written','plan_overdue','plan_decided'));

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['improvement_plan','plan_objective','plan_checkin'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;
grant delete on app.plan_objective to app_tenant;   -- a draft's objectives are rewritten
