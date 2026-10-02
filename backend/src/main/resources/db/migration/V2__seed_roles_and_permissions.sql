-- Permission catalogue, system roles and what each role may do.

insert into app.permission (code, description) values
  ('employee.read',      'View employee records'),
  ('employee.write',     'Create and change employee records'),
  ('compensation.read',  'View salary structures, payslips and tax documents'),
  ('compensation.write', 'Change salary structures and pay inputs'),
  ('payroll.read',       'View payroll runs, registers and reports'),
  ('payroll.prepare',    'Enter inputs and calculate a payroll run'),
  ('payroll.approve',    'Approve and lock a payroll run'),
  ('payroll.release',    'Release the bank payment file'),
  ('statutory.file',     'Generate statutory output files'),
  ('user.read',          'View the user list and pending invitations'),
  ('user.invite',        'Invite users; resend and revoke invitations'),
  ('user.manage',        'Suspend, reactivate and end memberships'),
  ('role.assign',        'Grant and remove roles'),
  ('audit.read',         'View the audit log'),
  ('tenant.manage',      'Change Organization settings; close the account'),
  ('billing.manage',     'Manage plan, seats and invoices');

insert into app.role (code, name, is_system, assignable) values
  ('owner',            'Org Admin',        true, true),
  ('hr_admin',         'HR Admin',         true, true),
  ('payroll_operator', 'Payroll Operator', true, true),
  ('payroll_approver', 'Payroll Approver', true, true),
  ('manager',          'Manager',          true, true),
  ('employee',         'Employee',         true, true),
  ('auditor',          'Auditor',          true, true),
  ('ex_employee',      'Ex-employee',      true, false),
  ('support_read',     'Support (read)',   true, false),
  ('support_write',    'Support (write)',  true, false);

insert into app.role_permission (role_id, permission_code, scope)
select r.id, p.code, 'tenant' from app.role r cross join app.permission p where r.code = 'owner';

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('hr_admin','employee.read','tenant'), ('hr_admin','employee.write','tenant'),
  ('hr_admin','user.read','tenant'), ('hr_admin','user.invite','tenant'),
  ('hr_admin','user.manage','tenant'), ('hr_admin','role.assign','tenant'),
  ('hr_admin','audit.read','assigned'),

  ('payroll_operator','employee.read','assigned'), ('payroll_operator','compensation.read','assigned'),
  ('payroll_operator','compensation.write','assigned'), ('payroll_operator','payroll.read','assigned'),
  ('payroll_operator','payroll.prepare','assigned'), ('payroll_operator','statutory.file','assigned'),
  ('payroll_operator','audit.read','assigned'),

  ('payroll_approver','employee.read','assigned'), ('payroll_approver','compensation.read','assigned'),
  ('payroll_approver','payroll.read','assigned'), ('payroll_approver','payroll.approve','assigned'),
  ('payroll_approver','payroll.release','assigned'), ('payroll_approver','audit.read','tenant'),

  ('manager','employee.read','team'),

  ('employee','employee.read','self'), ('employee','employee.write','self'),
  ('employee','compensation.read','self'),

  ('auditor','employee.read','tenant'), ('auditor','compensation.read','tenant'),
  ('auditor','payroll.read','tenant'), ('auditor','audit.read','tenant'),

  ('ex_employee','employee.read','self'), ('ex_employee','compensation.read','self'),

  ('support_read','employee.read','tenant'), ('support_read','compensation.read','tenant'),
  ('support_read','payroll.read','tenant'), ('support_read','user.read','tenant'),
  ('support_read','audit.read','tenant'),

  ('support_write','employee.read','tenant'), ('support_write','compensation.read','tenant'),
  ('support_write','payroll.read','tenant'), ('support_write','user.read','tenant'),
  ('support_write','audit.read','tenant'), ('support_write','employee.write','tenant'),
  ('support_write','compensation.write','tenant'), ('support_write','payroll.prepare','tenant'),
  ('support_write','user.invite','tenant'), ('support_write','user.manage','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role;
