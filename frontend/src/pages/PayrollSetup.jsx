import { useState } from 'react'
import { payroll } from '../api.js'
import { formatDate, formatDay, useAction, useLoad, numeric, wholeNumber } from '../hooks.js'
import { Badge, Empty, ErrorNote, Tabs } from '../ui.jsx'
import { AMOUNT_TYPES, KINDS, PRORATION, ROUNDING, STATE_NAMES, STATUTES, WAGE, monthLabel } from './payrollText.js'

const nextMonth = (today) => {
  const d = new Date(`${today}T00:00:00`)
  return new Date(Date.UTC(d.getFullYear(), d.getMonth() + 1, 1)).toISOString().slice(0, 10)
}
const thisMonth = (today) => `${today.slice(0, 7)}-01`
/** The later of two ISO dates: a default date never falls in a month already approved. */
const later = (a, b) => (b && b > a ? b : a)

/**
 * Payroll setup: the employer and its registrations, the components and their formulas, the
 * Organization's own tables, salary structures, pay groups, and the statutory rules in force.
 * Every change to a component or a structure applies from the first of a month.
 */
export default function PayrollSetup() {
  const setup = useLoad(payroll.setup)
  const [area, setArea] = useState('entity')
  if (setup.loading) return <p className="muted">Loading…</p>
  if (setup.error) return <ErrorNote>{setup.error}</ErrorNote>
  const s = setup.data
  const props = { s, onChanged: setup.reload }
  return (
    <>
      <Tabs
        tabs={[
          { id: 'entity', label: 'Employer' },
          { id: 'components', label: 'Components' },
          { id: 'structures', label: 'Structures' },
          { id: 'tables', label: 'Tables' },
          { id: 'groups', label: 'Pay groups' },
          { id: 'rules', label: 'Statutory rules' },
        ]}
        current={area}
        onChange={setArea}
      />
      {area === 'entity' && <Entity {...props} />}
      {area === 'components' && <Components {...props} />}
      {area === 'structures' && <Structures {...props} />}
      {area === 'tables' && <Tables {...props} />}
      {area === 'groups' && <Groups {...props} />}
      {area === 'rules' && <Rules {...props} />}
    </>
  )
}

// ── the employer ──────────────────────────────────────────────────────

