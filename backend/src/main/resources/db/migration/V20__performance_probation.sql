-- Performance management, stage 1: probation reviews.
-- Every employment on probation gets a probation review: check-ins by the manager at set days
-- from joining, the manager's recommendation before the probation ends, and HR's decision:
-- confirm (with the confirmation letter), extend (a new probation end and a new review), or not
-- confirm (which starts an exit). Nothing is decided by itself when the probation end passes.
-- The design is docs/performance/design.md.

-- ── permissions ──────────────────────────────────────────────────────

insert into app.permission (code, description) values
  ('performance.read',    'See goals, reviews, probation reviews and improvement plans'),
  ('performance.write',   'Set goals and write reviews, check-ins and recommendations'),
  ('performance.manage',  'Set up performance, run review cycles, decide probation and plans, prepare outcomes'),
  ('performance.approve', 'Approve review outcomes: increments and promotions');

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','performance.read','tenant'), ('owner','performance.write','tenant'),
  ('owner','performance.manage','tenant'), ('owner','performance.approve','tenant'),
  ('hr_admin','performance.read','tenant'), ('hr_admin','performance.write','tenant'),
  ('hr_admin','performance.manage','tenant'), ('hr_admin','performance.approve','tenant'),
  ('manager','performance.read','team'), ('manager','performance.write','team'),
  ('employee','performance.read','self'), ('employee','performance.write','self'),
  ('auditor','performance.read','tenant'),
  ('support_read','performance.read','tenant'), ('support_write','performance.read','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null;

-- ── settings ─────────────────────────────────────────────────────────

create table app.perf_settings (
  tenant_id                 uuid primary key references platform.tenant(id),
  -- check-ins the manager is asked for, in days from joining (only those before the probation end)
  probation_checkin_days    smallint[] not null default '{30,60,90}',
  -- how many days before the probation end the manager is asked to recommend
  probation_recommend_days  smallint not null default 21 check (probation_recommend_days between 7 and 90),
  updated_by                uuid references app.membership(id),
  updated_at                timestamptz not null default now(),
  check (cardinality(probation_checkin_days) between 0 and 6),
  check (0 < all(probation_checkin_days) and 366 > all(probation_checkin_days))
);

-- ── probation reviews ────────────────────────────────────────────────

create table app.probation_review (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null,
  employment_id        uuid not null,
  probation_end        date not null,                     -- the end this review decides
  -- open: check-ins running; recommended: the manager (or HR) has recommended; decided; cancelled (the employment ended)
  status               text not null default 'open' check (status in ('open','recommended','decided','cancelled')),
  recommendation       text check (recommendation in ('confirm','extend','not_confirm')),
  recommend_months     smallint check (recommend_months between 1 and 6),
  recommendation_note_enc bytea,
  recommended_by       uuid references app.membership(id),
  recommended_at       timestamptz,
  decision             text check (decision in ('confirm','extend','not_confirm')),
  decision_months      smallint check (decision_months between 1 and 6),
  decision_note_enc    bytea,
  decided_by           uuid references app.membership(id),
  decided_at           timestamptz,
  confirmed_from       date,                              -- the day after the probation end, for a confirmation
  new_probation_end    date,                              -- for an extension
  letter_id            uuid references app.issued_letter(id),
  exit_case_id         uuid,                              -- for a probation not confirmed
  manager_reminded_on  date,                              -- the recommendation reminder, at most once a day
  hr_reminded_on       date,                              -- the overdue decision reminder, at most once a day
  created_at           timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, exit_case_id) references app.exit_case (tenant_id, id),
  check ((recommendation is null) = (recommended_at is null)),
  check (recommendation is distinct from 'extend' or recommend_months is not null),
  check ((status = 'decided') = (decision is not null and decided_at is not null and decided_by is not null)),
  check (status <> 'recommended' or recommendation is not null),
  check (decision is distinct from 'confirm' or confirmed_from is not null),
  check (decision is distinct from 'extend' or (decision_months is not null and new_probation_end > probation_end)),
  check (decision is distinct from 'not_confirm' or exit_case_id is not null)
);
-- one review in progress per employment, and one review per probation end
create unique index probation_review_open_uq on app.probation_review (tenant_id, employment_id)
  where status in ('open','recommended');
create unique index probation_review_end_uq on app.probation_review (tenant_id, employment_id, probation_end)
  where status <> 'cancelled';
create index probation_review_due on app.probation_review (tenant_id, status, probation_end);

