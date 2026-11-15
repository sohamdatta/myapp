-- V63: which company account an Organization accepts for signing in with Google. domain is the Google Workspace domain (the
-- "hd" in the account's token), lower case. An Organization has at most one per provider. Nothing is accepted for an
-- Organization with no row. auth.allowed_provider_tenants answers, for a login, which companies its Organizations accept,
-- so the sign-in side can ask without reading another plane's tables. By hand: drop the function and the table.
set lock_timeout = '5s';

create table app.sign_in_provider (
  tenant_id  uuid not null references platform.tenant(id),
  provider   text not null check (provider in ('google','microsoft')),
  domain     text not null check (length(domain) between 3 and 253 and domain = lower(domain)),
  enabled_at timestamptz not null default now(),
  primary key (tenant_id, provider)
);

alter table app.sign_in_provider enable row level security;
alter table app.sign_in_provider force row level security;
create policy tenant_isolation on app.sign_in_provider
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update, delete on app.sign_in_provider to app_tenant;
grant select on app.sign_in_provider to app_definer;

create function auth.allowed_provider_tenants(p_identity uuid, p_provider text)
returns table (domain text)
language sql stable security definer set search_path = app, pg_temp as $$
  select distinct s.domain
    from app.sign_in_provider s
    join app.membership m on m.tenant_id = s.tenant_id
   where m.identity_id = p_identity
     and m.status = 'active'
     and s.provider = p_provider
$$;

grant create on schema auth to app_definer;   -- needed to own a function there; see V1 and V9
alter function auth.allowed_provider_tenants(uuid, text) owner to app_definer;
revoke create on schema auth from app_definer;
revoke all on function auth.allowed_provider_tenants(uuid, text) from public;
grant execute on function auth.allowed_provider_tenants(uuid, text) to app_auth;
