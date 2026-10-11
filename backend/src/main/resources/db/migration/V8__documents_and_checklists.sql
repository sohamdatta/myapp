-- Documents and checklists in the joiner flow.

-- The Documents section was off while uploads did not exist; it is switched on now in every flow.
update app.section_setting set enabled = true where section = 'documents';

-- Whom IT tasks go to: the first active member with this role. It grants nothing by itself;
-- the people holding it usually have the Employee role as well.
insert into app.role (code, name, is_system, assignable) values ('it_admin', 'IT Admin', true, true);
insert into app.role_permission (role_id, permission_code, scope)
select r.id, 'employee.read', 'self' from app.role r where r.code = 'it_admin' and r.tenant_id is null;

-- A one-off task records who added it; a task is linked to its document through attachment_id.
alter table app.onboarding_task add column created_by uuid references app.membership(id);
alter table app.document add column onboarding_id uuid,
  add foreign key (tenant_id, onboarding_id) references app.onboarding (tenant_id, id);
create index document_onboarding on app.document (onboarding_id) where onboarding_id is not null;

grant delete on app.onboarding_task to app_tenant;
