import { useState } from 'react'
import { people, performance } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote, Tabs } from '../ui.jsx'
import CalibrationPanel from './Calibration.jsx'
import OutcomesPanel from './Outcomes.jsx'
import { CYCLE_STATUS, REVIEW_STATUS } from './performanceText.js'
import ReviewPage from './ReviewPage.jsx'
import { RuleEditor } from './setup/SetupShared.jsx'
import { describeRules, useSetupLookups } from './setup/setupText.js'

/**
 * The Reviews tab: the reviews the caller writes as a manager, and, for HR, the cycles: set one
 * up, start it, follow it, take it to calibration and release it.
 */
export default function ReviewCycles({ canManage }) {
  const [open, setOpen] = useState(null) // { review } or { cycle } or { cycle: 'new' }
  if (open?.review) return <ReviewPage id={open.review} onBack={() => setOpen(open.from || null)} backLabel="← Back" />
  if (open?.cycle === 'new') return <CycleForm onDone={(id) => setOpen(id ? { cycle: id } : null)} />
  if (open?.cycle) return <CyclePage id={open.cycle} onBack={() => setOpen(null)} onReview={(review) => setOpen({ review, from: open })} />
  return (
    <>
      <ToWrite onOpen={(review) => setOpen({ review })} />
      {canManage && <Cycles onOpen={(cycle) => setOpen({ cycle })} onNew={() => setOpen({ cycle: 'new' })} />}
    </>
  )
}