function Entity({ s, onChanged }) {
  const st = s.settings
  const [v, setV] = useState({
    entityName: st.entityName || '',
    pan: st.pan || '',
    tan: st.tan || '',
    address: st.address || '',
    debitAccount: st.debitAccount || '',
    debitIfsc: st.debitIfsc || '',
    proration: st.proration,
    absenceIsLop: st.absenceIsLop,
    pfRestrict: st.pfRestrict,
    metroLocations: st.metroLocations || [],
    variancePct: st.variancePct,
    largeOneTime: st.largeOneTime,
    releaseAuto: st.releaseAuto,
  })
  const [saved, setSaved] = useState('')
  const { busy, error, run } = useAction()
  const set = (patch) => {
    setSaved('')
    setV({ ...v, ...patch })
  }
  return (
    <>
      <form
        className="panel"
        onSubmit={async (e) => {
          e.preventDefault()
          if (await run(() => payroll.saveSettings({ ...v, variancePct: numeric(v.variancePct, 'The net pay change'), largeOneTime: numeric(v.largeOneTime, 'The one-time amount') }))) {
            setSaved('Saved.')
            onChanged()
          }
        }}
      >
        <fieldset disabled={!s.canEdit}>
          <h3>The employer</h3>
          <div className="grid">
            <div>
              <label htmlFor="pe-name">Legal name</label>
              <input id="pe-name" value={v.entityName} onChange={(e) => set({ entityName: e.target.value })} />
            </div>
            <div>
              <label htmlFor="pe-pan">PAN</label>
              <input id="pe-pan" value={v.pan} onChange={(e) => set({ pan: e.target.value.toUpperCase() })} />
            </div>
            <div>
              <label htmlFor="pe-tan">TAN</label>
              <input id="pe-tan" value={v.tan} onChange={(e) => set({ tan: e.target.value.toUpperCase() })} />
            </div>
          </div>
          <label htmlFor="pe-address">Address, as printed on payslips</label>
          <input id="pe-address" value={v.address} onChange={(e) => set({ address: e.target.value })} />
          <div className="grid">
            <div>
              <label htmlFor="pe-debit">Salary account (debited)</label>
              <input id="pe-debit" inputMode="numeric" value={v.debitAccount} onChange={(e) => set({ debitAccount: e.target.value })} />
            </div>
            <div>
              <label htmlFor="pe-ifsc">Its IFSC</label>
              <input id="pe-ifsc" value={v.debitIfsc} onChange={(e) => set({ debitIfsc: e.target.value.toUpperCase() })} />
            </div>
          </div>

          <h3>How pay is worked out</h3>
          <label htmlFor="pe-proration">A part month is paid by</label>
          <select id="pe-proration" value={v.proration} onChange={(e) => set({ proration: e.target.value })}>
            {Object.entries(PRORATION).map(([k, words]) => (
              <option key={k} value={k}>
                {words}
              </option>
            ))}
          </select>
          <div className="check-list">
            <label className="check">
              <input type="checkbox" checked={v.absenceIsLop} onChange={(e) => set({ absenceIsLop: e.target.checked })} />
              Absence without leave or a request is loss of pay (unpaid leave always is)
            </label>
            <label className="check">
              <input type="checkbox" checked={v.pfRestrict} onChange={(e) => set({ pfRestrict: e.target.checked })} />
              PF on wages up to the ceiling only (unless an employee’s pay profile says full wages)
            </label>
          </div>
          <fieldset className="role-options">
            <legend>Metro locations, for HRA</legend>
            {s.locations.length === 0 && <p className="hint">No work locations yet. Add them under Attendance → Setup.</p>}
            {s.locations.map((l) => (
              <label key={l.id} className="check">
                <input
                  type="checkbox"
                  checked={v.metroLocations.includes(l.id)}
                  onChange={(e) =>
                    set({ metroLocations: e.target.checked ? [...v.metroLocations, l.id] : v.metroLocations.filter((x) => x !== l.id) })
                  }
                />
                {l.name} {l.stateCode && <span className="hint">· {STATE_NAMES[l.stateCode] || l.stateCode}</span>}
              </label>
            ))}
          </fieldset>
          <div className="grid">
            <div>
              <label htmlFor="pe-variance">Flag a change in net pay above (%)</label>
              <input id="pe-variance" inputMode="decimal" value={v.variancePct} onChange={(e) => set({ variancePct: e.target.value })} />
            </div>
            <div>
              <label htmlFor="pe-large">Flag a one-time payment above (₹)</label>
              <input id="pe-large" inputMode="decimal" value={v.largeOneTime} onChange={(e) => set({ largeOneTime: e.target.value })} />
            </div>
          </div>
          <label className="check">
            <input type="checkbox" checked={v.releaseAuto} onChange={(e) => set({ releaseAuto: e.target.checked })} />
            Release payslips as soon as a run is marked paid
          </label>
        </fieldset>
        <ErrorNote>{error}</ErrorNote>
        {saved && <p className="notice">{saved}</p>}
        {s.canEdit && (
          <div className="form-actions">
            <button type="submit" className="secondary" disabled={busy}>
              Save
            </button>
            {st.updatedByName && (
              <span className="hint">
                Last changed by {st.updatedByName}, {formatDate(st.updatedAt)}
              </span>
            )}
          </div>
        )}
      </form>
      <Registrations s={s} onChanged={onChanged} />
    </>
  )
}

