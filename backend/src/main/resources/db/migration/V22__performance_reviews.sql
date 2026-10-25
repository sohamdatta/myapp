-- Performance management, stage 3: review cycles, review forms, self and manager reviews, the
-- overall rating, release and acknowledgement. Calibration and outcomes come in stage 4; peers and
-- skip-level sign-off in stage 6. The design is docs/performance/design.md.
--
-- Breaking: no. It only adds tables, columns, permissions and allowed values.
-- To undo by hand: drop the review form, cycle, review and entry tables, and put back V21's message-event list.

set lock_timeout = '5s';

-- ── review forms ─────────────────────────────────────────────────────

create table app.review_form (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  name        text not null check (length(btrim(name)) between 1 and 60),
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (tenant_id, id)
);

-- A form's questions, as saved; a cycle keeps the version it started with.
-- Each question: {"id", "for": "self"|"manager"|"peer", "type": "text"|"rating", "text", "required"}
create table app.review_form_version (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null,
  form_id     uuid not null,
  version     int not null check (version >= 1),
  questions   jsonb not null check (jsonb_typeof(questions) = 'array'),
  created_by  uuid references app.membership(id),
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (form_id, version),
  foreign key (tenant_id, form_id) references app.review_form (tenant_id, id)
);

-- ── cycles ───────────────────────────────────────────────────────────

create table app.review_cycle (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  name              text not null check (length(btrim(name)) between 3 and 80),
  period_start      date not null,
  period_end        date not null,
  applies_to        jsonb not null default '{}' check (jsonb_typeof(applies_to) = 'object'),
  joined_by         date not null,                        -- people who joined after this are not in the cycle
  form_version_id   uuid not null,
  scale_id          uuid,                                 -- the rating scale version, fixed when the cycle starts
  goal_weight       smallint not null default 70 check (goal_weight between 0 and 100),   -- the rest is competencies
  self_review       boolean not null default true,
  self_due          date,
  manager_due       date,
  calibration_due   date,
  release_due       date,
  -- draft: being set up; active: reviews being written; calibrating; released: people can read them; closed
  status            text not null default 'draft' check (status in ('draft','active','calibrating','released','closed')),
  created_by        uuid references app.membership(id),
  created_at        timestamptz not null default now(),
  started_by        uuid references app.membership(id),
  started_at        timestamptz,
  released_by       uuid references app.membership(id),
  released_at       timestamptz,
  closed_at         timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, form_version_id) references app.review_form_version (tenant_id, id),
  foreign key (tenant_id, scale_id) references app.rating_scale (tenant_id, id),
  check (period_end > period_start),
  check ((status = 'draft') = (started_at is null)),
  check (status not in ('released','closed') or released_at is not null),
  check ((status = 'closed') = (closed_at is not null)),
  check (status = 'draft' or scale_id is not null)
);

-- ── reviews ──────────────────────────────────────────────────────────

create table app.review (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null,
  cycle_id             uuid not null,
  employment_id        uuid not null,
  manager_id           uuid,                     -- the manager's employment when the cycle started
  skip_level_id        uuid,                     -- their manager, for stage 6
  status               text not null check (status in ('self_review','manager_review','skip_level','calibration','ready',
                         'released','acknowledged','withdrawn')),
  self_returned_at     timestamptz,              -- a manager returns a self review once
  self_return_note     text check (self_return_note is null or length(self_return_note) <= 1000),
  proposed_rating      numeric(4,2),             -- worked out from the manager's goal and competency ratings
  manager_level        smallint check (manager_level between 1 and 7),
  override_reason_enc  bytea,                    -- why the manager's overall differs from the worked-out one
  calibrated_level     smallint check (calibrated_level between 1 and 7),
  final_level          smallint check (final_level between 1 and 7),
  released_at          timestamptz,
  acknowledged_at      timestamptz,
  employee_comment_enc bytea,
  moved_on_reason      text check (moved_on_reason is null or length(moved_on_reason) <= 500),
  withdrawn_reason     text check (withdrawn_reason is null or length(withdrawn_reason) <= 500),
  added_reason         text check (added_reason is null or length(added_reason) <= 500),
  reminded_on          date,
  created_at           timestamptz not null default now(),
  unique (tenant_id, id),
  unique (cycle_id, employment_id),
  foreign key (tenant_id, cycle_id) references app.review_cycle (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, manager_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, skip_level_id) references app.employment (tenant_id, id),
  check (manager_id is null or manager_id <> employment_id),
  check ((status in ('released','acknowledged')) = (released_at is not null)),
  check ((status = 'acknowledged') = (acknowledged_at is not null)),
  check (status not in ('released','acknowledged') or final_level is not null),
  check ((status = 'withdrawn') = (withdrawn_reason is not null))
);
create index review_manager on app.review (tenant_id, manager_id, status);
create index review_cycle_status on app.review (cycle_id, status);

-- What one reviewer wrote. Ratings are by goal and competency id; answers to the form are encrypted.
create table app.review_entry (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null,
  review_id              uuid not null,
  role                   text not null check (role in ('self','manager','peer','skip_level')),
  reviewer_id            uuid not null references app.membership(id),
  goal_ratings           jsonb not null default '[]' check (jsonb_typeof(goal_ratings) = 'array'),
  competency_ratings     jsonb not null default '[]' check (jsonb_typeof(competency_ratings) = 'array'),
  answers_enc            bytea,
  overall                smallint check (overall between 1 and 7),
  promotion_recommended  boolean not null default false,
  promotion_designation  text check (promotion_designation is null or length(btrim(promotion_designation)) between 1 and 80),
  submitted_at           timestamptz,
  updated_at             timestamptz not null default now(),
  unique (review_id, role, reviewer_id),
  foreign key (tenant_id, review_id) references app.review (tenant_id, id),
  check (promotion_recommended or promotion_designation is null)
);

-- Nobody writes the manager's part of, or rates, their own review; a submitted entry never changes.
create function app.review_entry_rules() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' and old.submitted_at is not null and new.submitted_at is not null then
    raise exception 'A submitted review cannot change' using errcode = 'check_violation';
  end if;
  if new.role <> 'self' and exists (select 1 from app.review r join app.employment e on e.id = r.employment_id
      where r.id = new.review_id and e.membership_id = new.reviewer_id) then
    raise exception 'Nobody reviews themselves except in their self review' using errcode = 'check_violation';
  end if;
  if new.role = 'self' and not exists (select 1 from app.review r join app.employment e on e.id = r.employment_id
      where r.id = new.review_id and e.membership_id = new.reviewer_id) then
    raise exception 'A self review is written by the person reviewed' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger review_entry_rules before insert or update on app.review_entry
  for each row execute function app.review_entry_rules();

-- ── messages ─────────────────────────────────────────────────────────

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
                   'review_self_open','review_manager_open','review_due','review_self_returned','review_released'));

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['review_form','review_form_version','review_cycle','review','review_entry'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;
