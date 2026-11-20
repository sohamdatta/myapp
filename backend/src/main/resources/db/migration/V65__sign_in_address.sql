-- V65: an Organization's own sign-in address. Its slug is the first part of the address, such as acme.hr.example.com, where
-- the sign-in page shows the Organization's name and logo and lets in only its members. The slug is set by the Super Admin
-- when the Organization is created; app.set_organization_slug lets the Org Admin change it, since the tenant role cannot
-- write the platform plane. The address is unique already (platform.tenant.slug).
--
-- A slug given up is held for 90 days (platform.retired_slug): people still have the old address bookmarked, and another
-- Organization must not appear there with its own name and logo. The Organization that gave it up can take it back.
-- The trigger holds it against both ways a slug is set: creating an Organization and changing one.
--
-- By hand: drop the trigger, the two functions and the table.
set lock_timeout = '5s';

create table platform.retired_slug (
  slug       text primary key,
  tenant_id  uuid not null references platform.tenant(id),
  retired_at timestamptz not null default now()
);

-- Refuses a slug another Organization gave up less than 90 days ago, and records the one given up on a change.
create function platform.hold_retired_slug()
returns trigger
language plpgsql security definer set search_path = platform, pg_temp as $$
begin
  if tg_op = 'UPDATE' and new.slug = old.slug then
    return new;
  end if;
  if exists (select 1 from platform.retired_slug r
              where r.slug = new.slug and r.tenant_id <> new.id and r.retired_at > now() - interval '90 days') then
    raise exception 'slug held: % was given up by another Organization less than 90 days ago', new.slug using errcode = 'P0001';
  end if;
  delete from platform.retired_slug where slug = new.slug;   -- taken (back): no longer held
  if tg_op = 'UPDATE' then
    insert into platform.retired_slug (slug, tenant_id) values (old.slug, old.id)
      on conflict (slug) do update set tenant_id = excluded.tenant_id, retired_at = now();
  end if;
  return new;
end
$$;

create trigger tenant_slug_hold before insert or update of slug on platform.tenant
  for each row execute function platform.hold_retired_slug();

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
  if p_slug is distinct from v_old then
    update platform.tenant set slug = p_slug where id = app.current_tenant();
  end if;
  return v_old;
end
$$;

grant select, insert, update, delete on platform.retired_slug to app_definer;
grant create on schema app, platform to app_definer;   -- needed to own a function there; see V1 and V43
alter function platform.hold_retired_slug() owner to app_definer;
alter function app.set_organization_slug(text) owner to app_definer;
revoke create on schema app, platform from app_definer;
revoke all on function platform.hold_retired_slug(), app.set_organization_slug(text) from public;
grant execute on function app.set_organization_slug(text) to app_tenant;
