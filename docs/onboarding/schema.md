# Schema

`V5__employee_onboarding.sql` loads on PostgreSQL 16 on top of V1 to V4 at commit `ebbeff0`. It has two parts: the employee record, and the configuration and onboarding tables. 20 checks passed against it as the application role. Record: a second live employment for one person, a full Aadhaar value, a PAN without its encrypted value, a malformed IFSC, an update to a bank account and a fixed-term employment without an end date are all refused. Configuration: rules that are not an object, a days-before invitation without the number of days, switching off the Personal section, a choice field without choices, a second Organization default for one field, a task owned by a role with no role named, a task needing an attachment marked done without one, a custom value both plain and encrypted, and a returned section without a note are all refused. Isolation: another Organization sees no people and no flows, cannot write this Organization's tenant id or point at its people, and nothing is visible without a tenant.

## Part 1: the employee record

```sql
-- Employee record and onboarding. Builds on V1 (users), V3 (attendance) and V4 (leave).
-- Same rules: every table carries tenant_id, has row-level security forced,
-- and is reachable only by the app_tenant role inside one Organization's transaction.
-- Values marked encrypted are AES-256-GCM ciphertext made by the application:
-- key id (1 byte) || nonce (12) || ciphertext || tag (16), with tenant_id as associated data.

-- ── organization structure ──

create table app.department (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  name        text not null check (length(btrim(name)) between 1 and 80),
  parent_id   uuid,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, parent_id) references app.department (tenant_id, id),
  check (parent_id is null or parent_id <> id)       -- longer loops are refused by the application
);
create unique index department_name_uq on app.department (tenant_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

-- ── the person ──

create table app.person (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  legal_name      text not null check (length(btrim(legal_name)) between 1 and 120),
  preferred_name  text check (length(preferred_name) <= 60),
  date_of_birth   date,
  gender          text check (gender in ('female','male','transgender','undisclosed')),
  marital_status  text check (marital_status in ('single','married','widowed','divorced','separated')),
  nationality     text not null default 'IN' check (nationality ~ '^[A-Z]{2}$'),
  blood_group     text check (blood_group in ('A+','A-','B+','B-','AB+','AB-','O+','O-')),
  personal_email  citext,
  mobile          text check (mobile ~ '^[6-9][0-9]{9}$'),
  alt_mobile      text check (alt_mobile ~ '^[0-9]{10,15}$'),
  photo_document_id uuid,                              -- set once the photo is uploaded
  created_at      timestamptz not null default now(),
  unique (tenant_id, id)
);

-- V1 reserved membership.person_id for this table
alter table app.membership
  add constraint membership_person_fk foreign key (tenant_id, person_id) references app.person (tenant_id, id);
alter table app.invitation
  add constraint invitation_person_fk foreign key (tenant_id, person_id) references app.person (tenant_id, id);

create table app.person_address (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  person_id      uuid not null,
  kind           text not null check (kind in ('current','permanent')),
  line1          text not null check (length(btrim(line1)) between 1 and 120),
  line2          text check (length(line2) <= 120),
  city           text not null check (length(btrim(city)) between 1 and 60),
  state_code     text not null check (state_code ~ '^[A-Z]{2}$'),   -- ISO 3166-2:IN subdivision, e.g. WB, KA
  pin            text check (pin ~ '^[1-9][0-9]{5}$'),
  country        text not null default 'IN' check (country ~ '^[A-Z]{2}$'),
  effective_from date not null,
  created_by     uuid references app.membership(id),
  created_at     timestamptz not null default now(),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  check (country <> 'IN' or pin is not null)
);
create index person_address_person on app.person_address (person_id, kind, effective_from desc, created_at desc);

create table app.emergency_contact (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null,
  person_id    uuid not null,
  name         text not null check (length(btrim(name)) between 1 and 120),
  relationship text not null check (length(btrim(relationship)) between 1 and 40),
  mobile       text not null check (mobile ~ '^[0-9]{10,15}$'),
  position     smallint not null default 1 check (position between 1 and 3),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  unique (person_id, position)
);

create table app.family_member (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null,
  person_id     uuid not null,
  name          text not null check (length(btrim(name)) between 1 and 120),
  relationship  text not null check (relationship in
                  ('spouse','son','daughter','father','mother','father_in_law','mother_in_law','brother','sister','other')),
  date_of_birth date,
  dependent     boolean not null default false,
  removed_at    timestamptz,                            -- kept for nominations made while they were listed
  unique (tenant_id, id),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id)
);

create table app.nomination (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null,
  person_id        uuid not null,
  scheme           text not null check (scheme in ('pf','gratuity','insurance')),
  family_member_id uuid not null,
  share_pct        numeric(5,2) not null check (share_pct > 0 and share_pct <= 100),
  effective_from   date not null,
  created_at       timestamptz not null default now(),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  foreign key (tenant_id, family_member_id) references app.family_member (tenant_id, id)
);
-- shares of one scheme's latest nomination set adding up to 100 are checked by the application

-- ── employment ──

create table app.employment (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null,
  person_id        uuid not null,
  membership_id    uuid,                                -- null until the joiner has a login
  legal_entity_id  uuid,                                -- multiple legal entities come later
  employee_code    text check (length(btrim(employee_code)) between 1 and 30),
  employment_type  text not null check (employment_type in ('permanent','probation','fixed_term','intern','consultant')),
  joining_date     date not null,
  probation_end    date,
  contract_end     date,
  status           text not null default 'onboarding' check (status in ('onboarding','active','exited','withdrawn')),
  activated_at     timestamptz,
  exit_date        date,
  exit_reason      text,
  created_by       uuid references app.membership(id),
  created_at       timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id),
  check (probation_end is null or probation_end > joining_date),
  check (contract_end is null or contract_end >= joining_date),
  check (employment_type <> 'fixed_term' or contract_end is not null),
  check ((status = 'exited') = (exit_date is not null)),
  check (exit_date is null or exit_date >= joining_date),
  check ((status in ('active','exited')) = (activated_at is not null))
);
-- one live stint per person; a rehire is a second row once the first has ended
create unique index employment_live_uq on app.employment (tenant_id, person_id) where status in ('onboarding','active');
create unique index employment_code_uq on app.employment (tenant_id, lower(employee_code))
  where employee_code is not null and status in ('onboarding','active');

create table app.job_assignment (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  employment_id  uuid not null,
  effective_from date not null,
  designation    text not null check (length(btrim(designation)) between 1 and 80),
  department_id  uuid,
  manager_id     uuid,                                  -- the manager's employment
  reason         text,
  created_by     uuid references app.membership(id),
  created_at     timestamptz not null default now(),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  foreign key (tenant_id, department_id) references app.department (tenant_id, id),
  foreign key (tenant_id, manager_id) references app.employment (tenant_id, id),
  check (manager_id is null or manager_id <> employment_id)   -- longer loops are refused by the application
);
create index job_assignment_employment on app.job_assignment (employment_id, effective_from desc, created_at desc);
create index job_assignment_manager on app.job_assignment (manager_id);

-- ── bank and statutory identifiers ──

create table app.bank_account (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null,
  person_id       uuid not null,
  holder_name     text not null check (length(btrim(holder_name)) between 1 and 120),
  account_enc     bytea not null,                       -- encrypted account number
  account_last4   text not null check (account_last4 ~ '^[0-9]{4}$'),
  ifsc            text not null check (ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
  bank_name       text,
  branch          text,
  effective_from  date not null,
  verified_by     uuid references app.membership(id),
  verified_at     timestamptz,
  created_by      uuid references app.membership(id),
  created_at      timestamptz not null default now(),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  check ((verified_by is null) = (verified_at is null))
);
create index bank_account_person on app.bank_account (person_id, effective_from desc, created_at desc);

create table app.statutory_id (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null,
  person_id    uuid not null,
  kind         text not null check (kind in ('pan','aadhaar','uan','pf_member','esi_ip')),
  value_enc    bytea,                                   -- encrypted; never set for Aadhaar
  value_hash   bytea,                                   -- HMAC of the value, to check a value is not already used by someone else
  last4        text not null check (last4 ~ '^[0-9A-Z]{4}$'),
  pending_reason text,                                  -- e.g. PAN applied for
  verified_by  uuid references app.membership(id),
  verified_at  timestamptz,
  replaced_at  timestamptz,                             -- a correction adds a row and marks the old one
  created_by   uuid references app.membership(id),
  created_at   timestamptz not null default now(),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  check (kind <> 'aadhaar' or value_enc is null),       -- only the last four digits of Aadhaar are kept
  check (kind = 'aadhaar' or value_enc is not null),
  check ((verified_by is null) = (verified_at is null))
);
create unique index statutory_id_current_uq on app.statutory_id (person_id, kind) where replaced_at is null;
create unique index statutory_id_value_uq on app.statutory_id (tenant_id, kind, value_hash)
  where replaced_at is null and value_hash is not null;

create table app.tax_regime_choice (
  tenant_id  uuid not null,
  person_id  uuid not null,
  tax_year   text not null check (tax_year ~ '^[0-9]{4}-[0-9]{2}$'),   -- 2026-27
  regime     text not null check (regime in ('new','old')),
  chosen_at  timestamptz not null default now(),
  primary key (person_id, tax_year),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id)
);

-- ── documents ──

create table app.document_type (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  name             text not null check (length(btrim(name)) between 1 and 80),
  required         text not null default 'optional' check (required in ('before_activation','within_days','optional')),
  within_days      smallint check (within_days between 1 and 365),
  instructions     text,
  applies_to       jsonb not null default '{}',         -- see the note on applies_to below
  position         smallint not null default 0,
  active           boolean not null default true,
  unique (tenant_id, id),
  check ((required = 'within_days') = (within_days is not null)),
  check (jsonb_typeof(applies_to) = 'object')
);
create unique index document_type_name_uq on app.document_type (tenant_id, lower(name));

create table app.document (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null,
  person_id      uuid not null,
  document_type_id uuid,
  file_name      text not null check (length(file_name) between 1 and 200),
  content_type   text not null check (content_type in ('application/pdf','image/jpeg','image/png')),
  size_bytes     int not null check (size_bytes between 1 and 10485760),
  sha256         bytea not null check (length(sha256) = 32),
  status         text not null default 'uploaded' check (status in ('uploaded','verified','rejected','superseded')),
  decision_note  text,
  decided_by     uuid references app.membership(id),
  decided_at     timestamptz,
  uploaded_by    uuid references app.membership(id),
  created_at     timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, person_id) references app.person (tenant_id, id),
  foreign key (tenant_id, document_type_id) references app.document_type (tenant_id, id),
  check (status <> 'rejected' or decision_note is not null)
);
create index document_person on app.document (person_id, created_at desc);

alter table app.person
  add constraint person_photo_fk foreign key (tenant_id, photo_document_id) references app.document (tenant_id, id);

create table app.document_content (
  document_id  uuid primary key,
  tenant_id    uuid not null,
  content_enc  bytea not null,                          -- encrypted file bytes
  foreign key (tenant_id, document_id) references app.document (tenant_id, id)
);
```

