import { useState } from 'react'
import { tax } from '../api.js'
import { useConfirm, useToast } from '../feedbackContext.js'
import { formatDate, formatDay, localToday, useLoad } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'
import { ClaimRow } from './TaxClaims.jsx'
import { rupees } from './payrollText.js'
import { HEADS, KINDS, limitWords } from './taxText.js'

/**
 * The employee's income tax declaration for this tax year (Form 124): the claims, certifying them,
 * and the proofs for each when HR collects them. Under the new regime the claims change nothing.
 */
export default function MyTax() {
  const load = useLoad(() => tax.mine())
  const [data, setData] = useState(null)
  const [editing, setEditing] = useState(null) // { head } for a new claim, or the item
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const confirm = useConfirm()
  const toast = useToast()
  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  const d = data || load.data
  const w = d.window

  async function act(fn) {
    setBusy(true)
    setError('')
    try {
      const next = await fn()
      setData(next)
      return next
    } catch (err) {
      setError(err.message)
      return null
    } finally {
      setBusy(false)
    }
  }

  async function submit() {
    const sure = await confirm({
      title: `Submit your declaration for ${d.taxYear}?`,
      body: 'You declare that what you have entered is true, and that you will give proofs when HR asks. Payroll deducts tax on it from the next run. You can still change it until the window closes.',
      confirmLabel: 'Submit declaration',
      cancelLabel: 'Not yet',
    })
    if (sure && (await act(() => tax.submit()))) toast('Declaration submitted')
  }

  async function remove(item) {
    const sure = await confirm({ title: `Remove ${item.label}?`, body: 'Its proofs are removed too.', confirmLabel: 'Remove', cancelLabel: 'Keep it' })
    if (sure) await act(() => tax.removeItem(item.id))
  }

  return (
    <>
      <div className="section-head">
        <div>
          <h2>
            Tax declaration {d.taxYear}{' '}
            {d.submittedAt ? <Badge tone="good">Submitted</Badge> : d.items.length > 0 ? <Badge>Not submitted</Badge> : null}
          </h2>
          <p className="muted">
            {d.submittedAt
              ? `Submitted ${formatDate(d.submittedAt)}. Changes you save while the window is open count straight away.`
              : 'What you expect to claim this tax year, so payroll deducts the right tax each month.'}
          </p>
        </div>
        {d.canEdit && d.items.length > 0 && !d.submittedAt && (
          <button type="button" className="primary" disabled={busy} onClick={submit}>
            Submit declaration
          </button>
        )}
      </div>

      {d.regime === 'new' && (
        <p className="notice" role="status">
          Your tax is worked out under the <strong>new regime</strong>, which does not take these claims: declaring them changes nothing.
          They count only under the old regime. To switch, ask HR.
        </p>
      )}
      <p className={d.declareOpen || d.proofsOpen ? 'notice' : 'notice warn-notice'} role="status">
        {!w && 'Declarations are not open yet. HR opens them for the tax year and you will be told.'}
        {w && d.declareOpen && `Declare until ${formatDay(w.declareUntil)}. `}
        {w && !d.declareOpen && `Declarations ${localToday() < w.declareFrom ? 'open' : 'were open'} from ${formatDay(w.declareFrom)} to ${formatDay(w.declareUntil)}. `}
        {w && w.proofsFrom && (d.proofsOpen ? `Send proofs until ${formatDay(w.proofsUntil)}.` : `Proofs are collected from ${formatDay(w.proofsFrom)} to ${formatDay(w.proofsUntil)}.`)}
        {w && w.verifiedFrom && ` From ${new Date(`${w.verifiedFrom}T00:00:00`).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' })}, payroll counts only what HR has accepted.`}
      </p>
      {d.proofsOpen && !d.submittedAt && d.items.length > 0 && <p className="hint">Submit your declaration to send proofs for it.</p>}
      <ErrorNote>{error}</ErrorNote>

      {HEADS.map(({ head, title, hint }) => {
        const items = d.items.filter((i) => i.head === head)
        if (!d.canEdit && items.length === 0) return null
        return (
          <section key={head} className="panel" data-head={head}>
            <div className="section-head">
              <div>
                <h3>{title}</h3>
                <p className="hint">
                  {hint} {limitWords(head, d.limits, rupees)}
                </p>
              </div>
              {d.canEdit && editing?.head !== head && (
                <button type="button" className="outline" onClick={() => setEditing({ head })}>
                  Add
                </button>
              )}
            </div>
            <div className="doc-list">
              {items.map((item) =>
                editing?.id === item.id ? (
                  <ClaimForm
                    key={item.id}
                    head={head}
                    item={item}
                    limits={d.limits}
                    busy={busy}
                    onCancel={() => setEditing(null)}
                    onSave={async (body) => (await act(() => tax.updateItem(item.id, body))) && setEditing(null)}
                  />
                ) : (
                  <ClaimRow
                    key={item.id}
                    item={item}
                    canEdit={d.canEdit}
                    canSendProofs={d.canSendProofs}
                    onEdit={() => setEditing(item)}
                    onRemove={() => remove(item)}
                    onChanged={setData}
                  />
                ),
              )}
              {editing?.head === head && !editing.id && (
                <ClaimForm
                  head={head}
                  limits={d.limits}
                  taxYear={d.taxYear}
                  busy={busy}
                  onCancel={() => setEditing(null)}
                  onSave={async (body) => (await act(() => tax.addItem(body))) && setEditing(null)}
                />
              )}
              {items.length === 0 && editing?.head !== head && <p className="hint">Nothing declared.</p>}
            </div>
          </section>
        )
      })}
      {!d.canEdit && d.items.length === 0 && <p className="empty">Nothing declared for {d.taxYear}.</p>}
    </>
  )
}

