import { lazy } from 'react'
import { pageFromLocation } from './router.js'

/**
 * Every page is its own download, fetched the first time it is opened (or when the person points
 * at it in the sidebar), so signing in does not wait for screens the person may never use.
 */
function page(load, props = () => ({})) {
  let pending = null
  const once = () => (pending ||= load().catch((err) => {
    // A failed download may succeed later (a dropped connection); let the next try fetch again.
    pending = null
    throw err
  }))
  return { load: once, Component: lazy(once), props }
}

const named = (load, name) => () => load().then((m) => ({ default: m[name] }))

const ORGANIZATION = {
  'my-attendance': page(() => import('./pages/MyAttendance.jsx')),
  'my-leave': page(() => import('./pages/MyLeave.jsx'), ({ me }) => ({ me })),
  'my-tasks': page(() => import('./pages/MyTasks.jsx')),
  'my-pay': page(() => import('./pages/MyPay.jsx')),
  'my-profile': page(named(() => import('./pages/People.jsx'), 'MyProfile'), ({ me }) => ({ id: me.employment?.id })),
  'my-exit': page(() => import('./pages/MyExit.jsx'), ({ me }) => ({ me })),
  'my-onboarding': page(() => import('./pages/MyOnboarding.jsx'), ({ me }) => ({ me })),
  'org-chart': page(() => import('./pages/OrgChart.jsx'), ({ onNavigate }) => ({ onNavigate })),
  people: page(() => import('./pages/People.jsx'), ({ me, onNavigate }) => ({ me, onNavigate })),
  attendance: page(() => import('./pages/Attendance.jsx'), ({ me }) => ({ me })),
  'work-calendar': page(() => import('./pages/WorkCalendar.jsx'), ({ me }) => ({ me })),
  leave: page(() => import('./pages/Leave.jsx'), ({ me }) => ({ me })),
  exits: page(() => import('./pages/Exits.jsx'), ({ me, onNavigate }) => ({ me, onNavigate })),
  payroll: page(() => import('./pages/Payroll.jsx'), ({ me }) => ({ me })),
  'tax-declarations': page(() => import('./pages/TaxReview.jsx'), ({ me }) => ({ me })),
  users: page(named(() => import('./pages/Organization.jsx'), 'UserManagement'), ({ me }) => ({ me })),
  audit: page(named(() => import('./pages/Organization.jsx'), 'AuditTab')),
}

const consolePage = page(() => import('./pages/Console.jsx'), (ctx) => ({ me: ctx.me, page: ctx.page, onEnteredSupport: ctx.onEnteredSupport }))
const CONSOLE = { organizations: consolePage, users: consolePage, support: consolePage }

export const registry = (me) => (me.kind === 'platform' ? CONSOLE : ORGANIZATION)

const LANDING_KEY = 'hrms-landing'

/** Remembers which page this person lands on, so the next visit can start fetching it straight away. */
export function rememberLanding(me, id) {
  try {
    localStorage.setItem(LANDING_KEY, `${me.kind === 'platform' ? 'platform' : 'org'}:${id}`)
  } catch {
    // Without storage, the page simply starts downloading once the app knows who has signed in.
  }
}

/**
 * Called as the app starts, before it has asked who is signed in: fetches the page the address names,
 * or else the page this person landed on last time, in step with the sign-in check instead of after it.
 */
export function preloadLikely() {
  let id = pageFromLocation()
  let kind = null
  try {
    const [k, landing] = (localStorage.getItem(LANDING_KEY) || '').split(':')
    if (k) {
      kind = k
      id = id || landing
    }
  } catch {
    // No storage: only an address can say which page.
  }
  if (!id) return
  const sets = kind ? [kind === 'platform' ? CONSOLE : ORGANIZATION] : [ORGANIZATION, CONSOLE]
  sets.forEach((set) => set[id]?.load().catch(() => {}))
}

/** Starts fetching a page before it is opened: on hover or focus in the sidebar, and when the browser is idle. */
export function preloadPage(me, id) {
  registry(me)[id]?.load().catch(() => {})
}

/** Fetches the person's other pages once the first one is up, so moving around later is instant. */
export function preloadAll(me, ids) {
  // Not for someone who has asked to save data, or is on a slow connection: they get a page when they ask for it.
  const link = navigator.connection
  if (link && (link.saveData || /2g|3g/.test(link.effectiveType || ''))) return
  const idle = window.requestIdleCallback || ((fn) => setTimeout(fn, 1500))
  idle(() => ids.forEach((id) => preloadPage(me, id)))
}
