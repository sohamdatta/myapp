import { useState } from 'react'
import { tds } from '../api.js'
import { useConfirm, useToast } from '../feedbackContext.js'
import { formatDay, localToday, numeric, useAction, useLoad } from '../hooks.js'
import { Badge, Chevron, Empty, ErrorNote } from '../ui.jsx'
import { CHALLAN_STATES, DEPOSIT_STATES, canDo, monthLabel, rupees } from './payrollText.js'

const BLANK = { deductorType: 'company', minorHead: '200', bsrCode: '', serial: '', depositedOn: '', tax: '', surcharge: '', cess: '', interest: '', fee: '' }
const sum = (challans, keys) => challans.reduce((t, c) => t + keys.reduce((s, k) => s + Number(c[k] || 0), 0), 0)

function RecordForm({ month, runs, onDone, onCancel }) {
  const suggested = Math.max(0, Number(month.deducted) - sum(month.challans, ['tax', 'surcharge', 'cess']))
  const [form, setForm] = useState({ ...BLANK, tax: suggested ? String(suggested) : '' })
  const [picked, setPicked] = useState(runs.map((r) => r.id))
  const [problem, setProblem] = useState('')
  const { busy, error, run } = useAction()
  const set = (key) => (e) => setForm({ ...form, [key]: e.target.value })
  const ym = month.month.slice(0, 7)

  async function save(e) {
    e.preventDefault()
    setProblem('')
    try {
      if (picked.length === 0) throw new Error('Choose the run or runs this deposit is for')
      if (!/^[0-9]{7}$/.test(form.bsrCode)) throw new Error('The BSR code has 7 digits')
      if (!/^[0-9]{5}$/.test(form.serial)) throw new Error('The challan serial number has 5 digits')
      if (!form.depositedOn) throw new Error('Give the date it was deposited')
      const body = {
        month: ym,
        deductorType: form.deductorType,
        minorHead: form.minorHead,
        bsrCode: form.bsrCode,
        serial: form.serial,
        depositedOn: form.depositedOn,
        tax: numeric(form.tax, 'Tax') ?? 0,
        surcharge: numeric(form.surcharge, 'Surcharge') ?? 0,
        cess: numeric(form.cess, 'Cess') ?? 0,
        interest: numeric(form.interest, 'Interest') ?? 0,
        fee: numeric(form.fee, 'Fee') ?? 0,
        runIds: picked,
      }
      if (await run(() => tds.record(body))) onDone()
    } catch (err) {
      setProblem(err.message)
    }
  }

  return (
    <form className="inline-form" onSubmit={save} aria-label={`Record deposit for ${monthLabel(month.month)}`}>
      <fieldset>
        <legend>Runs this deposit covers</legend>
        {runs.length === 0 && <p className="hint">No approved run for this month without a deposit.</p>}
        {runs.map((r) => (
          <label key={r.id}>
            <input
              type="checkbox"
              checked={picked.includes(r.id)}
              onChange={() => setPicked(picked.includes(r.id) ? picked.filter((x) => x !== r.id) : [...picked, r.id])}
            />{' '}
            {r.groupName} · TDS {rupees(r.tds, true)}
          </label>
        ))}
      </fieldset>
      <label>
        Deducted by
        <select value={form.deductorType} onChange={set('deductorType')}>
          <option value="company">A company</option>
          <option value="other">Others</option>
        </select>
      </label>
      <label>
        Minor head
        <select value={form.minorHead} onChange={set('minorHead')}>
          <option value="200">200: tax deducted</option>
          <option value="400">400: regular assessment</option>
        </select>
      </label>
      <label>
        BSR code
        <input inputMode="numeric" maxLength={7} value={form.bsrCode} onChange={set('bsrCode')} />
      </label>
      <label>
        Challan serial number
        <input inputMode="numeric" maxLength={5} value={form.serial} onChange={set('serial')} />
      </label>
      <label>
        Deposited on
        <input type="date" min={month.month} max={localToday()} value={form.depositedOn} onChange={set('depositedOn')} />
      </label>
      {[
        ['tax', 'Tax'],
        ['surcharge', 'Surcharge'],
        ['cess', 'Cess'],
        ['interest', 'Interest'],
        ['fee', 'Fee'],
      ].map(([key, label]) => (
        <label key={key}>
          {label}
          <input inputMode="decimal" value={form[key]} onChange={set(key)} />
        </label>
      ))}
      <ErrorNote>{problem || error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" disabled={busy}>
          Record deposit
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/** Payroll → Tax filing → Deposits: tax deducted each month against the challans it was deposited with. */
export default function TdsDeposits({ taxYear, me }) {
  const load = useLoad(() => tds.year(taxYear))
  const [open, setOpen] = useState(null)
  const [recording, setRecording] = useState(null)
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()
  const canPrepare = canDo(me, 'payroll.prepare')
  const canApprove = canDo(me, 'payroll.approve')

  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  const months = load.data.months
  const runsOf = (m) => m.runs.filter((r) => !r.challanId)

  async function approve(c) {
    if (!(await confirm({ title: 'Approve this deposit?', body: `BSR ${c.bsrCode}, serial ${c.serial}, ${rupees(c.total, true)}.`, confirmLabel: 'Approve' }))) return
    if (await run(() => tds.approve(c.id))) {
      toast('Deposit approved')
      load.reload()
    }
  }

  async function remove(c) {
    if (!(await confirm({ title: 'Remove this recorded deposit?', body: 'Do this to correct a mistake; record it again afterwards.', confirmLabel: 'Remove', danger: true }))) return
    if (await run(() => tds.remove(c.id))) {
      toast('Removed')
      load.reload()
    }
  }

  return (
    <div data-testid="tds-deposits">
      <p className="hint">Tax deducted from salary is deposited by the 7th of the next month. Record each deposit; a second person approves it.</p>
      <ErrorNote>{error}</ErrorNote>
      {months.every((m) => Number(m.deducted) === 0 && m.challans.length === 0) ? (
        <Empty>No tax deducted in {taxYear} yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Month</th>
                <th className="num">Tax deducted</th>
                <th className="num">Deposited</th>
                <th>Status</th>
                <th>Due</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {months.map((m) => {
                const ym = m.month.slice(0, 7)
                const isOpen = open === ym
                return [
                  <tr key={ym} data-month={ym}>
                    <td>
                      <button type="button" className="text-link" aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : ym)}>
                        <Chevron open={isOpen} /> {monthLabel(m.month)}
                      </button>
                    </td>
                    <td className="num">{rupees(m.deducted, true)}</td>
                    <td className="num">{rupees(m.deposited, true)}</td>
                    <td>
                      <Badge tone={DEPOSIT_STATES[m.status]?.[0]}>{DEPOSIT_STATES[m.status]?.[1] ?? m.status}</Badge>{' '}
                      {m.overdue && <Badge tone="danger">Overdue</Badge>}
                    </td>
                    <td>{m.dueOn ? formatDay(m.dueOn) : 'Not known'}</td>
                    <td>
                      {canPrepare && Number(m.deducted) > 0 && m.status !== 'deposited' && (
                        <button type="button" className="outline" disabled={busy} onClick={() => { setOpen(ym); setRecording(ym) }}>
                          Record deposit…
                        </button>
                      )}
                    </td>
                  </tr>,
                  isOpen && (
                    <tr key={`${ym}-detail`}>
                      <td colSpan={6}>
                        {m.challans.length === 0 && recording !== ym && <p className="hint">No deposit recorded for this month.</p>}
                        {m.challans.map((c) => (
                          <div key={c.id} className="doc-row" data-challan={c.serial}>
                            <div>
                              <strong>BSR {c.bsrCode} · serial {c.serial}</strong>{' '}
                              <Badge tone={CHALLAN_STATES[c.status]?.[0]}>{CHALLAN_STATES[c.status]?.[1] ?? c.status}</Badge>
                              <br />
                              <span className="hint">
                                Deposited {formatDay(c.depositedOn)}
                                {c.daysLate > 0 ? ` · ${c.daysLate} days late` : ''} · tax {rupees(c.tax, true)} · surcharge {rupees(c.surcharge, true)} · cess{' '}
                                {rupees(c.cess, true)} · interest {rupees(c.interest, true)} · fee {rupees(c.fee, true)} · total {rupees(c.total, true)}
                              </span>
                            </div>
                            {c.status === 'recorded' && (
                              <div className="form-actions">
                                {canApprove && !c.recordedByMe && (
                                  <button type="button" disabled={busy} onClick={() => approve(c)}>
                                    Approve
                                  </button>
                                )}
                                {canApprove && c.recordedByMe && <span className="hint">Another person approves what you recorded.</span>}
                                {canPrepare && (
                                  <button type="button" className="quiet" disabled={busy} onClick={() => remove(c)}>
                                    Remove
                                  </button>
                                )}
                              </div>
                            )}
                          </div>
                        ))}
                        {recording === ym && (
                          <RecordForm
                            month={m}
                            runs={runsOf(m)}
                            onCancel={() => setRecording(null)}
                            onDone={() => {
                              setRecording(null)
                              toast('Deposit recorded')
                              load.reload()
                                            }}
                          />
                        )}
                      </td>
                    </tr>
                  ),
                ]
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
