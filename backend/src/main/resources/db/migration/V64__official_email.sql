-- V64: moving people from the personal address they were invited at to their company address. An Organization says which
-- email domain is its own (official_email_domain) and proves it with a DNS record (verify_token, verified_at); only a verified
-- domain, claimed by one Organization at a time, lets HR propose an address in it for a member (membership.proposed_email).
-- A proposal is good for 30 days and ends with the membership. Nothing changes by itself: the person asks for the change from Security with their own
-- password, and the link goes to the new address, so only its owner can finish it. auth.official_email_proposals lets the
-- person's own page see what has been proposed, across Organizations.
set lock_timeout = '5s';

create table app.official_email_domain (
  tenant_id uuid primary key references platform.tenant(id),
  domain    text not null check (length(domain) between 3 and 253 and domain = lower(domain)),
  set_at    timestamptz not null default now(),
  verify_token text not null,          -- the value the Organization publishes in DNS to show the domain is theirs
  verified_at  timestamptz
);

-- A domain can be verified by only one Organization.
create unique index official_email_domain_verified_uq on app.official_email_domain (domain) where verified_at is not null;

alter table app.official_email_domain enable row level security;
alter table app.official_email_domain force row level security;
create policy tenant_isolation on app.official_email_domain
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update, delete on app.official_email_domain to app_tenant;
grant select on app.official_email_domain to app_definer;

alter table app.membership
  add column proposed_email citext,
  add column proposed_at timestamptz;

-- A proposal ends with the membership, so a rehire does not bring an old one back.
create function app.membership_clear_proposal() returns trigger language plpgsql as $$
begin
  new.proposed_email := null;
  new.proposed_at := null;
  return new;
end $$;
create trigger membership_clear_proposal before update on app.membership
  for each row when (new.status = 'ended' and old.status is distinct from 'ended')
  execute function app.membership_clear_proposal();

create function auth.official_email_proposals(p_identity uuid)
returns table (organization text, email citext, proposed_at timestamptz)
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select t.name, m.proposed_email, m.proposed_at
    from app.membership m
    join platform.tenant t on t.id = m.tenant_id
    join auth.identity i on i.id = m.identity_id
   where m.identity_id = p_identity
     and m.status <> 'ended'
     and t.status = 'active'
     and m.proposed_email is not null
     and m.proposed_at > now() - interval '30 days'
     and lower(m.proposed_email::text) <> lower(i.email::text)   -- as text: citext is not on this function's search path
   order by m.proposed_at desc
$$;

grant create on schema auth to app_definer;   -- needed to own a function there; see V1 and V9
alter function auth.official_email_proposals(uuid) owner to app_definer;
revoke create on schema auth from app_definer;
revoke all on function auth.official_email_proposals(uuid) from public;
grant execute on function auth.official_email_proposals(uuid) to app_auth;
