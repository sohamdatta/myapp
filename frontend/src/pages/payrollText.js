/** Words and formats shared by the payroll screens and My pay. */

export const rupees = (n, decimals = false) =>
  n === null || n === undefined || n === ''
    ? '—'
    : `${Number(n) < 0 ? '−' : ''}₹${Math.abs(Number(n)).toLocaleString('en-IN', {
        minimumFractionDigits: decimals ? 2 : 0,
        maximumFractionDigits: 2,
      })}`

export const signed = (n) => `${Number(n) >= 0 ? '+' : '−'}₹${Math.abs(Number(n)).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`

/** A run's steps, in order, with the words for each. */
export const RUN_STEPS = [
  ['draft', 'Inputs'],
  ['frozen', 'Frozen'],
  ['calculated', 'Calculated'],
  ['approved', 'Approved'],
  ['paid', 'Paid'],
  ['released', 'Payslips out'],
]

export const RUN_STATES = {
  draft: ['neutral', 'Collecting inputs'],
  frozen: ['warn', 'Frozen, to calculate'],
  calculated: ['warn', 'Calculated, to approve'],
  approved: ['good', 'Approved, to pay'],
  paid: ['good', 'Paid, payslips to release'],
  released: ['good', 'Done'],
}

export const KINDS = {
  earning: 'Earning',
  deduction: 'Deduction',
  employer: 'Employer contribution',
  info: 'For information',
}

export const AMOUNT_TYPES = {
  formula: 'A formula',
  fixed: 'A fixed amount a month',
  input: 'Entered each month',
  statutory: 'Worked out by law',
}

export const WAGE = {
  include: 'Always counts as wages',
  exclude: 'Never counts as wages',
  conditional: 'Counts only to bring wages up to half of pay',
}

export const ROUNDING = {
  nearest: 'To the nearest rupee',
  up: 'Up to the next rupee',
  down: 'Down to the rupee',
  none: 'Not rounded',
}

export const PRORATION = {
  calendar: 'Calendar days of the month',
  working: 'Working days, from each person’s shift and holidays',
  fixed_30: 'A fixed 30 days',
}

export const STATUTES = {
  pf: 'Provident fund',
  esi: 'ESI',
  pt: 'Professional tax',
  lwf: 'Labour welfare fund',
  income_tax: 'Income tax',
}

export const STATE_NAMES = { GA: 'Goa', KA: 'Karnataka', MH: 'Maharashtra', IN: 'All India' }

export const monthLabel = (iso) => new Date(`${iso}T00:00:00`).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' })

// ── tax filing, statutory filing and certificates ────────────────────

/** Whether the person can do this permission for everyone (not only for themselves). */
export const canDo = (me, permission) => ['tenant', 'assigned'].includes(me?.scopes?.[permission])

/** The tax year (April to March) a date falls in, as "2026-27". */
export function taxYearOf(iso) {
  const [y, m] = iso.split('-').map(Number)
  const start = m >= 4 ? y : y - 1
  return `${start}-${String(start + 1).slice(2)}`
}

/** The latest tax years, newest first, counting from today. */
export function recentTaxYears(n, today = new Date().toISOString().slice(0, 10)) {
  const start = Number(taxYearOf(today).slice(0, 4))
  return Array.from({ length: n }, (_, i) => `${start - i}-${String(start - i + 1).slice(2)}`)
}

export const QUARTERS = { 1: 'Apr–Jun', 2: 'Jul–Sep', 3: 'Oct–Dec', 4: 'Jan–Mar' }

export const CHALLAN_STATES = {
  recorded: ['warn', 'Recorded, to approve'],
  approved: ['good', 'Approved'],
}

export const DEPOSIT_STATES = {
  none: ['neutral', 'Not deposited'],
  part: ['warn', 'Part deposited'],
  deposited: ['good', 'Deposited'],
  over: ['warn', 'Over-deposited'],
}

export const CERTIFICATE_STATES = {
  draft: ['warn', 'Draft'],
  released: ['good', 'Released'],
}

export const FILING_STATES = {
  draft: ['neutral', 'Built'],
  downloaded: ['warn', 'Downloaded, to record'],
  recorded: ['warn', 'Recorded, to approve'],
  filed: ['good', 'Filed'],
}

export const FILING_KINDS = { pf_ecr: 'PF (ECR)', esi: 'ESI' }

/** ESIC's reason codes for a person with no wages, until the server sends its own labels. */
export const ESI_CODES = {
  1: 'On leave without pay',
  2: 'Left service',
  3: 'Retired',
  4: 'Out of coverage',
  5: 'Expired',
  11: 'Without work',
}
