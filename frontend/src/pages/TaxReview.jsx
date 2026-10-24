import { useState } from 'react'
import { tax } from '../api.js'
import { useToast } from '../feedbackContext.js'
import { formatDate, formatDay, localToday, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { ClaimRow } from './TaxClaims.jsx'
import { rupees } from './payrollText.js'
import { HEADS, STATES } from './taxText.js'

/**
 * Income tax declarations for HR: the window for the tax year, where each employee stands, and one
 * employee's claims with their proofs to accept or return.
 */
export default function TaxReview() {
  const list = useLoad(() => tax.overview())
  const [person, setPerson] = useState(null)
  if (person) {
    return (
      <Employee
        id={person}
        onBack={() => {
          setPerson(null)
          list.reload()
        }}
      />
    )
  }
  if (list.loading) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  const d = list.data
  return (
    <>
      <div className="section-head">
        <div>
          <h2>Tax declarations {d.taxYear}</h2>
          <p className="muted">What employees expect to claim (Form 124), and the proofs behind it. Only the old regime takes these claims.</p>
        </div>
      </div>
      <Window data={d} onSaved={list.reload} />
      <div className="tiles">
        {Object.entries(STATES).map(([s, [, label]]) => (
          <div key={s} className="tile">
            <div className="tile-label">{label}</div>
            <div className="tile-value" data-testid={`tax-count-${s}`}>
              {d.counts[s]}
            </div>
          </div>
        ))}
      </div>
      {d.people.length === 0 ? (
        <Empty>Nobody is employed yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Employee</th>
                <th>Regime</th>
                <th>Where it is</th>
                <th className="num">Claims</th>
                <th className="num">To check</th>
                <th className="num">Accepted</th>
              </tr>
            </thead>
            <tbody>
              {d.people.map((p) => (
                <tr key={p.employmentId} data-employee={p.name}>
                  <td>
                    <button type="button" className="text-link" onClick={() => setPerson(p.employmentId)}>
                      {p.name}
                    </button>{' '}
                    <span className="hint">{p.employeeCode}</span>
                  </td>
                  <td>{p.regime === 'old' ? 'Old' : 'New'}</td>
                  <td>
                    <Badge tone={STATES[p.state][0]}>{STATES[p.state][1]}</Badge>
                  </td>
                  <td className="num">{p.claims}</td>
                  <td className="num">{Number(p.toCheck) > 0 ? <strong>{p.toCheck}</strong> : '—'}</td>
                  <td className="num">{Number(p.accepted) > 0 ? p.accepted : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

/** When employees may declare and send proofs, and from which payroll month only checked amounts count. */
function Window({ data, onSaved }) {
  const w = data.window
  const start = Number(data.taxYear.slice(0, 4))
  const [editing, setEditing] = useState(false)
  const [f, setF] = useState({
    declareFrom: w?.declareFrom || localToday(),
    declareUntil: w?.declareUntil || `${start}-05-31`,
    proofsFrom: w?.proofsFrom || '',
    proofsUntil: w?.proofsUntil || '',
    verifiedFrom: w?.verifiedFrom ? w.verifiedFrom.slice(0, 7) : '',
  })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await tax.saveWindow({ taxYear: data.taxYear, ...f })
      setEditing(false)
      toast(w ? 'Window saved' : `Declarations for ${data.taxYear} are open; employees have been told`)
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="panel" data-testid="tax-window">
      <div className="section-head">
        <h3>
          Window <Badge tone={data.declareOpen ? 'good' : 'neutral'}>{data.declareOpen ? 'Declarations open' : 'Declarations closed'}</Badge>{' '}
          {data.proofsOpen && <Badge tone="warn">Proofs open</Badge>}
        </h3>
        {data.canManage && !editing && (
          <button type="button" className="outline" onClick={() => setEditing(true)}>
            {w ? 'Change' : 'Open declarations…'}
          </button>
        )}
      </div>
      {!editing &&
        (w ? (
          <ul className="plain-list">
            <li>
              Declarations: {formatDay(w.declareFrom)} to {formatDay(w.declareUntil)}
            </li>
            <li>Proofs: {w.proofsFrom ? `${formatDay(w.proofsFrom)} to ${formatDay(w.proofsUntil)}` : 'not set yet'}</li>
            <li>
              Payroll counts{' '}
              {w.verifiedFrom
                ? `declared amounts until ${new Date(`${w.verifiedFrom}T00:00:00`).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' })}, and only accepted amounts from then`
                : 'declared amounts until you set the month from which only accepted amounts count'}
            </li>
          </ul>
        ) : (
          <p className="hint">Not open yet. Open declarations to let employees declare; they are told when you do.</p>
        ))}
      {editing && (
        <form onSubmit={save}>
          <div className="grid">
            <div>
              <label htmlFor="tw-declare-from">Declarations open</label>
              <input id="tw-declare-from" type="date" value={f.declareFrom} onChange={set('declareFrom')} />
            </div>
            <div>
              <label htmlFor="tw-declare-until">Declarations close</label>
              <input id="tw-declare-until" type="date" value={f.declareUntil} onChange={set('declareUntil')} />
            </div>
            <div>
              <label htmlFor="tw-proofs-from">Proofs open (optional)</label>
              <input id="tw-proofs-from" type="date" value={f.proofsFrom} onChange={set('proofsFrom')} />
            </div>
            <div>
              <label htmlFor="tw-proofs-until">Proofs close</label>
              <input id="tw-proofs-until" type="date" value={f.proofsUntil} onChange={set('proofsUntil')} />
            </div>
            <div>
              <label htmlFor="tw-verified-from">Only accepted amounts from (optional)</label>
              <input id="tw-verified-from" type="month" value={f.verifiedFrom} min={`${start}-04`} max={`${start + 1}-03`} onChange={set('verifiedFrom')} />
            </div>
          </div>
          <p className="hint">
            A usual year: declarations in April and May; proofs in January; only accepted amounts from February, so the last months of the year
            catch up the tax on anything not proved.
          </p>
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              Save
            </button>
            <button type="button" className="quiet" onClick={() => setEditing(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </section>
  )
}

/** One employee's declaration, with each claim's proofs to accept or return. */
function Employee({ id, onBack }) {
  const load = useLoad(() => tax.employee(id))
  const [data, setData] = useState(null)
  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  const d = { ...load.data, ...(data || {}) }
  const c = d.counted
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← All employees
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            {d.name} {d.submittedAt ? <Badge tone="good">Submitted</Badge> : <Badge>Not submitted</Badge>}
          </h2>
          <p className="muted">
            {[d.employeeCode, `${d.regime === 'old' ? 'Old' : 'New'} regime`, d.submittedAt && `submitted ${formatDate(d.submittedAt)}`]
              .filter(Boolean)
              .join(' · ')}
          </p>
        </div>
      </div>
      {d.own && <p className="notice">This is your own declaration: someone else checks its proofs.</p>}
      {d.regime === 'new' && d.items.length > 0 && (
        <p className="notice">The new regime does not take these claims, so they do not change this employee’s tax.</p>
      )}
      {c && d.regime === 'old' && (
        <p className="hint">
          Payroll counts {c.basis === 'verified' ? 'accepted amounts only' : 'declared amounts'} this month
          {c.rent ? `: rent for ${c.rent.reduce((n, r) => n + Number(r.months), 0)} months` : ''}
          {c.sec80c ? `, ${rupees(c.sec80c)} in the ₹1.5 lakh group besides PF` : ''}
          {c.nps ? `, NPS ${rupees(c.nps)}` : ''}. Limits are applied when the run is calculated.
        </p>
      )}
      {d.items.length === 0 && <Empty>Nothing declared for {d.taxYear}.</Empty>}
      {HEADS.map(({ head, title }) => {
        const items = d.items.filter((i) => i.head === head)
        if (items.length === 0) return null
        return (
          <section key={head} className="panel">
            <h3>{title}</h3>
            <div className="doc-list">
              {items.map((item) => (
                <ClaimRow key={item.id} item={item} hr canReview={d.canReview} onChanged={(next) => setData(next)} />
              ))}
            </div>
          </section>
        )
      })}
    </>
  )
}
