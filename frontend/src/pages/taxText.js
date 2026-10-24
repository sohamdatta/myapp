/** Words shared by the employee's tax declaration and HR's checks. */

/** The kinds of claim, in the order the screens show them, with what each asks for. */
export const HEADS = [
  { head: 'rent', title: 'Rent (HRA)', hint: 'Rent you pay for the home you live in. HRA is exempt up to the least of: the HRA you receive, rent above 10% of basic, and half (or 40%) of basic.' },
  { head: 'sec80c', title: 'Investments and payments in the ₹1.5 lakh group', hint: 'Formerly section 80C. Counts together with your PF.' },
  { head: 'nps', title: 'NPS, your own contribution', hint: 'Formerly section 80CCD(1B), over and above the ₹1.5 lakh group.' },
  { head: 'health_self', title: 'Health insurance: you and family', hint: 'Formerly section 80D. Spouse and children included.' },
  { head: 'health_parents', title: 'Health insurance: parents', hint: 'Formerly section 80D. Higher limit when a parent is a senior citizen.' },
  { head: 'home_loan', title: 'Home-loan interest', hint: 'Interest on a loan for the home you live in. Principal goes in the ₹1.5 lakh group.' },
  { head: 'edu_loan', title: 'Education-loan interest', hint: 'Formerly section 80E. Interest only, with no upper limit.' },
]

export const KINDS = [
  ['life_insurance', 'Life insurance premium'],
  ['ppf', 'Public Provident Fund'],
  ['elss', 'Tax-saving mutual fund (ELSS)'],
  ['nsc', 'National Savings Certificate'],
  ['ssy', 'Sukanya Samriddhi'],
  ['tuition', 'Children’s tuition fees'],
  ['home_loan_principal', 'Home-loan principal'],
  ['tax_saver_fd', 'Five-year tax-saving deposit'],
  ['epf_voluntary', 'Voluntary PF'],
  ['other', 'Other in the group'],
]

/** Where a claim's proof stands, in the employee's words or HR's. */
export const PROOF = {
  none: ['neutral', 'No proof yet', 'No proof yet'],
  submitted: ['warn', 'Proof sent, waiting', 'To check'],
  accepted: ['good', 'Proof accepted', 'Accepted'],
  rejected: ['warn', 'Proof returned', 'Returned'],
}

/** Where an employee's declaration stands, for HR's list. */
export const STATES = {
  not_started: ['neutral', 'Not started'],
  draft: ['neutral', 'Not submitted'],
  submitted: ['good', 'Submitted'],
  to_check: ['warn', 'Proofs to check'],
}

export const monthLabel = (ym) =>
  ym ? new Date(`${ym}-01T00:00:00`).toLocaleDateString('en-IN', { month: 'short', year: 'numeric' }) : '—'

/** The limit for a kind of claim, in words, from the rules in force. */
export function limitWords(head, limits, rupees) {
  if (!limits) return ''
  switch (head) {
    case 'sec80c':
      return limits.section80cMax ? `Up to ${rupees(limits.section80cMax)} a year, with your PF.` : ''
    case 'nps':
      return limits.npsMax ? `Up to ${rupees(limits.npsMax)} a year.` : ''
    case 'health_self':
      return limits.healthSelfMax ? `Up to ${rupees(limits.healthSelfMax)}; ${rupees(limits.healthSelfSeniorMax)} if you or your spouse is a senior citizen.` : ''
    case 'health_parents':
      return limits.healthParentsMax ? `Up to ${rupees(limits.healthParentsMax)}; ${rupees(limits.healthParentsSeniorMax)} if a parent is a senior citizen.` : ''
    case 'home_loan':
      return limits.homeLoanInterestMax ? `Up to ${rupees(limits.homeLoanInterestMax)} a year.` : ''
    case 'rent':
      return limits.landlordPanAbove ? `The landlord’s PAN is needed when rent is above ${rupees(limits.landlordPanAbove)} a year.` : ''
    default:
      return ''
  }
}

/** A claim's details in one line, as both sides see them. */
export function detailWords(item) {
  const d = item.details || {}
  switch (item.head) {
    case 'rent':
      return [
        `${monthLabel(d.from)} to ${monthLabel(d.to)}`,
        d.city,
        d.landlordName && `landlord ${d.landlordName}${d.landlordPan ? ` (PAN ${d.landlordPan})` : ''}`,
        d.relationship && `related: ${d.relationship}`,
      ]
        .filter(Boolean)
        .join(' · ')
    case 'home_loan':
      return [d.lenderName, d.lenderPan && `PAN ${d.lenderPan}`].filter(Boolean).join(' · ')
    case 'health_self':
    case 'health_parents':
      return [d.insurer, d.senior && 'senior citizen'].filter(Boolean).join(' · ')
    case 'edu_loan':
      return d.lenderName || ''
    default:
      return d.note || ''
  }
}
