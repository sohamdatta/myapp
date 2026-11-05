-- Employee licences: how many employees an Organization has bought.
-- null means no limit (every Organization before this change, and any not yet given a number).
alter table platform.tenant
  add column licensed_employees integer check (licensed_employees is null or licensed_employees >= 1);

-- Employees that count against the licence: those joining or working. Ended ones are free again.
-- The platform console sees counts only; the tenant role cannot read the platform plane, so both go through functions.
create function app.employee_licence()
returns table (licensed integer, used bigint)
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select t.licensed_employees,
         (select count(*) from app.employment e where e.tenant_id = t.id and e.status in ('onboarding','active'))
    from platform.tenant t where t.id = app.current_tenant()
$$;

create function platform.tenant_licence(p_tenant uuid)
returns table (licensed integer, used bigint)
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select t.licensed_employees,
         (select count(*) from app.employment e where e.tenant_id = t.id and e.status in ('onboarding','active'))
    from platform.tenant t where t.id = p_tenant
$$;

-- Refuses a new live employee once the licence is used up. Covers every way one can appear:
-- adding, importing, a joiner starting, and a rehire reactivating an ended employment.
create function app.enforce_employee_licence()
returns trigger
language plpgsql security definer set search_path = app, platform, pg_temp as $$
declare
  v_limit integer;
  v_used bigint;
begin
  if new.status not in ('onboarding','active') then
    return new;
  end if;
  if tg_op = 'UPDATE' and old.status in ('onboarding','active') then
    return new;
  end if;
  select t.licensed_employees into v_limit from platform.tenant t where t.id = new.tenant_id;
  if v_limit is null then
    return new;
  end if;
  select count(*) into v_used from app.employment e where e.tenant_id = new.tenant_id and e.status in ('onboarding','active');
  if v_used >= v_limit then
    raise exception 'employee licence limit: all % employee licences are in use', v_limit using errcode = 'P0001';
  end if;
  return new;
end
$$;

grant create on schema app, platform to app_definer;   -- needed to own a function there; see V1 and V16
alter function app.employee_licence() owner to app_definer;
alter function app.enforce_employee_licence() owner to app_definer;
alter function platform.tenant_licence(uuid) owner to app_definer;
revoke create on schema app, platform from app_definer;
revoke all on function app.employee_licence(), platform.tenant_licence(uuid), app.enforce_employee_licence() from public;
grant execute on function app.employee_licence() to app_tenant;
grant execute on function platform.tenant_licence(uuid) to app_platform;
grant select on platform.tenant to app_definer;

create trigger employment_licence before insert or update of status on app.employment
  for each row execute function app.enforce_employee_licence();
