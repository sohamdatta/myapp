/**
 * What the sidebar offers a signed-in person: sections of pages, decided by
 * where they are (an Organization or the platform console) and what they may do.
 */
export function navFor(me) {
  if (me.kind === 'platform') {
    const isSuperAdmin = me.platformRole === 'super_admin'
    const canSupport = isSuperAdmin || me.platformRole === 'support'
    return [
      {
        label: 'Platform',
        items: [
          { id: 'organizations', label: 'Organizations' },
          isSuperAdmin && { id: 'users', label: 'Platform users' },
          canSupport && { id: 'support', label: 'Support access' },
        ].filter(Boolean),
      },
    ]
  }

  // A joiner who has not started yet sees only their onboarding.
  if ((me.roles || []).includes('joiner')) {
    return [{ label: 'Joining', items: [{ id: 'my-onboarding', label: 'My onboarding' }] }]
  }

  // A former employee keeps only their letters and settlement, until that access ends.
  if ((me.roles || []).includes('ex_employee')) {
    return [{ label: 'Former employee', items: [{ id: 'my-exit', label: 'My exit' }] }]
  }

  const scopes = me.scopes || {}
  // What the Organization has bought: Core HR always; Attendance (with leave) and Payroll as add-ons.
  const hasAttendance = (me.modules || []).includes('attendance')
  const hasPayroll = (me.modules || []).includes('payroll')
  // Punching is for users of the Organization, never for a support session.
  const canPunch = hasAttendance && me.permissions.includes('attendance.punch') && me.kind === 'tenant'
  const seesOthersAttendance = Boolean(scopes['attendance.read']) && scopes['attendance.read'] !== 'self'
  // A support session has no leave of its own; a former employee can still look at theirs.
  const hasOwnLeave = hasAttendance && Boolean(scopes['leave.read']) && me.kind === 'tenant' && (scopes['leave.read'] === 'self' || me.permissions.includes('leave.apply'))
  const seesOthersLeave = hasAttendance && Boolean(scopes['leave.read']) && scopes['leave.read'] !== 'self'
  const seesPeople = Boolean(scopes['employee.read']) && scopes['employee.read'] !== 'self'
  return [
    {
      label: 'My work',
      items: [
        canPunch && { id: 'my-attendance', label: 'My attendance' },
        hasOwnLeave && { id: 'my-leave', label: 'My leave' },
        me.openTasks > 0 && { id: 'my-tasks', label: 'My tasks' },
        hasPayroll && me.employment && me.kind === 'tenant' && { id: 'my-pay', label: 'My pay' },
        me.employment && me.kind === 'tenant' && { id: 'my-profile', label: 'My profile' },
        me.employment && me.kind === 'tenant' && { id: 'my-exit', label: 'My exit' },
      ].filter(Boolean),
    },
    {
      label: 'Organization',
      items: [
        me.permissions.includes('orgchart.read') && { id: 'org-chart', label: 'Org chart' },
        seesPeople && { id: 'people', label: 'People' },
        hasAttendance && seesOthersAttendance && { id: 'attendance', label: 'Attendance' },
        // Without Attendance, payroll and joining still need the work calendar: locations, shifts and who works where.
        !hasAttendance && scopes['attendance.manage'] === 'tenant' && { id: 'work-calendar', label: 'Work locations' },
        seesOthersLeave && { id: 'leave', label: 'Leave' },
        Boolean(scopes['exit.read']) && scopes['exit.read'] !== 'self' && { id: 'exits', label: 'Exits' },
        hasPayroll && (me.permissions.includes('payroll.read') || me.permissions.includes('payroll.prepare')) && { id: 'payroll', label: 'Payroll' },
        // Declarations are checked by whoever handles salaries for everyone: HR Admins and the payroll roles.
        hasPayroll && me.kind === 'tenant' && [scopes['compensation.read'], scopes['payroll.read']].some((s) => s === 'tenant' || s === 'assigned') && {
          id: 'tax-declarations',
          label: 'Tax declarations',
        },
        me.permissions.includes('user.read') && { id: 'users', label: 'User management' },
        // Settings are for whoever owns one (security for the Org Admin, windows for attendance) and, read-only, for auditors.
        me.kind === 'tenant' && ['tenant.manage', 'attendance.manage', 'audit.read'].some((p) => scopes[p] === 'tenant') && { id: 'settings', label: 'Settings' },
        me.permissions.includes('audit.read') && { id: 'audit', label: 'Audit log' },
      ].filter(Boolean),
    },
  ].filter((section) => section.items.length > 0)
}

/** The page a person lands on: their own work first, otherwise the first thing they can see. */
export function firstPage(nav) {
  return nav.length ? nav[0].items[0].id : null
}
