import { useState } from 'react'
import { downloadDocument as download, exits } from '../api.js'
import { formatDay, localToday, useAction } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'

const rupees = (n) =>
  n === null || n === undefined || n === ''
    ? '—'
    : `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`

const DIRECTIONS = { earning: 'Paid to them', deduction: 'Deducted', demand: 'Owed by them, asked for separately' }
const MY_DIRECTIONS = { earning: 'Paid to you', deduction: 'Deducted', demand: 'Owed by you, asked for separately' }
const STATES = {
  none: ['neutral', 'Not prepared'],
  draft: ['warn', 'Draft'],
  approved: ['warn', 'Approved, being paid'],
  paid: ['good', 'Paid'],
}

const qty = (l) => (l.quantity !== null && l.quantity !== undefined && l.unit ? `${Number(l.quantity)} ${l.unit}` : '')


/**
 * The full and final settlement for HR: work out the lines, type the amounts, have a second person
 * approve it, then record each payment with its bank reference.
 */
export function Settlement({ exit: x, onChanged }) {
  const s = x.settlement
  const [adding, setAdding] = useState(false)
  const [paying, setPaying] = useState(null)
  const { busy, error, run } = useAction()
  if (!s) return null
  const [tone, words] = STATES[s.state] || ['neutral', s.state]
  const act = async (work) => {
    if (await run(work)) onChanged()
  }
  const show = s.state !== 'none' || s.canPrepare
  if (!show) return null

  return (
    <section className="panel settlement" data-testid="settlement">
      <div className="toolbar">
        <h3>
          Full and final settlement <Badge tone={tone}>{words}</Badge>
        </h3>
        <span className="spacer" />
        {s.canPrepare && (
          <button type="button" className={s.state === 'none' ? 'primary' : 'outline'} disabled={busy} onClick={() => act(() => exits.prepareSettlement(x.id))}>
            {s.state === 'none' ? 'Work out the settlement' : 'Work it out again'}
          </button>
        )}
        {s.statementDocumentId && (
          <button type="button" className="outline" onClick={() => run(() => download(s.statementDocumentId, 'Full and final settlement.pdf'))}>
            Statement (PDF)
          </button>
        )}
      </div>
      {s.state === 'none' && (
        <p className="hint">
          The app works out the days of salary, leave to encash, notice and gratuity from the exit, attendance and leave, and adds the claims and
          recoveries from clearance. You type the amounts.
        </p>
      )}
      {s.gratuity && s.state !== 'none' && (
        <p className="hint" data-testid="gratuity">
          Gratuity: {s.gratuity.eligible ? 'due' : 'not due'}. {s.gratuity.reason}
          {s.gratuity.eligible && s.gratuity.service ? ` (service ${s.gratuity.service})` : ''}.
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      {s.lines.length > 0 && (
        <div className="table-wrap">
          <table className="settlement-lines">
            <thead>
              <tr>
                <th>Line</th>
                <th>Quantity</th>
                <th>Amount</th>
                <th>Due by</th>
                <th>{s.state === 'draft' ? '' : 'Paid'}</th>
              </tr>
            </thead>
            <tbody>
              {['earning', 'deduction', 'demand'].map((d) =>
                s.lines
                  .filter((l) => l.direction === d)
                  .map((l, i) =>
                    s.canEdit ? (
                      <EditLine key={l.id} line={l} heading={i === 0 ? DIRECTIONS[d] : null} exitId={x.id} onChanged={onChanged} />
                    ) : (
                      <PaidLine
                        key={l.id}
                        line={l}
                        heading={i === 0 ? DIRECTIONS[d] : null}
                        canPay={s.canPay}
                        paying={paying === l.id}
                        onPay={() => setPaying(l.id)}
                        onCancel={() => setPaying(null)}
                        onPaid={() => {
                          setPaying(null)
                          onChanged()
                        }}
                        exitId={x.id}
                      />
                    ),
                  ),
              )}
            </tbody>
          </table>
        </div>
      )}
      {s.state !== 'none' && <Totals s={s} />}
      {s.canEdit && !adding && (
        <button type="button" className="quiet" onClick={() => setAdding(true)}>
          Add a line
        </button>
      )}
      {adding && (
        <AddLine
          exitId={x.id}
          onDone={() => {
            setAdding(false)
            onChanged()
          }}
          onCancel={() => setAdding(false)}
        />
      )}
      {s.state === 'draft' && (
        <div className="form-actions">
          {s.canApprove ? (
            <button type="button" className="primary" disabled={busy} onClick={() => act(() => exits.approveSettlement(x.id))}>
              Approve and send the statement
            </button>
          ) : (
            <p className="hint">Someone other than the person who prepared it approves it; then the employee gets the statement.</p>
          )}
        </div>
      )}
      {(s.preparedByEmail || s.approvedByEmail) && (
        <p className="hint">
          {s.preparedByEmail && <>Prepared by {s.preparedByEmail}. </>}
          {s.approvedByEmail && (
            <>
              Approved by {s.approvedByEmail}
              {s.approvedAt ? ` on ${formatDay(s.approvedAt.slice(0, 10))}` : ''}.
            </>
          )}
        </p>
      )}
    </section>
  )
}

function Totals({ s, mine }) {
  return (
    <dl className="facts totals">
      <dt>{mine ? 'Paid to you' : 'Paid to them'}</dt>
      <dd>{rupees(s.earnings)}</dd>
      {Number(s.deductions) > 0 && (
        <>
          <dt>Deducted</dt>
          <dd>{rupees(s.deductions)}</dd>
        </>
      )}
      <dt>Net</dt>
      <dd data-testid="settlement-net">
        <strong>{rupees(s.net)}</strong>
      </dd>
      {Number(s.demands) > 0 && (
        <>
          <dt>{mine ? 'Owed by you' : 'Owed by them'}</dt>
          <dd>{rupees(s.demands)}</dd>
        </>
      )}
    </dl>
  )
}

function Heading({ text }) {
  return text ? (
    <tr className="group">
      <th colSpan={5}>{text}</th>
    </tr>
  ) : null
}

function EditLine({ line: l, heading, exitId, onChanged }) {
  const initial = {
    amount: l.amount ?? '',
    quantity: l.quantity ?? '',
    dueDate: l.dueDate ?? '',
    note: l.note ?? '',
  }
  const [form, setForm] = useState(initial)
  const { busy, error, run } = useAction()
  const changed = Object.keys(initial).some((k) => String(initial[k]) !== String(form[k]))
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  const num = (v) => (v === '' || v === null ? null : Number(v))

  async function save() {
    const body = {
      quantity: num(form.quantity),
      unit: l.unit,
      amount: num(form.amount),
      dueDate: form.dueDate || null,
      note: form.note || null,
    }
    if (await run(() => exits.changeSettlementLine(exitId, l.id, body))) onChanged()
  }

  async function remove() {
    if (await run(() => exits.removeSettlementLine(exitId, l.id))) onChanged()
  }

  return (
    <>
      <Heading text={heading} />
      <tr data-testid={`line-${l.code}`}>
        <td>
          {l.label}
          <input
            className="note"
            aria-label={`Note on ${l.label}`}
            placeholder="Note"
            value={form.note}
            onChange={(e) => set({ note: e.target.value })}
          />
          {error && <ErrorNote>{error}</ErrorNote>}
        </td>
        <td>
          {l.unit ? (
            <span className="qty">
              <input
                type="number"
                min="0"
                step="0.5"
                aria-label={`Quantity of ${l.label}`}
                value={form.quantity}
                onChange={(e) => set({ quantity: e.target.value })}
              />{' '}
              {l.unit}
            </span>
          ) : (
            ''
          )}
        </td>
        <td>
          <input
            type="number"
            min="0"
            step="0.01"
            aria-label={`Amount of ${l.label}`}
            placeholder="₹"
            value={form.amount}
            onChange={(e) => set({ amount: e.target.value })}
          />
        </td>
        <td>
          <input type="date" aria-label={`Due date of ${l.label}`} value={form.dueDate} onChange={(e) => set({ dueDate: e.target.value })} />
        </td>
        <td className="actions">
          {changed && (
            <button type="button" className="secondary" disabled={busy} onClick={save}>
              Save
            </button>
          )}
          {!l.exitTaskId && !['salary', 'tax'].includes(l.code) && (
            <button type="button" className="quiet danger" disabled={busy} onClick={remove} aria-label={`Remove ${l.label}`}>
              Remove
            </button>
          )}
        </td>
      </tr>
    </>
  )
}

function PaidLine({ line: l, heading, canPay, paying, onPay, onCancel, onPaid, exitId }) {
  const [paidOn, setPaidOn] = useState(localToday())
  const [reference, setReference] = useState('')
  const { busy, error, run } = useAction()
  const payable = Number(l.amount) > 0
  return (
    <>
      <Heading text={heading} />
      <tr data-testid={`line-${l.code}`}>
        <td>
          {l.label}
          {l.note && <div className="hint">{l.note}</div>}
        </td>
        <td>{qty(l)}</td>
        <td>{rupees(l.amount)}</td>
        <td>{l.dueDate ? formatDay(l.dueDate) : '—'}</td>
        <td>
          {l.paidOn ? (
            <span>
              {formatDay(l.paidOn)} · {l.paidReference}
            </span>
          ) : canPay && payable && !paying ? (
            <button type="button" className="outline" onClick={onPay}>
              {l.direction === 'demand' ? 'Mark received…' : 'Mark paid…'}
            </button>
          ) : null}
        </td>
      </tr>
      {paying && (
        <tr>
          <td colSpan={5}>
            <form
              className="inline-form"
              onSubmit={async (e) => {
                e.preventDefault()
                if (await run(() => exits.paySettlementLine(exitId, l.id, { paidOn, reference }))) onPaid()
              }}
            >
              <div>
                <label htmlFor={`paid-on-${l.id}`}>{l.direction === 'demand' ? 'Received on' : 'Paid on'}</label>
                <input id={`paid-on-${l.id}`} type="date" max={localToday()} value={paidOn} onChange={(e) => setPaidOn(e.target.value)} />
              </div>
              <div>
                <label htmlFor={`paid-ref-${l.id}`}>Bank reference (UTR)</label>
                <input id={`paid-ref-${l.id}`} value={reference} maxLength={80} onChange={(e) => setReference(e.target.value)} />
              </div>
              <div className="form-actions">
                <button type="submit" className="primary" disabled={busy}>
                  Record
                </button>
                <button type="button" className="quiet" onClick={onCancel}>
                  Cancel
                </button>
              </div>
              <ErrorNote>{error}</ErrorNote>
            </form>
          </td>
        </tr>
      )}
    </>
  )
}

function AddLine({ exitId, onDone, onCancel }) {
  const [form, setForm] = useState({ code: 'other', label: '', direction: 'earning', amount: '', dueDate: '' })
  const { busy, error, run } = useAction()
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        const body = { ...form, amount: form.amount === '' ? null : Number(form.amount), dueDate: form.dueDate || null }
        if (await run(() => exits.addSettlementLine(exitId, body))) onDone()
      }}
      noValidate
    >
      <div className="grid">
        <div>
          <label htmlFor="line-label">What it is for</label>
          <input id="line-label" value={form.label} maxLength={120} onChange={(e) => set({ label: e.target.value })} />
        </div>
        <div>
          <label htmlFor="line-kind">Kind</label>
          <select id="line-kind" value={form.code} onChange={(e) => set({ code: e.target.value })}>
            <option value="other">Other</option>
            <option value="bonus">Bonus</option>
            <option value="reimbursement">Reimbursement</option>
            <option value="recovery">Recovery</option>
          </select>
        </div>
        <div>
          <label htmlFor="line-direction">Direction</label>
          <select id="line-direction" value={form.direction} onChange={(e) => set({ direction: e.target.value })}>
            {Object.entries(DIRECTIONS).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="line-amount">Amount (₹)</label>
          <input id="line-amount" type="number" min="0" step="0.01" value={form.amount} onChange={(e) => set({ amount: e.target.value })} />
        </div>
        <div>
          <label htmlFor="line-due">Due by</label>
          <input id="line-due" type="date" value={form.dueDate} onChange={(e) => set({ dueDate: e.target.value })} />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Add
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/** The employee's own statement, once it is approved. */
export function MySettlement({ settlement: s }) {
  const { error, run } = useAction()
  if (!s || !['approved', 'paid'].includes(s.state)) return null
  return (
    <section className="panel settlement" data-testid="my-settlement">
      <div className="toolbar">
        <h3>
          Your full and final settlement <Badge tone={STATES[s.state][0]}>{STATES[s.state][1]}</Badge>
        </h3>
        <span className="spacer" />
        {s.statementDocumentId && (
          <button type="button" className="outline" onClick={() => run(() => download(s.statementDocumentId, 'Full and final settlement.pdf'))}>
            Download the statement
          </button>
        )}
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="table-wrap">
        <table className="settlement-lines">
          <tbody>
            {['earning', 'deduction', 'demand'].map((d) =>
              s.lines
                .filter((l) => l.direction === d && Number(l.amount) > 0)
                .map((l, i) => (
                  <MyLine key={l.id} line={l} heading={i === 0 ? MY_DIRECTIONS[d] : null} />
                )),
            )}
          </tbody>
        </table>
      </div>
      <Totals s={s} mine />
      <p className="hint">Wages are due within two working days of your last day, and gratuity within thirty. Write to HR if anything looks wrong.</p>
    </section>
  )
}

function MyLine({ line: l, heading }) {
  return (
    <>
      <Heading text={heading} />
      <tr>
        <td>
          {l.label} {qty(l) && <span className="hint">({qty(l)})</span>}
        </td>
        <td>{rupees(l.amount)}</td>
        <td>{l.paidOn ? `Paid ${formatDay(l.paidOn)}` : l.dueDate ? `Due by ${formatDay(l.dueDate)}` : ''}</td>
      </tr>
    </>
  )
}
