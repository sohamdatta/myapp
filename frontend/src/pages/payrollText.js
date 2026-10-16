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
