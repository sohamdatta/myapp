import { useState } from 'react'
import { performance } from '../api.js'
import { formatDate, formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, ErrorNote, Trail } from '../ui.jsx'
import { REVIEW_STATUS } from './performanceText.js'

/**
 * One review, for whoever opens it: the employee writes their self review and later reads the
 * released review; the manager (or HR) writes the manager's part, with the overall rating worked
 * out as they go.
 */
export default function ReviewPage({ id, onBack, backLabel = '← Back' }) {
  const review = useLoad(() => performance.review(id))
  const [view, setView] = useState(null)
  const r = view || review.data
  if (review.loading && !r) return <p className="muted">Loading…</p>
  if (review.error) return <ErrorNote>{review.error}</ErrorNote>
  const employee = r.youAre === 'employee'
  const [tone, forHr, forEmployee] = REVIEW_STATUS[r.status] || ['neutral', r.status, r.status]
  const levelName = (v) => r.scale.find((l) => l.value === v)?.label
  return (
    <div className="review" data-testid="review">
      {onBack && (
        <p>
          <button type="button" className="quiet plain" onClick={onBack}>
            {backLabel}
          </button>
        </p>
      )}
      <div className="section-head">
        <div>
          <h2>
            {employee ? r.cycle.name : r.employee.name} <Badge tone={tone}>{employee ? forEmployee : forHr}</Badge>
          </h2>
          <p className="muted">
            {employee
              ? `${formatDay(r.cycle.periodStart)} to ${formatDay(r.cycle.periodEnd)}${r.manager ? ` · reviewed by ${r.manager}` : ''}`
              : [r.cycle.name, r.employee.designation, r.employee.department, r.manager && `manager ${r.manager}`].filter(Boolean).join(' · ')}
          </p>
        </div>
      </div>
      {r.finalLevel && (
        <p className="notice" data-testid="final-rating">
          Overall rating: <strong>{r.finalLevel} · {levelName(r.finalLevel)}</strong>
        </p>
      )}
      {r.selfReturnNote && r.status === 'self_review' && employee && <p className="notice">Returned by your manager: {r.selfReturnNote}</p>}
      {r.skipLevelNote && r.status === 'manager_review' && !employee && (
        <p className="notice" data-testid="skip-level-note">
          Sent back by {r.skipLevelManager}: {r.skipLevelNote}
        </p>
      )}
      {r.status === 'skip_level' && !employee && !r.canSignOff && <p className="notice">Waiting for {r.skipLevelManager} to sign it off.</p>}
      {r.canSignOff && <SignOff r={r} onSaved={setView} />}
      {r.canWriteSelf && <EntryForm r={r} role="self" onSaved={setView} />}
      {r.canWriteManager && <EntryForm r={r} role="manager" onSaved={setView} />}
      {!r.canWriteSelf && !r.canWriteManager && <ReadOnly r={r} levelName={levelName} />}
      {r.skipLevelEntry && (
        <section className="panel">
          <h3>Signed off by {r.skipLevelEntry.reviewer}</h3>
          {r.skipLevelEntry.comment && <p className="pre">{r.skipLevelEntry.comment}</p>}
          <p className="hint">{formatDate(r.skipLevelEntry.submittedAt)}</p>
        </section>
      )}
      {(r.peerLimit > 0 || r.peers?.length > 0) && <Peers r={r} onSaved={setView} />}
      {r.canReturnSelf && <ReturnSelf r={r} onSaved={setView} />}
      {r.canAcknowledge && <Acknowledge r={r} onSaved={setView} />}
      {r.acknowledgedAt && (
        <section className="panel">
          <h3>Acknowledged {formatDate(r.acknowledgedAt)}</h3>
          {r.employeeComment && <p className="pre">{r.employeeComment}</p>}
        </section>
      )}
    </div>
  )
}

