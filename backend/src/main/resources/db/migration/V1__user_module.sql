-- User module schema. Tested on PostgreSQL 16. See README for the design.
-- Run by the application's migrator as the database owner.

-- roles and schemas
-- Roles are cluster-wide, so create them only if they are missing.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'app_platform') then
    create role app_platform login nobypassrls;   -- platform console pool
  end if;
  if not exists (select 1 from pg_roles where rolname = 'app_auth') then
    create role app_auth login nobypassrls;       -- sign-in, sessions, invitations
  end if;
  if not exists (select 1 from pg_roles where rolname = 'app_tenant') then
    create role app_tenant login nobypassrls;     -- everything inside an Organization
  end if;
  if not exists (select 1 from pg_roles where rolname = 'app_definer') then
    create role app_definer nologin bypassrls;    -- owns the few SECURITY DEFINER functions
  end if;
end $$;

create schema platform;   -- control plane: no employee data
create schema auth;       -- identity plane: logins and sessions
create schema app;        -- tenant plane: tenant_id + FORCE RLS on every table
create extension if not exists citext;

-- identity plane
create table auth.identity (
  id                 uuid primary key default gen_random_uuid(),
  email              citext not null unique,
  password_hash      text,                          -- argon2id; null until first set
  mfa_secret_enc     bytea,                         -- TOTP secret, encrypted
  mfa_enrolled_at    timestamptz,
  status             text not null default 'active'
                     check (status in ('active','locked','disabled')),
  failed_login_count int  not null default 0,
  locked_until       timestamptz,
  last_login_at      timestamptz,
  created_at         timestamptz not null default now()
);

create table auth.mfa_recovery_code (
  identity_id uuid not null references auth.identity(id),
  code_hash   bytea not null,
  used_at     timestamptz,
  primary key (identity_id, code_hash)
);

create table auth.password_reset (
  id          uuid primary key default gen_random_uuid(),
  identity_id uuid not null references auth.identity(id),
  token_hash  bytea not null unique,
  expires_at  timestamptz not null,                 -- 30 minutes
  used_at     timestamptz
);

-- control plane
create table platform.platform_user (
  id          uuid primary key default gen_random_uuid(),
  identity_id uuid not null unique references auth.identity(id),
  role        text not null check (role in ('super_admin','support','billing')),
  status      text not null default 'active' check (status in ('active','suspended')),
  created_by  uuid references platform.platform_user(id),   -- null for the seeded first Super Admin
  created_at  timestamptz not null default now()
);

create table platform.tenant (
  id           uuid primary key default gen_random_uuid(),
  name         text not null,
  slug         text not null unique check (slug ~ '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$'),
  status       text not null default 'provisioning'
               check (status in ('provisioning','active','suspended','closed')),
  plan_code    text not null,
  created_by   uuid not null references platform.platform_user(id),
  created_at   timestamptz not null default now(),
  suspended_at timestamptz,
  closed_at    timestamptz
);

create table platform.impersonation_grant (
  id               uuid primary key default gen_random_uuid(),
  platform_user_id uuid not null references platform.platform_user(id),   -- requester
  tenant_id        uuid not null references platform.tenant(id),
  reason           text not null check (length(btrim(reason)) >= 10),
  mode             text not null default 'read_only' check (mode in ('read_only','write')),
  status           text not null default 'requested'
                   check (status in ('requested','approved','rejected','revoked')),
  approved_by      uuid references platform.platform_user(id),
  auto_approved    boolean not null default false,   -- only while one approver exists
  requested_at     timestamptz not null default now(),
  granted_at       timestamptz,
  expires_at       timestamptz,                      -- granted_at + 60 minutes by default
  revoked_at       timestamptz,
  check (approved_by is null or approved_by <> platform_user_id or auto_approved),
  check (status <> 'approved' or (approved_by is not null and granted_at is not null and expires_at is not null))
);

create table platform.platform_audit (
  id               bigint generated always as identity primary key,
  platform_user_id uuid references platform.platform_user(id),
  action           text not null,
  target_type      text,
  target_id        uuid,
  detail           jsonb not null default '{}',
  ip               inet,
  at               timestamptz not null default now()
);

