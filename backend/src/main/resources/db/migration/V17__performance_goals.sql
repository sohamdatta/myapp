-- Performance management, stage 2: the rating scale, competencies, goals, one-to-ones and feedback.
-- Goals belong to an employment and a goal period (a financial or calendar year). The employee
-- drafts and submits a weighted set; the manager agrees it or returns it. A change to an agreed
-- goal keeps the earlier version and is agreed again. One-to-ones belong to a manager and a
-- report; feedback is given to anyone. The design is docs/performance/design.md.

-- ── settings ─────────────────────────────────────────────────────────

alter table app.perf_settings
  add column goal_year_start   smallint not null default 4 check (goal_year_start in (1, 4)),   -- April (financial year) or January
  add column one_to_one_days   smallint not null default 14 check (one_to_one_days between 7 and 90),
  add column one_to_one_nudge_days smallint not null default 45 check (one_to_one_nudge_days between 14 and 180),
  add column rating_scale_id   uuid;

-- ── the rating scale ─────────────────────────────────────────────────

create table app.rating_scale (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  version     int not null check (version >= 1),
  created_by  uuid references app.membership(id),
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, version)
);

create table app.rating_level (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null,
  scale_id    uuid not null,
  value       smallint not null check (value between 1 and 7),
  label       text not null check (length(btrim(label)) between 1 and 40),
  description text check (description is null or length(description) <= 300),
  unique (scale_id, value),
  foreign key (tenant_id, scale_id) references app.rating_scale (tenant_id, id)
);

alter table app.perf_settings add foreign key (tenant_id, rating_scale_id) references app.rating_scale (tenant_id, id);

-- A scale in use never changes: a change is a new version.
create function app.rating_level_final() returns trigger language plpgsql as $$
begin
  raise exception 'A rating scale cannot change once saved; save a new version instead' using errcode = 'check_violation';
end $$;
create trigger rating_level_final before update or delete on app.rating_level
  for each row execute function app.rating_level_final();

-- ── competencies ─────────────────────────────────────────────────────

create table app.competency (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  name        text not null check (length(btrim(name)) between 1 and 60),
  description text check (description is null or length(description) <= 500),
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (tenant_id, id)
);
create unique index competency_name_uq on app.competency (tenant_id, lower(name));

-- Competencies people are rated on, chosen by the same rules as onboarding flows. The first
-- active set, in order, whose rules match an employee is theirs.
create table app.competency_set (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  name           text not null check (length(btrim(name)) between 1 and 60),
  applies_to     jsonb not null default '{}' check (jsonb_typeof(applies_to) = 'object'),
  competency_ids uuid[] not null default '{}' check (cardinality(competency_ids) <= 12),
  position       smallint not null default 0,
  active         boolean not null default true,
  updated_by     uuid references app.membership(id),
  updated_at     timestamptz not null default now(),
  unique (tenant_id, id)
);

-- ── goals ────────────────────────────────────────────────────────────

create table app.goal (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  employment_id   uuid,                                   -- a person's goal
  department_id   uuid,                                   -- or a department's, which people's goals can support
  period_start    date not null,
  period_end      date not null,
  title           text not null check (length(btrim(title)) between 3 and 200),
  description     text check (description is null or length(description) <= 2000),
  weight          smallint check (weight between 5 and 100 and weight % 5 = 0),
  measure         text not null default 'rating' check (measure in ('rating','number','done')),
  target          numeric(14,2),
  unit            text check (unit is null or length(btrim(unit)) between 1 and 20),
  due_date        date,
  parent_goal_id  uuid,
  -- draft: being written; submitted: waiting for the manager; agreed; returned: sent back with a note; dropped
  status          text not null default 'draft' check (status in ('draft','submitted','agreed','returned','dropped')),
  version         int not null default 1 check (version >= 1),
  change_reason   text check (change_reason is null or length(change_reason) <= 500),
  return_note     text check (return_note is null or length(return_note) <= 1000),
  drop_reason     text check (drop_reason is null or length(drop_reason) <= 500),
  agreed_by       uuid references app.membership(id),
  agreed_at       timestamptz,
  progress        smallint check (progress between 0 and 100),   -- the latest check-in, as a percentage
  progress_value  numeric(14,2),                                 -- the latest value, for a number goal
  created_by      uuid references app.membership(id),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, department_id) references app.department (tenant_id, id),
  foreign key (tenant_id, parent_goal_id) references app.goal (tenant_id, id),
  check ((employment_id is null) <> (department_id is null)),
  check (employment_id is null or weight is not null),
  check (period_end > period_start),
  check (measure <> 'number' or target is not null),
  check (due_date is null or due_date between period_start and period_end),
  check (parent_goal_id is null or parent_goal_id <> id),
  check ((status = 'dropped') = (drop_reason is not null)),
  check (status <> 'agreed' or agreed_at is not null)
);
create index goal_employment on app.goal (tenant_id, employment_id, period_start);
create index goal_department on app.goal (tenant_id, department_id, period_start) where department_id is not null;

-- Earlier versions of a goal, kept when an agreed goal changes.
create table app.goal_version (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  goal_id        uuid not null,
  version        int not null,
  title          text not null,
  description    text,
  weight         smallint,
  measure        text not null,
  target         numeric(14,2),
  unit           text,
  due_date       date,
  agreed_by      uuid references app.membership(id),
  agreed_at      timestamptz,
  replaced_by    uuid references app.membership(id),
  replaced_at    timestamptz not null default now(),
  unique (goal_id, version),
  foreign key (tenant_id, goal_id) references app.goal (tenant_id, id)
);

