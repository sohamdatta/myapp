-- Onboarding: each Organization's configuration (settings, privacy notice, flows, verification,
-- sections, fields, custom fields, employee code series, letters, policies, messages, checklists)
-- and each joiner's onboarding (sections, reviews, tasks, notifications).
-- Builds on V5 (the employee record). Same rules: tenant_id on every table, row-level security
-- forced, reachable only by app_tenant inside one Organization's transaction.
-- Nothing here names a customer: an Organization's defaults are written by the application
-- the first time its configuration is read.

-- ── configuration: everything below is set per Organization ────────
-- applies_to, wherever it appears, is a JSON object of attribute -> list of values. The attributes are
-- department and work_location (lists of ids), employment_type and designation (lists of words).
-- An empty object applies to everyone. Every listed attribute must match (and); values within one attribute are alternatives (or).
-- The application validates the attribute names and that the ids belong to this Organization.

create table app.onboarding_settings (
  tenant_id                uuid primary key references platform.tenant(id),
  offer_stage              boolean not null default false,       -- joiners start as candidates with an offer to accept
  invite_when              text not null default 'manual'
                           check (invite_when in ('manual','on_create','on_offer_accepted','days_before_joining')),
  invite_days_before       smallint check (invite_days_before between 1 and 90),
  link_valid_days          smallint not null default 14 check (link_valid_days between 1 and 120),
  activation               text not null default 'automatic' check (activation in ('automatic','manual')),
  allow_activation_without_pan boolean not null default true,
  rehire_match             text[] not null default '{pan,personal_email,mobile}',
  withdrawn_retention_days smallint not null default 90 check (withdrawn_retention_days between 30 and 3650),
  exited_retention_years   smallint not null default 5 check (exited_retention_years between 5 and 30),
  updated_by               uuid references app.membership(id),
  updated_at               timestamptz not null default now(),
  check ((invite_when = 'days_before_joining') = (invite_days_before is not null)),
  check (invite_when <> 'on_offer_accepted' or offer_stage),
  check (rehire_match <@ array['pan','personal_email','mobile','aadhaar_last4']::text[])
);

create table app.privacy_notice (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  version       int not null check (version >= 1),
  body          text not null check (length(btrim(body)) >= 50),
  published_by  uuid references app.membership(id),
  published_at  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, version)
);

-- A flow is a named onboarding for a group of joiners. The first active flow, by position,
-- whose applies_to matches the joiner is used; HR can pick another when adding the joiner.
create table app.onboarding_flow (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  name             text not null check (length(btrim(name)) between 1 and 80),
  applies_to       jsonb not null default '{}',
  position         smallint not null default 0,
  welcome_message  text,
  welcome_video_url text check (welcome_video_url ~ '^https://'),
  day_one_note     text,                                 -- where and when to report
  hr_contact_id    uuid references app.membership(id),
  verification_levels smallint not null default 1 check (verification_levels between 0 and 3),  -- 0 = nothing is verified
  active           boolean not null default true,
  updated_at       timestamptz not null default now(),
  unique (tenant_id, id),
  check (jsonb_typeof(applies_to) = 'object')
);
create unique index onboarding_flow_name_uq on app.onboarding_flow (tenant_id, lower(name));

-- Who verifies at each level of a flow: a role, a named user, or the joiner's reporting manager.
create table app.verification_step (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  flow_id       uuid not null,
  level         smallint not null check (level between 1 and 3),
  section       text,                                  -- null = every section
  verifier      text not null check (verifier in ('role','user','manager')),
  role_id       uuid references app.role(id),
  membership_id uuid references app.membership(id),
  foreign key (tenant_id, flow_id) references app.onboarding_flow (tenant_id, id),
  check ((verifier = 'role') = (role_id is not null)),
  check ((verifier = 'user') = (membership_id is not null))
);

-- Sections a flow shows, in what order, and whether each is verified.
create table app.section_setting (
  tenant_id   uuid not null,
  flow_id     uuid not null,
  section     text not null check (section in
                ('employment','personal','contact','address','emergency','family','bank','statutory','documents','policies','letters')),
  enabled     boolean not null default true,
  position    smallint not null default 0,
  title       text,                                    -- null = the built-in title
  intro       text,                                    -- shown at the top of the section
  verify      boolean not null default true,
  primary key (flow_id, section),
  foreign key (tenant_id, flow_id) references app.onboarding_flow (tenant_id, id),
  check (section not in ('employment','personal','statutory') or enabled)   -- the record cannot exist without these
);