## Part 2: configuration and onboarding

```sql
-- ── configuration: everything below is set per Organization ──
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

-- ── onboarding ──

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

-- ── row-level security, exactly as in V1, V3 and V4 ──

do $$
declare t text;
begin
  foreach t in array array['department','person','person_address','emergency_contact','family_member','nomination',
                           'employment','job_assignment','bank_account','statutory_id','tax_regime_choice',
                           'document_type','document','document_content','onboarding_settings','privacy_notice',
                           'onboarding_flow','verification_step','section_setting','field_setting','custom_field',
                           'custom_field_value','employee_number_series','employee_number_counter','letter_template',
                           'issued_letter','policy','policy_acknowledgement','message_template','checklist_template',
                           'checklist_template_task','onboarding','onboarding_section','section_review',
                           'onboarding_task','notification'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

-- ── grants ──
-- History tables are add-only: addresses, job assignments, bank accounts, nominations,
-- documents and notices are never updated or deleted by the application, only superseded.
-- Configuration tables can be changed; a joiner's own onboarding keeps what it copied when it was created.
grant select, insert, update on app.department, app.person, app.employment, app.emergency_contact,
  app.family_member, app.statutory_id, app.tax_regime_choice, app.document_type, app.document,
  app.onboarding_settings, app.onboarding_flow, app.section_setting, app.field_setting, app.custom_field,
  app.custom_field_value, app.employee_number_series, app.employee_number_counter, app.letter_template,
  app.issued_letter, app.policy, app.message_template, app.checklist_template, app.checklist_template_task,
  app.onboarding, app.onboarding_section, app.onboarding_task, app.notification to app_tenant;
grant delete on app.emergency_contact, app.checklist_template_task, app.verification_step, app.field_setting to app_tenant;
grant select, insert on app.person_address, app.job_assignment, app.bank_account, app.nomination,
  app.document_content, app.privacy_notice, app.verification_step, app.section_review,
  app.policy_acknowledgement to app_tenant;
grant select, insert, update, delete on all tables in schema app to app_definer;

-- ── permissions and the Joiner role ──

insert into app.permission (code, description) values
  ('employee.sensitive', 'See full PAN, bank account and statutory numbers; open employee documents'),
  ('onboarding.configure', 'Change onboarding flows, forms, fields, checklists, letters, policies and messages');

insert into app.role (code, name, is_system, assignable) values
  ('joiner', 'Joiner', true, false);

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','employee.sensitive','tenant'), ('owner','onboarding.configure','tenant'),
  ('hr_admin','employee.sensitive','tenant'), ('hr_admin','onboarding.configure','tenant'),
  ('payroll_operator','employee.sensitive','assigned'),
  ('employee','employee.sensitive','self'),
  ('joiner','employee.read','self'), ('joiner','employee.write','self'), ('joiner','employee.sensitive','self')
) as v(role, perm, scope)
join app.role r on r.code = v.role;
```
