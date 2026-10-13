-- Employee exit: resignation and the other kinds of exit, notice, clearance, the full and final
-- settlement, the exit interview, exit letters and former employees' access.
-- An employment stays 'active' through the notice period; it becomes 'exited', with its exit_date,
-- once the last working day has passed, so managers, attendance and approvals keep working until then.

-- ── permissions ──────────────────────────────────────────────────────

insert into app.permission (code, description) values
  ('exit.read',   'See exits and how they are progressing'),
  ('exit.manage', 'Start, accept and close exits, prepare settlements, read exit interviews');

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','exit.read','tenant'), ('owner','exit.manage','tenant'),
  ('hr_admin','exit.read','tenant'), ('hr_admin','exit.manage','tenant'),
  ('manager','exit.read','team'),
  ('payroll_approver','exit.read','assigned'),
  ('auditor','exit.read','tenant'),
  ('support_read','exit.read','tenant'), ('support_write','exit.read','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null;

-- ── settings ─────────────────────────────────────────────────────────

create table app.exit_settings (
  tenant_id             uuid primary key references platform.tenant(id),
  -- notice owed, in days, by employment type; probation is the notice while on probation
  notice_days           jsonb not null default '{"permanent":30,"probation":15,"fixed_term":30,"intern":0,"consultant":15}',
  manager_step          boolean not null default true,       -- the manager recommends before HR accepts
  notice_leave_type_ids uuid[],                               -- leave allowed during notice; null = every type
  shortfall_default     text not null default 'leave_then_owed'
                        check (shortfall_default in ('waived','leave_then_owed','owed')),
  retirement_age        smallint not null default 60 check (retirement_age between 50 and 75),
  absconding_days       smallint not null default 7 check (absconding_days between 3 and 60),
  response_days         smallint not null default 7 check (response_days between 1 and 30),
  former_access_months  smallint not null default 12 check (former_access_months between 0 and 36),
  encashable_leave_type_ids uuid[] not null default '{}',
  interview_version     int not null default 1 check (interview_version >= 1),
  interview_questions   jsonb not null default '[]',
  updated_by            uuid references app.membership(id),
  updated_at            timestamptz not null default now(),
  check (jsonb_typeof(notice_days) = 'object'),
  check (jsonb_typeof(interview_questions) = 'array')
);

-- ── the exit ─────────────────────────────────────────────────────────

create table app.exit_case (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null,
  employment_id      uuid not null,
  person_id          uuid not null,
  kind               text not null check (kind in ('resignation','termination','end_of_contract','retirement','death','absconding')),
  -- proposed: the app suggests it (contract end, retirement) or HR has begun an absconding case
  -- submitted: the employee resigned; recommended: the manager agreed a last day
  -- accepted: the last working day is fixed; closed: everything done
  status             text not null check (status in ('proposed','submitted','recommended','accepted','withdrawn','cancelled','closed')),
  started_by         uuid references app.membership(id),       -- null when the app proposed it
  started_at         timestamptz not null default now(),
  reason_category    text check (reason_category in ('career','pay','relocation','studies','health','personal','conduct',
                       'performance','contract','retirement','death','absconding','other')),
  reason_note_enc    bytea,                                      -- the employee's or HR's own words, encrypted
  requested_last_day date,
  notice_days        smallint not null default 0 check (notice_days between 0 and 365),
  notice_end         date,                                       -- started + notice; the last day owed
  recommended_by     uuid references app.membership(id),
  recommended_at     timestamptz,
  recommended_last_day date,
  recommendation_note text check (recommendation_note is null or length(recommendation_note) <= 500),
  last_working_day   date,
  accepted_by        uuid references app.membership(id),
  accepted_at        timestamptz,
  shortfall_days     smallint not null default 0 check (shortfall_days >= 0),
  shortfall_action   text check (shortfall_action in ('waived','leave','owed','paid_in_lieu')),
  new_manager_id     uuid,                                       -- the employment that takes over the reports
  withdrawal_requested_at timestamptz,
  withdrawal_note    text check (withdrawal_note is null or length(withdrawal_note) <= 500),
  withdrawal_refused_at timestamptz,
  withdrawn_at       timestamptz,
  cancelled_reason   text,
  ended_at           timestamptz,                                -- when the app ended the employment after the last day
  former_access_until date,
  rehire             text check (rehire in ('yes','no','maybe')),
  rehire_note        text check (rehire_note is null or length(rehire_note) <= 500),
  legal_hold         boolean not null default false,
  settlement_state   text not null default 'none' check (settlement_state in ('none','draft','approved','paid')),
  settlement_prepared_by uuid references app.membership(id),
  settlement_approved_by uuid references app.membership(id),
  settlement_approved_at timestamptz,
  closed_by          uuid references app.membership(id),
  closed_at          timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  foreign key (tenant_id, new_manager_id) references app.employment (tenant_id, id),
  check ((status in ('accepted','closed')) = (last_working_day is not null and accepted_at is not null)
         or status in ('withdrawn') ),
  check (settlement_approved_by is null or settlement_approved_by is distinct from settlement_prepared_by),
  check ((status = 'closed') = (closed_at is not null))
);
-- one open exit per employment
create unique index exit_case_open_uq on app.exit_case (tenant_id, employment_id)
  where status in ('proposed','submitted','recommended','accepted');
create index exit_case_status on app.exit_case (tenant_id, status, last_working_day);

-- ── clearance ────────────────────────────────────────────────────────

-- Checklist templates now serve joining or exit; exit tasks count from acceptance or the last day.
alter table app.checklist_template
  add column kind text not null default 'joining' check (kind in ('joining','exit'));
alter table app.checklist_template_task drop constraint checklist_template_task_trigger_event_check;
alter table app.checklist_template_task add constraint checklist_template_task_trigger_event_check
  check (trigger_event in ('added','offer_accepted','invited','joining_date','activated','exit_accepted','last_working_day'));
alter table app.checklist_template_task drop constraint checklist_template_task_owner_check;
alter table app.checklist_template_task add constraint checklist_template_task_owner_check
  check (owner in ('hr','it','finance','manager','buddy','joiner','role','user'));

create table app.exit_task (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null,
  exit_case_id     uuid not null,
  template_task_id uuid,                                  -- null for a one-off task
  title            text not null check (length(btrim(title)) between 1 and 120),
  instructions     text,
  owner            text not null check (owner in ('hr','it','finance','manager','buddy','joiner','role','user')),
  assignee_id      uuid references app.membership(id),
  due_date         date,
  required         boolean not null default true,
  -- no dues: an amount the employee owes (an unreturned laptop) or is owed (an expense claim)
  amount           numeric(12,2) check (amount is null or amount > 0),
  amount_direction text check (amount_direction in ('owed_by_employee','owed_to_employee')),
  amount_note      text check (amount_note is null or length(amount_note) <= 200),
  remind_days_before smallint,
  repeat_every_days  smallint,
  last_reminded_at timestamptz,
  done_by          uuid references app.membership(id),
  done_at          timestamptz,
  skipped_reason   text,
  position         smallint not null default 0,
  created_by       uuid references app.membership(id),
  created_at       timestamptz not null default now(),
  foreign key (tenant_id, exit_case_id) references app.exit_case (tenant_id, id),
  foreign key (tenant_id, template_task_id) references app.checklist_template_task (tenant_id, id),
  check ((done_by is null) = (done_at is null)),
  check (skipped_reason is null or (done_at is not null and length(btrim(skipped_reason)) >= 5)),
  check ((amount is null) = (amount_direction is null))
);
create index exit_task_assignee on app.exit_task (assignee_id) where done_at is null;

-- ── the settlement ───────────────────────────────────────────────────

create table app.exit_settlement_line (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  exit_case_id  uuid not null,
  position      smallint not null default 0,
  code          text not null check (code in ('salary','leave_encashment','notice_pay','notice_recovery','gratuity','bonus',
                  'reimbursement','recovery','tax','other')),
  label         text not null check (length(btrim(label)) between 1 and 120),
  -- earning: paid to the employee; deduction: taken from what is paid; demand: owed by the employee, asked for separately
  direction     text not null check (direction in ('earning','deduction','demand')),
  quantity      numeric(10,2),
  unit          text check (unit in ('days','years','months')),
  amount        numeric(12,2) check (amount is null or amount >= 0),
  due_date      date,
  exit_task_id  uuid,                                     -- the clearance task it came from
  note          text check (note is null or length(note) <= 300),
  paid_on       date,
  paid_reference text check (paid_reference is null or length(btrim(paid_reference)) between 1 and 80),
  paid_by       uuid references app.membership(id),
  created_at    timestamptz not null default now(),
  foreign key (tenant_id, exit_case_id) references app.exit_case (tenant_id, id),
  check ((paid_on is null) = (paid_reference is null)),
  check (paid_on is null or amount is not null)
);
create unique index exit_settlement_line_code_uq on app.exit_settlement_line (exit_case_id, code)
  where code in ('salary','leave_encashment','notice_pay','notice_recovery','gratuity','bonus','tax') ;

-- A paid line never changes; it can only be read.
create function app.exit_line_paid_is_final() returns trigger language plpgsql as $$
begin
  if old.paid_on is not null then
    raise exception 'A paid settlement line cannot change' using errcode = 'check_violation';
  end if;
  return case when tg_op = 'DELETE' then old else new end;
end $$;
create trigger exit_line_paid_final before update or delete on app.exit_settlement_line
  for each row execute function app.exit_line_paid_is_final();

-- ── the exit interview ───────────────────────────────────────────────

create table app.exit_interview (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  exit_case_id  uuid not null,
  version       int not null,
  questions     jsonb not null check (jsonb_typeof(questions) = 'array'),   -- as sent, so later edits do not change it
  status        text not null default 'sent' check (status in ('sent','submitted','declined','in_person')),
  answers_enc   bytea,                                    -- the answers, encrypted
  notes_enc     bytea,                                    -- HR's notes from an interview held in person
  submitted_at  timestamptz,
  updated_by    uuid references app.membership(id),
  updated_at    timestamptz not null default now(),
  unique (tenant_id, exit_case_id),
  foreign key (tenant_id, exit_case_id) references app.exit_case (tenant_id, id)
);

-- ── letters and messages ─────────────────────────────────────────────

alter table app.letter_template drop constraint letter_template_kind_check;
alter table app.letter_template add constraint letter_template_kind_check
  check (kind in ('offer','appointment','confirmation','other','exit_acceptance','relieving','experience','termination',
                  'retirement','contract_end','show_cause','final_notice'));
alter table app.issued_letter drop constraint issued_letter_kind_check;
alter table app.issued_letter add constraint issued_letter_kind_check
  check (kind in ('offer','appointment','confirmation','other','exit_acceptance','relieving','experience','termination',
                  'retirement','contract_end','show_cause','final_notice','settlement'));
alter table app.issued_letter
  add column exit_case_id uuid,
  add foreign key (tenant_id, exit_case_id) references app.exit_case (tenant_id, id);

alter table app.message_template drop constraint message_template_event_check;
alter table app.message_template add constraint message_template_event_check
  check (event in ('joiner_invited','offer_issued','section_returned','reminder_joiner','task_assigned','task_due','task_overdue',
                   'joiner_ready','activated','welcome_team',
                   'resignation_submitted','resignation_recommended','resignation_accepted','resignation_withdrawn',
                   'withdrawal_requested','withdrawal_decided','exit_started','exit_proposed','exit_task_assigned',
                   'exit_task_overdue','last_day_tomorrow','settlement_due','exit_letter_issued','interview_sent',
                   'former_access_ending'));

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['exit_settings','exit_case','exit_task','exit_settlement_line','exit_interview'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

grant select, insert, update on app.exit_settings, app.exit_case, app.exit_task, app.exit_interview to app_tenant;
grant select, insert, update, delete on app.exit_settlement_line to app_tenant;
grant delete on app.exit_task to app_tenant;
grant select, insert, update, delete on app.exit_settings, app.exit_case, app.exit_task, app.exit_settlement_line,
  app.exit_interview to app_definer;