-- Built-in fields: hidden, optional or required, with a label and help of the Organization's own.
-- flow_id null = the Organization's default; a row for a flow overrides it for that flow.
create table app.field_setting (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  flow_id     uuid,
  field_key   text not null check (field_key ~ '^[a-z][a-z0-9_.]{1,60}$'),   -- e.g. person.blood_group, statutory.aadhaar
  visibility  text not null check (visibility in ('hidden','optional','required')),
  label       text,
  help        text,
  editable_by text not null default 'joiner' check (editable_by in ('joiner','hr')),
  foreign key (tenant_id, flow_id) references app.onboarding_flow (tenant_id, id)
);
create unique index field_setting_uq on app.field_setting
  (tenant_id, coalesce(flow_id, '00000000-0000-0000-0000-000000000000'::uuid), field_key);
-- the application refuses hidden or optional for the fields the record cannot do without
-- (person.legal_name, person.date_of_birth, employment.joining_date) and refuses hiding PAN.

-- Fields of the Organization's own, placed in any section.
create table app.custom_field (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  field_key   text not null check (field_key ~ '^[a-z][a-z0-9_]{1,40}$'),
  label       text not null check (length(btrim(label)) between 1 and 80),
  help        text,
  section     text not null check (section in
                ('employment','personal','contact','address','emergency','family','bank','statutory','documents')),
  data_type   text not null check (data_type in ('text','long_text','number','date','select','multi_select','yes_no','email','phone')),
  options     text[],                                  -- for select and multi_select
  pattern     text,                                    -- optional regular expression for text
  min_value   numeric,
  max_value   numeric,
  sensitive   boolean not null default false,          -- stored encrypted, shown like PAN
  manager_can_see boolean not null default false,
  applies_to  jsonb not null default '{}',
  visibility  text not null default 'optional' check (visibility in ('optional','required')),
  editable_by text not null default 'joiner' check (editable_by in ('joiner','hr')),
  position    smallint not null default 0,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  check ((data_type in ('select','multi_select')) = (options is not null and cardinality(options) >= 1)),
  check (jsonb_typeof(applies_to) = 'object')
);
create unique index custom_field_key_uq on app.custom_field (tenant_id, field_key);

create table app.custom_field_value (
  tenant_id   uuid not null,
  person_id   uuid not null,
  field_id    uuid not null,
  value       jsonb,                                   -- plain values
  value_enc   bytea,                                   -- sensitive values
  updated_by  uuid references app.membership(id),
  updated_at  timestamptz not null default now(),
  primary key (person_id, field_id),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  foreign key (tenant_id, field_id) references app.custom_field (tenant_id, id),
  check (value is null or value_enc is null)
);

-- Employee codes made from a pattern: tokens {YYYY} {YY} {MM} {LOC} {DEPT} {TYPE} in prefix and suffix.
create table app.employee_number_series (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  name        text not null check (length(btrim(name)) between 1 and 60),
  prefix      text not null default '' check (length(prefix) <= 30),
  suffix      text not null default '' check (length(suffix) <= 30),
  digits      smallint not null default 4 check (digits between 1 and 10),
  start_at    int not null default 1 check (start_at >= 0),
  reset       text not null default 'never' check (reset in ('never','yearly')),
  applies_to  jsonb not null default '{}',
  position    smallint not null default 0,
  active      boolean not null default true,
  unique (tenant_id, id),
  check (jsonb_typeof(applies_to) = 'object')
);

-- One counter per series and per expanded prefix (and year, when the series resets yearly).
create table app.employee_number_counter (
  tenant_id   uuid not null,
  series_id   uuid not null,
  bucket      text not null,                           -- the expanded prefix, plus the year when resetting
  next_number int not null check (next_number >= 0),
  primary key (series_id, bucket),
  foreign key (tenant_id, series_id) references app.employee_number_series (tenant_id, id)
);

-- Letters (offer, appointment, confirmation) and policies, written by the Organization with placeholders
-- such as {{person.legal_name}} and {{employment.joining_date}}. A change is a new version.
create table app.letter_template (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  kind        text not null check (kind in ('offer','appointment','confirmation','other')),
  name        text not null check (length(btrim(name)) between 1 and 80),
  version     int not null default 1 check (version >= 1),
  body        text not null,
  response    text not null default 'accept' check (response in ('none','accept','accept_or_decline')),
  applies_to  jsonb not null default '{}',
  active      boolean not null default true,
  created_by  uuid references app.membership(id),
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  check (jsonb_typeof(applies_to) = 'object')
);

create table app.issued_letter (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  person_id     uuid not null,
  template_id   uuid,                                  -- null when HR uploaded the letter instead
  template_version int,
  document_id   uuid not null,                         -- the rendered or uploaded PDF
  issued_by     uuid references app.membership(id),
  issued_at     timestamptz not null default now(),
  expires_on    date,
  response      text check (response in ('accepted','declined')),
  responded_at  timestamptz,
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  foreign key (tenant_id, template_id) references app.letter_template (tenant_id, id),
  foreign key (tenant_id, document_id) references app.document (tenant_id, id),
  check ((response is null) = (responded_at is null))
);

