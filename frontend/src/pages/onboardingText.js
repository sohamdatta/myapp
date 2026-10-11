/** Words shared by the onboarding screens. */

export const SECTION_STATUS = {
  not_started: 'To do',
  draft: 'In progress',
  submitted: 'Sent for checking',
  returned: 'Needs a change',
  verified: 'Checked',
}

const OPTION_LABELS = {
  permanent: 'Permanent',
  probation: 'On probation',
  fixed_term: 'Fixed term',
  intern: 'Intern',
  consultant: 'Consultant',
  undisclosed: 'Prefer not to say',
  new: 'New regime',
  old: 'Old regime',
}

export const pretty = (value) => {
  if (value === null || value === undefined || value === '') return '—'
  const text = String(value)
  return OPTION_LABELS[text] || text.charAt(0).toUpperCase() + text.slice(1).replaceAll('_', ' ')
}

/** Where a letter stands: accepted, declined, expired, waiting, or needing no answer. */
export function letterState(l) {
  if (l.response) return l.response
  if (l.expired) return 'expired'
  return l.responseNeeded === 'none' ? 'none' : 'waiting'
}
