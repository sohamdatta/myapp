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

/** Placeholders a letter can use, as the server allows them. */
export const LETTER_PLACEHOLDERS = {
  'organization.name': "The Organization's name",
  'person.legal_name': 'The full name as on PAN',
  'person.first_name': 'The first name, or the preferred name',
  'job.designation': 'Designation',
  'job.department': 'Department',
  'job.department_with_in': '", in the X department", or nothing',
  'job.manager_name': "The reporting manager's name",
  'job.work_location': 'Work location',
  'employment.joining_date': 'Joining date, as 12 October 2026',
  'employment.type': 'Type of employment, in words',
  'employment.probation_text': 'Probation in words, or "none"',
  'employment.contract_end': 'When a fixed-term contract ends',
  'employment.code': 'Employee code, once known',
  'statutory.uan_or_pending': 'UAN, or "to be generated"',
  'offer.expires_on': 'The date an offer must be answered by',
  today: "Today's date",
}

/** Placeholders an exit letter can use: a letter's, and the exit's own details. */
export const EXIT_LETTER_PLACEHOLDERS = {
  ...LETTER_PLACEHOLDERS,
  'exit.kind': 'The kind of exit, in words: resignation, retirement and so on',
  'exit.submitted_on': 'When the resignation was given, or the exit started',
  'exit.last_working_day': 'The last working day',
  'exit.notice_days': 'The notice owed, in days',
  'exit.notice_end': 'When the notice would end',
  'exit.response_by': 'The date a reply is due by (show-cause and final notice)',
  'employment.tenure': 'Length of service, as 3 years 2 months',
  'employment.designations': 'Every designation held, with dates',
}

/** Placeholders a message can use: a letter's, and the event's own details. */
export const MESSAGE_PLACEHOLDERS = {
  ...EXIT_LETTER_PLACEHOLDERS,
  link: 'The link the message is about',
  note: "HR's note",
  'section.title': "The section's title",
  'task.title': 'The task',
  'task.due_date': "The task's due date",
  'joiner.name': "The joiner's name",
  'employee.name': "The leaving employee's name",
  'exit.access_until': "When a former employee's access ends",
  'letter.title': "The letter's title",
  'settlement.line': 'A settlement line, such as Gratuity',
  'settlement.due_date': 'When a settlement line is due',
}

/** Sample values, to show how a message reads. */
export const SAMPLE_VALUES = {
  'organization.name': 'Globex Ltd',
  'person.legal_name': 'Asha Rao',
  'person.first_name': 'Asha',
  'joiner.name': 'Asha Rao',
  'job.designation': 'Analyst',
  'job.department': 'Engineering',
  'job.department_with_in': ', in the Engineering department',
  'job.manager_name': 'Ravi Sen',
  'job.work_location': 'Bengaluru office',
  'employment.joining_date': '12 October 2026',
  'employment.type': 'Permanent',
  'employment.probation_text': 'until 12 April 2027',
  'employment.contract_end': '',
  'employment.code': 'GLX-26-0001',
  'statutory.uan_or_pending': 'to be generated',
  'offer.expires_on': '19 October 2026',
  today: '5 October 2026',
  link: 'https://…',
  note: 'Please upload a clearer photo',
  'section.title': 'Bank account',
  'task.title': 'Prepare laptop and accounts',
  'task.due_date': '10 October 2026',
  'employee.name': 'Asha Rao',
  'exit.kind': 'resignation',
  'exit.submitted_on': '1 October 2026',
  'exit.last_working_day': '31 October 2026',
  'exit.notice_days': '30',
  'exit.notice_end': '31 October 2026',
  'exit.response_by': '12 October 2026',
  'exit.access_until': '31 October 2027',
  'employment.tenure': '3 years 2 months',
  'employment.designations': 'Analyst, 12 August 2023 to 31 March 2025\nSenior Analyst, 1 April 2025 to 31 October 2026',
  'letter.title': 'relieving letter',
  'settlement.line': 'Gratuity',
  'settlement.due_date': '30 November 2026',
}

/** Fills placeholders with values, as the server does: unknown or empty ones become nothing. */
export function fill(text, values) {
  return (text || '').replace(/\{\{\s*([A-Za-z0-9_.]+)\s*\}\}/g, (_, key) => values[key] ?? '')
}

/** Employee code tokens as the server expands them, for a live example while typing. */
export function expandCode(pattern, joining = new Date()) {
  const yyyy = String(joining.getFullYear())
  const tokens = { YYYY: yyyy, YY: yyyy.slice(2), MM: String(joining.getMonth() + 1).padStart(2, '0'), LOC: 'OFF', DEPT: 'ENG', TYPE: 'P' }
  return (pattern || '').replace(/\{([A-Z]+)\}/g, (m, t) => tokens[t] ?? m)
}

