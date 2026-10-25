import { useState } from 'react'
import { downloadDocument as download, performance } from '../api.js'
import { formatDate, formatDay, localToday, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { CHOICES, DECIDED, PROBATION_STAGES, RATINGS, months } from './performanceText.js'

/**
 * Probation reviews, for HR (everyone) and managers (their team): a list by stage, and one review
 * at a time with its check-ins, the recommendation and HR's decision.
 */
export default function ProbationReviews() {
  const [open, setOpen] = useState(null)
  if (open) return <ProbationPage key={open} id={open} onBack={() => setOpen(null)} />
  return <ProbationList onOpen={setOpen} />
}

function ProbationList({ onOpen }) {
  const [status, setStatus] = useState('open')
  const list = useLoad(() => performance.probations(status))
  return (
    <>
      <div className="filters">
        {[
          ['open', 'In progress'],
          ['decided', 'Decided'],
          ['all', 'All'],
        ].map(([value, label]) => (
          <button
            key={value}
            type="button"
            className={status === value ? 'chip chip-current' : 'chip'}
            onClick={() => {
              setStatus(value)
              list.reload()
            }}
          >
            {label}
          </button>
        ))}
      </div>
      {list.loading && <p className="muted">Loading…</p>}
      <ErrorNote>{list.error}</ErrorNote>
      {list.data && list.data.length === 0 && (
        <Empty>{status === 'open' ? 'Nobody is on probation.' : 'No probation reviews here.'}</Empty>
      )}
      {list.data && list.data.length > 0 && (
        <div className="table-wrap">
          <table className="stack">
            <thead>
              <tr>
                <th>Name</th>
                <th>Probation ends</th>
                <th>Check-ins</th>
                <th>Stage</th>
                <th>Manager</th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((r) => {
                const [tone, words] = PROBATION_STAGES[r.stage] || ['neutral', r.stage]
                return (
                  <tr key={r.id} data-probation={r.name}>
                    <td>
                      <button type="button" className="text-link" onClick={() => onOpen(r.id)}>
                        {r.name}
                      </button>
                      <div className="hint">{[r.designation, r.department, r.employeeCode].filter(Boolean).join(' · ')}</div>
                    </td>
                    <td>{formatDay(r.probationEnd)}</td>
                    <td>
                      {r.checkinsWritten} of {r.checkinsTotal}
                      {r.nextCheckin && <div className="hint">next due {formatDay(r.nextCheckin)}</div>}
                    </td>
                    <td>
                      <Badge tone={tone}>{r.decision ? DECIDED[r.decision] : words}</Badge>
                      {r.recommendation && !r.decision && <div className="hint">recommends: {CHOICES[r.recommendation].toLowerCase()}</div>}
                    </td>
                    <td>{r.manager || '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function ProbationPage({ id, onBack }) {
  const review = useLoad(() => performance.probation(id))
  const [form, setForm] = useState(null) // recommend | decide
  const { error: downloadError, run: runDownload } = useAction()
  if (review.loading) return <p className="muted">Loading…</p>
  if (review.error) return <ErrorNote>{review.error}</ErrorNote>
  const r = review.data
  const [tone, words] = PROBATION_STAGES[r.stage] || ['neutral', r.stage]
  const done = () => {
    setForm(null)
    review.reload()
  }
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← All probations
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            {r.name} <Badge tone={tone}>{r.decision ? DECIDED[r.decision] : words}</Badge>
          </h2>
          <p className="muted">{[r.designation, r.department, r.employeeCode].filter(Boolean).join(' · ')}</p>
        </div>
        {!form && (
          <div className="form-actions">
            {r.canWrite && (
              <button type="button" className={r.canDecide ? 'secondary' : 'primary'} onClick={() => setForm('recommend')}>
                {r.recommendation ? 'Change the recommendation' : 'Recommend'}
              </button>
            )}
            {r.canDecide && (
              <button type="button" className="primary" onClick={() => setForm('decide')}>
                Decide
              </button>
            )}
          </div>
        )}
      </div>

      {form === 'recommend' && <Recommend review={r} onDone={done} onCancel={() => setForm(null)} />}
      {form === 'decide' && <Decide review={r} onDone={done} onCancel={() => setForm(null)} />}

      <div className="panels">
        <section className="panel">
          <h3>Probation</h3>
          <dl className="facts">
            <dt>Joined</dt>
            <dd>{formatDay(r.joiningDate)}</dd>
            <dt>Probation ends</dt>
            <dd data-testid="probation-end">{formatDay(r.probationEnd)}</dd>
            <dt>Manager</dt>
            <dd>{r.manager || '—'}</dd>
            {r.recommendation && (
              <>
                <dt>Recommended</dt>
                <dd data-testid="recommendation">
                  {CHOICES[r.recommendation]}
                  {r.recommendation === 'extend' && r.recommendMonths && ` by ${months(r.recommendMonths)}`}
                  <div className="hint">
                    by {r.recommendedBy}, {formatDate(r.recommendedAt)}
                  </div>
                  {r.recommendationNote && <div className="pre">{r.recommendationNote}</div>}
                </dd>
              </>
            )}
            {r.decision && (
              <>
                <dt>Decision</dt>
                <dd data-testid="decision">
                  {DECIDED[r.decision]}
                  {r.decision === 'confirm' && ` from ${formatDay(r.confirmedFrom)}`}
                  {r.decision === 'extend' && ` to ${formatDay(r.newProbationEnd)}`}
                  <div className="hint">
                    by {r.decidedBy}, {formatDate(r.decidedAt)}
                  </div>
                  {r.decisionNote && <div className="pre">{r.decisionNote}</div>}
                </dd>
              </>
            )}
            {r.letter && (
              <>
                <dt>Letter</dt>
                <dd>
                  <button
                    type="button"
                    className="text-link"
                    onClick={() => runDownload(() => download(r.letter.documentId, `${r.letter.title}.pdf`))}
                  >
                    {r.letter.title}
                  </button>
                </dd>
              </>
            )}
            {r.exitCaseId && (
              <>
                <dt>Exit</dt>
                <dd>Started under Exits, as a termination for performance.</dd>
              </>
            )}
          </dl>
          <ErrorNote>{downloadError}</ErrorNote>
          {r.history?.length > 0 && (
            <>
              <h4>Earlier</h4>
              <ul className="bullet-list">
                {r.history.map((h) => (
                  <li key={h.id}>
                    Probation to {formatDay(h.probationEnd)}: {DECIDED[h.decision].toLowerCase()}
                    {h.newProbationEnd && ` to ${formatDay(h.newProbationEnd)}`}
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
        <section className="panel">
          <h3>Check-ins</h3>
          {r.checkins.length === 0 && <p className="hint">No check-ins fall before the end of this probation.</p>}
          {r.checkins.map((k) => (
            <Checkin key={k.id} review={r} checkin={k} onChanged={review.reload} />
          ))}
        </section>
      </div>
      {r.stage === 'decided' && r.decision === 'extend' && (
        <p className="hint">The extension has its own review in the list, with check-ins every 30 days.</p>
      )}
    </>
  )
}

function Checkin({ review, checkin: k, onChanged }) {
  const [writing, setWriting] = useState(false)
  const [rating, setRating] = useState(k.rating || '')
  const [note, setNote] = useState(k.note || '')
  const { busy, error, run } = useAction()
  const [tone, words] = RATINGS[k.rating] || ['neutral', '']
  const overdue = !k.writtenAt && k.dueDate < localToday() && ['open', 'recommended'].includes(review.status)
  return (
    <div className="checkin" data-checkin={k.day}>
      <div className="checkin-head">
        <strong>Day {k.day}</strong> <span className="hint">due {formatDay(k.dueDate)}</span>{' '}
        {k.writtenAt ? <Badge tone={tone}>{words}</Badge> : overdue ? <Badge tone="warn">Overdue</Badge> : <Badge>Not written</Badge>}
      </div>
      {k.writtenAt && !writing && (
        <>
          <p className="pre">{k.note}</p>
          <p className="hint">
            {k.writtenByName}, {formatDate(k.writtenAt)}
          </p>
          {k.reply && (
            <div className="reply">
              <span className="hint">Their reply, {formatDate(k.repliedAt)}</span>
              <p className="pre">{k.reply}</p>
            </div>
          )}
        </>
      )}
      {review.canWrite && !k.repliedAt && !writing && (
        <button type="button" className="outline" onClick={() => setWriting(true)}>
          {k.writtenAt ? 'Change' : 'Write the check-in'}
        </button>
      )}
      {writing && (
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            if (await run(() => performance.writeCheckin(review.id, k.id, { rating, note }))) {
              setWriting(false)
              onChanged()
            }
          }}
        >
          <fieldset className="choice-list perf-choices">
            <legend>How are they doing?</legend>
            {Object.entries(RATINGS).map(([value, [, label]]) => (
              <label key={value} className={rating === value ? 'choice choice-current' : 'choice'}>
                <input type="radio" name={`rating-${k.id}`} value={value} checked={rating === value} onChange={() => setRating(value)} />
                <span>{label}</span>
              </label>
            ))}
          </fieldset>
          <label htmlFor={`note-${k.id}`}>
            Note <span className="hint">(the employee reads this)</span>
          </label>
          <textarea id={`note-${k.id}`} rows={4} value={note} onChange={(e) => setNote(e.target.value)} />
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              {busy ? 'Saving…' : 'Save the check-in'}
            </button>
            <button type="button" className="quiet" onClick={() => setWriting(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </div>
  )
}

function Choice({ name, value, onChange }) {
  return (
    <fieldset className="choice-list perf-choices">
      <legend>{name}</legend>
      {Object.entries(CHOICES).map(([k, label]) => (
        <label key={k} className={value === k ? 'choice choice-current' : 'choice'}>
          <input type="radio" name={name} value={k} checked={value === k} onChange={() => onChange(k)} />
          <span>{label}</span>
        </label>
      ))}
    </fieldset>
  )
}

function Recommend({ review: r, onDone, onCancel }) {
  const [choice, setChoice] = useState(r.recommendation || '')
  const [extendBy, setExtendBy] = useState(String(r.recommendMonths || 3))
  const [note, setNote] = useState(r.recommendationNote || '')
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        const body = { recommendation: choice, months: choice === 'extend' ? Number(extendBy) : null, note }
        if (await run(() => performance.recommend(r.id, body))) onDone()
      }}
    >
      <h3>Your recommendation</h3>
      <p className="hint">HR decides. You can change your recommendation until then.</p>
      <Choice name="Recommendation" value={choice} onChange={setChoice} />
      {choice === 'extend' && <MonthsField id="rec-months" value={extendBy} onChange={setExtendBy} end={r.probationEnd} />}
      <label htmlFor="rec-note">
        {choice === 'confirm' || !choice ? 'Note for HR (optional)' : 'Why, for HR'}{' '}
        <span className="hint">(the employee does not see this)</span>
      </label>
      <textarea id="rec-note" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy || !choice}>
          Send to HR
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function Decide({ review: r, onDone, onCancel }) {
  const [choice, setChoice] = useState(r.recommendation || '')
  const [extendBy, setExtendBy] = useState(String(r.recommendMonths || 3))
  const [note, setNote] = useState('')
  const [lastDay, setLastDay] = useState(r.proposedLastDay)
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        const body = {
          decision: choice,
          months: choice === 'extend' ? Number(extendBy) : null,
          note,
          lastWorkingDay: choice === 'not_confirm' ? lastDay : null,
        }
        if (await run(() => performance.decide(r.id, body))) onDone()
      }}
    >
      <h3>Decide the probation</h3>
      <Choice name="Decision" value={choice} onChange={setChoice} />
      {choice === 'confirm' && (
        <p className="hint">
          Confirmed from {formatDay(nextDay(r.probationEnd))}. The confirmation letter is issued and the employee is told.
        </p>
      )}
      {choice === 'extend' && (
        <>
          <MonthsField id="dec-months" value={extendBy} onChange={setExtendBy} end={r.probationEnd} />
          <p className="hint">The extension letter is issued, and a new review starts with check-ins every 30 days.</p>
        </>
      )}
      {choice === 'not_confirm' && (
        <>
          {!r.canEndEmployment && <p className="error">Ending an employment needs the exit.manage permission.</p>}
          <label htmlFor="dec-last-day">Last working day</label>
          <input id="dec-last-day" type="date" min={localToday()} value={lastDay} onChange={(e) => setLastDay(e.target.value)} />
          <p className="hint">
            The probation notice is {r.probationNoticeDays} days. An exit starts under Exits, as a termination for performance, with its
            clearance and settlement. Take legal advice before not confirming someone.
          </p>
        </>
      )}
      <label htmlFor="dec-note">
        {choice === 'confirm' || !choice ? 'Note (optional)' : 'The reason'} <span className="hint">(kept with the decision)</span>
      </label>
      <textarea id="dec-note" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className={choice === 'not_confirm' ? 'danger' : 'primary'} disabled={busy || !choice}>
          {busy ? 'Saving…' : choice ? CHOICES[choice] : 'Decide'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function MonthsField({ id, value, onChange, end }) {
  const until = addMonths(end, Number(value))
  return (
    <div>
      <label htmlFor={id}>Extend by</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        {[1, 2, 3, 4, 5, 6].map((n) => (
          <option key={n} value={n}>
            {months(n)}
          </option>
        ))}
      </select>
      <p className="hint">The probation would end on {formatDay(until)}.</p>
    </div>
  )
}

function nextDay(date) {
  const d = new Date(`${date}T12:00:00`)
  d.setDate(d.getDate() + 1)
  return iso(d)
}

/** The same day some months later, as the server counts it: 31 January plus one month is 28 or 29 February. */
function addMonths(date, n) {
  const d = new Date(`${date}T12:00:00`)
  const day = d.getDate()
  d.setDate(1)
  d.setMonth(d.getMonth() + n)
  const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()
  d.setDate(Math.min(day, last))
  return iso(d)
}

const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