create table app.policy (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  name        text not null check (length(btrim(name)) between 1 and 80),
  version     int not null default 1 check (version >= 1),
  body        text,
  document_id uuid,
  due         text not null default 'before_activation' check (due in ('before_activation','within_days')),
  within_days smallint check (within_days between 1 and 365),
  applies_to  jsonb not null default '{}',
  active      boolean not null default true,
  published_at timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, document_id) references app.document (tenant_id, id),
  check (body is not null or document_id is not null),
  check ((due = 'within_days') = (within_days is not null)),
  check (jsonb_typeof(applies_to) = 'object')
);

create table app.policy_acknowledgement (
  tenant_id   uuid not null,
  policy_id   uuid not null,
  person_id   uuid not null,
  version     int not null,
  acknowledged_at timestamptz not null default now(),
  primary key (policy_id, person_id, version),
  foreign key (tenant_id, policy_id) references app.policy (tenant_id, id),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id)
);

-- Words sent to people at each event. Shown in the app now; sent by email once email exists.
create table app.message_template (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  event       text not null check (event in ('joiner_invited','offer_issued','section_returned','reminder_joiner',
                'task_assigned','task_due','task_overdue','joiner_ready','activated','welcome_team')),
  channel     text not null default 'in_app' check (channel in ('in_app','email')),
  subject     text,
  body        text not null,
  active      boolean not null default true,
  unique (tenant_id, event, channel)
);

create table app.checklist_template (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  name             text not null check (length(btrim(name)) between 1 and 80),
  applies_to       jsonb not null default '{}',
  active           boolean not null default true,
  unique (tenant_id, id),
  check (jsonb_typeof(applies_to) = 'object')
);

create table app.checklist_template_task (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  template_id     uuid not null,
  title           text not null check (length(btrim(title)) between 1 and 120),
  instructions    text,
  owner           text not null check (owner in ('hr','it','manager','buddy','joiner','role','user')),
  owner_role_id   uuid references app.role(id),
  owner_membership_id uuid references app.membership(id),
  trigger_event   text not null default 'joining_date'
                  check (trigger_event in ('added','offer_accepted','invited','joining_date','activated')),
  offset_days     smallint not null default 0 check (offset_days between -90 and 180),
  required        boolean not null default true,
  needs_attachment boolean not null default false,     -- cannot be marked done without a file
  approver        text check (approver in ('hr','manager','role','user')),
  approver_role_id uuid references app.role(id),
  approver_membership_id uuid references app.membership(id),
  remind_days_before smallint check (remind_days_before between 1 and 30),
  repeat_every_days  smallint check (repeat_every_days between 1 and 30),  -- after the due date
  applies_to      jsonb not null default '{}',
  position        smallint not null default 0,
  unique (tenant_id, id),
  foreign key (tenant_id, template_id) references app.checklist_template (tenant_id, id),
  check ((owner = 'role') = (owner_role_id is not null)),
  check ((owner = 'user') = (owner_membership_id is not null)),
  check ((approver = 'role') = (approver_role_id is not null)),
  check ((approver = 'user') = (approver_membership_id is not null)),
  check (jsonb_typeof(applies_to) = 'object')
);

-- ── onboarding ──────────────────────────────────────────────────────

create table app.onboarding (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null,
  person_id         uuid not null,
  employment_id     uuid not null,
  flow_id           uuid not null,
  status            text not null default 'draft'
                    check (status in ('draft','offered','invited','in_progress','active','withdrawn','offer_declined')),
  buddy_id          uuid references app.membership(id),
  invite_due_on     date,                                 -- when invite_when schedules the invitation
  invited_at        timestamptz,
  notice_id         uuid,
  notice_accepted_at timestamptz,
  activation_requested_at timestamptz,                  -- pressed before the joining date; takes effect on it
  activated_at      timestamptz,
  withdrawn_at      timestamptz,
  withdrawn_reason  text,
  rehire_of         uuid,                                 -- an earlier person matched by the rehire check
  created_by        uuid references app.membership(id),
  created_at        timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, flow_id) references app.onboarding_flow (tenant_id, id),
  foreign key (tenant_id, notice_id) references app.privacy_notice (tenant_id, id),
  foreign key (tenant_id, rehire_of) references app.person (tenant_id, id),
  check ((notice_id is null) = (notice_accepted_at is null)),
  check ((status = 'active') = (activated_at is not null)),
  check ((status = 'withdrawn') = (withdrawn_at is not null)),
  check (status <> 'withdrawn' or length(btrim(withdrawn_reason)) >= 5)
);
create unique index onboarding_open_uq on app.onboarding (tenant_id, person_id)
  where status in ('draft','offered','invited','in_progress');

