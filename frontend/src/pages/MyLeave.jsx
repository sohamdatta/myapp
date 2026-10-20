import { useEffect, useState } from 'react'
import { leave } from '../api.js'
import { formatDay, formatDays, formatRange, leaveBody, newLeave, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote, StatusBadge } from '../ui.jsx'
import { useToast } from '../feedbackContext.js'

const ENTRY_KINDS = {
  accrual: 'Credited',
  taken: 'Leave taken',
  returned: 'Leave returned',
  adjustment: 'Adjusted by HR',
  lapse: 'Lapsed at year end',
  encash: 'Encashed at year end',
  comp_off_earned: 'Comp-off earned',
  comp_off_expired: 'Comp-off expired',
}

/** One balance per leave type. Unpaid leave has no balance to show. */
export function BalanceTiles({ balances }) {
  const shown = balances.filter((b) => b.active || Number(b.balance) !== 0)
  if (shown.length === 0) return <Empty>No leave types are set up yet. HR adds them under Leave → Setup.</Empty>
  return (
    <div className="tiles">
      {shown.map((b) => (
        <div key={b.leaveTypeId} className="tile">
          <div className="tile-label">{b.name}</div>
          <div className="tile-value">{b.paid ? Number(b.available) : '—'}</div>
          <div className="hint">
            {!b.paid
              ? 'Unpaid, no balance'
              : Number(b.pending) > 0
                ? `${formatDays(b.pending)} awaiting approval`
                : b.accrual === 'none'
                  ? 'days available'
                  : `of ${Number(b.yearlyQuota)} a year`}
          </div>
        </div>
      ))}
    </div>
  )
}

/** The fields of a leave request, shared by the user's own form and the one HR fills in for someone. */
export function LeaveFields({ idPrefix, types, value, onChange }) {
  const set = (patch) => onChange({ ...value, ...patch })
  const single = !value.toDate || value.toDate === value.fromDate
  return (
    <>
      <div className="grid">
        <div>
          <label htmlFor={`${idPrefix}-type`}>Leave type</label>
          <select id={`${idPrefix}-type`} value={value.leaveTypeId} onChange={(e) => set({ leaveTypeId: e.target.value })}>
            <option value="">Choose…</option>
            {types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`${idPrefix}-from`}>From</label>
          <input
            id={`${idPrefix}-from`}
            type="date"
            value={value.fromDate}
            onChange={(e) => set({ fromDate: e.target.value, toDate: value.toDate < e.target.value ? e.target.value : value.toDate })}
          />
        </div>
        <div>
          <label htmlFor={`${idPrefix}-to`}>To</label>
          <input
            id={`${idPrefix}-to`}
            type="date"
            value={value.toDate}
            min={value.fromDate}
            onChange={(e) => set({ toDate: e.target.value, half: e.target.value === value.fromDate ? value.half : '' })}
          />
        </div>
        <div>
          <label htmlFor={`${idPrefix}-half`}>How much of the day</label>
          <select id={`${idPrefix}-half`} value={single ? value.half : ''} disabled={!single} onChange={(e) => set({ half: e.target.value })}>
            <option value="">Whole day</option>
            <option value="first">First half</option>
            <option value="second">Second half</option>
          </select>
        </div>
      </div>
      <label htmlFor={`${idPrefix}-reason`}>Reason</label>
      <input id={`${idPrefix}-reason`} value={value.reason} onChange={(e) => set({ reason: e.target.value })} />
    </>
  )
}

