-- V49: Organizations define their own roles (V1 kept room for them: app.role.tenant_id).
-- A custom role belongs to one Organization, has a code of the form custom_<name>, and holds permissions its creator holds.
-- Which permissions may go in a role, and the payroll maker-checker rule, are kept by the application and by the
-- limits below: a custom role can never carry the payroll preparing, approving or releasing permissions.
-- Additive. By hand: delete the custom roles, drop the two policies and the trigger.
set lock_timeout = '5s';

alter table app.role add column description text check (description is null or length(description) <= 300);
alter table app.role add constraint role_custom_code check (tenant_id is null or code ~ '^custom_[a-z0-9_]{2,40}$');

insert into app.permission (code, description) values
  ('role.manage', 'Create, change and delete the Organization''s own roles');
insert into app.role_permission (role_id, permission_code, scope)
select r.id, 'role.manage', 'tenant' from app.role r where r.code = 'owner' and r.tenant_id is null;

-- Writing roles: only the Organization's own, and never a system role.
create policy role_write on app.role for insert to app_tenant
  with check (tenant_id = app.current_tenant() and not is_system);
create policy role_change on app.role for update to app_tenant
  using (tenant_id = app.current_tenant() and not is_system) with check (tenant_id = app.current_tenant() and not is_system);
create policy role_remove on app.role for delete to app_tenant
  using (tenant_id = app.current_tenant() and not is_system);
grant insert, update, delete on app.role to app_tenant;

-- role_permission had no row security because nobody could write it. Reading stays open to everyone who could read it;
-- writing is allowed only for the rows of the Organization's own roles.
alter table app.role_permission enable row level security;
create policy role_permission_read on app.role_permission for select using (true);
create policy role_permission_write on app.role_permission for all to app_tenant
  using (exists (select 1 from app.role r where r.id = role_id and r.tenant_id = app.current_tenant() and not r.is_system))
  with check (exists (select 1 from app.role r where r.id = role_id and r.tenant_id = app.current_tenant() and not r.is_system));
grant insert, update, delete on app.role_permission to app_tenant;

-- Changing what a role allows takes effect for everyone who holds it, on their next request.
create function app.bump_holders_of_role() returns trigger
language plpgsql security definer set search_path = app, pg_temp as $$
declare r uuid;
begin
  if tg_op = 'DELETE' then r := old.role_id; else r := new.role_id; end if;
  update app.membership set scope_version = scope_version + 1
   where id in (select membership_id from app.membership_role where role_id = r);
  return null;
end $$;
grant create on schema app to app_definer;
alter function app.bump_holders_of_role() owner to app_definer;
revoke create on schema app from app_definer;
create trigger role_permission_bump after insert or update or delete on app.role_permission
  for each row execute function app.bump_holders_of_role();
