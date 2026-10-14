import { formatDay } from '../hooks.js'

const SETTLEMENT = { none: 'not worked out yet', draft: 'being prepared', approved: 'approved, being paid', paid: 'paid' }

/**
 * Where an exit stands, as a short row of steps: what is done, and what comes next. HR sees the
 * whole way to closing; the employee sees what concerns them.
 */
export function ExitProgress({ exit: x, mine }) {
  if (['withdrawn', 'cancelled'].includes(x.status)) return null
  const accepted = ['accepted', 'closed'].includes(x.status)
  const ended = Boolean(x.endedAt) || x.status === 'closed'
  const clearance = x.clearance || { done: 0, total: 0 }
  const settlement = x.settlement?.state || 'none'
  const relieving = (x.letters || []).some((l) => l.kind === 'relieving')
  const gone = ['death', 'absconding'].includes(x.kind)
  const steps = [
    {
      label: x.kind === 'resignation' ? (mine ? 'Resignation accepted' : 'Accepted') : x.status === 'proposed' ? 'Confirmed' : 'Started',
      done: accepted,
      detail: accepted ? null : x.stage === 'awaiting_manager' ? 'waiting for the manager' : 'waiting for HR',
    },
    {
      label: 'Clearance',
      done: clearance.total > 0 && clearance.done === clearance.total,
      detail: accepted && clearance.total > 0 ? `${clearance.done} of ${clearance.total} done` : null,
    },
    {
      label: 'Last working day',
      done: ended,
      detail: x.lastWorkingDay ? formatDay(x.lastWorkingDay) : null,
    },
    {
      label: 'Settlement',
      done: settlement === 'paid',
      detail: accepted ? SETTLEMENT[settlement] : null,
    },
    !gone && { label: 'Relieving letter', done: relieving, detail: relieving ? 'issued' : null },
    !mine && { label: 'Closed', done: x.status === 'closed', detail: null },
  ].filter(Boolean)
  const next = steps.findIndex((s) => !s.done)
  return (
    <ol className="exit-progress" aria-label="Where the exit stands">
      {steps.map((s, i) => (
        <li key={s.label} className={s.done ? 'step step-done' : i === next ? 'step step-next' : 'step'}>
          <span className="step-mark" aria-hidden="true">
            {s.done ? '✓' : i + 1}
          </span>
          <span>
            <span className="step-label">{s.label}</span>
            {s.detail && <span className="hint step-detail">{s.detail}</span>}
            <span className="sr-only">{s.done ? ' (done)' : i === next ? ' (next)' : ''}</span>
          </span>
        </li>
      ))}
    </ol>
  )
}