function ApplyForm({ types, onApplied, onCancel }) {
  const [value, setValue] = useState(newLeave)
  const [preview, setPreview] = useState(null)
  const { busy, error, run } = useAction()
  const toast = useToast()

  // The server counts the days, so the form shows exactly what will be deducted.
  const { leaveTypeId, fromDate, toDate, half } = value
  useEffect(() => {
    if (!leaveTypeId || !fromDate) return undefined
    let cancelled = false
    const timer = setTimeout(() => {
      leave
        .preview(leaveBody({ leaveTypeId, fromDate, toDate, half }))
        .then((p) => !cancelled && setPreview(p))
        .catch((err) => !cancelled && setPreview({ error: err.message }))
    }, 250)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [leaveTypeId, fromDate, toDate, half])

  async function submit(event) {
    event.preventDefault()
    const created = await run(() => leave.apply(leaveBody(value)))
    if (created) {
      toast(created.status === 'approved' ? 'Leave applied and approved' : 'Leave request sent for approval')
      onApplied()
    }
  }

  const shown = leaveTypeId && fromDate ? preview : null
  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>Apply for leave</h2>
      <LeaveFields idPrefix="apply" types={types} value={value} onChange={setValue} />
      {shown && (
        <p className="hint" role="status">
          {shown.error
            ? shown.error
            : Number(shown.days) === 0
              ? 'These dates are all days off, so no leave is needed.'
              : !shown.covered
                ? `${shown.message}.`
                : `This takes ${formatDays(shown.days)}` +
                  (shown.available !== null && shown.available !== undefined ? ` of the ${formatDays(shown.available)} you have available.` : '.') +
                  (Number(shown.unpaidDays) > 0 ? ` ${formatDays(shown.unpaidDays)} will be unpaid.` : '')}
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy || !leaveTypeId}>
          {busy ? 'Sending…' : 'Send for approval'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function MyRequests({ requests, onChanged }) {
  const { busy, error, run } = useAction()
  const toast = useToast()

  async function cancel(id) {
    const done = await run(async () => {
      await leave.cancel(id)
      return true
    })
    if (done) {
      toast('Leave request cancelled')
      onChanged()
    }
  }

  if (requests.length === 0) return <Empty>You have not applied for any leave.</Empty>
  return (
    <>
      <ErrorNote>{error}</ErrorNote>
      <div className="table-wrap">
        <table className="stack">
          <thead>
            <tr>
              <th>Dates</th>
              <th>Leave</th>
              <th>Reason</th>
              <th>Status</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {requests.map((r) => (
              <tr key={r.id}>
                <td>{formatRange(r.fromDate, r.toDate, r.half)}</td>
                <td>
                  {r.leaveType}
                  <div className="hint">
                    {formatDays(r.days)}
                    {Number(r.unpaidDays) > 0 && (Number(r.unpaidDays) === Number(r.days) ? ' unpaid' : `, ${formatDays(r.unpaidDays)} unpaid`)}
                    {r.appliedByHr && ' · recorded by HR'}
                  </div>
                </td>
                <td>{r.reason}</td>
                <td>
                  <StatusBadge status={r.status} />
                  {r.decisionNote && <div className="hint">Note: “{r.decisionNote}”</div>}
                </td>
                <td className="actions">
                  {r.canCancel && (
                    <button type="button" className="quiet danger" disabled={busy} onClick={() => cancel(r.id)}>
                      Cancel
                    </button>
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

/** Every entry behind a balance, newest first. The balance is their sum. */
/** Why a credit was what it was: the year's quota, a part of it for a part year, or a month's share. */
function accrualWhy(entry, type) {
  if (!type) return null
  if (type.accrual === 'monthly') return `${Number(type.yearlyQuota) / 12} a month, of ${Number(type.yearlyQuota)} a year`
  if (type.accrual === 'yearly') {
    return Number(entry.days) < Number(type.yearlyQuota)
      ? `Part of the ${Number(type.yearlyQuota)} a year, for the part of the year after joining`
      : `The ${Number(type.yearlyQuota)} days of the year`
  }
  return null
}

export function Ledger({ load, type }) {
  const entries = useLoad(load)
  if (entries.loading) return <p className="muted">Loading…</p>
  if (entries.error) return <ErrorNote>{entries.error}</ErrorNote>
  if (entries.data.length === 0) return <Empty>Nothing has been credited or taken yet.</Empty>
  return (
    <div className="table-wrap">
      <table className="stack">
        <thead>
          <tr>
            <th>Date</th>
            <th>What happened</th>
            <th>Days</th>
          </tr>
        </thead>
        <tbody>
          {entries.data.map((e) => (
            <tr key={e.id}>
              <td>{formatDay(e.onDate)}</td>
              <td>
                {ENTRY_KINDS[e.kind] || e.kind}
                {e.kind === 'accrual' && accrualWhy(e, type) && <div className="hint">{accrualWhy(e, type)}</div>}
                {(e.reason || e.fromDate || e.expiresOn) && (
                  <div className="hint">
                    {e.fromDate && formatRange(e.fromDate, e.toDate)}
                    {e.reason && (e.fromDate ? ` · ${e.reason}` : e.reason)}
                    {e.kind === 'comp_off_earned' && e.expiresOn && ` · use by ${formatDay(e.expiresOn)}`}
                    {e.enteredBy && ` · by ${e.enteredBy}`}
                  </div>
                )}
              </td>
              <td>
                <Badge tone={Number(e.days) > 0 ? 'good' : 'neutral'}>
                  {Number(e.days) > 0 ? '+' : ''}
                  {Number(e.days)}
                </Badge>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function History({ balances }) {
  const paid = balances.filter((b) => b.paid)
  const [typeId, setTypeId] = useState(paid[0]?.leaveTypeId)
  if (paid.length === 0) return null
  return (
    <>
      <div className="section-head">
        <h2>How my balance was reached</h2>
      </div>
      <div className="filters">
        {paid.map((b) => (
          <button
            key={b.leaveTypeId}
            type="button"
            className={typeId === b.leaveTypeId ? 'chip chip-current' : 'chip'}
            onClick={() => setTypeId(b.leaveTypeId)}
          >
            {b.name}
          </button>
        ))}
      </div>
      <Ledger key={typeId} load={() => leave.myLedger(typeId)} type={paid.find((b) => b.leaveTypeId === typeId)} />
    </>
  )
}

/** The signed-in user's own leave: balances, applying, requests, and the entries behind each balance. */
export default function MyLeave({ me }) {
  const balances = useLoad(leave.myBalances)
  const requests = useLoad(leave.myRequests)
  const [applying, setApplying] = useState(false)
  const [version, setVersion] = useState(0)
  const canApply = me.permissions.includes('leave.apply')

  if (balances.loading || requests.loading) return <p className="muted">Loading…</p>
  if (balances.error || requests.error) return <ErrorNote>{balances.error || requests.error}</ErrorNote>
  const types = balances.data.filter((b) => b.active).map((b) => ({ id: b.leaveTypeId, name: b.name }))

  function refresh() {
    balances.reload()
    requests.reload()
    setVersion((v) => v + 1)
  }

  return (
    <>
      <BalanceTiles balances={balances.data} />

      <div className="section-head">
        <h2>My leave requests</h2>
        {canApply && types.length > 0 && !applying && (
          <button type="button" className="outline" onClick={() => setApplying(true)}>
            Apply for leave
          </button>
        )}
      </div>
      {applying && (
        <ApplyForm
          types={types}
          onApplied={() => {
            setApplying(false)
            refresh()
          }}
          onCancel={() => setApplying(false)}
        />
      )}
      <MyRequests requests={requests.data} onChanged={refresh} />

      <History key={version} balances={balances.data} />
    </>
  )
}