create table app.onboarding_section (
  tenant_id      uuid not null,
  onboarding_id  uuid not null,
  section        text not null check (section in
                   ('employment','personal','contact','address','emergency','family','bank','statutory','documents','policies','letters')),
  status         text not null default 'not_started'
                 check (status in ('not_started','draft','submitted','returned','verified')),
  level          smallint not null default 0 check (level between 0 and 3),   -- verification levels passed so far
  note           text,
  submitted_by   uuid references app.membership(id),
  submitted_at   timestamptz,
  primary key (onboarding_id, section),
  foreign key (tenant_id, onboarding_id) references app.onboarding (tenant_id, id),
  check (status <> 'returned' or note is not null),
  check (status not in ('submitted','returned','verified') or submitted_by is not null)
);

-- Every decision on a section, one row per level; the section's status is the latest of these.
create table app.section_review (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  onboarding_id  uuid not null,
  section        text not null,
  level          smallint not null check (level between 1 and 3),
  decision       text not null check (decision in ('verified','returned')),
  note           text,
  decided_by     uuid not null references app.membership(id),
  decided_at     timestamptz not null default now(),
  foreign key (onboarding_id, section) references app.onboarding_section (onboarding_id, section),
  foreign key (tenant_id, onboarding_id) references app.onboarding (tenant_id, id),
  check (decision <> 'returned' or note is not null)
);

create table app.onboarding_task (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  onboarding_id  uuid not null,
  template_task_id uuid,                                -- null for a one-off task
  title          text not null check (length(btrim(title)) between 1 and 120),
  instructions   text,
  owner          text not null check (owner in ('hr','it','manager','buddy','joiner','role','user')),
  assignee_id    uuid references app.membership(id),
  due_date       date,                                  -- null until its trigger event happens
  required       boolean not null default true,
  needs_attachment boolean not null default false,
  attachment_id  uuid,
  approver_id    uuid references app.membership(id),
  approved_at    timestamptz,
  remind_days_before smallint,
  repeat_every_days  smallint,
  last_reminded_at timestamptz,
  done_by        uuid references app.membership(id),
  done_at        timestamptz,
  skipped_reason text,
  position       smallint not null default 0,
  created_at     timestamptz not null default now(),
  foreign key (tenant_id, onboarding_id) references app.onboarding (tenant_id, id),
  foreign key (tenant_id, template_task_id) references app.checklist_template_task (tenant_id, id),
  foreign key (tenant_id, attachment_id) references app.document (tenant_id, id),
  check ((done_by is null) = (done_at is null)),
  check (skipped_reason is null or (done_at is not null and length(btrim(skipped_reason)) >= 5)),
  check (not needs_attachment or done_at is null or skipped_reason is not null or attachment_id is not null),
  check (approved_at is null or (approver_id is not null and done_at is not null))
);
create index onboarding_task_assignee on app.onboarding_task (assignee_id) where done_at is null;

-- In-app notifications made from the message templates.
create table app.notification (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  membership_id uuid not null,
  event         text not null,
  subject       text,
  body          text not null,
  link          text,
  created_at    timestamptz not null default now(),
  read_at       timestamptz,
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id)
);
create index notification_unread on app.notification (membership_id, created_at desc) where read_at is null;


-- ── row-level security, exactly as in V1, V3, V4 and V5 ─────────────

do $$
declare t text;
begin
  foreach t in array array['onboarding_settings','privacy_notice','onboarding_flow','verification_step','section_setting',
                           'field_setting','custom_field','custom_field_value','employee_number_series',
                           'employee_number_counter','letter_template','issued_letter','policy','policy_acknowledgement',
                           'message_template','checklist_template','checklist_template_task','onboarding',
                           'onboarding_section','section_review','onboarding_task','notification'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

-- ── grants ──────────────────────────────────────────────────────────
-- Configuration can be changed; notices, reviews and acknowledgements are add-only.
-- A joiner's onboarding keeps what it copied when it was created.
grant select, insert, update on app.onboarding_settings, app.onboarding_flow, app.section_setting, app.field_setting,
  app.custom_field, app.custom_field_value, app.employee_number_series, app.employee_number_counter,
  app.letter_template, app.issued_letter, app.policy, app.message_template, app.checklist_template,
  app.checklist_template_task, app.onboarding, app.onboarding_section, app.onboarding_task, app.notification to app_tenant;
grant delete on app.checklist_template_task, app.verification_step, app.field_setting to app_tenant;
grant select, insert on app.privacy_notice, app.verification_step, app.section_review, app.policy_acknowledgement to app_tenant;
grant select, insert, update, delete on all tables in schema app to app_definer;

-- ── permission ──────────────────────────────────────────────────────

insert into app.permission (code, description) values
  ('onboarding.configure', 'Change onboarding flows, forms, fields, checklists, letters, policies and messages');

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','onboarding.configure','tenant'),
  ('hr_admin','onboarding.configure','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null;
