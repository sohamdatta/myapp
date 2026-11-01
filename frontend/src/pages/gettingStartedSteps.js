import { GS } from './gettingStartedText.js'

/**
 * The checklist, worked out from what the server counted. A step is done when the thing it asks for
 * exists, so there is nothing to remember between visits. People only count once there is more than
 * the Org Admin, and leave and attendance only work for people who have signed in.
 *
 * @param {object} status what GET /org/setup returned
 * @param {{ organization: { name: string } }} me the signed-in person
 * @param {string[]} pages the page ids this person can open
 */
export function stepsFor(status, me, pages) {
  const f = status.facts
  const t = GS.steps
  const started = f.workLocations > 0 || f.leaveTypes > 0
  const canPick = status.open.calendar || status.open.leave
  const link = (...ids) => ids.find((id) => pages.includes(id)) || null

  const steps = [
    { id: 'company', title: t.company.title, summary: t.company.summary(me.organization.name), done: true },
    {
      id: 'starter',
      title: t.starter.title,
      summary: started && !canPick ? t.starter.done(f) : t.starter.todo,
      done: started && !canPick,
      picker: canPick,
    },
    {
      id: 'people',
      title: t.people.title,
      summary: f.people > 1 ? [t.people.done(f.people), f.joiners > 0 && t.people.joiners(f.joiners)].filter(Boolean).join(' ') : `${t.people.todo} ${t.people.where}`,
      done: f.people > 1,
      page: link('people'),
      action: t.people.action,
    },
    {
      id: 'invite',
      title: t.invite.title,
      summary: f.otherLogins > 0 ? t.invite.done(f.otherLogins, f.pendingInvitations) : f.pendingInvitations > 0 ? t.invite.waiting(f.pendingInvitations) : t.invite.todo,
      done: f.otherLogins > 0,
      page: link('users'),
      action: t.invite.action,
    },
  ]

  if (status.attendanceOn || link('work-calendar')) {
    const page = status.attendanceOn ? link('attendance') : link('work-calendar')
    const waiting = f.peopleWithLogin === 0
    steps.push({
      id: 'assign',
      title: t.assign.title,
      summary: waiting ? t.assign.none : f.loginsToAssign > 0 ? t.assign.todo(f.loginsToAssign) : t.assign.done,
      done: !waiting && f.loginsToAssign === 0,
      page,
      action: status.attendanceOn ? t.assign.action : t.assign.actionCalendar,
    })
  }

  if (status.leaveOn) {
    steps.push({
      id: 'leave',
      title: t.leave.title,
      summary: f.leaveTypes > 0 ? t.leave.done(f.leaveTypes) : t.leave.todo,
      done: f.leaveTypes > 0,
      page: link('leave'),
      action: t.leave.action,
    })
  }
  return steps
}

/** What stops payroll, in the order of how soon someone must act. Empty when nothing does. */
export function checksFor(status, names) {
  const r = status.readiness
  const t = GS.ready
  if (!r.applies) return []
  const out = []
  if (r.statesWithoutRules.length > 0) {
    const list = r.statesWithoutRules.map((code) => names[code] || code).join(', ')
    out.push({ id: 'states', level: 'blocking', fixable: false, ...t.states(list) })
  }
  if (status.facts.people > 0) {
    if (r.withoutLocation > 0) out.push({ id: 'location', level: 'blocking', fixable: true, ...t.withoutLocation(r.withoutLocation) })
    if (r.noBank > 0) out.push({ id: 'bank', level: 'blocking', fixable: true, ...t.noBank(r.noBank) })
    if (r.noPan > 0) out.push({ id: 'pan', level: 'blocking', fixable: true, ...t.noPan(r.noPan) })
  }
  if (r.locationsWithoutState > 0) out.push({ id: 'noState', level: 'warning', fixable: true, ...t.noState(r.locationsWithoutState) })
  if (r.draftRules) out.push({ id: 'draft', level: 'warning', fixable: false, ...t.draft })
  return out
}
