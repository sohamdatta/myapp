-- Performance management, stage 4: calibration and outcomes. HR groups a cycle's written reviews
-- into calibration sessions, compares the ratings against a guide distribution and changes them
-- with a reason; closing a session makes its reviews ready to release. After release HR prepares
-- outcomes (an increment from the merit table, a promotion, a bonus), and a second person approves
-- them, which writes the pay revision and the job change and issues the letters.
-- The design is docs/performance/design.md.
--
-- Breaking: no. It only adds tables, columns, permissions and allowed values.
-- To undo by hand: drop the calibration and outcome tables and the columns this adds to app.review, app.issued_letter and app.perf_settings, and put back V22's letter-kind and message-event lists.

set lock_timeout = '5s';

-- ── settings ─────────────────────────────────────────────────────────

-- Both are keyed by rating value. The guide is the share of people expected at each rating, in
-- per cent, shown beside the actual spread and never enforced. The merit table is the increment,
-- in per cent, each final rating suggests.
alter table app.perf_settings
  add column distribution_guide jsonb not null default '{"5":10,"4":20,"3":50,"2":15,"1":5}'
    check (jsonb_typeof(distribution_guide) = 'object'),
  add column merit_table jsonb not null default '{"5":12,"4":9,"3":6,"2":3,"1":0}'
    check (jsonb_typeof(merit_table) = 'object'),
  -- which stage's defaults the Organization has; stage 4 onwards are added from it
  add column defaults_stage smallint not null default 3;

-- ── calibration ──────────────────────────────────────────────────────

create table app.calibration_session (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  cycle_id       uuid not null,
  name           text not null check (length(btrim(name)) between 3 and 80),
  department_id  uuid,                       -- the reviews it took in; null for everyone left
  status         text not null default 'open' check (status in ('open','closed')),
  notes_enc      bytea,                      -- what was discussed, for HR
  created_by     uuid references app.membership(id),
  created_at     timestamptz not null default now(),
  closed_by      uuid references app.membership(id),
  closed_at      timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, cycle_id) references app.review_cycle (tenant_id, id),
  foreign key (tenant_id, department_id) references app.department (tenant_id, id),
  check ((status = 'closed') = (closed_at is not null))
);

alter table app.review add column calibration_session_id uuid;
-- adding-foreign-key-constraint: the column is new and empty, or the table was created by this release, so the check has no rows to scan.
-- constraint-missing-not-valid: the table is small (per-Organization templates and letters, or a column added just above), so the scan is instant.
-- squawk-ignore adding-foreign-key-constraint, constraint-missing-not-valid
alter table app.review add foreign key (tenant_id, calibration_session_id) references app.calibration_session (tenant_id, id);

-- Every change of a rating in calibration, with why. The reason is encrypted; it is at least 20
-- characters, which the application checks before sealing it.
create table app.calibration_change (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null,
  session_id   uuid not null,
  review_id    uuid not null,
  from_level   smallint not null check (from_level between 1 and 7),
  to_level     smallint not null check (to_level between 1 and 7),
  reason_enc   bytea not null,
  changed_by   uuid not null references app.membership(id),
  changed_at   timestamptz not null default now(),
  foreign key (tenant_id, session_id) references app.calibration_session (tenant_id, id),
  foreign key (tenant_id, review_id) references app.review (tenant_id, id),
  check (from_level <> to_level)
);
create index calibration_change_review on app.calibration_change (review_id, changed_at);

-- Nobody calibrates their own rating.
create function app.calibration_change_not_own() returns trigger language plpgsql as $$
begin
  if exists (select 1 from app.review r join app.employment e on e.id = r.employment_id
      where r.id = new.review_id and e.membership_id = new.changed_by) then
    raise exception 'Nobody calibrates their own rating' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger calibration_change_not_own before insert on app.calibration_change
  for each row execute function app.calibration_change_not_own();

-- ── outcomes ─────────────────────────────────────────────────────────

