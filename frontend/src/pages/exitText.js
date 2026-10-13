/** Words for exits, shared by My exit, the Exits page and Setup. */

export const KINDS = {
  resignation: 'Resignation',
  termination: 'Termination',
  end_of_contract: 'End of contract',
  retirement: 'Retirement',
  death: 'Death',
  absconding: 'Absconding',
}

export const RESIGN_REASONS = {
  career: 'Career growth',
  pay: 'Pay',
  relocation: 'Relocation',
  studies: 'Higher studies',
  health: 'Health',
  personal: 'Personal reasons',
  other: 'Something else',
}

export const REASONS = {
  ...RESIGN_REASONS,
  conduct: 'Conduct',
  performance: 'Performance',
  contract: 'Contract ended',
  retirement: 'Retirement',
  death: 'Death',
  absconding: 'Absent without leave',
}

/** Where an exit is: [badge tone, words for HR, words for the employee]. */
export const STAGES = {
  to_confirm: ['warn', 'To confirm', 'Being prepared'],
  absent: ['bad', 'Absent without leave', 'Asked to explain your absence'],
  awaiting_manager: ['warn', 'Waiting for the manager', 'Waiting for your manager'],
  awaiting_hr: ['warn', 'Waiting for HR', 'Waiting for HR'],
  withdrawal_asked: ['warn', 'Asks to withdraw', 'You asked to withdraw'],
  notice: ['neutral', 'Serving notice', 'Serving notice'],
  settling: ['warn', 'Settling', 'Settling your dues'],
  to_close: ['good', 'Ready to close', 'Settled'],
  closed: ['good', 'Closed', 'Closed'],
  withdrawn: ['neutral', 'Withdrawn', 'Withdrawn'],
  cancelled: ['neutral', 'Cancelled', 'Cancelled'],
}

export const SHORTFALL = {
  waived: 'Waived by the employer',
  leave: 'Set against earned leave',
  owed: 'Owed by the employee',
  paid_in_lieu: 'Notice paid in lieu',
}

export const REHIRE = { yes: 'Yes', maybe: 'Maybe', no: 'No' }

/** Whole days from one ISO date to another. */
export function daysBetween(from, to) {
  return Math.round((Date.parse(to) - Date.parse(from)) / 86400000)
}
