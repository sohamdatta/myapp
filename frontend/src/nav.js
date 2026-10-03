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

  const scopes = me.scopes || {}
  // Punching is for users of the Organization, never for a support session.
  const canPunch = me.permissions.includes('attendance.punch') && me.kind === 'tenant'
  const seesOthersAttendance = Boolean(scopes['attendance.read']) && scopes['attendance.read'] !== 'self'
  return [
    { label: 'My work', items: [canPunch && { id: 'my-attendance', label: 'My attendance' }].filter(Boolean) },
    {
      label: 'Organization',
      items: [
        seesOthersAttendance && { id: 'attendance', label: 'Attendance' },
        me.permissions.includes('user.read') && { id: 'users', label: 'User management' },
        me.permissions.includes('audit.read') && { id: 'audit', label: 'Audit log' },
      ].filter(Boolean),
    },
  ].filter((section) => section.items.length > 0)
}

/** The page a person lands on: their own work first, otherwise the first thing they can see. */
export function firstPage(nav) {
  return nav.length ? nav[0].items[0].id : null
}
