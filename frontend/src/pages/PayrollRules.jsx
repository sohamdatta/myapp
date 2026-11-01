import { useState } from 'react'
import { platform } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { STATE_NAMES } from './payrollText.js'

const STATUTES = { pt: 'Professional tax', lwf: 'Labour welfare fund' }
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const money = (v) => (v === null || v === undefined || v === '' ? '' : `₹${Number(v).toLocaleString('en-IN')}`)
const blankSlab = () => ({ from: '', to: '', monthly: '', february: '' })

const slabsToForm = (slabs) => (slabs || []).map((s) => ({ from: s.from ?? '', to: s.to ?? '', monthly: s.monthly ?? '', february: s.february ?? '' }))
const num = (v) => (v === '' || v === null || v === undefined ? null : Number(v))
const slabsToBody = (slabs) => slabs.map((s) => ({ from: num(s.from), to: num(s.to), monthly: num(s.monthly), february: num(s.february) }))

/**
 * Platform console → Payroll rules: state professional tax and labour welfare fund figures as data. A new version starts as a draft;
 * a draft is edited until the payroll consultant has signed it off, and a signed-off version is never changed (add a new one).
 */
export default function PayrollRules({ me }) {
  const loaded = useLoad(platform.rules)
  const [form, setForm] = useState(null)   // { rule? } while adding or editing
  const [signing, setSigning] = useState(null)
  const canChange = me.platformRole === 'super_admin'
  const rules = loaded.data || []

  const done = () => {
    setForm(null)
    setSigning(null)
    loaded.reload()
  }

  return (
    <section className="panel" data-testid="payroll-rules">
      <div className="section-head">
        <h3>State payroll rules</h3>
        {canChange && !form && (
          <button type="button" className="primary" onClick={() => setForm({})}>
            Add a rule
          </button>
        )}
      </div>
      <p className="hint">
        Professional tax and labour welfare fund by state. Payroll says on every payslip when a figure came from a draft that is waiting for the
        consultant’s sign-off.
      </p>
      <ErrorNote>{loaded.error}</ErrorNote>
      {form && <RuleForm rule={form.rule} onCancel={() => setForm(null)} onDone={done} />}
      {signing && <SignOffForm rule={signing} onCancel={() => setSigning(null)} onDone={done} />}
      {!loaded.loading && rules.length === 0 && <Empty>No rules yet.</Empty>}
      {rules.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>State</th>
                <th>Rule</th>
                <th>From</th>
                <th>Figures</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id} data-rule={`${r.statute}:${r.jurisdiction}:${r.validFrom}`}>
                  <td>{STATE_NAMES[r.jurisdiction] || r.jurisdiction}</td>
                  <td>{STATUTES[r.statute] || r.statute}</td>
                  <td>
                    {formatDay(r.validFrom)}
                    {r.validTo && <span className="hint"> to {formatDay(r.validTo)}</span>}
                  </td>
                  <td>{summary(r)}</td>
                  <td>
                    {r.status === 'draft' ? (
                      <Badge tone="warn">Draft</Badge>
                    ) : (
                      <>
                        <Badge tone="good">Signed off</Badge>
                        <div className="hint">
                          {r.signedOffBy}, {formatDay(r.signedOffOn)}
                        </div>
                      </>
                    )}
                  </td>
                  <td>
                    {canChange && r.status === 'draft' && (
                      <div className="form-actions">
                        <button type="button" className="secondary" onClick={() => { setSigning(null); setForm({ rule: r }) }}>
                          Edit
                        </button>
                        <button type="button" className="secondary" onClick={() => { setForm(null); setSigning(r) }}>
                          Record sign-off
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function summary(r) {
  if (r.pt) {
    const men = r.pt.men || []
    const women = r.pt.women || []
    const top = men.length ? Math.max(...men.map((s) => Number(s.monthly))) : 0
    return `${men.length} slab${men.length === 1 ? '' : 's'}${women.length ? ` (and ${women.length} for women)` : ''}, up to ${money(top)} a month${r.pt.yearlyCeiling ? `, ${money(r.pt.yearlyCeiling)} a year at most` : ''}`
  }
  if (r.lwf) {
    return `Employee ${money(r.lwf.employee)}, employer ${money(r.lwf.employer)}, in ${(r.lwf.months || []).map((m) => MONTHS[m - 1]).join(', ')}`
  }
  return ''
}

function SlabRows({ label, slabs, onChange }) {
  const set = (i, patch) => onChange(slabs.map((s, j) => (j === i ? { ...s, ...patch } : s)))
  return (
    <fieldset>
      <legend>{label}</legend>
      <p className="hint">Gross pay from and up to (leave “up to” empty for the last slab), the monthly amount, and the February amount where it differs.</p>
      {slabs.map((s, i) => (
        <div key={i} className="form-grid">
          <label>
            From (₹)
            <input type="number" min="0" value={s.from} onChange={(e) => set(i, { from: e.target.value })} aria-label={`${label} from ${i + 1}`} />
          </label>
          <label>
            Up to (₹)
            <input type="number" min="0" value={s.to} onChange={(e) => set(i, { to: e.target.value })} aria-label={`${label} up to ${i + 1}`} />
          </label>
          <label>
            Monthly (₹)
            <input type="number" min="0" value={s.monthly} onChange={(e) => set(i, { monthly: e.target.value })} aria-label={`${label} monthly ${i + 1}`} />
          </label>
          <label>
            February (₹)
            <input type="number" min="0" value={s.february} onChange={(e) => set(i, { february: e.target.value })} aria-label={`${label} february ${i + 1}`} />
          </label>
          <button type="button" className="secondary" onClick={() => onChange(slabs.filter((_, j) => j !== i))}>
            Remove
          </button>
        </div>
      ))}
      <button type="button" className="secondary" onClick={() => onChange([...slabs, blankSlab()])}>
        Add a slab
      </button>
    </fieldset>
  )
}

function RuleForm({ rule, onCancel, onDone }) {
  const editing = Boolean(rule)
  const { busy, error, run } = useAction()
  const [statute, setStatute] = useState(rule?.statute || 'pt')
  const [state, setState] = useState(rule?.jurisdiction || 'MH')
  const [from, setFrom] = useState(rule?.validFrom || '')
  const [source, setSource] = useState(rule?.source || '')
  const [men, setMen] = useState(slabsToForm(rule?.pt?.men).length ? slabsToForm(rule?.pt?.men) : [blankSlab()])
  const [women, setWomen] = useState(slabsToForm(rule?.pt?.women))
  const [ceiling, setCeiling] = useState(rule?.pt?.yearlyCeiling ?? '')
  const [employee, setEmployee] = useState(rule?.lwf?.employee ?? '')
  const [employer, setEmployer] = useState(rule?.lwf?.employer ?? '')
  const [months, setMonths] = useState(rule?.lwf?.months || [])

  async function save(e) {
    e.preventDefault()
    const body =
      statute === 'pt'
        ? { statute, jurisdiction: state, validFrom: from, source, pt: { men: slabsToBody(men), women: slabsToBody(women), yearlyCeiling: num(ceiling) } }
        : { statute, jurisdiction: state, validFrom: from, source, lwf: { employee: num(employee), employer: num(employer), months } }
    const saved = await run(() => (editing ? platform.editRule(rule.id, body) : platform.addRule(body)))
    if (saved) onDone()
  }

  return (
    <form onSubmit={save} className="panel inner" aria-label={editing ? 'Edit rule' : 'Add a rule'}>
      <h3>{editing ? 'Edit this draft' : 'Add a rule'}</h3>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-grid">
        <label>
          Rule
          <select value={statute} disabled={editing} onChange={(e) => setStatute(e.target.value)}>
            {Object.entries(STATUTES).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </label>
        <label>
          State
          <select value={state} disabled={editing} onChange={(e) => setState(e.target.value)}>
            {Object.entries(STATE_NAMES)
              .filter(([k]) => k !== 'IN')
              .map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
          </select>
        </label>
        <label>
          Applies from (first of a month)
          <input type="date" value={from} disabled={editing} onChange={(e) => setFrom(e.target.value)} />
        </label>
      </div>
      <label>
        Where the figures come from
        <input value={source} maxLength={300} onChange={(e) => setSource(e.target.value)} placeholder="Notification or consultant’s note" />
      </label>
      {statute === 'pt' ? (
        <>
          <SlabRows label="Slabs for men (and everyone, where there are none for women)" slabs={men} onChange={setMen} />
          <SlabRows label="Slabs for women (optional)" slabs={women} onChange={setWomen} />
          <label>
            Most a person pays in a year (₹, optional)
            <input type="number" min="0" value={ceiling} onChange={(e) => setCeiling(e.target.value)} />
          </label>
        </>
      ) : (
        <>
          <div className="form-grid">
            <label>
              Employee pays (₹)
              <input type="number" min="0" value={employee} onChange={(e) => setEmployee(e.target.value)} />
            </label>
            <label>
              Employer pays (₹)
              <input type="number" min="0" value={employer} onChange={(e) => setEmployer(e.target.value)} />
            </label>
          </div>
          <fieldset>
            <legend>Months it falls due</legend>
            {MONTHS.map((m, i) => (
              <label key={m} className="check">
                <input
                  type="checkbox"
                  checked={months.includes(i + 1)}
                  onChange={(e) => setMonths(e.target.checked ? [...months, i + 1].sort((a, b) => a - b) : months.filter((x) => x !== i + 1))}
                />
                {m}
              </label>
            ))}
          </fieldset>
        </>
      )}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {editing ? 'Save the draft' : 'Add as a draft'}
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function SignOffForm({ rule, onCancel, onDone }) {
  const { busy, error, run } = useAction()
  const [by, setBy] = useState('')
  const [on, setOn] = useState('')
  async function save(e) {
    e.preventDefault()
    if (await run(() => platform.signOffRule(rule.id, { signedBy: by, signedOn: on }))) onDone()
  }
  return (
    <form onSubmit={save} className="panel inner" aria-label="Record sign-off">
      <h3>
        Record the consultant’s sign-off: {STATUTES[rule.statute]}, {STATE_NAMES[rule.jurisdiction] || rule.jurisdiction}, from {formatDay(rule.validFrom)}
      </h3>
      <p className="hint">After this the version can no longer be edited. A change needs a new version.</p>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-grid">
        <label>
          Consultant’s name
          <input value={by} maxLength={120} onChange={(e) => setBy(e.target.value)} />
        </label>
        <label>
          Date they signed it off
          <input type="date" value={on} onChange={(e) => setOn(e.target.value)} />
        </label>
      </div>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy || by.trim().length < 3 || !on}>
          Record sign-off
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