function Registrations({ s, onChanged }) {
  const [v, setV] = useState({ statute: 'pf', state: 'KA', number: '' })
  const { busy, error, run } = useAction()
  const states = v.statute === 'pt' || v.statute === 'lwf'
  return (
    <section className="panel" data-testid="registrations">
      <h3>Registrations</h3>
      <p className="hint">A run cannot be approved while employees need a registration the employer does not have.</p>
      {s.registrations.length > 0 && (
        <table>
          <tbody>
            {s.registrations.map((r) => (
              <tr key={r.id}>
                <td>
                  {STATUTES[r.statute]}
                  {r.state ? `, ${STATE_NAMES[r.state] || r.state}` : ''}
                </td>
                <td>{r.number}</td>
                <td>
                  {s.canEdit && (
                    <button type="button" className="quiet" onClick={() => run(() => payroll.removeRegistration(r.id)).then(onChanged)}>
                      Remove
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {s.canEdit && (
        <form
          className="inline-form"
          onSubmit={async (e) => {
            e.preventDefault()
            if (await run(() => payroll.addRegistration({ ...v, state: states ? v.state : null }))) {
              setV((x) => ({ ...x, number: '' }))
              onChanged()
            }
          }}
        >
          <div>
            <label htmlFor="reg-statute">For</label>
            <select id="reg-statute" value={v.statute} onChange={(e) => setV({ ...v, statute: e.target.value })}>
              {['pf', 'esi', 'pt', 'lwf'].map((k) => (
                <option key={k} value={k}>
                  {STATUTES[k]}
                </option>
              ))}
            </select>
          </div>
          {states && (
            <div>
              <label htmlFor="reg-state">State</label>
              <select id="reg-state" value={v.state} onChange={(e) => setV({ ...v, state: e.target.value })}>
                {['GA', 'KA', 'MH'].map((k) => (
                  <option key={k} value={k}>
                    {STATE_NAMES[k]}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div>
            <label htmlFor="reg-number">Number</label>
            <input id="reg-number" value={v.number} onChange={(e) => setV({ ...v, number: e.target.value })} />
          </div>
          <div className="form-actions">
            <button type="submit" className="secondary" disabled={busy || !v.number.trim()}>
              Add
            </button>
          </div>
        </form>
      )}
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

// ── components ────────────────────────────────────────────────────────

function Components({ s, onChanged }) {
  const [editing, setEditing] = useState(null) // a component, or 'new'
  if (editing) {
    return (
      <ComponentForm
        s={s}
        component={editing === 'new' ? null : editing}
        onDone={() => {
          setEditing(null)
          onChanged()
        }}
        onCancel={() => setEditing(null)}
      />
    )
  }
  const groups = [
    ['earning', 'Earnings'],
    ['deduction', 'Deductions'],
    ['employer', 'Employer contributions'],
    ['info', 'For information'],
  ]
  return (
    <>
      <div className="toolbar">
        <p className="hint">
          Each component is one line a payslip can carry. A change applies from the first of a month; earlier months keep the version they were paid
          with.
        </p>
        <span className="spacer" />
        {s.canEdit && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            Add a component
          </button>
        )}
      </div>
      {groups.map(([kind, title]) => {
        const list = s.components.filter((c) => c.kind === kind && !c.retiredAt)
        if (!list.length) return null
        return (
          <section key={kind} className="panel">
            <h3>{title}</h3>
            <div className="table-wrap">
              <table className="components">
                <thead>
                  <tr>
                    <th>Component</th>
                    <th>Amount</th>
                    <th>Counts as</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {list.map((c) => (
                    <tr key={c.id} data-component={c.code}>
                      <td>
                        <strong>{c.name}</strong> <code className="hint">{c.code}</code>
                      </td>
                      <td>
                        {c.amountType === 'formula' ? <code>{c.formula}</code> : c.amountType === 'fixed' ? `₹${c.formula} a month` : AMOUNT_TYPES[c.amountType]}
                        {c.nextFrom && <div className="hint">Changes from {formatDay(c.nextFrom)}</div>}
                      </td>
                      <td className="hint">
                        {c.statutory
                          ? 'Set by the rule pack'
                          : [c.wage !== 'exclude' && WAGE[c.wage].toLowerCase(), c.esiWage && 'ESI wage', c.taxable && 'taxable', c.prorate && 'prorated', c.oneTime && 'one-time']
                              .filter(Boolean)
                              .join(' · ')}
                      </td>
                      <td>
                        {s.canEdit && (
                          <button type="button" className="quiet" onClick={() => setEditing(c)}>
                            Change
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )
      })}
    </>
  )
}

function FormulaField({ id, value, onChange, label = 'Formula', hint = true }) {
  const [check, setCheck] = useState(null)
  return (
    <div className="formula-field">
      <label htmlFor={id}>{label}</label>
      <textarea
        id={id}
        rows={2}
        className="code"
        value={value}
        onChange={(e) => {
          setCheck(null)
          onChange(e.target.value)
        }}
        spellCheck={false}
      />
      <div className="form-actions">
        <button
          type="button"
          className="quiet"
          disabled={!value.trim()}
          onClick={async () => {
            try {
              setCheck(await payroll.checkFormula(value))
            } catch (err) {
              setCheck({ ok: false, message: err.message })
            }
          }}
        >
          Check
        </button>
        {check && (
          <span className={check.ok ? 'hint good-text' : 'warn-text'} role="status">
            {check.ok
              ? `Looks right. Uses ${[...check.components, ...check.facts].join(', ') || 'no other values'}.`
              : `${check.message}${check.position >= 0 ? ` (at character ${check.position + 1})` : ''}`}
          </span>
        )}
      </div>
      {hint && (
        <p className="hint">
          Use components in capitals (BASIC, CTC_MONTHLY), facts in lower case (location.metro, days.paid), and if(), case(), min(), max(), round(),
          round_up(), round_down(), table(&quot;name&quot;, key), ytd(COMPONENT).
        </p>
      )}
    </div>
  )
}

function ComponentForm({ s, component: c, onDone, onCancel }) {
  const [v, setV] = useState({
    code: c?.code || '',
    name: c?.name || '',
    kind: c?.kind || 'earning',
    validFrom: later(nextMonth(s.today), s.openFrom),
    amountType: c?.amountType || 'formula',
    formula: c?.formula || '',
    wage: c?.wage || 'exclude',
    esiWage: c ? c.esiWage : true,
    taxable: c ? c.taxable : true,
    prorate: c ? c.prorate : true,
    rounding: c?.rounding || 'nearest',
    oneTime: c ? c.oneTime : false,
    ledgerCode: c?.ledgerCode || '',
    position: c?.position || 60,
  })
  const { busy, error, run } = useAction()
  const set = (patch) => setV({ ...v, ...patch })
  const statutory = c?.statutory
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => payroll.saveComponent(c?.id, { ...v, position: wholeNumber(v.position, 'The order on the payslip') }))) onDone()
      }}
    >
      <h3>{c ? `Change ${c.name}` : 'Add a component'}</h3>
      {statutory && <p className="hint">Worked out every month by its rule pack. Only its name, place on the payslip and ledger can change.</p>}
      <div className="grid">
        <div>
          <label htmlFor="pc-name">Name on the payslip</label>
          <input id="pc-name" value={v.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="pc-code">Code</label>
          <input id="pc-code" value={v.code} disabled={Boolean(c)} onChange={(e) => set({ code: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '') })} />
          {!c && <p className="hint">Used in formulas, like MEAL_CARD.</p>}
        </div>
        <div>
          <label htmlFor="pc-kind">Kind</label>
          <select id="pc-kind" value={v.kind} disabled={Boolean(c)} onChange={(e) => set({ kind: e.target.value })}>
            {Object.entries(KINDS).map(([k, words]) => (
              <option key={k} value={k}>
                {words}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="pc-from">Applies from</label>
          <input id="pc-from" type="date" value={v.validFrom} onChange={(e) => set({ validFrom: e.target.value })} />
          <p className="hint">The first of a month.</p>
        </div>
      </div>
      {!statutory && (
        <>
          <label htmlFor="pc-type">The amount is</label>
          <select id="pc-type" value={v.amountType} onChange={(e) => set({ amountType: e.target.value })}>
            {['formula', 'fixed', 'input'].map((k) => (
              <option key={k} value={k}>
                {AMOUNT_TYPES[k]}
              </option>
            ))}
          </select>
          {v.amountType === 'formula' && <FormulaField id="pc-formula" value={v.formula} onChange={(formula) => set({ formula })} />}
          {v.amountType === 'fixed' && (
            <>
              <label htmlFor="pc-fixed">Amount a month (₹)</label>
              <input id="pc-fixed" inputMode="decimal" value={v.formula} onChange={(e) => set({ formula: e.target.value })} />
            </>
          )}
          {v.kind === 'earning' && (
            <>
              <label htmlFor="pc-wage">Wages under the Code on Wages</label>
              <select id="pc-wage" value={v.wage} onChange={(e) => set({ wage: e.target.value })}>
                {Object.entries(WAGE).map(([k, words]) => (
                  <option key={k} value={k}>
                    {words}
                  </option>
                ))}
              </select>
              <p className="hint">Wages are the base for PF. Allowances that count only up to half of pay are added back when wages fall below half.</p>
            </>
          )}
          <div className="check-list">
            {v.kind === 'earning' && (
              <>
                <label className="check">
                  <input type="checkbox" checked={v.esiWage} onChange={(e) => set({ esiWage: e.target.checked })} />
                  Counts for ESI
                </label>
                <label className="check">
                  <input type="checkbox" checked={v.taxable} onChange={(e) => set({ taxable: e.target.checked })} />
                  Taxable
                </label>
              </>
            )}
            <label className="check">
              <input type="checkbox" checked={v.prorate} onChange={(e) => set({ prorate: e.target.checked })} />
              Paid for the days paid (prorated) in a part month
            </label>
            <label className="check">
              <input type="checkbox" checked={v.oneTime} onChange={(e) => set({ oneTime: e.target.checked })} />
              One-time: left out of next months’ tax projection
            </label>
          </div>
          <label htmlFor="pc-rounding">Rounding</label>
          <select id="pc-rounding" value={v.rounding} onChange={(e) => set({ rounding: e.target.value })}>
            {Object.entries(ROUNDING).map(([k, words]) => (
              <option key={k} value={k}>
                {words}
              </option>
            ))}
          </select>
        </>
      )}
      <div className="grid">
        <div>
          <label htmlFor="pc-ledger">Ledger in the accounts</label>
          <input id="pc-ledger" value={v.ledgerCode} onChange={(e) => set({ ledgerCode: e.target.value })} />
        </div>
        <div>
          <label htmlFor="pc-position">Order on the payslip</label>
          <input id="pc-position" inputMode="numeric" value={v.position} onChange={(e) => set({ position: e.target.value })} />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {c ? `Save from ${monthLabel(v.validFrom)}` : 'Add component'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

// ── structures ────────────────────────────────────────────────────────

function Structures({ s, onChanged }) {
  const [editing, setEditing] = useState(null)
  if (editing) {
    return (
      <StructureForm
        s={s}
        structure={editing === 'new' ? null : editing}
        onDone={() => {
          setEditing(null)
          onChanged()
        }}
        onCancel={() => setEditing(null)}
      />
    )
  }
  const name = (code) => s.components.find((c) => c.code === code)?.name || code
  return (
    <>
      <div className="toolbar">
        <p className="hint">A structure turns a yearly CTC (or gross) into the monthly components. Each employee is given one under People → Pay.</p>
        <span className="spacer" />
        {s.canEdit && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            Add a structure
          </button>
        )}
      </div>
      {s.structures.map((st) => (
        <section key={st.id} className="panel" data-structure={st.name}>
          <div className="toolbar">
            <h3>
              {st.name} <span className="hint">from {st.basis === 'ctc' ? 'CTC' : 'gross'} · {st.people} people</span>
            </h3>
            <span className="spacer" />
            {s.canEdit && (
              <button type="button" className="quiet" onClick={() => setEditing(st)}>
                Change
              </button>
            )}
          </div>
          <table>
            <tbody>
              {st.lines.map((l) => {
                const comp = s.components.find((c) => c.code === l.code)
                return (
                  <tr key={l.code}>
                    <td>
                      {name(l.code)} {st.balancing === l.code && <Badge>Balances</Badge>}
                    </td>
                    <td>
                      <code>{l.formula || (comp?.statutory ? 'by the PF rule' : comp?.formula || AMOUNT_TYPES[comp?.amountType])}</code>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          <p className="hint">
            Version of {formatDay(st.validFrom)}
            {st.changedByName ? `, by ${st.changedByName}` : ''}
            {st.nextFrom ? `; changes from ${formatDay(st.nextFrom)}` : ''}.
          </p>
        </section>
      ))}
    </>
  )
}

function StructureForm({ s, structure: st, onDone, onCancel }) {
  const usable = s.components.filter((c) => !c.retiredAt && (!c.statutory || c.code === 'PF_ER') && c.kind !== 'deduction')
  const [v, setV] = useState({
    name: st?.name || '',
    basis: st?.basis || 'ctc',
    validFrom: later(st ? nextMonth(s.today) : thisMonth(s.today), s.openFrom),
    balancing: st?.balancing || '',
    lines: st ? st.lines.map((l) => ({ code: l.code, formula: l.formula || '' })) : [{ code: 'BASIC', formula: '' }],
  })
  const { busy, error, run } = useAction()
  const set = (patch) => setV({ ...v, ...patch })
  const setLine = (i, patch) => set({ lines: v.lines.map((l, j) => (j === i ? { ...l, ...patch } : l)) })
  const comp = (code) => s.components.find((c) => c.code === code)
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => payroll.saveStructure(st?.id, { ...v, lines: v.lines.map((l) => (l.formula.trim() ? l : { code: l.code })) }))) onDone()
      }}
    >
      <h3>{st ? `Change ${st.name}` : 'Add a structure'}</h3>
      <div className="grid">
        <div>
          <label htmlFor="ss-name">Name</label>
          <input id="ss-name" value={v.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ss-basis">Starts from</label>
          <select id="ss-basis" value={v.basis} onChange={(e) => set({ basis: e.target.value })}>
            <option value="ctc">Yearly CTC (CTC_MONTHLY in formulas)</option>
            <option value="gross">Yearly gross pay (GROSS_MONTHLY)</option>
          </select>
        </div>
        <div>
          <label htmlFor="ss-from">Applies from</label>
          <input id="ss-from" type="date" value={v.validFrom} onChange={(e) => set({ validFrom: e.target.value })} />
        </div>
      </div>
      <h4>Components, worked out in the order their formulas need</h4>
      <ol className="structure-lines">
        {v.lines.map((l, i) => {
          const c = comp(l.code)
          return (
            <li key={i} className="structure-line">
              <div className="grid">
                <div>
                  <label htmlFor={`sl-code-${i}`}>Component</label>
                  <select id={`sl-code-${i}`} value={l.code} onChange={(e) => setLine(i, { code: e.target.value })}>
                    {usable.map((u) => (
                      <option key={u.code} value={u.code}>
                        {u.name} ({u.code})
                      </option>
                    ))}
                  </select>
                </div>
                <div className="form-actions">
                  <label className="check">
                    <input type="radio" name="balancing" checked={v.balancing === l.code} onChange={() => set({ balancing: l.code })} />
                    Balances what is left
                  </label>
                  <button type="button" className="quiet" onClick={() => set({ lines: v.lines.filter((_, j) => j !== i) })}>
                    Remove
                  </button>
                </div>
              </div>
              {c && !c.statutory && c.amountType !== 'input' && (
                <FormulaField
                  id={`sl-formula-${i}`}
                  label={`Formula in this structure (empty uses the component’s: ${c.formula || '—'})`}
                  value={l.formula}
                  onChange={(formula) => setLine(i, { formula })}
                  hint={false}
                />
              )}
              {c?.statutory && <p className="hint">Worked out by the PF rule on this structure’s wages.</p>}
            </li>
          )
        })}
      </ol>
      <button type="button" className="outline" onClick={() => set({ lines: [...v.lines, { code: usable[0]?.code, formula: '' }] })}>
        Add a component
      </button>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {st ? `Save from ${monthLabel(v.validFrom)}` : 'Add structure'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

// ── tables ────────────────────────────────────────────────────────────

function Tables({ s, onChanged }) {
  const [editing, setEditing] = useState(null)
  return (
    <>
      <div className="toolbar">
        <p className="hint">
          Your own values by a key, read in formulas with table(&quot;name&quot;, key): a meal card by designation, an allowance by department.
        </p>
        <span className="spacer" />
        {s.canEdit && !editing && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            Add a table
          </button>
        )}
      </div>
      {editing && (
        <TableForm
          table={editing === 'new' ? null : editing}
          onDone={() => {
            setEditing(null)
            onChanged()
          }}
          onCancel={() => setEditing(null)}
        />
      )}
      {s.tables.length === 0 && !editing && <Empty>No tables yet.</Empty>}
      {s.tables.map((t) => (
        <section key={t.id} className="panel">
          <div className="toolbar">
            <h3>
              <code>{t.name}</code> <span className="hint">by {t.keyLabel}</span>
            </h3>
            <span className="spacer" />
            {s.canEdit && (
              <button type="button" className="quiet" onClick={() => setEditing(t)}>
                Change
              </button>
            )}
          </div>
          <table>
            <tbody>
              {t.rows.map((r) => (
                <tr key={r.key}>
                  <td>{r.key}</td>
                  <td className="num">{Number(r.value).toLocaleString('en-IN')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
    </>
  )
}

function TableForm({ table: t, onDone, onCancel }) {
  const [v, setV] = useState({ name: t?.name || '', keyLabel: t?.keyLabel || 'Designation', rows: t ? t.rows.map((r) => ({ ...r })) : [{ key: '', value: '' }] })
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => payroll.saveTable(t?.id, { ...v, rows: v.rows.filter((r) => String(r.key).trim()) }))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="vt-name">Name</label>
          <input id="vt-name" value={v.name} disabled={Boolean(t)} onChange={(e) => setV({ ...v, name: e.target.value.toLowerCase() })} />
        </div>
        <div>
          <label htmlFor="vt-key">Looked up by</label>
          <input id="vt-key" value={v.keyLabel} onChange={(e) => setV({ ...v, keyLabel: e.target.value })} />
        </div>
      </div>
      {v.rows.map((r, i) => (
        <div key={i} className="grid">
          <div>
            <label htmlFor={`vt-k-${i}`}>{v.keyLabel || 'Key'}</label>
            <input id={`vt-k-${i}`} value={r.key} onChange={(e) => setV({ ...v, rows: v.rows.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)) })} />
          </div>
          <div>
            <label htmlFor={`vt-v-${i}`}>Value</label>
            <input
              id={`vt-v-${i}`}
              inputMode="decimal"
              value={r.value}
              onChange={(e) => setV({ ...v, rows: v.rows.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)) })}
            />
          </div>
        </div>
      ))}
      <button type="button" className="quiet" onClick={() => setV({ ...v, rows: [...v.rows, { key: '', value: '' }] })}>
        Add a row
      </button>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save table
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

// ── pay groups ────────────────────────────────────────────────────────

function Groups({ s, onChanged }) {
  const [showing, setShowing] = useState(null)
  const [editing, setEditing] = useState(null)
  return (
    <>
      <div className="toolbar">
        <p className="hint">People paid together, on one calendar. Most Organizations need one.</p>
        <span className="spacer" />
        {s.canEdit && !editing && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            Add a pay group
          </button>
        )}
      </div>
      {editing && (
        <GroupForm
          s={s}
          group={editing === 'new' ? null : editing}
          onDone={() => {
            setEditing(null)
            onChanged()
          }}
          onCancel={() => setEditing(null)}
        />
      )}
      {s.groups.map((g) => (
        <section key={g.id} className="panel" data-group={g.name}>
          <div className="toolbar">
            <h3>
              {g.name} <span className="hint">{g.members} people</span>
            </h3>
            <span className="spacer" />
            <button type="button" className="quiet" onClick={() => setShowing(showing === g.id ? null : g.id)}>
              {showing === g.id ? 'Hide people' : 'People'}
            </button>
            {s.canEdit && (
              <button type="button" className="quiet" onClick={() => setEditing(g)}>
                Change
              </button>
            )}
          </div>
          <p className="hint">
            Inputs close on the {g.cutoffDay}th · paid on the {g.payDay}
            {g.payDay === 1 ? 'st' : 'th'} · new people get the {g.defaultStructureName || 'no'} structure
          </p>
          {showing === g.id && <Members group={g} canEdit={s.canEdit} onChanged={onChanged} />}
        </section>
      ))}
    </>
  )
}

function Members({ group, canEdit, onChanged }) {
  const m = useLoad(() => payroll.members(group.id))
  const { busy, error, run } = useAction()
  if (m.loading) return <p className="muted">Loading…</p>
  if (m.error) return <ErrorNote>{m.error}</ErrorNote>
  return (
    <div data-testid="members">
      {m.data.unassigned.length > 0 && (
        <p className="notice">
          {m.data.unassigned.length} employee{m.data.unassigned.length === 1 ? ' is' : 's are'} in no pay group: {m.data.unassigned.map((u) => u.name).join(', ')}.{' '}
          {canEdit && (
            <button
              type="button"
              className="text-link"
              disabled={busy}
              onClick={async () => {
                if (await run(() => payroll.addMembers(group.id, [], null))) {
                  m.reload()
                  onChanged()
                }
              }}
            >
              Add them all to {group.name}
            </button>
          )}
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      {m.data.members.length === 0 ? (
        <Empty>Nobody in this group yet.</Empty>
      ) : (
        <ul className="plain-list">
          {m.data.members.map((p) => (
            <li key={p.id}>
              {p.name} <span className="hint">{p.employeeCode} · since {formatDay(p.validFrom)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function GroupForm({ s, group: g, onDone, onCancel }) {
  const [v, setV] = useState({ name: g?.name || '', cutoffDay: g?.cutoffDay || 25, payDay: g?.payDay || 30, defaultStructureId: g?.defaultStructureId || '' })
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => payroll.saveGroup(g?.id, { ...v, cutoffDay: wholeNumber(v.cutoffDay, 'The cut-off day'), payDay: wholeNumber(v.payDay, 'The pay day') }))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="pg-name">Name</label>
          <input id="pg-name" value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="pg-cutoff">Inputs close on day</label>
          <input id="pg-cutoff" inputMode="numeric" value={v.cutoffDay} onChange={(e) => setV({ ...v, cutoffDay: e.target.value })} />
        </div>
        <div>
          <label htmlFor="pg-pay">Pay day</label>
          <input id="pg-pay" inputMode="numeric" value={v.payDay} onChange={(e) => setV({ ...v, payDay: e.target.value })} />
        </div>
        <div>
          <label htmlFor="pg-structure">Structure for new people</label>
          <select id="pg-structure" value={v.defaultStructureId} onChange={(e) => setV({ ...v, defaultStructureId: e.target.value })}>
            <option value="">None</option>
            {s.structures.map((st) => (
              <option key={st.id} value={st.id}>
                {st.name}
              </option>
            ))}
          </select>
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save pay group
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

// ── statutory rules ───────────────────────────────────────────────────

function ruleSummary(r) {
  const d = typeof r.data === 'string' ? JSON.parse(r.data) : r.data
  switch (r.statute) {
    case 'pf':
      return `Employee ${d.employeeRate}%, employer ${d.employerRate}% (pension ${d.epsRate}%), on wages up to ₹${Number(d.ceiling).toLocaleString('en-IN')}`
    case 'esi':
      return `Employee ${d.employeeRate}%, employer ${d.employerRate}%, for ESI wages up to ₹${Number(d.threshold).toLocaleString('en-IN')} (₹${Number(d.thresholdDisability).toLocaleString('en-IN')} with a disability)`
    case 'pt':
      return (d.men || [])
        .map((s) => {
          const from = Math.floor(Number(s.from))
          const where = s.to ? `up to ₹${Number(s.to).toLocaleString('en-IN')}` : `${from === Number(s.from) ? 'from' : 'above'} ₹${from.toLocaleString('en-IN')}`
          return `${where}: ₹${s.monthly}${s.february ? ` (₹${s.february} in February)` : ''}`
        })
        .join('; ')
        .concat(d.women ? '; women have their own slabs' : '')
    case 'lwf':
      return `Employee ₹${d.employee}, employer ₹${d.employer}, in ${d.months.map((m) => new Date(2000, m - 1, 1).toLocaleString('en-IN', { month: 'long' })).join(' and ')}`
    case 'income_tax':
      return `New regime: standard deduction ₹${Number(d.new.standardDeduction).toLocaleString('en-IN')}, no tax up to ₹${Number(d.new.rebateLimit).toLocaleString('en-IN')} after rebate. Old regime: standard deduction ₹${Number(d.old.standardDeduction).toLocaleString('en-IN')}. Cess ${d.cess}%.`
    default:
      return ''
  }
}

function Rules({ s }) {
  return (
    <>
      <p className="hint">
        The law, as data we maintain: the same for every Organization, dated, and resolved for the month being paid. Your choices within it are under
        Employer.
      </p>
      <div className="table-wrap">
        <table className="rules">
          <thead>
            <tr>
              <th>Rule</th>
              <th>Where</th>
              <th>In force</th>
              <th>What it says</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {s.rules.map((r) => (
              <tr key={r.id} data-rule={`${r.statute}-${r.jurisdiction}`}>
                <td>{STATUTES[r.statute]}</td>
                <td>{STATE_NAMES[r.jurisdiction] || r.jurisdiction}</td>
                <td>from {formatDay(r.validFrom)}</td>
                <td>
                  {ruleSummary(r)}
                  <div className="hint">{r.source}</div>
                </td>
                <td>
                  {r.status === 'draft' ? (
                    <Badge tone="warn">Awaiting sign-off</Badge>
                  ) : (
                    <Badge tone="good">
                      Signed off by {r.signedOffBy}, {formatDay(r.signedOffOn)}
                    </Badge>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