create table app.goal_checkin (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null,
  goal_id     uuid not null,
  progress    smallint check (progress between 0 and 100),
  value       numeric(14,2),
  note        text check (note is null or length(note) <= 1000),
  written_by  uuid references app.membership(id),
  written_at  timestamptz not null default now(),
  foreign key (tenant_id, goal_id) references app.goal (tenant_id, id),
  check (progress is not null or value is not null or note is not null)
);
create index goal_checkin_goal on app.goal_checkin (goal_id, written_at desc);

-- Nobody agrees their own goals.
create function app.goal_not_own() returns trigger language plpgsql as $$
begin
  if new.agreed_by is not null and new.agreed_by is distinct from old.agreed_by
     and exists (select 1 from app.employment e where e.id = new.employment_id and e.membership_id = new.agreed_by) then
    raise exception 'Nobody can agree their own goals' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger goal_not_own before update on app.goal for each row execute function app.goal_not_own();

-- ── one-to-ones ──────────────────────────────────────────────────────

create table app.one_to_one (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null,
  manager_id          uuid not null,                     -- the manager's employment
  report_id           uuid not null,                     -- the report's employment
  meeting_date        date not null,
  shared_notes_enc    bytea,                             -- the agenda and notes both read
  manager_private_enc bytea,                             -- read only by the manager
  report_private_enc  bytea,                             -- read only by the report
  created_by          uuid references app.membership(id),
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, manager_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, report_id) references app.employment (tenant_id, id),
  check (manager_id <> report_id)
);
create index one_to_one_pair on app.one_to_one (tenant_id, manager_id, report_id, meeting_date desc);

create table app.one_to_one_item (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  one_to_one_id uuid not null,
  text          text not null check (length(btrim(text)) between 1 and 300),
  owner         text not null check (owner in ('manager','report')),
  done_at       timestamptz,
  done_by       uuid references app.membership(id),
  created_by    uuid references app.membership(id),
  created_at    timestamptz not null default now(),
  foreign key (tenant_id, one_to_one_id) references app.one_to_one (tenant_id, id),
  check ((done_at is null) = (done_by is null))
);

-- When a manager was last reminded that they have not met a report, so the reminder comes at most once a week.
create table app.one_to_one_nudge (
  tenant_id   uuid not null,
  manager_id  uuid not null,
  report_id   uuid not null,
  nudged_on   date not null,
  primary key (tenant_id, manager_id, report_id),
  foreign key (tenant_id, manager_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, report_id) references app.employment (tenant_id, id)
);

-- ── feedback ─────────────────────────────────────────────────────────

create table app.feedback (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null,
  giver_id          uuid not null references app.membership(id),
  receiver_id       uuid not null,                       -- the receiver's employment
  -- praise: the receiver reads it; private: only the receiver's manager and HR read it
  kind              text not null check (kind in ('praise','private')),
  share_with_manager boolean not null default false,     -- praise the receiver's manager also sees
  text_enc          bytea,
  goal_id           uuid,
  competency_id     uuid,
  created_at        timestamptz not null default now(),
  edited_at         timestamptz,
  withdrawn_at      timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, receiver_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, goal_id) references app.goal (tenant_id, id),
  foreign key (tenant_id, competency_id) references app.competency (tenant_id, id),
  check ((withdrawn_at is null) = (text_enc is not null))
);
create index feedback_receiver on app.feedback (tenant_id, receiver_id, created_at desc);

-- Feedback cannot be changed after a day, and is never deleted: a withdrawn item keeps its date, not its text.
create function app.feedback_rules() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'Feedback is never deleted; withdraw it instead' using errcode = 'check_violation';
  end if;
  if old.withdrawn_at is not null then
    raise exception 'Withdrawn feedback cannot change' using errcode = 'check_violation';
  end if;
  if new.withdrawn_at is null and new.text_enc is distinct from old.text_enc and old.created_at < now() - interval '24 hours' then
    raise exception 'Feedback can be changed only within a day of giving it' using errcode = 'check_violation';
  end if;
  if exists (select 1 from app.employment e where e.id = new.receiver_id and e.membership_id = new.giver_id) then
    raise exception 'Nobody gives feedback to themselves' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger feedback_rules before update or delete on app.feedback for each row execute function app.feedback_rules();
create function app.feedback_not_self() returns trigger language plpgsql as $$
begin
  if exists (select 1 from app.employment e where e.id = new.receiver_id and e.membership_id = new.giver_id) then
    raise exception 'Nobody gives feedback to themselves' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger feedback_not_self before insert on app.feedback for each row execute function app.feedback_not_self();

-- ── messages ─────────────────────────────────────────────────────────

alter table app.message_template drop constraint message_template_event_check;
alter table app.message_template add constraint message_template_event_check
  check (event in ('joiner_invited','offer_issued','section_returned','reminder_joiner','task_assigned','task_due','task_overdue',
                   'joiner_ready','activated','welcome_team',
                   'resignation_submitted','resignation_recommended','resignation_accepted','resignation_withdrawn',
                   'withdrawal_requested','withdrawal_decided','exit_started','exit_proposed','exit_task_assigned',
                   'exit_task_overdue','last_day_tomorrow','settlement_due','exit_letter_issued','interview_sent',
                   'former_access_ending','payroll_ready','payroll_sent_back','payslip_released',
                   'probation_checkin_due','probation_checkin_written','probation_recommend_due','probation_recommended',
                   'probation_overdue','probation_decided','probation_letter_issued',
                   'goals_submitted','goals_agreed','goals_returned','goal_changed','one_to_one_nudge','feedback_received'));

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['rating_scale','rating_level','competency','competency_set','goal','goal_version','goal_checkin',
                           'one_to_one','one_to_one_item','one_to_one_nudge','feedback'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;

grant delete on app.goal, app.one_to_one_item, app.competency_set to app_tenant;