create table app.review_outcome (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null,
  review_id          uuid not null,
  cycle_id           uuid not null,
  employment_id      uuid not null,
  level              smallint not null check (level between 1 and 7),   -- the final rating
  current_annual     numeric(14,2),            -- pay on the effective date, when they have any
  structure_id       uuid,
  increment_pct      numeric(5,2) not null default 0 check (increment_pct between 0 and 100),
  new_annual         numeric(14,2),
  promotion          boolean not null default false,
  new_designation    text check (new_designation is null or length(btrim(new_designation)) between 1 and 80),
  bonus_amount       numeric(14,2) check (bonus_amount is null or bonus_amount >= 0),
  effective_from     date not null check (extract(day from effective_from) = 1),
  note               text check (note is null or length(note) <= 500),   -- why it differs from the merit table
  -- draft: HR is preparing it; submitted: waiting for approval; applied: approved and written
  status             text not null default 'draft' check (status in ('draft','submitted','applied')),
  return_note        text check (return_note is null or length(return_note) <= 500),
  prepared_by        uuid references app.membership(id),
  prepared_at        timestamptz not null default now(),
  submitted_by       uuid references app.membership(id),
  submitted_at       timestamptz,
  approved_by        uuid references app.membership(id),
  approved_at        timestamptz,
  compensation_id    uuid references app.compensation(id),
  job_assignment_id  uuid references app.job_assignment(id),
  unique (tenant_id, id),
  unique (review_id),
  foreign key (tenant_id, review_id) references app.review (tenant_id, id),
  foreign key (tenant_id, cycle_id) references app.review_cycle (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  check (promotion = (new_designation is not null)),
  check (new_annual is null or (current_annual is not null and new_annual >= current_annual)),
  check ((status = 'draft') = (submitted_at is null)),
  check ((status = 'applied') = (approved_at is not null)),
  -- the person who sent it for approval does not approve it
  check (approved_by is null or approved_by <> submitted_by)
);
create index review_outcome_cycle on app.review_outcome (cycle_id, status);

-- Nobody approves their own outcome, and an applied outcome never changes.
create function app.review_outcome_rules() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' and old.status = 'applied' then
    raise exception 'An applied outcome cannot change' using errcode = 'check_violation';
  end if;
  if new.approved_by is not null and exists (select 1 from app.employment e
      where e.id = new.employment_id and e.membership_id = new.approved_by) then
    raise exception 'Nobody approves their own outcome' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger review_outcome_rules before insert or update on app.review_outcome
  for each row execute function app.review_outcome_rules();

-- ── letters and messages ─────────────────────────────────────────────

-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.letter_template drop constraint letter_template_kind_check;
-- constraint-missing-not-valid: the table is small (per-Organization templates and letters, or a column added just above), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.letter_template add constraint letter_template_kind_check
  check (kind in ('offer','appointment','confirmation','other','exit_acceptance','relieving','experience','termination',
                  'retirement','contract_end','show_cause','final_notice','probation_extension','increment','promotion'));
-- ban-drop-constraint: the check is dropped only to be added again at once with a longer list; every value it allowed is still allowed.
-- squawk-ignore ban-drop-constraint
alter table app.issued_letter drop constraint issued_letter_kind_check;
-- constraint-missing-not-valid: the table is small (per-Organization templates and letters, or a column added just above), so the scan is instant.
-- squawk-ignore constraint-missing-not-valid
alter table app.issued_letter add constraint issued_letter_kind_check
  check (kind in ('offer','appointment','confirmation','other','exit_acceptance','relieving','experience','termination',
                  'retirement','contract_end','show_cause','final_notice','settlement','probation_extension','increment','promotion'));
alter table app.issued_letter add column review_outcome_id uuid;
-- adding-foreign-key-constraint: the column is new and empty, or the table was created by this release, so the check has no rows to scan.
-- constraint-missing-not-valid: the table is small (per-Organization templates and letters, or a column added just above), so the scan is instant.
-- squawk-ignore adding-foreign-key-constraint, constraint-missing-not-valid
alter table app.issued_letter add foreign key (tenant_id, review_outcome_id) references app.review_outcome (tenant_id, id);

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
                   'review_calibrated','outcomes_submitted','outcomes_returned'));

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['calibration_session','calibration_change','review_outcome'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;
