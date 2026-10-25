import { useState } from 'react'
import { downloadDocument as download, performance } from '../api.js'
import { formatDate, formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { PLAN_STAGE, PROGRESS } from './performanceText.js'

/**
 * The Improvement plans tab, for managers (their team) and HR (everyone): the plans running and
 * in draft, a new plan, and one plan with its check-ins and decision.
 */
export default function PlansTab() {
  const [which, setWhich] = useState('live')
  const [open, setOpen] = useState(null) // a plan id, or 'new'
  const list = useLoad(() => performance.plans(which === 'all' ? 'all' : null))
  if (open === 'new') return <PlanForm onDone={(p) => (setOpen(p ? p.id : null), list.reload())} />
  if (open) return <PlanPage id={open} onBack={() => (setOpen(null), list.reload())} />
  return (
    <section className="panel" data-testid="plans">
      <div className="section-head">
        <h3>Improvement plans</h3>
        <div className="form-actions">
          <button type="button" className="primary" onClick={() => setOpen('new')}>
            New plan
          </button>
        </div>
      </div>
      <p className="hint">
        A plan sets out what has to improve, how it is measured and the support offered, over a fixed time with regular check-ins. A
        manager drafts it; HR starts it and decides it at the end.
      </p>
      <div className="filters">
        {[
          ['live', 'Running and drafts'],
          ['all', 'All'],
        ].map(([id, label]) => (
          <button key={id} type="button" className={which === id ? 'chip chip-current' : 'chip'} onClick={() => (setWhich(id), list.reload())}>
            {label}
          </button>
        ))}
      </div>
      {list.loading && <p className="muted">Loading…</p>}
      <ErrorNote>{list.error}</ErrorNote>
      {list.data && list.data.length === 0 && <Empty>No improvement plans.</Empty>}
      {list.data && list.data.length > 0 && (
        <div className="table-wrap">
          <table className="stack">
            <thead>
              <tr>
                <th>Name</th>
                <th>Manager</th>
                <th>Runs</th>
                <th>Stage</th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((p) => {
                const [tone, words] = PLAN_STAGE[p.stage] || ['neutral', p.stage]
                return (
                  <tr key={p.id} data-plan={p.name}>
                    <td>
                      <button type="button" className="text-link" onClick={() => setOpen(p.id)}>
                        {p.name}
                      </button>
                      <div className="hint">{[p.designation, p.department].filter(Boolean).join(' · ')}</div>
                    </td>
                    <td>{p.manager || '—'}</td>
                    <td>{p.startDate ? `${formatDay(p.startDate)} to ${formatDay(p.endDate)}` : '—'}</td>
                    <td>
                      <Badge tone={tone}>{words}</Badge>
                      {p.status === 'active' && !p.acknowledgedAt && <div className="hint">Not acknowledged yet</div>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

const BLANK_OBJECTIVE = { title: '', measure: '' }

function PlanForm({ plan, onDone }) {
  const team = useLoad(performance.team)
  const [form, setForm] = useState(() => ({
    employmentId: plan?.employee.id || '',
    reason: plan?.reason || '',
    support: plan?.support || '',
    lengthDays: String(plan?.lengthDays || 60),
    checkinEvery: String(plan?.checkinEvery || 14),
    objectives: plan?.objectives.map((o) => ({ title: o.title, measure: o.measure })) || [{ ...BLANK_OBJECTIVE }, { ...BLANK_OBJECTIVE }],
  }))
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  const setObjective = (i, patch) => set({ objectives: form.objectives.map((o, j) => (i === j ? { ...o, ...patch } : o)) })
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      data-testid="plan-form"
      onSubmit={async (e) => {
        e.preventDefault()
        const saved = await run(() =>
          performance.savePlan(plan?.id, {
            ...form,
            lengthDays: Number(form.lengthDays),
            checkinEvery: Number(form.checkinEvery),
          }),
        )
        if (saved) onDone(saved)
      }}
    >
      <h3>{plan ? 'Change the draft plan' : 'New improvement plan'}</h3>
      <div className="grid">
        <div>
          <label htmlFor="plan-employee">For</label>
          {plan ? (
            <p>{plan.employee.name}</p>
          ) : (
            <select id="plan-employee" value={form.employmentId} onChange={(e) => set({ employmentId: e.target.value })}>
              <option value="">Choose someone…</option>
              {(team.data?.people || []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          )}
        </div>
        <div>
          <label htmlFor="plan-length">Length</label>
          <select id="plan-length" value={form.lengthDays} onChange={(e) => set({ lengthDays: e.target.value })}>
            {[30, 45, 60, 90].map((d) => (
              <option key={d} value={d}>
                {d} days
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="plan-every">Check in</label>
          <select id="plan-every" value={form.checkinEvery} onChange={(e) => set({ checkinEvery: e.target.value })}>
            <option value="7">Every week</option>
            <option value="14">Every 2 weeks</option>
            <option value="30">Every 30 days</option>
          </select>
        </div>
      </div>
      <label htmlFor="plan-reason">
        Why the plan is needed <span className="hint">(the employee reads this; be specific, with examples)</span>
      </label>
      <textarea id="plan-reason" rows={3} value={form.reason} onChange={(e) => set({ reason: e.target.value })} />
      <fieldset className="plan-objectives">
        <legend>What has to improve, and how it is measured</legend>
        {form.objectives.map((o, i) => (
          <div key={i} className="grid">
            <div>
              <label htmlFor={`obj-title-${i}`}>Objective {i + 1}</label>
              <input id={`obj-title-${i}`} value={o.title} onChange={(e) => setObjective(i, { title: e.target.value })} />
            </div>
            <div>
              <label htmlFor={`obj-measure-${i}`}>Measured by</label>
              <input id={`obj-measure-${i}`} value={o.measure} onChange={(e) => setObjective(i, { measure: e.target.value })} />
            </div>
          </div>
        ))}
        {form.objectives.length < 6 && (
          <button type="button" className="quiet plain" onClick={() => set({ objectives: [...form.objectives, { ...BLANK_OBJECTIVE }] })}>
            Add an objective
          </button>
        )}
      </fieldset>
      <label htmlFor="plan-support">Support offered</label>
      <textarea id="plan-support" rows={2} value={form.support} onChange={(e) => set({ support: e.target.value })} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {plan ? 'Save' : 'Save the draft'}
        </button>
        <button type="button" className="quiet" onClick={() => onDone(null)}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function PlanPage({ id, onBack }) {
  const loaded = useLoad(() => performance.plan(id))
  const [view, setView] = useState(null)
  const [editing, setEditing] = useState(false)
  const p = view || loaded.data
  if (loaded.loading && !p) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  if (editing) return <PlanForm plan={p} onDone={(next) => (setEditing(false), next && setView(next))} />
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← All plans
        </button>
      </p>
      <PlanView plan={p} onChanged={setView} onEdit={() => setEditing(true)} />
    </>
  )
}

/** One plan, as the caller may act on it: the employee, their manager, or HR. */
export function PlanView({ plan: p, onChanged, onEdit }) {
  const [tone, words] = PLAN_STAGE[p.stage] || ['neutral', p.stage]
  const { error, run } = useAction()
  const own = p.youAre === 'employee'
  return (
    <div data-testid="plan">
      <div className="section-head">
        <div>
          <h2>
            {own ? 'Your improvement plan' : `Improvement plan: ${p.employee.name}`} <Badge tone={tone}>{words}</Badge>
          </h2>
          <p className="muted">
            {p.startDate ? `${formatDay(p.startDate)} to ${formatDay(p.endDate)}` : `${p.lengthDays} days`} · check-ins every {p.checkinEvery} days
            {p.originalEnd && ` · extended from ${formatDay(p.originalEnd)}`}
            {!own && p.employee.manager && ` · manager ${p.employee.manager}`}
          </p>
        </div>
        {p.canEdit && (
          <div className="form-actions">
            <button type="button" className="secondary" onClick={onEdit}>
              Change
            </button>
          </div>
        )}
      </div>
      {p.letter && (
        <p>
          <button type="button" className="text-link" onClick={() => run(() => download(p.letter.documentId, `${p.letter.title}.pdf`))}>
            {p.letter.title}
          </button>{' '}
          <span className="hint">issued {formatDate(p.letter.issuedAt)}</span>
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      {p.status === 'cancelled' && <p className="notice">Cancelled: {p.cancelledReason}</p>}
      <section className="panel">
        <h3>Why</h3>
        <p className="pre">{p.reason}</p>
        <h3>What has to improve</h3>
        <ol className="plan-objective-list">
          {p.objectives.map((o) => (
            <li key={o.id}>
              <strong>{o.title}</strong>
              <div className="hint">Measured by: {o.measure}</div>
            </li>
          ))}
        </ol>
        {p.support && (
          <>
            <h3>Support</h3>
            <p className="pre">{p.support}</p>
          </>
        )}
      </section>
      <Acknowledgement plan={p} onChanged={onChanged} />
      {p.canStart && <StartPlan plan={p} onChanged={onChanged} />}
      {p.startedAt && (
        <section className="panel">
          <h3>Check-ins</h3>
          {p.checkins.length === 0 && <p className="hint">Your manager has not written a check-in yet.</p>}
          {p.checkins.map((k) => (
            <PlanCheckin key={k.id} plan={p} checkin={k} onChanged={onChanged} />
          ))}
        </section>
      )}
      {p.decisionNote && (
        <section className="panel">
          <h3>Decision</h3>
          <p className="pre">{p.decisionNote}</p>
        </section>
      )}
      {(p.canDecide || p.canCancel) && <DecidePlan plan={p} onChanged={onChanged} />}
    </div>
  )
}

function Acknowledgement({ plan: p, onChanged }) {
  const [text, setText] = useState('')
  const { busy, error, run } = useAction()
  if (p.acknowledgedAt) {
    return (
      <section className="panel">
        <p>
          <Badge tone="good">Acknowledged</Badge> <span className="hint">{formatDate(p.acknowledgedAt)}</span>
        </p>
        {p.response && (
          <>
            <h3>{p.youAre === 'employee' ? 'Your response' : 'Their response'}</h3>
            <p className="pre">{p.response}</p>
          </>
        )}
      </section>
    )
  }
  if (!p.canAcknowledge) {
    return p.startedAt && p.status === 'active' ? <p className="hint">They have not acknowledged the plan yet.</p> : null
  }
  return (
    <section className="panel" data-testid="acknowledge-plan">
      <h3>Acknowledge the plan</h3>
      <p className="hint">
        Acknowledging says you have read the plan; it does not mean you agree with it. You can write a response, which your manager and
        HR read.
      </p>
      <label htmlFor="plan-response">Your response (optional)</label>
      <textarea id="plan-response" rows={3} value={text} onChange={(e) => setText(e.target.value)} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button
          type="button"
          className="primary"
          disabled={busy}
          onClick={async () => {
            const next = await run(() => performance.acknowledgePlan(p.id, text))
            if (next) onChanged(next)
          }}
        >
          I have read it
        </button>
      </div>
    </section>
  )
}

function StartPlan({ plan: p, onChanged }) {
  const [day, setDay] = useState(() => new Date().toISOString().slice(0, 10))
  const { busy, error, run } = useAction()
  return (
    <section className="panel" data-testid="start-plan">
      <h3>Start the plan</h3>
      <p className="hint">
        Starting issues the improvement plan letter, tells {p.employee.name}, and schedules a check-in every {p.checkinEvery} days.
      </p>
      <div className="inline-form">
        <div>
          <label htmlFor="plan-start">Starts on</label>
          <input id="plan-start" type="date" value={day} onChange={(e) => setDay(e.target.value)} />
        </div>
        <div className="form-actions">
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={async () => {
              const next = await run(() => performance.startPlan(p.id, day))
              if (next) onChanged(next)
            }}
          >
            Start the plan
          </button>
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

function PlanCheckin({ plan: p, checkin: k, onChanged }) {
  const [writing, setWriting] = useState(false)
  const [ratings, setRatings] = useState(k.ratings || {})
  const [note, setNote] = useState(k.note || '')
  const [reply, setReply] = useState('')
  const [replying, setReplying] = useState(false)
  const { busy, error, run } = useAction()
  const written = !!k.writtenAt
  return (
    <div className="checkin" data-plan-checkin={k.dueDate}>
      <div className="checkin-head">
        <strong>Due {formatDay(k.dueDate)}</strong>{' '}
        {written ? <span className="hint">{k.writtenByName}, {formatDate(k.writtenAt)}</span> : <Badge tone="neutral">Not written</Badge>}
      </div>
      {written && !writing && (
        <>
          <ul className="bullet-list">
            {p.objectives.map((o) => {
              const [tone, words] = PROGRESS[k.ratings[o.id]] || ['neutral', '—']
              return (
                <li key={o.id}>
                  {o.title} <Badge tone={tone}>{words}</Badge>
                </li>
              )
            })}
          </ul>
          <p className="pre">{k.note}</p>
        </>
      )}
      {k.reply && (
        <div className="reply">
          <span className="hint">
            {p.youAre === 'employee' ? 'Your reply' : 'Their reply'}, {formatDate(k.repliedAt)}
          </span>
          <p className="pre">{k.reply}</p>
        </div>
      )}
      {k.canWrite && !writing && (
        <button type="button" className="outline" onClick={() => setWriting(true)}>
          {written ? 'Change' : 'Write the check-in'}
        </button>
      )}
      {writing && (
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            const next = await run(() => performance.planCheckin(p.id, k.id, { ratings, note }))
            if (next) {
              setWriting(false)
              onChanged(next)
            }
          }}
        >
          {p.objectives.map((o) => (
            <fieldset key={o.id} className="perf-choices">
              <legend>{o.title}</legend>
              {Object.entries(PROGRESS).map(([value, [, words]]) => (
                <label key={value} className="choice">
                  <input
                    type="radio"
                    name={`progress-${k.id}-${o.id}`}
                    checked={ratings[o.id] === value}
                    onChange={() => setRatings({ ...ratings, [o.id]: value })}
                  />{' '}
                  {words}
                </label>
              ))}
            </fieldset>
          ))}
          <label htmlFor={`plan-note-${k.id}`}>
            Note <span className="hint">(the employee reads it)</span>
          </label>
          <textarea id={`plan-note-${k.id}`} rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              Save the check-in
            </button>
            <button type="button" className="quiet" onClick={() => setWriting(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}
      {k.canReply && !replying && (
        <button type="button" className="outline" onClick={() => setReplying(true)}>
          Reply
        </button>
      )}
      {replying && (
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            const next = await run(() => performance.replyToPlanCheckin(k.id, reply))
            if (next) {
              setReplying(false)
              onChanged(next)
            }
          }}
        >
          <label htmlFor={`plan-reply-${k.id}`}>
            Your reply <span className="hint">(your manager and HR read it; you can reply once)</span>
          </label>
          <textarea id={`plan-reply-${k.id}`} rows={3} value={reply} onChange={(e) => setReply(e.target.value)} />
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              Send
            </button>
            <button type="button" className="quiet" onClick={() => setReplying(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </div>
  )
}

const DECISIONS = {
  completed: 'Completed: the objectives are met',
  extended: 'Extend the plan (once)',
  not_met: 'Not met: start the exit',
  cancelled: 'Cancel the plan',
}

function DecidePlan({ plan: p, onChanged }) {
  const choices = Object.keys(DECISIONS).filter((d) =>
    d === 'cancelled' ? p.canCancel : d === 'extended' ? p.canExtend : p.canDecide,
  )
  const [decision, setDecision] = useState('')
  const [note, setNote] = useState('')
  const [days, setDays] = useState('30')
  const [lastDay, setLastDay] = useState('')
  const { busy, error, run } = useAction()
  return (
    <section className="panel" data-testid="decide-plan">
      <h3>{p.status === 'draft' ? 'Cancel the draft' : 'Decide'}</h3>
      {p.status === 'active' && (
        <p className="hint">
          Decide once the plan has ended, on {formatDay(p.endDate)}. Not met needs at least one check-in and starts a termination in Exits, with
          the last working day you give; the employee and their manager are told the decision.
        </p>
      )}
      <fieldset className="perf-choices">
        <legend>Decision</legend>
        {choices.map((d) => (
          <label key={d} className="choice">
            <input type="radio" name="plan-decision" checked={decision === d} onChange={() => setDecision(d)} /> {DECISIONS[d]}
          </label>
        ))}
      </fieldset>
      {decision === 'extended' && (
        <>
          <label htmlFor="plan-extend">Extend by (days)</label>
          <input id="plan-extend" inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value)} />
        </>
      )}
      {decision === 'not_met' && (
        <>
          <label htmlFor="plan-last-day">Last working day</label>
          <input id="plan-last-day" type="date" value={lastDay} onChange={(e) => setLastDay(e.target.value)} />
        </>
      )}
      {decision && (
        <>
          <label htmlFor="plan-decision-note">{decision === 'cancelled' ? 'Why' : 'Note on the decision'}</label>
          <textarea id="plan-decision-note" rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
        </>
      )}
      <ErrorNote>{error}</ErrorNote>
      {decision && (
        <div className="form-actions">
          <button
            type="button"
            className={decision === 'not_met' ? 'secondary danger' : 'primary'}
            disabled={busy}
            onClick={async () => {
              const next = await run(() =>
                performance.decidePlan(p.id, { decision, note, extendDays: Number(days) || null, lastWorkingDay: lastDay || null }),
              )
              if (next) onChanged(next)
            }}
          >
            Record the decision
          </button>
        </div>
      )}
    </section>
  )
}
