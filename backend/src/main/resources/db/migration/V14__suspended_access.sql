-- V14: tell a suspended user which Organization suspended their access, instead of
-- "you do not belong to an Organization". Names only; entering stays refused.

create function auth.suspended_memberships_for_identity(p_identity uuid)
returns table (tenant_name text)
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select t.name
    from app.membership m
    join platform.tenant t on t.id = m.tenant_id
   where m.identity_id = p_identity
     and m.status = 'suspended'
     and t.status = 'active'
$$;

alter function auth.suspended_memberships_for_identity(uuid) owner to app_definer;
revoke all on function auth.suspended_memberships_for_identity(uuid) from public;
grant execute on function auth.suspended_memberships_for_identity(uuid) to app_auth;
