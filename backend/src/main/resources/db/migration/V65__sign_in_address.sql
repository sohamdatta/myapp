-- V65: an Organization's own sign-in address. Its slug is the first part of the address, such as acme.hr.example.com, where
-- the sign-in page shows the Organization's name and logo and lets in only its members. The slug is set by the Super Admin
-- when the Organization is created; app.set_organization_slug lets the Org Admin change it, since the tenant role cannot
-- write the platform plane. The address is unique already (platform.tenant.slug). By hand: drop the function.
set lock_timeout = '5s';

create function app.set_organization_slug(p_slug text)
returns text
language plpgsql security definer set search_path = app, platform, pg_temp as $$
declare
  v_old text;
begin
  if app.current_tenant() is null then
    raise exception 'no Organization is set for this transaction' using errcode = 'P0001';
  end if;
  select t.slug into v_old from platform.tenant t where t.id = app.current_tenant() for update;
  if not found then
    raise exception 'no such Organization' using errcode = 'P0001';
  end if;
  update platform.tenant set slug = p_slug where id = app.current_tenant();
  return v_old;
end
$$;

grant create on schema app to app_definer;   -- needed to own a function there; see V1 and V43
alter function app.set_organization_slug(text) owner to app_definer;
revoke create on schema app from app_definer;
revoke all on function app.set_organization_slug(text) from public;
grant execute on function app.set_organization_slug(text) to app_tenant;
