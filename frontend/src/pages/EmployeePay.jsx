import { useState } from 'react'
import { payroll } from '../api.js'
import { formatDate, formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { monthLabel, rupees, signed } from './payrollText.js'

const firstOfNextMonth = (iso) => {
  const d = new Date(`${iso}T00:00:00`)
  return new Date(Date.UTC(d.getFullYear(), d.getMonth() + 1, 1)).toISOString().slice(0, 10)
}

/** A month's breakup as a short table: one row per line, then gross, deductions and net. */
function Breakup({ b, compare, testid }) {
  if (!b) return <Empty>No pay yet.</Empty>
  const lines = b.lines.filter((l) => Number(l.amount) !== 0 || compare?.lines.some((x) => x.code === l.code && Number(x.amount) !== 0))
  const before = (code) => compare?.lines.find((x) => x.code === code)?.amount
  return (
    <table className="breakup" data-testid={testid}>
      <tbody>
        {['earning', 'deduction', 'employer'].map((kind) =>
          lines
            .filter((l) => l.kind === kind)
            .map((l) => (
              <tr key={l.code} className={kind === 'employer' ? 'hint' : ''}>
                <td>
                  {l.name}
                  {kind === 'employer' && ' (employer)'}
                </td>
                <td className="num">{rupees(l.amount)}</td>
                {compare && (
                  <td className="num hint">
                    {before(l.code) !== undefined && Number(before(l.code)) !== Number(l.amount) ? signed(Number(l.amount) - Number(before(l.code))) : ''}
                  </td>
                )}
              </tr>
            )),
        )}
        <tr className="total">
          <td>Gross</td>
          <td className="num">{rupees(b.gross)}</td>
          {compare && <td className="num hint">{Number(b.gross) !== Number(compare.gross) ? signed(Number(b.gross) - Number(compare.gross)) : ''}</td>}
        </tr>
        <tr className="total">
          <td>Net pay</td>
          <td className="num" data-testid={testid && `${testid}-net`}>
            <strong>{rupees(b.net)}</strong>
          </td>
          {compare && <td className="num hint">{Number(b.net) !== Number(compare.net) ? signed(Number(b.net) - Number(compare.net)) : ''}</td>}
        </tr>
        <tr className="hint">
          <td>Cost to the employer this month</td>
          <td className="num">{rupees(b.employerCost)}</td>
          {compare && <td />}
        </tr>
      </tbody>
    </table>
  )
}

function ReviseForm({ p, employmentId, onDone, onCancel }) {
  const current = p.history[0]
  const [v, setV] = useState({
    validFrom: current ? firstOfNextMonth(p.thisMonth) : p.thisMonth,
    structureId: current?.structureId || p.structures[0]?.id || '',
    annualAmount: current ? String(Number(current.annualAmount)) : '',
    reason: current ? '' : 'Pay on joining',
  })
  const [preview, setPreview] = useState(null)
  const { busy, error, run } = useAction()
  const set = (patch) => {
    setPreview(null)
    setV({ ...v, ...patch })
  }
  const body = () => ({ ...v, annualAmount: Number(v.annualAmount) })
  const basis = p.structures.find((s) => s.id === v.structureId)?.basis
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (!preview) {
          const r = await run(() => payroll.previewPay(employmentId, body()))
          if (r) setPreview(r)
          return
        }
        if (await run(() => payroll.revisePay(employmentId, body()))) onDone()
      }}
    >
      <h3>{current ? 'Revise pay' : 'Set pay'}</h3>
      <div className="grid">
        <div>
          <label htmlFor="pay-from">From</label>
          <input id="pay-from" type="date" value={v.validFrom} onChange={(e) => set({ validFrom: e.target.value })} />
        </div>
        <div>
          <label htmlFor="pay-structure">Structure</label>
          <select id="pay-structure" value={v.structureId} onChange={(e) => set({ structureId: e.target.value })}>
            {p.structures.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="pay-amount">{basis === 'gross' ? 'Yearly gross pay (₹)' : 'Yearly CTC (₹)'}</label>
          <input id="pay-amount" inputMode="decimal" value={v.annualAmount} onChange={(e) => set({ annualAmount: e.target.value.replace(/[^0-9.]/g, '') })} />
          {v.annualAmount && <p className="hint">{rupees(v.annualAmount)} a year</p>}
        </div>
      </div>
      <label htmlFor="pay-reason">Reason</label>
      <input id="pay-reason" value={v.reason} onChange={(e) => set({ reason: e.target.value })} placeholder="Annual revision 2026" />
      {preview && (
        <div className="preview-compare" data-testid="pay-preview">
          <h4>What {monthLabel(preview.period)} would look like</h4>
          <div className="panels">
            {preview.before && (
              <div>
                <p className="hint">Now</p>
                <Breakup b={preview.before} testid="pay-before" />
              </div>
            )}
            <div>
              <p className="hint">{preview.before ? 'After' : 'With this pay'}</p>
              <Breakup b={preview.after} compare={preview.before} testid="pay-after" />
            </div>
          </div>
          {preview.after.problems.map((x, i) => (
            <p key={i} className="warn-text">
              {x.message}
            </p>
          ))}
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy || !v.annualAmount || !v.structureId || !v.reason.trim()}>
          {preview ? `Save from ${formatDay(v.validFrom)}` : 'Preview'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function OverrideForm({ p, employmentId, onDone, onCancel }) {
  const [v, setV] = useState({ componentCode: p.components[0]?.code || '', monthlyAmount: '', validFrom: p.thisMonth, validTo: '', reason: '' })
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => payroll.addOverride(employmentId, { ...v, monthlyAmount: Number(v.monthlyAmount), validTo: v.validTo || null }))) onDone()
      }}
    >
      <h3>A different amount for one component</h3>
      <p className="hint">For this employee only, instead of the structure’s formula. The balancing component absorbs the difference.</p>
      <div className="grid">
        <div>
          <label htmlFor="ov-code">Component</label>
          <select id="ov-code" value={v.componentCode} onChange={(e) => setV({ ...v, componentCode: e.target.value })}>
            {p.components
              .filter((c) => c.kind === 'earning')
              .map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
          </select>
        </div>
        <div>
          <label htmlFor="ov-amount">A month (₹)</label>
          <input id="ov-amount" inputMode="decimal" value={v.monthlyAmount} onChange={(e) => setV({ ...v, monthlyAmount: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ov-from">From</label>
          <input id="ov-from" type="date" value={v.validFrom} onChange={(e) => setV({ ...v, validFrom: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ov-to">Until (optional)</label>
          <input id="ov-to" type="date" value={v.validTo} onChange={(e) => setV({ ...v, validTo: e.target.value })} />
        </div>
      </div>
      <label htmlFor="ov-reason">Reason</label>
      <input id="ov-reason" value={v.reason} onChange={(e) => setV({ ...v, reason: e.target.value })} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy || !v.monthlyAmount || !v.reason.trim()}>
          Add
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function Profile({ p, employmentId, onChanged }) {
  const [v, setV] = useState({ ...p.profile })
  const [saved, setSaved] = useState('')
  const { busy, error, run } = useAction()
  const box = (key, words) => (
    <label className="check">
      <input
        type="checkbox"
        checked={Boolean(v[key])}
        disabled={!p.canWrite}
        onChange={(e) => {
          setSaved('')
          setV({ ...v, [key]: e.target.checked })
        }}
      />
      {words}
    </label>
  )
  return (
    <form
      className="panel"
      data-testid="pay-profile"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => payroll.saveProfile(employmentId, v))) {
          setSaved('Saved.')
          onChanged()
        }
      }}
    >
      <h3>Statutory profile</h3>
      {box('pfMember', 'A member of the provident fund')}
      {v.pfMember && box('pfFullWages', 'PF on full wages, above the ceiling')}
      {v.pfMember && box('epsEligible', 'In the pension scheme (EPS)')}
      {box('esiExempt', 'Not covered by ESI (for example, insured elsewhere)')}
      {box('ptExempt', 'Exempt from professional tax')}
      {box('lwfExempt', 'Exempt from the labour welfare fund')}
      <label htmlFor="pp-regime">Income tax regime</label>
      <select
        id="pp-regime"
        value={v.taxRegime}
        disabled={!p.canWrite}
        onChange={(e) => {
          setSaved('')
          setV({ ...v, taxRegime: e.target.value })
        }}
      >
        <option value="new">New regime (the default)</option>
        <option value="old">Old regime</option>
      </select>
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">{saved}</p>}
      {p.canWrite && (
        <div className="form-actions">
          <button type="submit" className="secondary" disabled={busy}>
            Save profile
          </button>
        </div>
      )}
    </form>
  )
}

/** People → an employee → Pay: their CTC history, this month's breakup, revisions with a preview, overrides and statutory profile. */
export default function EmployeePay({ employmentId }) {
  const pay = useLoad(() => payroll.pay(employmentId))
  const [form, setForm] = useState(null)
  const { error, run } = useAction()
  if (pay.loading) return <p className="muted">Loading…</p>
  if (pay.error) return <ErrorNote>{pay.error}</ErrorNote>
  const p = pay.data
  const done = () => {
    setForm(null)
    pay.reload()
  }
  const current = p.history[0]
  return (
    <>
      {form === 'revise' && <ReviseForm p={p} employmentId={employmentId} onDone={done} onCancel={() => setForm(null)} />}
      {form === 'override' && <OverrideForm p={p} employmentId={employmentId} onDone={done} onCancel={() => setForm(null)} />}
      {!form && p.canWrite && (
        <div className="form-actions">
          <button type="button" className="outline" onClick={() => setForm('revise')}>
            {current ? 'Revise pay' : 'Set pay'}
          </button>
          {current && (
            <button type="button" className="quiet" onClick={() => setForm('override')}>
              Different amount for a component…
            </button>
          )}
        </div>
      )}
      <div className="panels">
        <section className="panel">
          <h3>
            Pay now{' '}
            {current && (
              <span className="hint">
                {rupees(current.annualAmount)} a year {current.basis === 'gross' ? 'gross' : 'CTC'}, {current.structureName}
              </span>
            )}
          </h3>
          {p.group ? (
            <p className="hint">
              Paid with {p.group.name} since {formatDay(p.group.validFrom)}
            </p>
          ) : (
            <p className="warn-text">In no pay group yet: setting pay adds them to the first one.</p>
          )}
          <Breakup b={p.breakup} testid="pay-now" />
        </section>
        <Profile key={JSON.stringify(p.profile)} p={p} employmentId={employmentId} onChanged={pay.reload} />
      </div>
      <section className="panel">
        <h3>History</h3>
        {p.history.length === 0 ? (
          <Empty>No pay recorded yet.</Empty>
        ) : (
          <table data-testid="pay-history">
            <thead>
              <tr>
                <th>From</th>
                <th>Yearly</th>
                <th>Structure</th>
                <th>Reason</th>
                <th>Recorded</th>
              </tr>
            </thead>
            <tbody>
              {p.history.map((h) => (
                <tr key={h.id}>
                  <td>{formatDay(h.validFrom)}</td>
                  <td className="num">{rupees(h.annualAmount)}</td>
                  <td>{h.structureName}</td>
                  <td>{h.reason}</td>
                  <td className="hint">
                    {h.recordedByName}, {formatDate(h.recordedAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {p.overrides.length > 0 && (
          <>
            <h4>Different amounts</h4>
            <ul className="plain-list">
              {p.overrides.map((o) => (
                <li key={o.id}>
                  <Badge>{o.componentName || o.componentCode}</Badge> {rupees(o.monthlyAmount)} a month from {formatDay(o.validFrom)}
                  {o.validTo ? ` until ${formatDay(o.validTo)}` : ''} · {o.reason}{' '}
                  {p.canWrite && (
                    <button type="button" className="quiet" onClick={() => run(() => payroll.removeOverride(employmentId, o.id)).then(pay.reload)}>
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}
        <ErrorNote>{error}</ErrorNote>
      </section>
    </>
  )
}