/** The worked-out overall rating, as the server works it out: the goals' weighted average and the competencies' average. */
function proposed(r, goalRatings, compRatings) {
  let sum = 0
  let weights = 0
  for (const g of r.goals) {
    const v = goalRatings[g.id]?.rating
    if (v) {
      sum += v * g.weight
      weights += g.weight
    }
  }
  const goalPart = weights ? sum / weights : null
  const comps = r.competencies.map((k) => compRatings[k.id]?.rating).filter(Boolean)
  const compPart = comps.length ? comps.reduce((a, b) => a + b, 0) / comps.length : null
  if (goalPart == null && compPart == null) return null
  if (compPart == null || r.competencies.length === 0) return goalPart
  if (goalPart == null) return compPart
  return (goalPart * r.cycle.goalWeight + compPart * (100 - r.cycle.goalWeight)) / 100
}

function toMap(list) {
  return Object.fromEntries((list || []).map((x) => [x.id, { rating: x.rating || null, comment: x.comment || '' }]))
}

function EntryForm({ r, role, onSaved }) {
  const mine = role === 'self' ? r.self : r.managerEntry
  const [goals, setGoals] = useState(() => toMap(mine?.goalRatings))
  const [comps, setComps] = useState(() => toMap(mine?.competencyRatings))
  const [answers, setAnswers] = useState(() => mine?.answers || {})
  const [overall, setOverall] = useState(mine?.overall ? String(mine.overall) : '')
  const [reason, setReason] = useState(r.overrideReason || '')
  const [promote, setPromote] = useState(Boolean(mine?.promotionRecommended))
  const [designation, setDesignation] = useState(mine?.promotionDesignation || '')
  const [saved, setSaved] = useState(false)
  const { busy, error, run } = useAction()
  const manager = role === 'manager'
  const selfGoals = toMap(r.self?.goalRatings)
  const worked = manager ? proposed(r, goals, comps) : null
  const rounded = worked == null ? null : Math.round(worked + 1e-9)
  const chosen = overall ? Number(overall) : rounded
  const overriding = manager && chosen != null && rounded != null && chosen !== rounded

  async function save(submit) {
    setSaved(false)
    const list = (m) => Object.entries(m).map(([id, v]) => ({ id, rating: v.rating, comment: v.comment }))
    const next = await run(() =>
      performance.saveEntry(r.id, role, {
        goalRatings: list(goals),
        competencyRatings: manager ? list(comps) : [],
        answers,
        overall: manager ? chosen : null,
        overrideReason: overriding ? reason : null,
        promotionRecommended: manager && promote,
        promotionDesignation: manager && promote ? designation : null,
        submit,
      }),
    )
    if (next) {
      setSaved(!submit)
      onSaved(next)
    }
  }

  const questions = r.questions.filter((q) => q.for === role)
  return (
    <form
      className="panel"
      onSubmit={(e) => {
        e.preventDefault()
        save(true)
      }}
    >
      <h3>{manager ? 'Your review' : 'Your self review'}</h3>
      {r.cycle[manager ? 'managerDue' : 'selfDue'] && <p className="hint">Due {formatDay(r.cycle[manager ? 'managerDue' : 'selfDue'])}.</p>}
      {manager && r.feedbackCount > 0 && <p className="hint">{r.feedbackCount} pieces of feedback this period are under Team → Feedback.</p>}
      <h4>Goals</h4>
      {r.goals.length === 0 && <p className="hint">No goals were set for this period.</p>}
      {r.goals.map((g) => (
        <div className="review-item" key={g.id} data-review-goal={g.title}>
          <div className="goal-head">
            <strong>{g.title}</strong>
            <span className="goal-weight">{g.weight}%</span>
          </div>
          {g.status === 'agreed' && g.progress != null && <p className="hint">Progress {g.progress}%</p>}
          {manager && selfGoals[g.id]?.rating && (
            <p className="hint">
              Their rating: {selfGoals[g.id].rating}
              {selfGoals[g.id].comment && ` · “${selfGoals[g.id].comment}”`}
            </p>
          )}
          <RatingPicker
            name={`${role}-goal-${g.id}`}
            scale={r.scale}
            value={goals[g.id]?.rating}
            onChange={(rating) => setGoals({ ...goals, [g.id]: { ...goals[g.id], rating } })}
          />
          <input
            aria-label={`Comment on ${g.title}`}
            placeholder="Comment (optional)"
            value={goals[g.id]?.comment || ''}
            onChange={(e) => setGoals({ ...goals, [g.id]: { ...goals[g.id], comment: e.target.value } })}
          />
        </div>
      ))}
      {manager && r.competencies.length > 0 && (
        <>
          <h4>Competencies</h4>
          {r.competencies.map((k) => (
            <div className="review-item" key={k.id} data-review-competency={k.name}>
              <strong>{k.name}</strong>
              {k.description && <p className="hint">{k.description}</p>}
              <RatingPicker
                name={`comp-${k.id}`}
                scale={r.scale}
                value={comps[k.id]?.rating}
                onChange={(rating) => setComps({ ...comps, [k.id]: { ...comps[k.id], rating } })}
              />
            </div>
          ))}
        </>
      )}
      {manager && r.self && (
        <details className="their-answers">
          <summary>Their self review</summary>
          {r.questions
            .filter((q) => q.for === 'self')
            .map((q) => (
              <div key={q.id}>
                <h4>{q.text}</h4>
                <p className="pre">{r.self.answers?.[q.id] || '—'}</p>
              </div>
            ))}
        </details>
      )}
      {questions.map((q) => (
        <div key={q.id}>
          <label htmlFor={`${role}-q-${q.id}`}>
            {q.text} {!q.required && <span className="hint">(optional)</span>}
          </label>
          {q.type === 'rating' ? (
            <RatingPicker name={`${role}-q-${q.id}`} scale={r.scale} value={answers[q.id]} onChange={(v) => setAnswers({ ...answers, [q.id]: v })} />
          ) : (
            <textarea id={`${role}-q-${q.id}`} rows={3} value={answers[q.id] || ''} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />
          )}
        </div>
      ))}
      {manager && (
        <div className="overall">
          <h4>Overall rating</h4>
          <p className="hint" data-testid="worked-out">
            {worked == null ? 'Rate the goals to see the worked-out rating.' : `Worked out from your ratings: ${worked.toFixed(2)}, rounded to ${rounded}.`}
          </p>
          <label htmlFor="overall">Overall</label>
          <select id="overall" value={overall} onChange={(e) => setOverall(e.target.value)}>
            <option value="">{rounded ? `As worked out (${rounded})` : 'Choose…'}</option>
            {r.scale.map((l) => (
              <option key={l.value} value={l.value}>
                {l.value} · {l.label}
              </option>
            ))}
          </select>
          {overriding && (
            <>
              <label htmlFor="override-reason">Why it differs from the worked-out rating</label>
              <textarea id="override-reason" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
            </>
          )}
          <label className="check">
            <input type="checkbox" checked={promote} onChange={(e) => setPromote(e.target.checked)} /> Recommend a promotion
          </label>
          {promote && (
            <>
              <label htmlFor="promote-to">To</label>
              <input id="promote-to" value={designation} placeholder="Senior Analyst" onChange={(e) => setDesignation(e.target.value)} />
            </>
          )}
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">Saved as a draft.</p>}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {manager ? 'Send the review' : 'Send to my manager'}
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => save(false)}>
          Save a draft
        </button>
      </div>
      <p className="hint">{manager ? 'Once sent, the review goes to calibration and cannot change.' : 'Once sent, your manager reads it; it cannot change unless they return it.'}</p>
    </form>
  )
}

function RatingPicker({ name, scale, value, onChange }) {
  return (
    <div className="rating-picker" role="radiogroup" aria-label="Rating">
      {[...scale].reverse().map((l) => (
        <label key={l.value} className={value === l.value ? 'rating-option rating-current' : 'rating-option'} title={l.description || l.label}>
          <input type="radio" name={name} checked={value === l.value} onChange={() => onChange(l.value)} />
          <span>{l.value}</span> <span className="rating-label">{l.label}</span>
        </label>
      ))}
    </div>
  )
}

function ReadOnly({ r, levelName }) {
  const self = r.self
  const manager = r.managerEntry
  const byId = (list) => Object.fromEntries((list || []).map((x) => [x.id, x]))
  const selfGoals = byId(self?.goalRatings)
  const managerGoals = byId(manager?.goalRatings)
  const managerComps = byId(manager?.competencyRatings)
  return (
    <div className="panels">
      <section className="panel">
        <h3>Goals</h3>
        {r.goals.map((g) => (
          <div className="review-item" key={g.id}>
            <div className="goal-head">
              <strong>{g.title}</strong>
              <span className="goal-weight">{g.weight}%</span>
            </div>
            {selfGoals[g.id]?.rating && (
              <p className="hint">
                Self: {selfGoals[g.id].rating} · {levelName(selfGoals[g.id].rating)}
                {selfGoals[g.id].comment && ` · “${selfGoals[g.id].comment}”`}
              </p>
            )}
            {managerGoals[g.id]?.rating && (
              <p>
                Manager: {managerGoals[g.id].rating} · {levelName(managerGoals[g.id].rating)}
                {managerGoals[g.id].comment && ` · “${managerGoals[g.id].comment}”`}
              </p>
            )}
          </div>
        ))}
        {r.goals.length === 0 && <p className="hint">No goals in this period.</p>}
        {r.managerLevel && (
          <p>
            Manager's overall rating: <strong>{r.managerLevel} · {levelName(r.managerLevel)}</strong>
          </p>
        )}
        {r.competencies.length > 0 && manager && (
          <>
            <h4>Competencies</h4>
            {r.competencies.map((k) => (
              <p key={k.id}>
                {k.name}: {managerComps[k.id]?.rating ? `${managerComps[k.id].rating} · ${levelName(managerComps[k.id].rating)}` : '—'}
              </p>
            ))}
          </>
        )}
        {r.why?.length > 0 && (
          <>
            <h4>Why</h4>
            <Trail steps={r.why} />
            {r.overrideReason && <p className="hint">The manager's reason: {r.overrideReason}</p>}
            {r.calibratedLevel && r.calibratedLevel !== r.managerLevel && (
              <p className="hint">
                Calibration changed it from {r.managerLevel} to {r.calibratedLevel}.
              </p>
            )}
          </>
        )}
      </section>
      <section className="panel">
        {self ? (
          <>
            <h3>Self review</h3>
            {r.questions
              .filter((q) => q.for === 'self')
              .map((q) => (
                <div key={q.id}>
                  <h4>{q.text}</h4>
                  <p className="pre">{self.answers?.[q.id] || '—'}</p>
                </div>
              ))}
          </>
        ) : (
          <p className="hint">{r.cycle.selfReview ? 'No self review yet.' : 'This cycle has no self review.'}</p>
        )}
        {manager && (
          <>
            <h3>Manager's review</h3>
            {r.questions
              .filter((q) => q.for === 'manager')
              .map((q) => (
                <div key={q.id}>
                  <h4>{q.text}</h4>
                  <p className="pre">{manager.answers?.[q.id] || '—'}</p>
                </div>
              ))}
            {manager.promotionRecommended && r.youAre !== 'employee' && <p>Recommends a promotion to {manager.promotionDesignation}.</p>}
            <p className="hint">
              {manager.reviewer}
              {manager.submittedAt ? `, sent ${formatDate(manager.submittedAt)}` : ', a draft'}
            </p>
          </>
        )}
      </section>
    </div>
  )
}

function ReturnSelf({ r, onSaved }) {
  const [open, setOpen] = useState(false)
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  if (!open) {
    return (
      <p>
        <button type="button" className="quiet plain" onClick={() => setOpen(true)}>
          Return their self review…
        </button>
      </p>
    )
  }
  return (
    <div className="inline-form">
      <div>
        <label htmlFor="return-self">What should they add or change (once only)</label>
        <input id="return-self" value={note} onChange={(e) => setNote(e.target.value)} />
      </div>
      <div className="form-actions">
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={async () => {
            const next = await run(() => performance.returnSelf(r.id, note))
            if (next) onSaved(next)
          }}
        >
          Return
        </button>
        <button type="button" className="quiet" onClick={() => setOpen(false)}>
          Cancel
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </div>
  )
}

function Acknowledge({ r, onSaved }) {
  const [comment, setComment] = useState('')
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        const next = await run(() => performance.acknowledge(r.id, comment))
        if (next) onSaved(next)
      }}
    >
      <h3>Acknowledge your review</h3>
      <label htmlFor="ack-comment">
        Your comments <span className="hint">(optional; your manager and HR read them)</span>
      </label>
      <textarea id="ack-comment" rows={3} value={comment} onChange={(e) => setComment(e.target.value)} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Acknowledge
        </button>
      </div>
      <p className="hint">Acknowledging says you have read it, not that you agree.</p>
    </form>
  )
}

