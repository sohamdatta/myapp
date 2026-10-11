import { attendance, org, people } from '../../api.js'
import { useLoad } from '../../hooks.js'
import { pretty } from '../onboardingText.js'

const TYPES = ['permanent', 'probation', 'fixed_term', 'intern', 'consultant']
export { TYPES }

/** Rules in words, as the list shows them. */
export function describeRules(rules, lookups) {
  if (!rules || Object.keys(rules).length === 0) return 'Everyone'
  const name = (list, id) => list.find((x) => x.id === id)?.name || 'a removed one'
  const parts = []
  if (rules.employment_type) parts.push(rules.employment_type.map(pretty).join(' or '))
  if (rules.department) parts.push(`in ${rules.department.map((id) => name(lookups.departments, id)).join(' or ')}`)
  if (rules.work_location) parts.push(`at ${rules.work_location.map((id) => name(lookups.locations, id)).join(' or ')}`)
  if (rules.designation) parts.push(`as ${rules.designation.join(' or ')}`)
  return parts.join(', ')
}

/** Inserts text into a textarea at its cursor, through React state. */
export function insertAt(ref, current, text, set) {
  const el = ref.current
  if (!el) {
    set(`${current}${text}`)
    return
  }
  const start = el.selectionStart ?? current.length
  const end = el.selectionEnd ?? current.length
  set(current.slice(0, start) + text + current.slice(end))
  requestAnimationFrame(() => {
    el.focus()
    el.selectionStart = el.selectionEnd = start + text.length
  })
}

/** What setup screens choose from: departments, work locations, roles and users. Loaded once. */
export function useSetupLookups() {
  const departments = useLoad(people.departments)
  const locations = useLoad(() => attendance.locations().catch(() => []))
  const roles = useLoad(() => org.roles().catch(() => []))
  const users = useLoad(() => org.users().catch(() => []))
  return {
    loading: [departments, locations, roles, users].some((l) => l.loading),
    departments: (departments.data || []).filter((d) => d.active),
    locations: locations.data || [],
    roles: (roles.data || []).filter((r) => r.code !== 'ex_employee' && r.code !== 'joiner'),
    users: (users.data || []).filter((u) => u.status === 'active'),
  }
}