create table auth.session (
  id                     uuid primary key default gen_random_uuid(),
  identity_id            uuid not null references auth.identity(id),
  kind                   text not null check (kind in ('identity','tenant','platform','support')),
  tenant_id              uuid references platform.tenant(id),
  membership_id          uuid,
  impersonation_grant_id uuid references platform.impersonation_grant(id),
  refresh_token_hash     bytea not null unique,
  mfa_at                 timestamptz,
  issued_at              timestamptz not null default now(),
  last_seen_at           timestamptz not null default now(),
  expires_at             timestamptz not null,
  revoked_at             timestamptz,
  ip                     inet,
  user_agent             text,
  check (
    (kind in ('identity','platform') and tenant_id is null and membership_id is null and impersonation_grant_id is null) or
    (kind = 'tenant'   and tenant_id is not null and membership_id is not null and impersonation_grant_id is null) or
    (kind = 'support'  and tenant_id is not null and membership_id is null and impersonation_grant_id is not null)
  )
);
create index session_membership_idx on auth.session (membership_id) where revoked_at is null;

-- tenant plane
create table app.membership (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  identity_id   uuid not null references auth.identity(id),
  person_id     uuid,                    -- FK to app.person is added with Core HR
  status        text not null default 'active'
                check (status in ('active','suspended','ended')),
  scope_version int  not null default 1,
  invited_by    uuid references app.membership(id),   -- null = invited by the platform
  joined_at     timestamptz not null default now(),
  suspended_at  timestamptz,
  ended_at      timestamptz,
  unique (tenant_id, identity_id),
  unique (tenant_id, id)
);
create unique index membership_person_uq
  on app.membership (tenant_id, person_id) where person_id is not null;

create table app.role (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid references platform.tenant(id),   -- null = system role, shared by all tenants
  code       text not null,
  name       text not null,
  is_system  boolean not null default false,
  assignable boolean not null default true,         -- false for the two support roles
  check (is_system = (tenant_id is null))
);
create unique index role_code_uq
  on app.role (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

create table app.permission (
  code        text primary key,
  description text not null
);

create table app.role_permission (
  role_id         uuid not null references app.role(id),
  permission_code text not null references app.permission(code),
  scope           text not null check (scope in ('self','team','assigned','tenant')),
  primary key (role_id, permission_code)
);

create table app.membership_role (
  tenant_id     uuid not null,
  membership_id uuid not null,
  role_id       uuid not null references app.role(id),
  granted_by    uuid references app.membership(id),  -- null = granted by the system
  granted_at    timestamptz not null default now(),
  primary key (membership_id, role_id),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id)
);

create table app.access_scope (                       -- enforcement deferred
  tenant_id     uuid not null,
  membership_id uuid not null,
  scope_type    text not null check (scope_type in ('legal_entity','work_location','org_unit')),
  scope_id      uuid not null,
  primary key (membership_id, scope_type, scope_id),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id)
);

create table app.invitation (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  email       citext not null,
  person_id   uuid,
  role_ids    uuid[] not null check (cardinality(role_ids) >= 1),
  token_hash  bytea not null unique,                  -- SHA-256 of a 32-byte random token
  invited_by  uuid references app.membership(id),     -- null = first Org Admin, invited by the platform
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,                   -- created_at + 7 days
  accepted_at timestamptz,
  revoked_at  timestamptz
);
create unique index invitation_pending_uq
  on app.invitation (tenant_id, email) where accepted_at is null and revoked_at is null;

create table app.audit_event (
  id                     bigint generated always as identity primary key,
  tenant_id              uuid not null references platform.tenant(id),
  actor_kind             text not null check (actor_kind in ('member','support','system')),
  actor_membership_id    uuid,
  actor_label            text not null,                 -- email of the member or platform user
  impersonation_grant_id uuid,
  action                 text not null,
  target_type            text,
  target_id              uuid,
  detail                 jsonb not null default '{}',
  at                     timestamptz not null default now()
);
create index audit_event_tenant_at on app.audit_event (tenant_id, at desc);