function ToWrite({ onOpen }) {
  const list = useLoad(performance.reviewsToWrite)
  if (list.loading) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  if (list.data.length === 0) return null
  return (
    <section className="panel" data-testid="reviews-to-write">
      <h3>Reviews you write</h3>
      <div className="table-wrap">
        <table className="stack">
          <thead>
            <tr>
              <th>Name</th>
              <th>Cycle</th>
              <th>Stage</th>
              <th>Due</th>
            </tr>
          </thead>
          <tbody>
            {list.data.map((r) => {
              const [tone, words] = REVIEW_STATUS[r.status] || ['neutral', r.status]
              return (
                <tr key={r.id} data-review={r.name}>
                  <td>
                    <button type="button" className="text-link" onClick={() => onOpen(r.id)}>
                      {r.name}
                    </button>
                    <div className="hint">{r.designation}</div>
                  </td>
                  <td>{r.cycle}</td>
                  <td>
                    <Badge tone={r.status === 'manager_review' ? 'warn' : tone}>{r.status === 'manager_review' ? 'Yours to write' : words}</Badge>
                  </td>
                  <td>{r.status === 'manager_review' && r.managerDue ? formatDay(r.managerDue) : '—'}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function Cycles({ onOpen, onNew }) {
  const list = useLoad(performance.cycles)
  return (
    <section className="panel" data-testid="cycles">
      <div className="section-head">
        <h3>Review cycles</h3>
        <button type="button" className="outline" onClick={onNew}>
          New cycle
        </button>
      </div>
      {list.loading && <p className="muted">Loading…</p>}
      <ErrorNote>{list.error}</ErrorNote>
      {list.data?.length === 0 && <Empty>No review cycles yet.</Empty>}
      {list.data?.length > 0 && (
        <ul className="plain-list cycle-list">
          {list.data.map((y) => {
            const [tone, words] = CYCLE_STATUS[y.status] || ['neutral', y.status]
            return (
              <li key={y.id} data-cycle={y.name}>
                <button type="button" className="text-link" onClick={() => onOpen(y.id)}>
                  {y.name}
                </button>{' '}
                <Badge tone={tone}>{words}</Badge>{' '}
                <span className="hint">
                  {formatDay(y.periodStart)} to {formatDay(y.periodEnd)}
                  {y.status !== 'draft' && ` · ${y.written} of ${y.people} written`}
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

function CycleForm({ cycle, onDone }) {
  const lookups = useSetupLookups()
  const forms = useLoad(performance.forms)
  const [v, setV] = useState(() => ({
    name: cycle?.name || '',
    periodStart: cycle?.periodStart || '',
    periodEnd: cycle?.periodEnd || '',
    appliesTo: cycle?.appliesTo || { employment_type: ['permanent', 'probation', 'fixed_term'] },
    joinedBy: cycle?.joinedBy || '',
    formId: cycle?.formId || '',
    goalWeight: String(cycle?.goalWeight ?? ''),
    selfReview: cycle?.selfReview ?? true,
    selfDue: cycle?.selfDue || '',
    managerDue: cycle?.managerDue || '',
    calibrationDue: cycle?.calibrationDue || '',
    releaseDue: cycle?.releaseDue || '',
    peerReviews: String(cycle?.peerReviews ?? 0),
    peersAnonymous: cycle?.peersAnonymous ?? true,
    skipLevel: cycle?.skipLevelSignoff ?? false,
  }))
  const { busy, error, run } = useAction()
  const set = (patch) => setV({ ...v, ...patch })
  const started = cycle && cycle.status !== 'draft'
  if (lookups.loading) return <p className="muted">Loading…</p>
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        const saved = await run(() =>
          performance.saveCycle(cycle?.id, {
            ...v,
            goalWeight: v.goalWeight === '' ? null : Number(v.goalWeight),
            formId: v.formId || null,
            peerReviews: Number(v.peerReviews),
          }),
        )
        if (saved) onDone(saved.id)
      }}
    >
      <h3>{cycle ? 'Change the cycle' : 'New review cycle'}</h3>
      <label htmlFor="cy-name">Name</label>
      <input id="cy-name" value={v.name} placeholder="Annual review FY 2026-27" onChange={(e) => set({ name: e.target.value })} />
      <fieldset disabled={started} className="grid">
        <div>
          <label htmlFor="cy-start">
            Period starts <span className="hint">(empty: this goal year)</span>
          </label>
          <input id="cy-start" type="date" value={v.periodStart} onChange={(e) => set({ periodStart: e.target.value })} />
        </div>
        <div>
          <label htmlFor="cy-end">Period ends</label>
          <input id="cy-end" type="date" value={v.periodEnd} onChange={(e) => set({ periodEnd: e.target.value })} />
        </div>
        <div>
          <label htmlFor="cy-joined">
            Joined on or before <span className="hint">(empty: 90 days before the end)</span>
          </label>
          <input id="cy-joined" type="date" value={v.joinedBy} onChange={(e) => set({ joinedBy: e.target.value })} />
        </div>
        <div>
          <label htmlFor="cy-weight">
            Goals count for <span className="hint">(%; competencies take the rest)</span>
          </label>
          <input id="cy-weight" inputMode="numeric" value={v.goalWeight} placeholder="70" onChange={(e) => set({ goalWeight: e.target.value })} />
        </div>
        <div>
          <label htmlFor="cy-form">Review form</label>
          <select id="cy-form" value={v.formId} onChange={(e) => set({ formId: e.target.value })}>
            <option value="">The first form</option>
            {(forms.data || []).map((f) => (
              <option key={f.id} value={f.id}>
                {f.name} (version {f.version})
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="cy-peers">Peer reviews a person</label>
          <select id="cy-peers" value={v.peerReviews} onChange={(e) => set({ peerReviews: e.target.value })}>
            <option value="0">None</option>
            {[1, 2, 3, 4, 5].map((n) => (
              <option key={n} value={n}>
                Up to {n}, chosen by the manager
              </option>
            ))}
          </select>
        </div>
        <label className="check">
          <input type="checkbox" checked={v.selfReview} onChange={(e) => set({ selfReview: e.target.checked })} /> Self review first
        </label>
        {v.peerReviews !== '0' && (
          <label className="check">
            <input type="checkbox" checked={v.peersAnonymous} onChange={(e) => set({ peersAnonymous: e.target.checked })} /> The person reads peer
            reviews without names (shown only when two or more are written)
          </label>
        )}
        <label className="check">
          <input type="checkbox" checked={v.skipLevel} onChange={(e) => set({ skipLevel: e.target.checked })} /> The manager's manager signs
          each review off before calibration
        </label>
      </fieldset>
      {!started && <RuleEditor idPrefix="cy" value={v.appliesTo} onChange={(appliesTo) => set({ appliesTo })} lookups={lookups} />}
      {started && <p className="hint">Who is in it: {describeRules(v.appliesTo, lookups)}. Only the name and dates change once started.</p>}
      <div className="grid">
        {[
          ['selfDue', 'Self review due'],
          ['managerDue', 'Manager review due'],
          ['calibrationDue', 'Calibration done by'],
          ['releaseDue', 'Released by'],
        ].map(([key, label]) => (
          <div key={key}>
            <label htmlFor={`cy-${key}`}>{label}</label>
            <input id={`cy-${key}`} type="date" value={v[key]} onChange={(e) => set({ [key]: e.target.value })} />
          </div>
        ))}
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save
        </button>
        <button type="button" className="quiet" onClick={() => onDone(cycle?.id || null)}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function CyclePage({ id, onBack, onReview }) {
  const cycle = useLoad(() => performance.cycle(id))
  const [view, setView] = useState(null)
  const [editing, setEditing] = useState(false)
  const [adding, setAdding] = useState(false)
  const [section, setSection] = useState(null)
  const { busy, error, run } = useAction()
  const y = view || cycle.data
  if (cycle.loading && !y) return <p className="muted">Loading…</p>
  if (cycle.error) return <ErrorNote>{cycle.error}</ErrorNote>
  if (editing) return <CycleForm cycle={y} onDone={() => (setEditing(false), setView(null), cycle.reload())} />
  const act = async (action, body) => {
    const next = await run(() => performance.cycleAction(y.id, action, body))
    if (next) setView(next)
  }
  const [tone, words] = CYCLE_STATUS[y.status] || ['neutral', y.status]
  const sections = [
    { id: 'reviews', label: 'Reviews' },
    ['calibrating', 'released', 'closed'].includes(y.status) && { id: 'calibration', label: 'Calibration' },
    ['released', 'closed'].includes(y.status) && { id: 'outcomes', label: 'Outcomes' },
  ].filter(Boolean)
  const current = sections.find((t) => t.id === section) ? section : 'reviews'
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← All cycles
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            {y.name} <Badge tone={tone}>{words}</Badge>
          </h2>
          <p className="muted">
            {formatDay(y.periodStart)} to {formatDay(y.periodEnd)} · {y.form} (version {y.formVersion}) · goals {y.goalWeight}%
            {y.goalWeight < 100 && `, competencies ${100 - y.goalWeight}%`}
          </p>
        </div>
        <div className="form-actions">
          {y.status !== 'closed' && (
            <button type="button" className="secondary" onClick={() => setEditing(true)}>
              Change
            </button>
          )}
          {y.status === 'draft' && (
            <button type="button" className="primary" disabled={busy} onClick={() => act('start')}>
              Start the cycle
            </button>
          )}
          {y.status === 'active' && (
            <button type="button" className="primary" disabled={busy} onClick={() => act('calibrate')}>
              Start calibration
            </button>
          )}
          {y.status === 'calibrating' && (
            <button type="button" className="primary" disabled={busy} onClick={() => act('release')}>
              Release to everyone
            </button>
          )}
          {y.status === 'released' && (
            <button type="button" className="secondary" disabled={busy} onClick={() => act('close')}>
              Close the cycle
            </button>
          )}
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      {sections.length > 1 && <Tabs tabs={sections} current={current} onChange={setSection} />}
      {current === 'calibration' && <CalibrationPanel key={y.status} cycle={y} />}
      {current === 'outcomes' && <OutcomesPanel cycle={y} />}
      {current === 'reviews' && y.status === 'draft' && (
        <p className="notice" data-testid="eligible">
          Starting creates a review for {y.eligibleCount} {y.eligibleCount === 1 ? 'person' : 'people'}, each with their manager today, and tells them.
        </p>
      )}
      {current === 'reviews' && y.status !== 'draft' && (
        <div className="stat-row" data-testid="cycle-progress">
          {Object.entries(y.counts).map(([status, n]) => (
            <span key={status} className="stat">
              <strong>{n}</strong> {(REVIEW_STATUS[status] || [])[1] || status}
            </span>
          ))}
        </div>
      )}
      {current === 'reviews' && y.status === 'active' && (
        <p>
          {adding ? (
            <AddPeople cycle={y} onDone={(next) => (setAdding(false), next && setView(next))} />
          ) : (
            <button type="button" className="quiet plain" onClick={() => setAdding(true)}>
              Add someone…
            </button>
          )}
        </p>
      )}
      {current === 'reviews' && y.reviews.length > 0 && (
        <div className="table-wrap">
          <table className="stack">
            <thead>
              <tr>
                <th>Name</th>
                <th>Manager</th>
                <th>Stage</th>
                <th>Rating</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {y.reviews.map((r) => (
                <ReviewRow key={r.id} r={r} scale={y.scale} onOpen={() => onReview(r.id)} onChanged={setView} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function ReviewRow({ r, scale, onOpen, onChanged }) {
  const [asking, setAsking] = useState(null) // withdraw | move-on
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()
  const [tone, words] = REVIEW_STATUS[r.status] || ['neutral', r.status]
  const level = r.finalLevel || r.calibratedLevel || r.managerLevel
  const label = scale.find((l) => l.value === level)?.label
  return (
    <tr data-review={r.name}>
      <td>
        <button type="button" className="text-link" onClick={onOpen}>
          {r.name}
        </button>
        <div className="hint">{[r.designation, r.department].filter(Boolean).join(' · ')}</div>
      </td>
      <td>{r.manager || '—'}</td>
      <td>
        <Badge tone={tone}>{words}</Badge>
        {r.withdrawnReason && <div className="hint">{r.withdrawnReason}</div>}
      </td>
      <td>{level ? `${level} · ${label}` : '—'}</td>
      <td>
        {!asking && !['released', 'acknowledged', 'withdrawn'].includes(r.status) && (
          <div className="goal-actions">
            {r.status === 'self_review' && (
              <button type="button" className="quiet plain" onClick={() => setAsking('move-on')}>
                Move on
              </button>
            )}
            <button type="button" className="quiet plain danger" onClick={() => setAsking('withdraw')}>
              Withdraw
            </button>
          </div>
        )}
        {asking && (
          <div className="inline-form">
            <div>
              <label htmlFor={`why-${r.id}`}>Why</label>
              <input id={`why-${r.id}`} value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <div className="form-actions">
              <button
                type="button"
                className="secondary"
                disabled={busy}
                onClick={async () => {
                  const next = await run(() => (asking === 'withdraw' ? performance.withdrawReview(r.id, reason) : performance.moveOn(r.id, reason)))
                  if (next) onChanged(next)
                }}
              >
                {asking === 'withdraw' ? 'Withdraw' : 'Move on'}
              </button>
              <button type="button" className="quiet" onClick={() => setAsking(null)}>
                Cancel
              </button>
            </div>
            <ErrorNote>{error}</ErrorNote>
          </div>
        )}
      </td>
    </tr>
  )
}

function AddPeople({ cycle, onDone }) {
  const everyone = useLoad(() => people.employees())
  const [who, setWho] = useState('')
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()
  const inCycle = new Set(cycle.reviews.map((r) => r.employmentId))
  const list = (everyone.data || []).filter((e) => !inCycle.has(e.id) && e.status === 'active')
  return (
    <span className="inline-form">
      <span>
        <label htmlFor="add-who">Person</label>
        <select id="add-who" value={who} onChange={(e) => setWho(e.target.value)}>
          <option value="">Choose…</option>
          {list.map((e) => (
            <option key={e.id} value={e.id}>
              {e.preferredName || e.legalName || e.name}
            </option>
          ))}
        </select>
      </span>
      <span>
        <label htmlFor="add-why">Why</label>
        <input id="add-why" value={reason} onChange={(e) => setReason(e.target.value)} />
      </span>
      <button
        type="button"
        className="secondary"
        disabled={busy || !who}
        onClick={async () => {
          const next = await run(() => performance.cycleAction(cycle.id, 'people', { employmentIds: [who], reason }))
          if (next) onDone(next)
        }}
      >
        Add
      </button>
      <button type="button" className="quiet" onClick={() => onDone(null)}>
        Cancel
      </button>
      <ErrorNote>{error}</ErrorNote>
    </span>
  )
}
