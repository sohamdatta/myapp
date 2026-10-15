-- The org chart: who reports to whom, for everyone who works here.
--
-- It shows only what a company directory would: name, designation, department, work email and
-- the reporting line. Nothing personal, nothing about pay or exits. Joiners and former employees
-- do not see it; a support session does, read only like everything else.

insert into app.permission (code, description) values
  ('orgchart.read', 'See the org chart: names, designations, departments and who reports to whom');

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('owner','orgchart.read','tenant'),
  ('hr_admin','orgchart.read','tenant'),
  ('payroll_operator','orgchart.read','tenant'),
  ('payroll_approver','orgchart.read','tenant'),
  ('manager','orgchart.read','tenant'),
  ('employee','orgchart.read','tenant'),
  ('auditor','orgchart.read','tenant'), ('it_admin','orgchart.read','tenant'),
  ('support_read','orgchart.read','tenant'), ('support_write','orgchart.read','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role and r.tenant_id is null;
