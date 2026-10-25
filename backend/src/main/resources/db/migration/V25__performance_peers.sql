-- Performance management, stage 6: peer reviews, skip-level sign-off and requested feedback.
-- A cycle can ask for peer reviews (up to five a person, chosen by the manager) and for the
-- manager's manager to sign each review off before calibration. Outside cycles, anyone can ask
-- colleagues for feedback about themselves, and a manager about someone in their team.
-- The design is docs/performance/design.md.
--
-- Breaking: no. It only adds tables, columns, permissions and allowed values.
-- To undo by hand: drop the peer, sign-off and feedback request tables and the columns this adds to app.review_cycle and app.review, and put back V24's message-event list.

set lock_timeout = '5s';

alter table app.review_cycle
  add column peer_reviews       smallint not null default 0 check (peer_reviews between 0 and 5),
  add column peers_anonymous    boolean not null default true,   -- the employee reads peer reviews without names
  add column skip_level_signoff boolean not null default false;

alter table app.review
  add column skip_level_note text check (skip_level_note is null or length(skip_level_note) <= 1000);

-- A peer asked to review someone in a cycle. What they write is a review_entry with role 'peer'.
create table app.peer_request (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  review_id       uuid not null,
  peer_id         uuid not null,                 -- the peer's employment
  requested_by    uuid not null references app.membership(id),
  -- open: waiting for the peer; submitted; declined (with a reason); expired: the review moved on first
  status          text not null default 'open' check (status in ('open','submitted','declined','expired')),
  declined_reason text check (declined_reason is null or length(declined_reason) between 3 and 300),
  created_at      timestamptz not null default now(),
  answered_at     timestamptz,
  reminded_on     date,
  unique (tenant_id, id),
  unique (review_id, peer_id),
  foreign key (tenant_id, review_id) references app.review (tenant_id, id),
  foreign key (tenant_id, peer_id) references app.employment (tenant_id, id),
  check ((status = 'declined') = (declined_reason is not null)),
  check ((status in ('submitted','declined')) = (answered_at is not null))
);
create index peer_request_peer on app.peer_request (tenant_id, peer_id, status);

-- Nobody is a peer reviewer of themselves.
create function app.peer_request_not_self() returns trigger language plpgsql as $$
begin
  if exists (select 1 from app.review r where r.id = new.review_id and r.employment_id = new.peer_id) then
    raise exception 'Nobody reviews themselves as a peer' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger peer_request_not_self before insert or update on app.peer_request
  for each row execute function app.peer_request_not_self();

-- Feedback asked for: by someone about themselves, or by a manager about someone in their team.
create table app.feedback_request (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  requested_by    uuid not null references app.membership(id),
  about_id        uuid not null,                 -- whom the feedback is about (an employment)
  giver_id        uuid not null,                 -- who is asked (an employment)
  note            text check (note is null or length(note) <= 300),
  status          text not null default 'open' check (status in ('open','given','declined')),
  feedback_id     uuid,
  declined_reason text check (declined_reason is null or length(declined_reason) between 3 and 300),
  created_at      timestamptz not null default now(),
  answered_at     timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, about_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, giver_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, feedback_id) references app.feedback (tenant_id, id),
  check (about_id <> giver_id),
  check ((status = 'given') = (feedback_id is not null)),
  check ((status = 'declined') = (declined_reason is not null))
);
create index feedback_request_giver on app.feedback_request (tenant_id, giver_id, status);
-- One open request from the same person to the same giver about the same person.
create unique index feedback_request_open on app.feedback_request (requested_by, about_id, giver_id) where status = 'open';

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
                   'plan_drafted','plan_started','plan_checkin_due','plan_checkin_written','plan_overdue','plan_decided',
                   'peer_review_requested','skip_level_open','skip_level_returned','feedback_requested'));

do $$
declare t text;
begin
  foreach t in array array['peer_request','feedback_request'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;