/** Adds or changes one claim; rent asks for the months, the city and the landlord. */
function ClaimForm({ head, item, limits, taxYear, busy, onSave, onCancel }) {
  const start = Number((taxYear || '2026-27').slice(0, 4))
  const d = item?.details || {}
  const metros = limits?.hraMetros || []
  const [f, setF] = useState({
    amount: item ? String(Number(item.amount)) : '',
    kind: item?.kind || 'ppf',
    from: d.from || `${start}-04`,
    to: d.to || `${start + 1}-03`,
    city: d.city && !metros.includes(d.city) ? 'other' : d.city || metros[0] || 'other',
    otherCity: d.city && !metros.includes(d.city) ? d.city : '',
    landlordName: d.landlordName || '',
    landlordAddress: d.landlordAddress || '',
    landlordPan: d.landlordPan || '',
    relationship: d.relationship || '',
    lenderName: d.lenderName || '',
    lenderAddress: d.lenderAddress || '',
    lenderPan: d.lenderPan || '',
    insurer: d.insurer || '',
    senior: Boolean(d.senior),
  })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const id = (k) => `claim-${head}-${k}`

  function body() {
    const details = {}
    if (head === 'rent') {
      Object.assign(details, {
        from: f.from,
        to: f.to,
        city: f.city === 'other' ? f.otherCity : f.city,
        landlordName: f.landlordName,
        landlordAddress: f.landlordAddress,
        landlordPan: f.landlordPan,
        relationship: f.relationship,
      })
    } else if (head === 'home_loan') {
      Object.assign(details, { lenderName: f.lenderName, lenderAddress: f.lenderAddress, lenderPan: f.lenderPan })
    } else if (head === 'edu_loan') {
      details.lenderName = f.lenderName
    } else if (head === 'health_self' || head === 'health_parents') {
      Object.assign(details, { insurer: f.insurer, senior: f.senior })
    }
    return { head, kind: head === 'sec80c' ? f.kind : null, amount: Number(f.amount), details }
  }

  return (
    <form
      className="doc-row claim-form"
      onSubmit={(e) => {
        e.preventDefault()
        onSave(body())
      }}
    >
      <div className="grid">
        {head === 'sec80c' && (
          <div>
            <label htmlFor={id('kind')}>What it is</label>
            <select id={id('kind')} value={f.kind} onChange={set('kind')}>
              {KINDS.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        )}
        <div>
          <label htmlFor={id('amount')}>{head === 'rent' ? 'Rent a month (₹)' : 'Amount for the year (₹)'}</label>
          <input id={id('amount')} inputMode="decimal" value={f.amount} onChange={set('amount')} />
        </div>
        {head === 'rent' && (
          <>
            <div>
              <label htmlFor={id('from')}>From</label>
              <input id={id('from')} type="month" value={f.from} onChange={set('from')} />
            </div>
            <div>
              <label htmlFor={id('to')}>To</label>
              <input id={id('to')} type="month" value={f.to} onChange={set('to')} />
            </div>
            <div>
              <label htmlFor={id('city')}>City of the rented home</label>
              <select id={id('city')} value={f.city} onChange={set('city')}>
                {metros.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
                <option value="other">Elsewhere</option>
              </select>
            </div>
            {f.city === 'other' && (
              <div>
                <label htmlFor={id('otherCity')}>Which city</label>
                <input id={id('otherCity')} value={f.otherCity} onChange={set('otherCity')} />
              </div>
            )}
            <div>
              <label htmlFor={id('landlordName')}>Landlord’s name</label>
              <input id={id('landlordName')} value={f.landlordName} onChange={set('landlordName')} />
            </div>
            <div className="span-2">
              <label htmlFor={id('landlordAddress')}>Address of the rented home</label>
              <input id={id('landlordAddress')} value={f.landlordAddress} onChange={set('landlordAddress')} />
            </div>
            <div>
              <label htmlFor={id('landlordPan')}>Landlord’s PAN</label>
              <input id={id('landlordPan')} value={f.landlordPan} onChange={set('landlordPan')} autoCapitalize="characters" />
            </div>
            <div>
              <label htmlFor={id('relationship')}>Related to you? (optional)</label>
              <input id={id('relationship')} value={f.relationship} placeholder="For example: none, or father" onChange={set('relationship')} />
            </div>
          </>
        )}
        {(head === 'home_loan' || head === 'edu_loan') && (
          <div>
            <label htmlFor={id('lenderName')}>Lender{head === 'edu_loan' ? ' (optional)' : ''}</label>
            <input id={id('lenderName')} value={f.lenderName} onChange={set('lenderName')} />
          </div>
        )}
        {head === 'home_loan' && (
          <>
            <div className="span-2">
              <label htmlFor={id('lenderAddress')}>Lender’s address (optional)</label>
              <input id={id('lenderAddress')} value={f.lenderAddress} onChange={set('lenderAddress')} />
            </div>
            <div>
              <label htmlFor={id('lenderPan')}>Lender’s PAN (optional)</label>
              <input id={id('lenderPan')} value={f.lenderPan} onChange={set('lenderPan')} autoCapitalize="characters" />
            </div>
          </>
        )}
        {(head === 'health_self' || head === 'health_parents') && (
          <>
            <div>
              <label htmlFor={id('insurer')}>Insurer (optional)</label>
              <input id={id('insurer')} value={f.insurer} onChange={set('insurer')} />
            </div>
            <div className="check-field">
              <label>
                <input type="checkbox" checked={f.senior} onChange={set('senior')} />{' '}
                {head === 'health_self' ? 'You or your spouse is a senior citizen' : 'A parent is a senior citizen'}
              </label>
            </div>
          </>
        )}
      </div>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy || !f.amount}>
          {item ? 'Save' : 'Add'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
