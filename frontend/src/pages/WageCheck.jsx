import { useState } from 'react'
import { payroll } from '../api.js'
import { useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { monthLabel, rupees } from './payrollText.js'

const percent = (v) => (v === null || v === undefined ? '—' : `${Number(v).toFixed(1)}%`)
/** A month the server can read: yyyy-mm with a sensible year (a half-typed year is not sent). */
const validMonth = (m) => /^\d{4}-(0[1-9]|1[0-2])$/.test(m) && Number(m.slice(0, 4)) >= 2000

/**
 * Payroll → Wages: the wage test of the Labour Codes for each person in a month (are wages at least half of the remuneration, or is the
 * difference added back for PF), and a what-if for one person. Nothing here is saved.
 */
export default function WageCheck({ me }) {
  const [month, setMonth] = useState('')
  const [open, setOpen] = useState(null)
  const applied = validMonth(month) ? month : ''
  // The month box stays mounted; only the result below it is loaded again when a complete month is chosen.
  if (open) return <WhatIf person={open.person} period={open.period} onBack={() => setOpen(null)} />
  return (
    <section className="panel">
      <div className="section-head">
        <h3>Wages under the Labour Codes</h3>
        <label className="muted">
          Month
          <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month to check" />
        </label>
      </div>
      <Check key={applied} month={applied} me={me} onOpen={(person, period) => setOpen({ person, period })} />
    </section>
  )
}

function Check({ month, me, onOpen }) {
  const check = useLoad(() => payroll.wageCheck(month || undefined))
  const v = check.data
  const canTry = me && (me.permissions.includes('compensation.read') || me.permissions.includes('payroll.prepare'))

  return (
    <>
      <ErrorNote>{check.error}</ErrorNote>
      {v?.draftRules && <p className="hint">Some figures use rules still awaiting the payroll consultant’s sign-off.</p>}
      {v && !v.period && <Empty>No payroll has been approved yet, so there is nothing to check. Choose a month once a run exists.</Empty>}
      {v?.period && (
        <>
          <p className="hint">
            {monthLabel(v.period)}: {v.summary.people} {v.summary.people === 1 ? 'person' : 'people'}, {v.summary.toppedUp} with wages added back to reach half
            of their pay for PF ({rupees(v.summary.addedBack)} added back, {rupees(v.summary.pfExtra)} more PF a month).
          </p>
          {v.pending.length > 0 && (
            <p className="hint">Not counted yet, because the run is not approved: {v.pending.map((p) => `${p.group} (${p.state})`).join(', ')}.</p>
          )}
          {v.rows.length === 0 && <Empty>No approved payroll for this month, so there is nothing to check.</Empty>}
          {v.rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Person</th>
                  <th>Pay</th>
                  <th>Wages</th>
                  <th>Wage share</th>
                  <th>Added back</th>
                  <th>Extra PF</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {v.rows.map((r) => (
                  <tr key={r.employmentId} data-person={r.name}>
                    <td>
                      {r.name} <code className="hint">{r.code}</code> {r.held && <Badge tone="warn">Held</Badge>}
                    </td>
                    <td>{rupees(r.remuneration)}</td>
                    <td>{rupees(r.wages)}</td>
                    <td>{percent(r.wageShare)}</td>
                    <td>{r.toppedUp ? rupees(r.addedBack) : <span className="hint">None</span>}</td>
                    <td>{r.toppedUp ? <Badge tone="warn">{r.pfExtra === null ? 'Yes' : rupees(r.pfExtra)}</Badge> : <span className="hint">None</span>}</td>
                    <td>
                      {canTry && (
                        <button type="button" className="secondary" onClick={() => onOpen(r, v.period)}>
                          What if
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          )}
        </>
      )}
    </>
  )
}

/** Try other amounts for a person’s components and see pay, PF and cost next to today’s; nothing is saved. */
function WhatIf({ person, period, onBack }) {
  const setup = useLoad(payroll.setup)
  const [rows, setRows] = useState([{ code: '', amount: '' }])
  const [result, setResult] = useState(null)
  const { busy, error, run } = useAction()
  const components = (setup.data?.components || []).filter((c) => !c.retiredAt && c.kind === 'earning' && !c.statutory && !c.oneTime && c.amountType !== 'input')

  const change = (next) => {
    setRows(next)
    setResult(null)   // the figures shown belong to the amounts that were tried
  }
  const setRow = (i, patch) => change(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const ready = rows.length > 0 && rows.every((r) => r.code && r.amount !== '' && Number(r.amount) >= 0)

  async function tryIt(e) {
    e.preventDefault()
    const r = await run(() =>
      payroll.simulate(person.employmentId, {
        month: period ? period.slice(0, 7) : undefined,
        changes: rows.map((x) => ({ code: x.code, amount: Number(x.amount) })),
      }),
    )
    if (r) setResult(r)
  }

  const lines = result
    ? [
        ['Total pay', 'remuneration'],
        ['Wages for the 50% test', 'wages'],
        ['Added back for PF', 'addedBack'],
        ['Employee PF', 'pfEmployee'],
        ['Employer PF', 'pfEmployer'],
        ['Take-home', 'takeHome'],
        ['Employer cost, a month', 'employerCostMonthly'],
        ['Employer cost, a year', 'employerCostYearly'],
      ]
    : []

  return (
    <section className="panel">
      <button type="button" className="quiet" onClick={onBack}>
        ← Wages
      </button>
      <h3>What if for {person.name}</h3>
      <p className="hint">Give the full-month amount to try for one or more earnings. Nothing is saved; this only shows what pay would look like.</p>
      <ErrorNote>{error || setup.error}</ErrorNote>
      <form onSubmit={tryIt} >
        {rows.map((r, i) => (
          <div key={i} className="form-grid">
            <label>
              Earning
              <select value={r.code} onChange={(e) => setRow(i, { code: e.target.value })} aria-label={`Earning ${i + 1}`}>
                <option value="">Choose…</option>
                {components.map((c) => (
                  <option key={c.id} value={c.code} disabled={rows.some((x, j) => j !== i && x.code === c.code)}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Amount a month (₹)
              <input type="number" min="0" step="1" inputMode="numeric" value={r.amount} onChange={(e) => setRow(i, { amount: e.target.value })} aria-label={`Amount ${i + 1}`} />
            </label>
            {rows.length > 1 && (
              <button type="button" className="secondary" onClick={() => change(rows.filter((_, j) => j !== i))}>
                Remove
              </button>
            )}
          </div>
        ))}
        <div className="form-actions">
          <button type="button" className="secondary" onClick={() => change([...rows, { code: '', amount: '' }])} disabled={rows.length >= 10}>
            Add another earning
          </button>
          <button type="submit" className="primary" disabled={busy || !ready}>
            {busy ? 'Working it out…' : 'Show the difference'}
          </button>
        </div>
      </form>
      {result && (
        <div data-testid="what-if-result">
          <h3>
            {monthLabel(result.period)}, {result.structure}
          </h3>
          {result.warnings.map((w) => (
            <p key={w} className="hint">
              {w}
            </p>
          ))}
          {result.problems.length > 0 && <ErrorNote>{result.problems.map((p) => p.message || p.code).join('. ')}</ErrorNote>}
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th />
                  <th>Current</th>
                  <th>With the change</th>
                </tr>
              </thead>
              <tbody>
                {lines.map(([label, key]) => (
                  <tr key={key}>
                    <td>{label}</td>
                    <td>{rupees(result.current[key])}</td>
                    <td>{rupees(result.proposed[key])}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {(result.current.pfDraft || result.proposed.pfDraft) && <p className="hint">PF here uses rule data not yet signed off.</p>}
        </div>
      )}
    </section>
  )
}
