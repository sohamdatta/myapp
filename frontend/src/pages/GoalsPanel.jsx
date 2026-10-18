import { useState } from 'react'
import { performance } from '../api.js'
import { formatDate, formatDay, numeric, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { GOAL_STATUS, MEASURES } from './performanceText.js'

/**
 * One person's goals for a goal period: for the employee, to write and send to their manager; for
 * the manager or HR, to agree, return, change or drop; for both, to record progress.
 */
export default function GoalsPanel({ employmentId }) {
  const [period, setPeriod] = useState('')
  const goals = useLoad(() => performance.goals(employmentId, period))
  const [view, setView] = useState(null)
  const g = view || goals.data
  if (goals.loading && !g) return <p className="muted">Loading…</p>
  if (goals.error) return <ErrorNote>{goals.error}</ErrorNote>
  const move = (years) => {
    const d = new Date(`${g.periodStart}T12:00:00`)
    d.setFullYear(d.getFullYear() + years)
    setPeriod(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`)
    setView(null)
    goals.reload()
  }
  return <Goals g={g} onChanged={setView} onMove={move} />
}

function Goals({ g, onChanged, onMove }) {
  const [adding, setAdding] = useState(false)
  const [returning, setReturning] = useState(false)
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  const [tone, words] = GOAL_STATUS[g.setStatus] || ['neutral', g.setStatus]
  const live = g.goals.filter((x) => x.status !== 'dropped')
  const act = async (call) => {
    const next = await run(call)
    if (next) onChanged(next)
    return next
  }
  return (
    <section className="panel goals" data-testid="goals">
      <div className="section-head">
        <h3>
          Goals · {g.period} <Badge tone={tone}>{words}</Badge>
        </h3>
        <div className="form-actions">
          <button type="button" className="quiet plain" onClick={() => onMove(-1)} aria-label="Earlier goal period">
            ‹ Earlier
          </button>
          {!g.isCurrentPeriod && (
            <button type="button" className="quiet plain" onClick={() => onMove(1)} aria-label="Later goal period">
              Later ›
            </button>
          )}
        </div>
      </div>
      {g.setStatus === 'returned' && live.find((x) => x.returnNote) && (
        <p className="notice" data-testid="return-note">
          Returned: {live.find((x) => x.returnNote).returnNote}
        </p>
      )}
      {live.length > 0 && (
        <p className={g.totalWeight === 100 ? 'hint' : 'hint warn-text'} data-testid="goal-weights">
          Weights add up to {g.totalWeight} of 100.
        </p>
      )}
      {g.goals.length === 0 && !adding && (
        <Empty>{g.own ? 'You have no goals for this period yet. Add them, then send them to your manager.' : 'No goals for this period yet.'}</Empty>
      )}
      {g.goals.map((goal) => (
        <Goal key={goal.id} goal={goal} g={g} onChanged={onChanged} />
      ))}
      {adding && <GoalForm g={g} onDone={(next) => (next ? (onChanged(next), setAdding(false)) : setAdding(false))} />}
      <ErrorNote>{error}</ErrorNote>
      {!adding && (
        <div className="form-actions">
          {g.canEdit && (
            <button type="button" className="outline" onClick={() => setAdding(true)}>
              Add a goal
            </button>
          )}
          {g.own && ['draft', 'returned'].includes(g.setStatus) && (
            <button
              type="button"
              className="primary"
              disabled={busy || !g.canSubmit}
              title={g.canSubmit ? undefined : 'The weights must add up to 100'}
              onClick={() => act(() => performance.submitGoals(g.periodStart))}
            >
              Send to my manager
            </button>
          )}
          {g.canAgree && (
            <button type="button" className="primary" disabled={busy} onClick={() => act(() => performance.agreeGoals(g.employee.id, g.periodStart))}>
              Agree the goals
            </button>
          )}
          {g.canReturn && !returning && (
            <button type="button" className="secondary" onClick={() => setReturning(true)}>
              Return with a note
            </button>
          )}
        </div>
      )}
      {returning && (
        <div className="inline-form">
          <div>
            <label htmlFor="return-note">What should change</label>
            <input id="return-note" value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="form-actions">
            <button
              type="button"
              className="secondary"
              disabled={busy}
              onClick={async () => {
                if (await act(() => performance.returnGoals(g.employee.id, g.periodStart, note))) setReturning(false)
              }}
            >
              Return
            </button>
            <button type="button" className="quiet" onClick={() => setReturning(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </section>
  )
}

function Goal({ goal, g, onChanged }) {
  const [mode, setMode] = useState(null) // edit | progress | drop | history
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()
  const [tone, words] = GOAL_STATUS[goal.status] || ['neutral', goal.status]
  const dropped = goal.status === 'dropped'
  const act = async (call) => {
    const next = await run(call)
    if (next) {
      setMode(null)
      onChanged(next)
    }
  }
  const latest = goal.checkins[0]
  return (
    <div className={dropped ? 'goal goal-dropped' : 'goal'} data-goal={goal.title}>
      <div className="goal-head">
        <strong>{goal.title}</strong> <Badge tone={tone}>{words}</Badge>
        {!dropped && <span className="goal-weight">{goal.weight}%</span>}
      </div>
      <p className="hint">
        {[
          MEASURES[goal.measure],
          goal.measure === 'number' && `target ${Number(goal.target)}${goal.unit ? ` ${goal.unit}` : ''}`,
          goal.dueDate && `due ${formatDay(goal.dueDate)}`,
          goal.parentTitle && `supports “${goal.parentTitle}”`,
          goal.version > 1 && `version ${goal.version}`,
        ]
          .filter(Boolean)
          .join(' · ')}
      </p>
      {goal.description && <p className="pre">{goal.description}</p>}
      {goal.status === 'submitted' && goal.changeReason && <p className="hint">Changed: {goal.changeReason}</p>}
      {dropped && <p className="hint">Dropped: {goal.dropReason}</p>}
      {goal.status === 'agreed' && (
        <div className="progress" aria-label={`Progress ${goal.progress ?? 0}%`}>
          <div className="progress-track">
            <div className="progress-fill" style={{ width: `${goal.progress ?? 0}%` }} />
          </div>
          <span className="hint">
            {goal.progress ?? 0}%{goal.progressValue != null && ` · ${Number(goal.progressValue)} of ${Number(goal.target)}`}
            {latest && ` · ${latest.writtenByName}, ${formatDate(latest.writtenAt)}`}
          </span>
        </div>
      )}
      {latest?.note && goal.status === 'agreed' && <p className="hint pre">“{latest.note}”</p>}
      {!mode && !dropped && (
        <div className="goal-actions">
          {g.canEdit && goal.status === 'agreed' && (
            <button type="button" className="quiet plain" onClick={() => setMode('progress')}>
              Record progress
            </button>
          )}
          {g.canEdit && (
            <button type="button" className="quiet plain" onClick={() => setMode('edit')}>
              Change
            </button>
          )}
          {g.canEdit && ['draft', 'returned', 'submitted'].includes(goal.status) && goal.version === 1 && (
            <button type="button" className="quiet plain danger" disabled={busy} onClick={() => act(() => performance.removeGoal(goal.id))}>
              Remove
            </button>
          )}
          {g.canDrop && ['agreed', 'submitted'].includes(goal.status) && (
            <button type="button" className="quiet plain danger" onClick={() => setMode('drop')}>
              Drop
            </button>
          )}
          {(goal.checkins.length > 1 || goal.versions.length > 0) && (
            <button type="button" className="quiet plain" onClick={() => setMode('history')}>
              History
            </button>
          )}
        </div>
      )}
      {mode === 'edit' && <GoalForm g={g} goal={goal} onDone={(next) => (next ? act(async () => next) : setMode(null))} />}
      {mode === 'progress' && <ProgressForm goal={goal} onDone={(next) => (next ? act(async () => next) : setMode(null))} />}
      {mode === 'drop' && (
        <div className="inline-form">
          <div>
            <label htmlFor={`drop-${goal.id}`}>Why it no longer applies</label>
            <input id={`drop-${goal.id}`} value={reason} onChange={(e) => setReason(e.target.value)} />
          </div>
          <div className="form-actions">
            <button type="button" className="danger" disabled={busy} onClick={() => act(() => performance.dropGoal(goal.id, reason))}>
              Drop the goal
            </button>
            <button type="button" className="quiet" onClick={() => setMode(null)}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {mode === 'history' && (
        <div className="goal-history">
          {goal.checkins.length > 0 && (
            <>
              <h4>Progress</h4>
              <ul className="plain-list">
                {goal.checkins.map((k) => (
                  <li key={k.id}>
                    {formatDate(k.writtenAt)}, {k.writtenByName}: {k.progress != null && `${k.progress}%`}
                    {k.value != null && ` (${Number(k.value)})`} {k.note}
                  </li>
                ))}
              </ul>
            </>
          )}
          {goal.versions.length > 0 && (
            <>
              <h4>Earlier versions</h4>
              <ul className="plain-list">
                {goal.versions.map((v) => (
                  <li key={v.version}>
                    Version {v.version}: {v.title}, {v.weight}%{v.target != null && `, target ${Number(v.target)}`} (until{' '}
                    {formatDate(v.replacedAt)})
                  </li>
                ))}
              </ul>
            </>
          )}
          <button type="button" className="quiet plain" onClick={() => setMode(null)}>
            Close
          </button>
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
    </div>
  )
}

const WEIGHTS = Array.from({ length: 20 }, (_, i) => (i + 1) * 5)

function GoalForm({ g, goal, onDone }) {
  const [v, setV] = useState({
    title: goal?.title || '',
    description: goal?.description || '',
    weight: String(goal?.weight || Math.max(5, Math.min(100, 100 - g.totalWeight)) || 20),
    measure: goal?.measure || 'rating',
    target: goal?.target != null ? String(Number(goal.target)) : '',
    unit: goal?.unit || '',
    dueDate: goal?.dueDate || '',
    parentGoalId: goal?.parentGoalId || '',
    reason: '',
  })
  const { busy, error, run } = useAction()
  const set = (patch) => setV({ ...v, ...patch })
  const agreed = goal?.status === 'agreed'
  const prefix = goal ? `goal-${goal.id}` : 'goal-new'
  return (
    <form
      className="goal-form"
      onSubmit={async (e) => {
        e.preventDefault()
        const next = await run(() => {
          const body = {
            employmentId: g.employee.id,
            periodStart: g.periodStart,
            title: v.title,
            description: v.description,
            weight: Number(v.weight),
            measure: v.measure,
            target: v.measure === 'number' ? numeric(v.target, 'The target') : null,
            unit: v.measure === 'number' ? v.unit : null,
            dueDate: v.dueDate || null,
            parentGoalId: v.parentGoalId || null,
            reason: agreed ? v.reason : null,
          }
          return goal ? performance.changeGoal(goal.id, body) : performance.addGoal(body)
        })
        if (next) onDone(next)
      }}
    >
      <label htmlFor={`${prefix}-title`}>Goal</label>
      <input id={`${prefix}-title`} value={v.title} onChange={(e) => set({ title: e.target.value })} />
      <label htmlFor={`${prefix}-description`}>
        How success is measured <span className="hint">(optional)</span>
      </label>
      <textarea id={`${prefix}-description`} rows={2} value={v.description} onChange={(e) => set({ description: e.target.value })} />
      <div className="grid">
        <div>
          <label htmlFor={`${prefix}-weight`}>Weight</label>
          <select id={`${prefix}-weight`} value={v.weight} onChange={(e) => set({ weight: e.target.value })}>
            {WEIGHTS.map((w) => (
              <option key={w} value={w}>
                {w}%
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`${prefix}-measure`}>Measured by</label>
          <select id={`${prefix}-measure`} value={v.measure} onChange={(e) => set({ measure: e.target.value })}>
            {Object.entries(MEASURES).map(([k, label]) => (
              <option key={k} value={k}>
                {label}
              </option>
            ))}
          </select>
        </div>
        {v.measure === 'number' && (
          <>
            <div>
              <label htmlFor={`${prefix}-target`}>Target</label>
              <input id={`${prefix}-target`} inputMode="decimal" value={v.target} onChange={(e) => set({ target: e.target.value })} />
            </div>
            <div>
              <label htmlFor={`${prefix}-unit`}>Unit</label>
              <input id={`${prefix}-unit`} value={v.unit} placeholder="accounts, ₹ lakh, %" onChange={(e) => set({ unit: e.target.value })} />
            </div>
          </>
        )}
        <div>
          <label htmlFor={`${prefix}-due`}>
            Due <span className="hint">(optional)</span>
          </label>
          <input id={`${prefix}-due`} type="date" min={g.periodStart} max={g.periodEnd} value={v.dueDate} onChange={(e) => set({ dueDate: e.target.value })} />
        </div>
        {g.parentOptions.length > 0 && (
          <div>
            <label htmlFor={`${prefix}-parent`}>Supports</label>
            <select id={`${prefix}-parent`} value={v.parentGoalId} onChange={(e) => set({ parentGoalId: e.target.value })}>
              <option value="">Nothing in particular</option>
              {g.parentOptions.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.source === 'manager' ? 'Manager: ' : 'Department: '}
                  {p.title}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>
      {agreed && (
        <>
          <label htmlFor={`${prefix}-reason`}>Why it changes</label>
          <input id={`${prefix}-reason`} value={v.reason} onChange={(e) => set({ reason: e.target.value })} />
          <p className="hint">
            {g.own
              ? 'The goal is agreed: your change goes back to your manager to agree, and the earlier version is kept.'
              : 'The goal stays agreed with your change; the earlier version is kept.'}
          </p>
        </>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {goal ? 'Save' : 'Add the goal'}
        </button>
        <button type="button" className="quiet" onClick={() => onDone(null)}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function ProgressForm({ goal, onDone }) {
  const [progress, setProgress] = useState(String(goal.progress ?? ''))
  const [value, setValue] = useState('')
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  const number = goal.measure === 'number'
  return (
    <form
      className="goal-form"
      onSubmit={async (e) => {
        e.preventDefault()
        const next = await run(() =>
          performance.goalCheckin(goal.id, {
            progress: number ? null : numeric(progress, 'Progress', { whole: true }),
            value: number ? numeric(value, 'The value') : null,
            note,
          }),
        )
        if (next) onDone(next)
      }}
    >
      <div className="grid">
        {number ? (
          <div>
            <label htmlFor={`value-${goal.id}`}>
              Reached so far <span className="hint">(of {Number(goal.target)} {goal.unit})</span>
            </label>
            <input id={`value-${goal.id}`} inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} />
          </div>
        ) : (
          <div>
            <label htmlFor={`progress-${goal.id}`}>Progress, %</label>
            <input id={`progress-${goal.id}`} inputMode="numeric" value={progress} onChange={(e) => setProgress(e.target.value)} />
          </div>
        )}
        <div>
          <label htmlFor={`note-${goal.id}`}>
            Note <span className="hint">(optional)</span>
          </label>
          <input id={`note-${goal.id}`} value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Record
        </button>
        <button type="button" className="quiet" onClick={() => onDone(null)}>
          Cancel
        </button>
      </div>
    </form>
  )
}
