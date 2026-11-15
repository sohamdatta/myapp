-- V64: moving people from the personal address they were invited at to their company address. An Organization says which
-- email domain is its own (official_email_domain); HR may then propose an address in that domain for a member
-- (membership.proposed_email). Nothing changes by itself: the person asks for the change from Security with their own
-- password, and the link goes to the new address, so only its owner can finish it. auth.official_email_proposals lets the
-- person's own page see what has been proposed, across Organizations.
set lock_timeout = '5s';

create table app.official_email_domain (
  tenant_id uuid primary key references platform.tenant(id),
  domain    text not null check (length(domain) between 3 and 253 and domain = lower(domain)),
  set_at    timestamptz not null default now()
);

alter table app.official_email_domain enable row level security;
alter table app.official_email_domain force row level security;
create policy tenant_isolation on app.official_email_domain
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update, delete on app.official_email_domain to app_tenant;
grant select on app.official_email_domain to app_definer;

alter table app.membership
  add column proposed_email citext,
  add column proposed_at timestamptz;

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
     and m.proposed_email <> i.email
   order by m.proposed_at desc
$$;

grant create on schema auth to app_definer;   -- needed to own a function there; see V1 and V9
alter function auth.official_email_proposals(uuid) owner to app_definer;
revoke create on schema auth from app_definer;
revoke all on function auth.official_email_proposals(uuid) from public;
grant execute on function auth.official_email_proposals(uuid) to app_auth;