-- row-level security
-- current_setting(..., true) is NULL when never set and '' after a previous
-- transaction's SET LOCAL on a pooled connection; both must match nothing.
create function app.current_tenant() returns uuid language sql stable as
$$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

do $$
declare t text;
begin
  foreach t in array array['membership','membership_role','access_scope','invitation','audit_event'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

alter table app.role enable row level security;
alter table app.role force row level security;
create policy role_read on app.role for select
  using (tenant_id is null or tenant_id = app.current_tenant());

-- integrity triggers (SECURITY DEFINER so they see the truth under RLS)
create function app.bump_scope_version() returns trigger
language plpgsql security definer set search_path = app, pg_temp as $$
begin
  if tg_op = 'DELETE' then
    update app.membership set scope_version = scope_version + 1 where id = old.membership_id;
    return old;
  end if;
  update app.membership set scope_version = scope_version + 1 where id = new.membership_id;
  return new;
end $$;

create trigger membership_role_bump after insert or delete on app.membership_role
  for each row execute function app.bump_scope_version();
create trigger access_scope_bump after insert or delete on app.access_scope
  for each row execute function app.bump_scope_version();

create function app.bump_on_status_change() returns trigger
language plpgsql as $$
begin
  if new.status is distinct from old.status then
    new.scope_version := old.scope_version + 1;
  end if;
  return new;
end $$;
create trigger membership_status_bump before update on app.membership
  for each row execute function app.bump_on_status_change();

-- maker-checker: one membership cannot hold both payroll roles
create function app.assert_not_operator_and_approver() returns trigger
language plpgsql security definer set search_path = app, pg_temp as $$
begin
  if (select count(*) from app.membership_role mr
        join app.role r on r.id = mr.role_id
       where mr.membership_id = new.membership_id
         and r.code in ('payroll_operator','payroll_approver')) > 1 then
    raise exception 'membership % cannot hold both payroll_operator and payroll_approver',
      new.membership_id using errcode = '23514';
  end if;
  return new;
end $$;
create constraint trigger membership_role_maker_checker
  after insert on app.membership_role
  deferrable initially deferred
  for each row execute function app.assert_not_operator_and_approver();

-- an Organization that has had an Org Admin always keeps at least one active one
create function app.assert_tenant_has_owner() returns trigger
language plpgsql security definer set search_path = app, pg_temp as $$
declare t uuid;
begin
  if tg_op = 'DELETE' then t := old.tenant_id; else t := new.tenant_id; end if;
  if not exists (
    select 1 from app.membership m
      join app.membership_role mr on mr.membership_id = m.id
      join app.role r on r.id = mr.role_id
     where m.tenant_id = t and m.status = 'active' and r.code = 'owner') then
    raise exception 'tenant % must keep at least one active owner', t using errcode = '23514';
  end if;
  return null;
end $$;
create constraint trigger last_owner_guard_role
  after delete on app.membership_role
  deferrable initially deferred
  for each row execute function app.assert_tenant_has_owner();
create constraint trigger last_owner_guard_status
  after update of status on app.membership
  deferrable initially deferred
  for each row execute function app.assert_tenant_has_owner();

-- functions that cross a plane boundary
-- sign-in: which Organizations can this identity enter?
create function auth.memberships_for_identity(p_identity uuid)
returns table (tenant_id uuid, tenant_name text, tenant_slug text, membership_id uuid, status text)
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select t.id, t.name, t.slug, m.id, m.status
    from app.membership m
    join platform.tenant t on t.id = m.tenant_id
   where m.identity_id = p_identity
     and m.status in ('active','ended')
     and t.status = 'active'
     and exists (select 1 from app.membership_role mr where mr.membership_id = m.id)
$$;

-- accept an invitation: creates (or reactivates) the membership and its roles
create function auth.accept_invitation(p_token_hash bytea, p_identity uuid)
returns table (tenant_id uuid, membership_id uuid)
language plpgsql security definer set search_path = app, platform, auth, pg_temp as $$
declare
  inv app.invitation;
  mid uuid;
begin
  select * into inv from app.invitation i
   where i.token_hash = p_token_hash
     and i.accepted_at is null and i.revoked_at is null and i.expires_at > now()
   for update;
  if not found then
    raise exception 'invitation is invalid, expired or already used' using errcode = 'P0001';
  end if;
  if not exists (select 1 from auth.identity i where i.id = p_identity and i.email = inv.email and i.status = 'active') then
    raise exception 'invitation was issued to a different email' using errcode = 'P0001';
  end if;
  if not exists (select 1 from platform.tenant t where t.id = inv.tenant_id and t.status in ('provisioning','active')) then
    raise exception 'organization is not open' using errcode = 'P0001';
  end if;

  insert into app.membership (tenant_id, identity_id, person_id, invited_by)
  values (inv.tenant_id, p_identity, inv.person_id, inv.invited_by)
  on conflict on constraint membership_tenant_id_identity_id_key do update
     set status = 'active', ended_at = null, joined_at = now(),
         person_id = coalesce(excluded.person_id, app.membership.person_id)
   where app.membership.status = 'ended'
  returning id into mid;
  if mid is null then
    raise exception 'this login already has a membership in the organization' using errcode = 'P0001';
  end if;

  delete from app.membership_role mr where mr.membership_id = mid;      -- rehire: drop ex_employee
  insert into app.membership_role (tenant_id, membership_id, role_id, granted_by)
  select inv.tenant_id, mid, r, inv.invited_by from unnest(inv.role_ids) r;

  update app.invitation set accepted_at = now() where id = inv.id;
  update platform.tenant set status = 'active' where id = inv.tenant_id and status = 'provisioning';
  return query select inv.tenant_id, mid;
end $$;

-- invitation page: what is being offered, without revealing anything else
create function auth.invitation_preview(p_token_hash bytea)
returns table (tenant_name text, email citext, role_names text[], identity_exists boolean, expires_at timestamptz)
language sql stable security definer set search_path = app, platform, auth, pg_temp as $$
  select t.name, i.email,
         array(select r.name from app.role r where r.id = any(i.role_ids) order by r.name),
         exists (select 1 from auth.identity x where x.email = i.email),
         i.expires_at
    from app.invitation i join platform.tenant t on t.id = i.tenant_id
   where i.token_hash = p_token_hash
     and i.accepted_at is null and i.revoked_at is null and i.expires_at > now()
     and t.status in ('provisioning','active')
$$;

-- platform console: invite the first Org Admin of a new Organization
create function platform.invite_first_owner(p_tenant uuid, p_email citext, p_token_hash bytea)
returns uuid
language plpgsql security definer set search_path = app, platform, pg_temp as $$
declare inv uuid;
begin
  if exists (select 1 from app.membership m where m.tenant_id = p_tenant) then
    raise exception 'organization already has members; its Org Admins manage invitations' using errcode = 'P0001';
  end if;
  update app.invitation set revoked_at = now()
   where tenant_id = p_tenant and accepted_at is null and revoked_at is null;   -- resend replaces
  insert into app.invitation (tenant_id, email, role_ids, token_hash, expires_at)
  select p_tenant, p_email, array[r.id], p_token_hash, now() + interval '7 days'
    from app.role r where r.code = 'owner' and r.tenant_id is null
  returning id into inv;
  return inv;
end $$;

-- platform console: counts only, never rows
create function platform.tenant_stats(p_tenant uuid)
returns table (active_members bigint, pending_invitations bigint, owners bigint)
language sql stable security definer set search_path = app, pg_temp as $$
  select
    (select count(*) from app.membership m where m.tenant_id = p_tenant and m.status = 'active'),
    (select count(*) from app.invitation i where i.tenant_id = p_tenant and i.accepted_at is null and i.revoked_at is null),
    (select count(*) from app.membership m join app.membership_role mr on mr.membership_id = m.id
       join app.role r on r.id = mr.role_id
      where m.tenant_id = p_tenant and m.status = 'active' and r.code = 'owner')
$$;

-- user list: emails of this Organization's members only
create function app.member_directory()
returns table (membership_id uuid, email citext, mfa_enrolled boolean, last_login_at timestamptz)
language sql stable security definer set search_path = app, auth, pg_temp as $$
  select m.id, i.email, i.mfa_enrolled_at is not null, i.last_login_at
    from app.membership m join auth.identity i on i.id = m.identity_id
   where m.tenant_id = app.current_tenant()
$$;

-- effective permissions of a membership (suspended memberships have none)
create function app.effective_permissions(p_membership uuid)
returns table (permission_code text, scope text)
language sql stable as $$
  select distinct rp.permission_code, rp.scope
    from app.membership m
    join app.membership_role mr on mr.membership_id = m.id
    join app.role_permission rp on rp.role_id = mr.role_id
   where m.id = p_membership and m.status in ('active','ended')
$$;

-- may this member grant this role? Every non-self permission of the role must
-- already be held by the grantor at the same scope or at tenant scope.
create function app.can_grant(p_grantor uuid, p_role uuid) returns boolean
language sql stable as $$
  select exists (select 1 from app.effective_permissions(p_grantor) g where g.permission_code = 'role.assign')
     and exists (select 1 from app.role r where r.id = p_role and r.assignable)
     and not exists (
       select 1 from app.role_permission rp
        where rp.role_id = p_role and rp.scope <> 'self'
          and not exists (
            select 1 from app.effective_permissions(p_grantor) g
             where g.permission_code = rp.permission_code
               and (g.scope = 'tenant' or g.scope = rp.scope)))
$$;

-- grants
alter function app.bump_scope_version()               owner to app_definer;
alter function app.assert_not_operator_and_approver() owner to app_definer;
alter function app.assert_tenant_has_owner()          owner to app_definer;
alter function auth.memberships_for_identity(uuid)    owner to app_definer;
alter function auth.accept_invitation(bytea, uuid)    owner to app_definer;
alter function platform.invite_first_owner(uuid, citext, bytea) owner to app_definer;
alter function auth.invitation_preview(bytea)        owner to app_definer;
alter function platform.tenant_stats(uuid)            owner to app_definer;
alter function app.member_directory()                 owner to app_definer;

grant usage on schema app, auth, platform to app_definer;
grant select, insert, update, delete on all tables in schema app to app_definer;
grant select on auth.identity to app_definer;
grant select, update on platform.tenant to app_definer;

revoke execute on all functions in schema app, auth, platform from public;

grant usage on schema platform to app_platform;
grant select, insert, update on platform.platform_user, platform.tenant, platform.impersonation_grant to app_platform;
grant select, insert on platform.platform_audit to app_platform;          -- append only
grant usage on schema auth to app_platform;
grant select (id, email, status) on auth.identity to app_platform;
grant execute on function platform.invite_first_owner(uuid, citext, bytea), platform.tenant_stats(uuid) to app_platform;

grant usage on schema auth, platform to app_auth;
grant select, insert, update on all tables in schema auth to app_auth;
grant select on platform.tenant, platform.platform_user, platform.impersonation_grant to app_auth;
grant execute on function auth.memberships_for_identity(uuid), auth.accept_invitation(bytea, uuid),
  auth.invitation_preview(bytea) to app_auth;

grant usage on schema app to app_tenant;
grant select, insert, update on app.membership, app.invitation to app_tenant;   -- no delete
grant select, insert, delete on app.membership_role, app.access_scope to app_tenant;
grant select, insert on app.audit_event to app_tenant;                          -- append only
grant select on app.role, app.permission, app.role_permission to app_tenant;     -- custom roles deferred
grant execute on function app.current_tenant(), app.member_directory(),
  app.effective_permissions(uuid), app.can_grant(uuid, uuid), app.bump_on_status_change() to app_tenant;
grant execute on function app.current_tenant() to app_definer, app_auth, app_platform;
