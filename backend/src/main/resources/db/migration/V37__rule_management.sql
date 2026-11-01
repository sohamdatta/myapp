-- State professional tax and labour welfare fund rules are managed from the platform console (release R5): a Super Admin adds
-- a new draft version of a state's rule (the version in force is closed on the day the new one starts), edits a draft, and
-- records the payroll consultant's sign-off. The console's database role needs to read and write the rule table.
--
-- This is the one place the platform plane is given anything in schema app, so it is kept as narrow as it can be:
--  * USAGE on the schema (to name the table; it grants no access to any table in it) and table privileges on app.payroll_rule
--    only, which is platform data, the same for every Organization, with no tenant column. Every tenant table keeps forced
--    row-level security and no grant to this role;
--  * the one function in the schema that PUBLIC could still run (app.organization_name, a definer function for the tenant plane)
--    is closed to PUBLIC, since USAGE would otherwise let the console call it;
--  * a trigger limits what the console role can do to the table itself, whatever the application code does: only
--    professional tax and labour welfare fund rows, new rows only as drafts, a signed-off row never changed except to close it.
-- PayrollRulesTest checks that the console still cannot read a tenant table or run another function.
-- Additive only (grants and a trigger), so an older build keeps running against it. By hand: drop the trigger and revoke both grants.
set lock_timeout = '5s';

revoke execute on function app.organization_name() from public;
grant execute on function app.organization_name() to app_tenant, app_definer;

grant usage on schema app to app_platform;
grant select, insert, update on app.payroll_rule to app_platform;

create function app.payroll_rule_console_guard() returns trigger language plpgsql as $$
begin
  if current_user <> 'app_platform' then
    return new;
  end if;
  if tg_op = 'INSERT' then
    if new.statute not in ('pt', 'lwf') or new.status <> 'draft' or new.signed_off_by is not null then
      raise exception 'The console adds state professional tax and labour welfare fund rules, as drafts' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.statute not in ('pt', 'lwf') then
    raise exception 'The console changes state professional tax and labour welfare fund rules only' using errcode = 'check_violation';
  end if;
  if (new.statute, new.jurisdiction, new.valid_from) is distinct from (old.statute, old.jurisdiction, old.valid_from) then
    raise exception 'A rule version keeps its statute, state and start date' using errcode = 'check_violation';
  end if;
  if old.status = 'signed_off' then
    if (new.data, new.source, new.status, new.signed_off_by, new.signed_off_on) is distinct from
       (old.data, old.source, old.status, old.signed_off_by, old.signed_off_on) then
      raise exception 'A signed-off rule is never changed; add a new version' using errcode = 'check_violation';
    end if;
    if old.valid_to is not null and new.valid_to is distinct from old.valid_to then
      raise exception 'A closed rule version stays closed' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end $$;
create trigger payroll_rule_console_guard before insert or update on app.payroll_rule
  for each row execute function app.payroll_rule_console_guard();
