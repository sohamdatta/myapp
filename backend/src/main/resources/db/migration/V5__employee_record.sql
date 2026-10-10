-- Employee record: departments, the person, employment and dated job assignments,
-- addresses, contacts, family, bank accounts, statutory identifiers and documents.
-- Builds on V1 (users), V3 (attendance) and V4 (leave). Same rules: every table carries
-- tenant_id, has row-level security forced, and is reachable only by the app_tenant role
-- inside one Organization's transaction.
-- Values marked encrypted are AES-256-GCM ciphertext made by the application:
-- key id (1 byte) || nonce (12) || ciphertext || tag (16), with tenant_id as associated data.
-- The onboarding configuration and joiner tables follow in V6.
-- ── organization structure ──────────────────────────────────────────

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

-- ── the person ──────────────────────────────────────────────────────

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

-- ── employment ──────────────────────────────────────────────────────

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

-- ── bank and statutory identifiers ──────────────────────────────────

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

-- ── documents ───────────────────────────────────────────────────────

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

-- ── row-level security, exactly as in V1, V3 and V4 ─────────────────

do $$
declare t text;
begin
  foreach t in array array['department','person','person_address','emergency_contact','family_member','nomination',
                           'employment','job_assignment','bank_account','statutory_id','tax_regime_choice',
                           'document_type','document','document_content'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

-- ── grants ──────────────────────────────────────────────────────────
-- History tables are add-only: addresses, job assignments, bank accounts, nominations and
-- document contents are never updated or deleted by the application, only superseded.
grant select, insert, update on app.department, app.person, app.employment, app.emergency_contact,
  app.family_member, app.statutory_id, app.tax_regime_choice, app.document_type, app.document to app_tenant;
grant delete on app.emergency_contact to app_tenant;
grant select, insert on app.person_address, app.job_assignment, app.bank_account, app.nomination,
  app.document_content to app_tenant;
grant select, insert, update, delete on all tables in schema app to app_definer;

-- ── permission and the Joiner role ──────────────────────────────────

insert into app.permission (code, description) values
  ('employee.sensitive', 'See full PAN, bank account and statutory numbers; open employee documents');

insert into app.role (code, name, is_system, assignable) values
  ('joiner', 'Joiner', true, false);

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','employee.sensitive','tenant'),
  ('hr_admin','employee.sensitive','tenant'),
  ('payroll_operator','employee.sensitive','assigned'),
  ('employee','employee.sensitive','self'),
  ('joiner','employee.read','self'), ('joiner','employee.write','self'), ('joiner','employee.sensitive','self')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null;
