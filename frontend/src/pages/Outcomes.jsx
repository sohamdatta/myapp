import { useState } from 'react'
import { performance } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { rupees } from './payrollText.js'
import { OUTCOME_STATUS } from './performanceText.js'

/**
 * A released cycle's outcomes: HR prepares them from the merit table, changes what needs changing
 * and sends them; someone else with approval rights approves them, which records the pay and the
 * job change and issues the letters.
 */
export default function OutcomesPanel({ cycle }) {
  const loaded = useLoad(() => performance.outcomes(cycle.id))
  const [view, setView] = useState(null)
  const [returning, setReturning] = useState(false)
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  const o = view || loaded.data
  if (loaded.loading && !o) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  const act = async (action, body) => {
    const next = await run(() => performance.outcomesAction(cycle.id, action, body))
    if (next) {
      setView(next)
      setReturning(false)
      setNote('')
    }
  }
  const counts = o.counts || {}
  const waitingForYou = o.outcomes.filter((x) => x.status === 'submitted' && !x.sentByYou && !x.own).length
  return (
    <div data-testid="outcomes">
      <section className="panel">
        <div className="section-head">
          <div>
            <h3>Outcomes</h3>
            <p className="hint">
              Each final rating suggests an increment from the merit table (under Setup). Change one with a note, add a promotion or a bonus,
              then send them for approval. Approving records the new pay and designation from the date shown and issues the letters.
            </p>
          </div>
          <div className="form-actions">
            {o.canPrepare && o.waiting > 0 && (
              <button type="button" className="primary" disabled={busy} onClick={() => act('prepare')}>
                Prepare {o.waiting} {o.waiting === 1 ? 'outcome' : 'outcomes'}
              </button>
            )}
            {o.canPrepare && counts.draft > 0 && (
              <button type="button" className="primary" disabled={busy} onClick={() => act('submit')}>
                Send drafts for approval
              </button>
            )}
            {o.canApprove && waitingForYou > 0 && (
              <>
                <button type="button" className="primary" disabled={busy} onClick={() => act('approve')}>
                  Approve {waitingForYou}
                </button>
                <button type="button" className="secondary" disabled={busy} onClick={() => setReturning(true)}>
                  Send back…
                </button>
              </>
            )}
          </div>
        </div>
        {returning && (
          <div className="inline-form">
            <div>
              <label htmlFor="outcomes-return">What should change</label>
              <input id="outcomes-return" value={note} onChange={(e) => setNote(e.target.value)} />
            </div>
            <div className="form-actions">
              <button type="button" className="secondary" disabled={busy} onClick={() => act('return', { note })}>
                Send back all waiting
              </button>
              <button type="button" className="quiet" onClick={() => setReturning(false)}>
                Cancel
              </button>
            </div>
          </div>
        )}
        <ErrorNote>{error}</ErrorNote>
        {o.outcomes.length > 0 && (
          <div className="stat-row" data-testid="outcome-totals">
            <span className="stat">
              <strong>{rupees(o.totals.increase)}</strong> more a year ({Number(o.totals.increasePct)}%)
            </span>
            <span className="stat">
              <strong>{rupees(o.totals.bonus)}</strong> in bonuses
            </span>
            {Object.entries(counts).map(([status, n]) => (
              <span key={status} className="stat">
                <strong>{n}</strong> {(OUTCOME_STATUS[status] || [])[1] || status}
              </span>
            ))}
          </div>
        )}
      </section>
      {o.outcomes.length === 0 && <Empty>{o.waiting > 0 ? 'Nothing prepared yet.' : 'No released review needs an outcome.'}</Empty>}
      {o.outcomes.length > 0 && (
        <div className="table-wrap">
          <table className="stack">
            <thead>
              <tr>
                <th>Name</th>
                <th>Rating</th>
                <th>Increment</th>
                <th>Promotion</th>
                <th>Bonus</th>
                <th>Stage</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {o.outcomes.map((x) => (
                <OutcomeRow key={x.id} x={x} canEdit={o.canPrepare} onChanged={setView} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function OutcomeRow({ x, canEdit, onChanged }) {
  const [editing, setEditing] = useState(false)
  const [tone, words] = OUTCOME_STATUS[x.status] || ['neutral', x.status]
  const differs = Number(x.incrementPct) !== Number(x.suggestedPct)
  return (
    <>
      <tr data-outcome={x.name}>
        <td>
          <strong>{x.name}</strong>
          <div className="hint">{[x.designation, x.department].filter(Boolean).join(' · ')}</div>
        </td>
        <td>
          {x.level} · {x.rating}
        </td>
        <td>
          {x.currentAnnual == null ? (
            <span className="hint">No pay recorded</span>
          ) : (
            <>
              <span data-testid="increment">{Number(x.incrementPct)}%</span>
              {differs && <span className="hint"> (table {Number(x.suggestedPct)}%)</span>}
              <div className="hint">
                {rupees(x.currentAnnual)} → {rupees(x.newAnnual)}
              </div>
            </>
          )}
          <div className="hint">from {formatDay(x.effectiveFrom)}</div>
        </td>
        <td>{x.promotion ? x.newDesignation : '—'}</td>
        <td>{x.bonusAmount == null ? '—' : rupees(x.bonusAmount)}</td>
        <td>
          <Badge tone={tone}>{words}</Badge>
          {x.status === 'applied' && <div className="hint">by {x.approvedByName}</div>}
          {x.status === 'submitted' && <div className="hint">sent by {x.submittedByName}</div>}
          {x.returnNote && x.status === 'draft' && <div className="hint">Sent back: {x.returnNote}</div>}
          {x.note && <div className="hint">Note: {x.note}</div>}
        </td>
        <td>
          {canEdit && x.status === 'draft' && !x.own && !editing && (
            <button type="button" className="quiet plain" onClick={() => setEditing(true)}>
              Change
            </button>
          )}
        </td>
      </tr>
      {editing && (
        <tr>
          <td colSpan={7}>
            <OutcomeForm x={x} onDone={(next) => (setEditing(false), next && onChanged(next))} />
          </td>
        </tr>
      )}
    </>
  )
}

function OutcomeForm({ x, onDone }) {
  const [form, setForm] = useState({
    incrementPct: String(Number(x.incrementPct)),
    promotion: !!x.promotion,
    newDesignation: x.newDesignation || '',
    bonusAmount: x.bonusAmount == null ? '' : String(Number(x.bonusAmount)),
    effectiveFrom: x.effectiveFrom,
    note: x.note || '',
  })
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  const { busy, error, run } = useAction()
  const pct = Number(form.incrementPct)
  const preview = x.currentAnnual == null || Number.isNaN(pct) ? null : Math.round(Number(x.currentAnnual) * (1 + pct / 100))
  return (
    <form
      className="inline-form outcome-form"
      onSubmit={async (e) => {
        e.preventDefault()
        const next = await run(() =>
          performance.saveOutcome(x.id, {
            incrementPct: form.incrementPct === '' ? 0 : Number(form.incrementPct),
            promotion: form.promotion,
            newDesignation: form.promotion ? form.newDesignation : null,
            bonusAmount: form.bonusAmount === '' ? null : Number(form.bonusAmount),
            effectiveFrom: form.effectiveFrom,
            note: form.note,
          }),
        )
        if (next) onDone(next)
      }}
    >
      <div>
        <label htmlFor={`inc-${x.id}`}>Increment (%)</label>
        <input id={`inc-${x.id}`} inputMode="decimal" value={form.incrementPct} onChange={(e) => set({ incrementPct: e.target.value })} />
        <p className="hint">
          The merit table suggests {Number(x.suggestedPct)}%{preview != null && `; new pay ${rupees(preview)} a year`}
        </p>
      </div>
      <div>
        <label htmlFor={`eff-${x.id}`}>From</label>
        <input id={`eff-${x.id}`} type="date" value={form.effectiveFrom} onChange={(e) => set({ effectiveFrom: e.target.value })} />
        <p className="hint">The first of a month</p>
      </div>
      <div>
        <label htmlFor={`bonus-${x.id}`}>Bonus (₹)</label>
        <input id={`bonus-${x.id}`} inputMode="numeric" value={form.bonusAmount} onChange={(e) => set({ bonusAmount: e.target.value })} />
        <p className="hint">Shown in the letter; pay it through a payroll run</p>
      </div>
      <div>
        <label className="check">
          <input type="checkbox" checked={form.promotion} onChange={(e) => set({ promotion: e.target.checked })} /> Promote
        </label>
        {form.promotion && (
          <>
            <label htmlFor={`desig-${x.id}`}>New designation</label>
            <input id={`desig-${x.id}`} value={form.newDesignation} onChange={(e) => set({ newDesignation: e.target.value })} />
          </>
        )}
      </div>
      <div className="wide">
        <label htmlFor={`note-${x.id}`}>
          Note <span className="hint">(needed when the increment differs from the merit table)</span>
        </label>
        <input id={`note-${x.id}`} value={form.note} onChange={(e) => set({ note: e.target.value })} />
      </div>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save
        </button>
        <button type="button" className="quiet" onClick={() => onDone(null)}>
          Cancel
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </form>
  )
}