create table app.probation_checkin (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null,
  probation_review_id  uuid not null,
  day                  smallint not null check (day between 1 and 400),   -- days from joining, or from the earlier end for an extension
  due_date             date not null,
  -- how the person is doing, in three words the employee can read without a scale
  rating               text check (rating in ('on_track','some_concerns','not_on_track')),
  note_enc             bytea,
  written_by           uuid references app.membership(id),
  written_at           timestamptz,
  reply_enc            bytea,                              -- the employee's reply
  replied_at           timestamptz,
  reminded_on          date,
  unique (probation_review_id, day),
  foreign key (tenant_id, probation_review_id) references app.probation_review (tenant_id, id),
  check ((written_by is null) = (written_at is null)),
  check ((rating is null) = (written_at is null)),
  check (replied_at is null or written_at is not null),
  check ((reply_enc is null) = (replied_at is null))
);

-- Nobody recommends, decides or writes a check-in about their own probation.
create function app.probation_not_own() returns trigger language plpgsql as $$
declare
  own uuid;
  review_employment uuid;
begin
  if tg_table_name = 'probation_review' then
    review_employment := new.employment_id;
  else
    select employment_id into review_employment from app.probation_review where id = new.probation_review_id;
  end if;
  select membership_id into own from app.employment where id = review_employment;
  if own is null then
    return new;
  end if;
  if tg_table_name = 'probation_review' then
    if new.recommended_by = own or new.decided_by = own then
      raise exception 'Nobody can recommend or decide their own probation' using errcode = 'check_violation';
    end if;
  elsif new.written_by = own then
    raise exception 'Nobody can write a check-in about their own probation' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger probation_review_not_own before insert or update on app.probation_review
  for each row execute function app.probation_not_own();
create trigger probation_checkin_not_own before insert or update on app.probation_checkin
  for each row execute function app.probation_not_own();

-- A decided review never changes, except to record the reminders that no longer apply.
create function app.probation_decided_is_final() returns trigger language plpgsql as $$
begin
  if old.status = 'decided' and (new.status, new.decision, new.decided_by, new.decided_at, new.recommendation, new.letter_id,
      new.exit_case_id, new.new_probation_end, new.confirmed_from)
      is distinct from (old.status, old.decision, old.decided_by, old.decided_at, old.recommendation,
      coalesce(new.letter_id, old.letter_id), old.exit_case_id, old.new_probation_end, old.confirmed_from) then
    raise exception 'A decided probation review cannot change' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger probation_review_final before update on app.probation_review
  for each row execute function app.probation_decided_is_final();

-- ── letters and messages ─────────────────────────────────────────────

alter table app.letter_template drop constraint letter_template_kind_check;
alter table app.letter_template add constraint letter_template_kind_check
  check (kind in ('offer','appointment','confirmation','other','exit_acceptance','relieving','experience','termination',
                  'retirement','contract_end','show_cause','final_notice','probation_extension'));
alter table app.issued_letter drop constraint issued_letter_kind_check;
alter table app.issued_letter add constraint issued_letter_kind_check
  check (kind in ('offer','appointment','confirmation','other','exit_acceptance','relieving','experience','termination',
                  'retirement','contract_end','show_cause','final_notice','settlement','probation_extension'));
alter table app.issued_letter
  add column probation_review_id uuid,
  add foreign key (tenant_id, probation_review_id) references app.probation_review (tenant_id, id);

alter table app.message_template drop constraint message_template_event_check;
alter table app.message_template add constraint message_template_event_check
  check (event in ('joiner_invited','offer_issued','section_returned','reminder_joiner','task_assigned','task_due','task_overdue',
                   'joiner_ready','activated','welcome_team',
                   'resignation_submitted','resignation_recommended','resignation_accepted','resignation_withdrawn',
                   'withdrawal_requested','withdrawal_decided','exit_started','exit_proposed','exit_task_assigned',
                   'exit_task_overdue','last_day_tomorrow','settlement_due','exit_letter_issued','interview_sent',
                   'former_access_ending','payroll_ready','payroll_sent_back','payslip_released',
                   'tax_window_open','tax_proof_returned','setting_pending','setting_decided','setting_weakened',
                   'probation_checkin_due','probation_checkin_written','probation_recommend_due','probation_recommended',
                   'probation_overdue','probation_decided','probation_letter_issued'));

-- ── row-level security, as everywhere else ───────────────────────────

do $$
declare t text;
begin
  foreach t in array array['perf_settings','probation_review','probation_checkin'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;
