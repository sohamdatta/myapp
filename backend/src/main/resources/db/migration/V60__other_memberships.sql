-- V60: does this login belong to another Organization? Asked before an administrator turns off someone's two-step
-- sign-in, which belongs to the login and so reaches every Organization the person is in. Unlike
-- auth.memberships_for_identity this does not look at the other Organization's status, or at whether the person
-- holds a role there: a suspended member, or one in a suspended Organization, still relies on it once reactivated.
-- Only a membership that has ended (the person left) is ignored. Returns a number, never a name.

create function auth.other_memberships(p_identity uuid, p_tenant uuid)
returns bigint
language sql stable security definer set search_path = app, pg_temp as $$
  select count(*)
    from app.membership m
   where m.identity_id = p_identity
     and m.tenant_id <> p_tenant
     and m.status <> 'ended'
$$;

grant create on schema auth to app_definer;   -- needed to own a function there; see V1 and V9
alter function auth.other_memberships(uuid, uuid) owner to app_definer;
revoke create on schema auth from app_definer;
revoke all on function auth.other_memberships(uuid, uuid) from public;
grant execute on function auth.other_memberships(uuid, uuid) to app_auth;
