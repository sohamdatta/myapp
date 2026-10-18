/** Words for performance, shared by My performance, the Performance page and its setup. */

/** How someone is doing at a probation check-in: [badge tone, words]. */
export const RATINGS = {
  on_track: ['good', 'On track'],
  some_concerns: ['warn', 'Some concerns'],
  not_on_track: ['bad', 'Not on track'],
}

/** A recommendation or decision about a probation, as a manager or HR chooses it. */
export const CHOICES = {
  confirm: 'Confirm',
  extend: 'Extend the probation',
  not_confirm: 'Do not confirm',
}

/** A decision once made, in words. */
export const DECIDED = {
  confirm: 'Confirmed',
  extend: 'Extended',
  not_confirm: 'Not confirmed',
}

/** Where a probation review stands, for lists: [badge tone, words]. */
export const PROBATION_STAGES = {
  checkins: ['neutral', 'Check-ins'],
  recommend: ['warn', 'Recommendation due'],
  deciding: ['warn', 'Waiting for HR'],
  overdue: ['bad', 'Ended, not decided'],
  decided: ['good', 'Decided'],
  cancelled: ['neutral', 'Ended'],
}

/** Where a goal, or a person's set of goals, stands: [badge tone, words]. */
export const GOAL_STATUS = {
  none: ['neutral', 'No goals yet'],
  draft: ['neutral', 'Draft'],
  submitted: ['warn', 'Waiting to be agreed'],
  agreed: ['good', 'Agreed'],
  returned: ['warn', 'Returned'],
  dropped: ['neutral', 'Dropped'],
}

/** How a goal is measured. */
export const MEASURES = {
  rating: 'Rated at the review',
  number: 'A number to reach',
  done: 'Done or not done',
}

/** "3 months", "1 month". */
export const months = (n) => `${n} ${Number(n) === 1 ? 'month' : 'months'}`