const PEER_STATUS = { open: ['neutral', 'Asked'], submitted: ['good', 'Written'], declined: ['neutral', 'Declined'], expired: ['neutral', 'Not written'] }

/** Peer reviews: for the manager and HR, who was asked and what they wrote, and asking more; for the employee, once released, what was written. */
function Peers({ r, onSaved }) {
  const employee = r.youAre === 'employee'
  const colleagues = useLoad(() => (r.canRequestPeers ? performance.colleagues() : Promise.resolve([])))
  const [who, setWho] = useState('')
  const { busy, error, run } = useAction()
  const peerQuestions = r.peerQuestions || []
  const asked = new Set((r.peers || []).map((p) => p.name))
  if (employee && r.peers.length === 0) return null
  return (
    <section className="panel" data-testid="peers">
      <h3>{employee ? 'What colleagues said' : 'Peer reviews'}</h3>
      {employee && r.peersAnonymous && <p className="hint">Shown without names, in no particular order.</p>}
      {!employee && r.peers.length === 0 && <p className="hint">No colleague has been asked yet.</p>}
      {r.peers.map((p, i) => {
        const [tone, words] = PEER_STATUS[p.status] || ['neutral', p.status]
        return (
          <div key={p.id} className="review-item" data-peer={p.name || `peer-${i + 1}`}>
            {!employee && (
              <div className="goal-head">
                <strong>{p.name}</strong> <Badge tone={tone}>{words}</Badge>
              </div>
            )}
            {p.declinedReason && <p className="hint">Declined: {p.declinedReason}</p>}
            {Object.entries(p.answers || {}).map(([q, text]) => (
              <div key={q}>
                <h4>{peerQuestions.find((x) => x.id === q)?.text || q}</h4>
                <p className="pre">{text || '—'}</p>
              </div>
            ))}
          </div>
        )
      })}
      {r.canRequestPeers && (
        <div className="inline-form">
          <div>
            <label htmlFor="peer-who">Ask a colleague</label>
            <select id="peer-who" value={who} onChange={(e) => setWho(e.target.value)}>
              <option value="">Choose…</option>
              {(colleagues.data || [])
                .filter((c) => c.id !== r.employee.id && c.name !== r.manager && !asked.has(c.name))
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                    {c.designation ? ` · ${c.designation}` : ''}
                  </option>
                ))}
            </select>
          </div>
          <div className="form-actions">
            <button
              type="button"
              className="secondary"
              disabled={busy || !who}
              onClick={async () => {
                const next = await run(() => performance.requestPeers(r.id, [who]))
                if (next) {
                  setWho('')
                  onSaved(next)
                }
              }}
            >
              Ask
            </button>
          </div>
          <p className="hint">
            Up to {r.peerLimit} a person. {r.peersAnonymous ? 'The person reads them without names.' : 'The person reads them with names.'}
          </p>
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

/** The manager's manager signs the review off, or sends it back to the manager with a note. */
function SignOff({ r, onSaved }) {
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  const act = async (action) => {
    const next = await run(() => performance.signOff(r.id, action, note))
    if (next) onSaved(next)
  }
  return (
    <section className="panel" data-testid="sign-off">
      <h3>Sign off</h3>
      <p className="hint">
        {r.manager} has written this review. Signing it off sends it to calibration; sending it back reopens it for {r.manager} with your note.
      </p>
      <label htmlFor="sign-off-note">Comment, or what should change</label>
      <textarea id="sign-off-note" rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="primary" disabled={busy} onClick={() => act('agree')}>
          Sign it off
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => act('return')}>
          Send back
        </button>
      </div>
    </section>
  )
}
