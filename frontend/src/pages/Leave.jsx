import { useState } from 'react'
import { attendance, leave } from '../api.js'
import { formatDay, formatDays, formatMonth, formatRange, leaveBody, localMonth, localToday, newLeave, useAction, useLoad, whoName, numeric, wholeNumber } from '../hooks.js'
import { Badge, Empty, ErrorNote, StatusBadge, Tabs, Who } from '../ui.jsx'
import { LeaveFields, Ledger } from './MyLeave.jsx'
import { useConfirm, useToast } from '../feedbackContext.js'

const number = (value, what) => numeric(value, what)

// ── requests ────────────────────────────────────────────────────────

const LEAVE_DONE = { approve: 'approved', reject: 'rejected', cancel: 'cancelled' }

function RequestRow({ request, canDecide, canManage, onChanged }) {
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()

  async function act(action) {
    const name = whoName(request)
    const what = `${request.leaveType}, ${formatRange(request.fromDate, request.toDate, request.half)} (${formatDays(request.days)}).`
    if (action === 'reject') {
      const sure = await confirm({
        title: `Reject ${name}'s leave?`,
        body: `${what} ${note ? 'Your note goes with it. ' : ''}A rejection cannot be undone.`,
        confirmLabel: 'Reject leave',
        danger: true,
      })
      if (!sure) return
    }
    if (action === 'cancel') {
      const sure = await confirm({
        title: `Cancel ${name}'s approved leave?`,
        body: `${what} This cannot be undone.`,
        confirmLabel: 'Cancel leave',
        cancelLabel: 'Keep the leave',
        danger: true,
      })
      if (!sure) return
    }
    const done = await run(async () => {
      await leave.decide(request.id, action, note)
      return true
    })
    if (done) {
      toast(`Leave ${LEAVE_DONE[action]} for ${name}`)
      onChanged()
    }
  }

  const short = request.paid && Number(request.balance) < Number(request.days) - Number(request.unpaidDays)
  return (
    <>
      <tr>
        <td>
          <Who person={request} />
        </td>
        <td>
          {request.leaveType}
          <div className="hint">{formatRange(request.fromDate, request.toDate, request.half)}</div>
        </td>
        <td>
          {formatDays(request.days)}
          {Number(request.unpaidDays) > 0 && (
            <div className="hint">{Number(request.unpaidDays) === Number(request.days) ? 'Unpaid' : `${formatDays(request.unpaidDays)} unpaid`}</div>
          )}
          {request.paid && request.status === 'pending' && (
            <div className="hint">
              {Number(request.balance)} available {short && <Badge tone="warn">not enough</Badge>}
            </div>
          )}
        </td>
        <td>
          {request.reason}
          {request.alsoAway?.length > 0 && <div className="hint">Also away then: {request.alsoAway.join(', ')}</div>}
        </td>
        <td className="actions">
          {request.status === 'pending' && request.own ? (
            <span className="hint">Your own request: someone else must decide it</span>
          ) : request.status === 'pending' && canDecide ? (
            <>
              <input
                className="cell-input"
                aria-label={`Note for ${whoName(request)}`}
                placeholder="Note (optional)"
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />{' '}
              <button type="button" className="quiet" disabled={busy} onClick={() => act('approve')}>
                Approve
              </button>
              <button type="button" className="quiet danger" disabled={busy} onClick={() => act('reject')}>
                Reject
              </button>
            </>
          ) : (
            <>
              <StatusBadge status={request.status} />
              {request.decisionNote && <div className="hint">Note: “{request.decisionNote}”</div>}
              {request.status === 'approved' && canManage && !request.own && (
                <div>
                  <button type="button" className="quiet danger" disabled={busy} onClick={() => act('cancel')}>
                    Cancel this leave
                  </button>
                </div>
              )}
            </>
          )}
        </td>
      </tr>
      {error && (
        <tr className="detail-row">
          <td colSpan={5}>
            <ErrorNote>{error}</ErrorNote>
          </td>
        </tr>
      )}
    </>
  )
}

function RequestList({ status, canDecide, canManage }) {
  const requests = useLoad(() => leave.requests(status))
  if (requests.loading) return <p className="muted">Loading…</p>
  if (requests.error) return <ErrorNote>{requests.error}</ErrorNote>
  if (requests.data.length === 0) return <Empty>No {status} leave requests.</Empty>
  return (
    <div className="table-wrap">
      <table className="stack">
        <thead>
          <tr>
            <th>User</th>
            <th>Leave</th>
            <th>Days</th>
            <th>Reason</th>
            <th aria-label="Decision" />
          </tr>
        </thead>
        <tbody>
          {requests.data.map((r) => (
            <RequestRow key={r.id} request={r} canDecide={canDecide} canManage={canManage} onChanged={requests.reload} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Requests({ canDecide, canManage }) {
  const [status, setStatus] = useState('pending')
  return (
    <>
      <div className="filters">
        {['pending', 'approved', 'rejected', 'cancelled'].map((s) => (
          <button key={s} type="button" className={status === s ? 'chip chip-current' : 'chip'} onClick={() => setStatus(s)}>
            {s[0].toUpperCase() + s.slice(1)}
          </button>
        ))}
      </div>
      <RequestList key={status} status={status} canDecide={canDecide} canManage={canManage} />
    </>
  )
}

// ── calendar ────────────────────────────────────────────────────────

function monthEnd(month) {
  const [year, m] = month.split('-').map(Number)
  return `${month}-${String(new Date(year, m, 0).getDate()).padStart(2, '0')}`
}

function Away({ month }) {
  const rows = useLoad(() => leave.calendar(`${month}-01`, monthEnd(month)))
  if (rows.loading) return <p className="muted">Loading…</p>
  if (rows.error) return <ErrorNote>{rows.error}</ErrorNote>
  if (rows.data.length === 0) return <Empty>Nobody is away in {formatMonth(month)}.</Empty>
  return (
    <div className="table-wrap">
      <table className="stack">
        <thead>
          <tr>
            <th>Dates</th>
            <th>User</th>
            <th>Away for</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.data.map((r, index) => (
            <tr key={`${r.membershipId}-${r.kind}-${r.fromDate}-${index}`}>
              <td>{formatRange(r.fromDate, r.toDate, r.half)}</td>
              <td>
                <Who person={r} />
              </td>
              <td>{r.label}</td>
              <td>
                <StatusBadge status={r.status} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Calendar() {
  const [month, setMonth] = useState(localMonth())
  return (
    <>
      <div className="filters">
        <label htmlFor="away-month" className="sr-only">
          Month
        </label>
        <input id="away-month" type="month" value={month} onChange={(e) => setMonth(e.target.value || localMonth())} />
        <span className="you">Leave, on duty and work from home in {formatMonth(month)}</span>
      </div>
      <Away key={month} month={month} />
    </>
  )
}

// ── balances ────────────────────────────────────────────────────────

function AdjustForm({ person, types, onSaved, onCancel }) {
  const [leaveTypeId, setLeaveTypeId] = useState(types[0]?.id || '')
  const [days, setDays] = useState('')
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    const done = await run(async () => {
      await leave.adjust(person.membershipId, { leaveTypeId, days: number(days, 'Days'), reason })
      return true
    })
    if (done) onSaved()
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="toolbar">
        <div>
          <label htmlFor="adj-type">Leave type</label>
          <select id="adj-type" value={leaveTypeId} onChange={(e) => setLeaveTypeId(e.target.value)}>
            {types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="adj-days">Days to add</label>
          <input id="adj-days" type="number" step="0.5" value={days} onChange={(e) => setDays(e.target.value)} />
        </div>
        <div>
          <label htmlFor="adj-reason">Reason</label>
          <input id="adj-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Save adjustment'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Close
        </button>
      </div>
      <p className="hint">Use a negative number to take days away. This is also how an opening balance is loaded.</p>
      <ErrorNote>{error}</ErrorNote>
    </form>
  )
}

function OnBehalfForm({ person, types, onSaved, onCancel }) {
  const [value, setValue] = useState(newLeave)
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    const made = await run(() => leave.applyFor(person.membershipId, leaveBody(value)))
    if (made) onSaved()
  }

  return (
    <form onSubmit={submit} noValidate>
      <LeaveFields idPrefix="behalf" types={types} value={value} onChange={setValue} />
      <p className="hint">The leave is recorded as approved straight away, for any date in a leave year that is still open.</p>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy || !value.leaveTypeId}>
          {busy ? 'Saving…' : `Record leave for ${whoName(person)}`}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Close
        </button>
      </div>
    </form>
  )
}

function PersonLedger({ person, onCancel }) {
  const paid = person.balances.filter((b) => b.paid)
  const [typeId, setTypeId] = useState(paid[0]?.leaveTypeId)
  return (
    <>
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
        <span className="spacer" />
        <button type="button" className="quiet" onClick={onCancel}>
          Close
        </button>
      </div>
      {typeId ? (
        <Ledger key={typeId} load={() => leave.userLedger(person.membershipId, typeId)} type={paid.find((b) => b.leaveTypeId === typeId)} />
      ) : (
        <Empty>No paid leave types yet.</Empty>
      )}
    </>
  )
}

function CompOff({ onGranted }) {
  const candidates = useLoad(leave.compOffCandidates)
  const { busy, error, run } = useAction()

  async function grant(row) {
    const done = await run(async () => {
      await leave.grantCompOff(row.membershipId, { workDate: row.workDate, days: Number(row.days) })
      return true
    })
    if (done) {
      candidates.reload()
      onGranted()
    }
  }

  if (candidates.loading || candidates.error || candidates.data.length === 0) return null
  return (
    <>
      <div className="section-head">
        <h2>Days off that were worked</h2>
      </div>
      <p className="hint">In the last 90 days. Granting comp-off adds the day to the user's comp-off balance, to be used before it expires.</p>
      <ErrorNote>{error}</ErrorNote>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>User</th>
              <th>Day worked</th>
              <th>Comp-off</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {candidates.data.map((row) => (
              <tr key={`${row.membershipId}-${row.workDate}`}>
                <td>
                  <Who person={row} />
                </td>
                <td>
                  {formatDay(row.workDate)}
                  <div className="hint">{row.status === 'holiday' ? 'A holiday' : 'A weekly off'}</div>
                </td>
                <td>{formatDays(row.days)}</td>
                <td className="actions">
                  <button type="button" className="quiet" disabled={busy} onClick={() => grant(row)}>
                    Grant
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

function Balances({ canManage, me }) {
  const people = useLoad(leave.balances)
  const types = useLoad(leave.types)
  const [open, setOpen] = useState(null) // { id, form: 'adjust' | 'behalf' | 'ledger' }
  const [search, setSearch] = useState('')

  if (people.loading || types.loading) return <p className="muted">Loading…</p>
  if (people.error || types.error) return <ErrorNote>{people.error || types.error}</ErrorNote>
  const active = types.data.filter((t) => t.active)
  if (active.length === 0) return <Empty>No leave types yet. Add them under Setup, then balances appear here.</Empty>
  const paid = active.filter((t) => t.paid)
  const shown = people.data.filter((p) => `${p.name || ''} ${p.email}`.toLowerCase().includes(search.trim().toLowerCase()))
  // Acting for someone else needs a user of the Organization; a support session can only look.
  const canAct = canManage && me.kind === 'tenant'

  function saved() {
    setOpen(null)
    people.reload()
  }

  return (
    <>
      <div className="filters">
        <label htmlFor="bal-search" className="sr-only">
          Search users
        </label>
        <input id="bal-search" type="search" placeholder="Search users" value={search} onChange={(e) => setSearch(e.target.value)} />
        <span className="you">Days available as of {formatDay(localToday())}</span>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>User</th>
              {paid.map((t) => (
                <th key={t.id}>{t.name}</th>
              ))}
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {shown.map((p) => {
              const byType = Object.fromEntries(p.balances.map((b) => [b.leaveTypeId, b]))
              const toggle = (form) => setOpen(open?.id === p.membershipId && open.form === form ? null : { id: p.membershipId, form })
              return (
                <PersonRows
                  key={p.membershipId}
                  columns={paid.length + 2}
                  detail={
                    open?.id !== p.membershipId ? null : open.form === 'adjust' ? (
                      <AdjustForm person={p} types={paid} onSaved={saved} onCancel={() => setOpen(null)} />
                    ) : open.form === 'behalf' ? (
                      <OnBehalfForm person={p} types={active} onSaved={saved} onCancel={() => setOpen(null)} />
                    ) : (
                      <PersonLedger person={p} onCancel={() => setOpen(null)} />
                    )
                  }
                >
                  <td>
                    <Who person={p} />
                    {p.status === 'suspended' && (
                      <>
                        {' '}
                        <Badge tone="warn">suspended</Badge>
                      </>
                    )}
                  </td>
                  {paid.map((t) => (
                    <td key={t.id}>
                      {byType[t.id] ? Number(byType[t.id].available) : '—'}
                      {byType[t.id] && Number(byType[t.id].pending) > 0 && <div className="hint">{Number(byType[t.id].pending)} pending</div>}
                    </td>
                  ))}
                  <td className="actions">
                    <button type="button" className="quiet" onClick={() => toggle('ledger')}>
                      History
                    </button>
                    {canAct && !p.own && (
                      <>
                        <button type="button" className="quiet" onClick={() => toggle('adjust')}>
                          Adjust
                        </button>
                        <button type="button" className="quiet" onClick={() => toggle('behalf')}>
                          Record leave
                        </button>
                      </>
                    )}
                    {canAct && p.own && <div className="hint">Your own: another HR changes it</div>}
                  </td>
                </PersonRows>
              )
            })}
          </tbody>
        </table>
      </div>
      {canAct && <CompOff onGranted={people.reload} />}
    </>
  )
}

/** A user's row, with a second row below it while one of their forms is open. */
function PersonRows({ columns, detail, children }) {
  return (
    <>
      <tr>{children}</tr>
      {detail && (
        <tr className="detail-row">
          <td colSpan={columns}>{detail}</td>
        </tr>
      )}
    </>
  )
}

// ── setup ───────────────────────────────────────────────────────────

const NEW_TYPE = {
  name: '',
  kind: 'regular',
  paid: true,
  accrual: 'yearly',
  yearlyQuota: '',
  prorateJoiners: true,
  carryForwardCap: '',
  excessAtYearEnd: 'lapse',
  whenShort: 'refuse',
  countDaysOff: false,
  expiryDays: 90,
  active: true,
}

function TypeForm({ type, onSaved, onCancel }) {
  const [form, setForm] = useState(() =>
    type ? { ...type, yearlyQuota: type.yearlyQuota ?? '', carryForwardCap: type.carryForwardCap ?? '', expiryDays: type.expiryDays ?? 90 } : NEW_TYPE,
  )
  const { busy, error, run } = useAction()
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  const compOff = form.kind === 'comp_off'
  const accrues = !compOff && form.paid && form.accrual !== 'none'
  const fixed = Boolean(type) // what kind it is, and whether it is paid, cannot change afterwards

  async function submit(event) {
    event.preventDefault()
    const saved = await run(() =>
      leave.saveType(type?.id, {
        name: form.name,
        kind: form.kind,
        paid: compOff ? true : form.paid,
        accrual: compOff || !form.paid ? 'none' : form.accrual,
        yearlyQuota: accrues ? number(form.yearlyQuota, 'Days a year') : null,
        prorateJoiners: form.prorateJoiners,
        carryForwardCap: compOff || !form.paid ? null : number(form.carryForwardCap, 'The carry-forward cap'),
        excessAtYearEnd: form.excessAtYearEnd,
        whenShort: form.whenShort,
        countDaysOff: form.countDaysOff,
        expiryDays: compOff ? wholeNumber(form.expiryDays, 'Expiry days') : null,
        active: form.active,
      }),
    )
    if (saved) onSaved()
  }

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>{type ? `Change ${type.name}` : 'Add a leave type'}</h2>
      <div className="grid">
        <div>
          <label htmlFor="lt-name">Name</label>
          <input id="lt-name" value={form.name} placeholder="Casual leave" onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="lt-kind">Kind</label>
          <select
            id="lt-kind"
            disabled={fixed}
            value={compOff ? 'comp_off' : form.paid ? 'paid' : 'unpaid'}
            onChange={(e) => set({ kind: e.target.value === 'comp_off' ? 'comp_off' : 'regular', paid: e.target.value !== 'unpaid' })}
          >
            <option value="paid">Paid leave</option>
            <option value="unpaid">Unpaid leave (loss of pay)</option>
            <option value="comp_off">Comp-off</option>
          </select>
        </div>
        {!compOff && form.paid && (
          <div>
            <label htmlFor="lt-accrual">Days are credited</label>
            <select id="lt-accrual" value={form.accrual} onChange={(e) => set({ accrual: e.target.value })}>
              <option value="yearly">All at the start of the leave year</option>
              <option value="monthly">A part each month</option>
              <option value="none">Only when HR adds them</option>
            </select>
          </div>
        )}
        {accrues && (
          <div>
            <label htmlFor="lt-quota">Days a year</label>
            <input id="lt-quota" type="number" min="0" step="0.5" value={form.yearlyQuota} onChange={(e) => set({ yearlyQuota: e.target.value })} />
          </div>
        )}
        {!compOff && form.paid && (
          <>
            <div>
              <label htmlFor="lt-cap">Carry forward at most</label>
              <input
                id="lt-cap"
                type="number"
                min="0"
                step="0.5"
                placeholder="No limit"
                value={form.carryForwardCap}
                onChange={(e) => set({ carryForwardCap: e.target.value })}
              />
            </div>
            <div>
              <label htmlFor="lt-excess">Days above that at year end</label>
              <select id="lt-excess" value={form.excessAtYearEnd} onChange={(e) => set({ excessAtYearEnd: e.target.value })}>
                <option value="lapse">Lapse</option>
                <option value="encash">Are marked for encashment</option>
              </select>
            </div>
            <div>
              <label htmlFor="lt-short">When the balance is short</label>
              <select id="lt-short" value={form.whenShort} onChange={(e) => set({ whenShort: e.target.value })}>
                <option value="refuse">Refuse the request</option>
                <option value="unpaid">Take the rest as unpaid</option>
              </select>
            </div>
          </>
        )}
        {compOff && (
          <div>
            <label htmlFor="lt-expiry">Expires after (days)</label>
            <input id="lt-expiry" type="number" min="1" max="366" value={form.expiryDays} onChange={(e) => set({ expiryDays: e.target.value })} />
          </div>
        )}
      </div>
      <div className="days">
        {accrues && (
          <label className="check">
            <input type="checkbox" checked={form.prorateJoiners} onChange={(e) => set({ prorateJoiners: e.target.checked })} />
            Someone who joins mid-year gets a share for the months left
          </label>
        )}
        <label className="check">
          <input type="checkbox" checked={form.countDaysOff} onChange={(e) => set({ countDaysOff: e.target.checked })} />
          Weekly offs and holidays inside a leave count as leave
        </label>
        <label className="check">
          <input type="checkbox" checked={form.active} onChange={(e) => set({ active: e.target.checked })} />
          Users can apply for it
        </label>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : type ? 'Save changes' : 'Add leave type'}
        </button>
        {onCancel && (
          <button type="button" className="quiet" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </form>
  )
}

function describe(t) {
  if (t.kind === 'comp_off') return `Earned by working a day off · expires after ${t.expiryDays} days`
  if (!t.paid) return 'Unpaid · no balance'
  const credit =
    t.accrual === 'yearly' ? `${Number(t.yearlyQuota)} days at the start of the year` : t.accrual === 'monthly' ? `${Number(t.yearlyQuota)} days a year, credited monthly` : 'Added by HR'
  const carry =
    t.carryForwardCap === null || t.carryForwardCap === undefined
      ? 'all carries forward'
      : Number(t.carryForwardCap) === 0
        ? `nothing carries forward; what is left ${t.excessAtYearEnd === 'encash' ? 'is encashed' : 'lapses'} at year end`
        : `carries forward up to ${Number(t.carryForwardCap)}, the rest ${t.excessAtYearEnd === 'encash' ? 'is encashed' : 'lapses'}`
  return `${credit} · ${carry}`
}

function Types({ canManage }) {
  const types = useLoad(leave.types)
  const [editing, setEditing] = useState(null) // a type, 'new', or null

  if (types.loading) return <p className="muted">Loading…</p>
  if (types.error) return <ErrorNote>{types.error}</ErrorNote>

  function saved() {
    setEditing(null)
    types.reload()
  }

  return (
    <>
      <div className="section-head">
        <h2>Leave types</h2>
        {canManage && editing !== 'new' && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            Add leave type
          </button>
        )}
      </div>
      {editing === 'new' && <TypeForm onSaved={saved} onCancel={() => setEditing(null)} />}
      {types.data.length === 0 ? (
        <Empty>No leave types yet. Add the ones your Organization gives, such as casual, sick and earned leave.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Leave type</th>
                <th>Rules</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {types.data.map((t) => (
                <tr key={t.id}>
                  <td>
                    {t.name} {!t.active && <Badge>inactive</Badge>}
                  </td>
                  <td>
                    {describe(t)}
                    {t.paid && t.kind === 'regular' && t.whenShort === 'unpaid' && <div className="hint">A shortfall is taken as unpaid</div>}
                    {t.countDaysOff && <div className="hint">Days off inside a leave count</div>}
                  </td>
                  <td className="actions">
                    {canManage && (
                      <button type="button" className="quiet" onClick={() => setEditing(t)}>
                        Change
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && editing !== 'new' && <TypeForm key={editing.id} type={editing} onSaved={saved} onCancel={() => setEditing(null)} />}
    </>
  )
}

function LeaveYear({ canManage, onChanged }) {
  const settings = useLoad(attendance.settings)
  const { busy, error, run } = useAction()

  if (settings.loading) return <p className="muted">Loading…</p>
  if (settings.error) return <ErrorNote>{settings.error}</ErrorNote>
  const s = settings.data

  async function change(event) {
    const leaveYearStartMonth = Number(event.target.value)
    const saved = await run(() =>
      attendance.saveSettings({
        leaveYearStartMonth,
        wfhEnabled: s.wfhEnabled,
        wfhRequiresPunch: s.wfhRequiresPunch,
        wfhLimitPerWeek: s.wfhLimitPerWeek,
        wfhLimitPerMonth: s.wfhLimitPerMonth,
      }),
    )
    if (saved) {
      settings.reload()
      onChanged()
    }
  }

  return (
    <>
      <div className="section-head">
        <h2>Leave year</h2>
      </div>
      <div className="toolbar">
        <div>
          <label htmlFor="leave-year-start">The leave year runs</label>
          <select id="leave-year-start" value={s.leaveYearStartMonth} disabled={!canManage || busy} onChange={change}>
            <option value={1}>January to December</option>
            <option value={4}>April to March</option>
          </select>
        </div>
      </div>
      <p className="hint">Yearly leave is credited on the first day of the leave year. This cannot be changed once a year has been closed.</p>
      <ErrorNote>{error}</ErrorNote>
    </>
  )
}

function YearEnd({ canManage }) {
  const [year, setYear] = useState(() => new Date().getFullYear() - 1)
  const [preview, setPreview] = useState(null)
  const [confirming, setConfirming] = useState(false)
  const { busy, error, run } = useAction()

  async function load(event) {
    event?.preventDefault()
    setConfirming(false)
    setPreview((await run(() => leave.yearEndPreview(year))) || null)
  }

  async function close() {
    const done = await run(() => leave.yearEndRun(year))
    if (done) {
      setConfirming(false)
      setPreview(await leave.yearEndPreview(year))
    }
  }

  return (
    <>
      <div className="section-head">
        <h2>Close a leave year</h2>
      </div>
      <form className="toolbar" onSubmit={load} noValidate>
        <div>
          <label htmlFor="ye-year">Leave year starting in</label>
          <input
            id="ye-year"
            type="number"
            min="2000"
            max="2100"
            value={year}
            onChange={(e) => {
              setYear(Number(e.target.value))
              setPreview(null)
            }}
          />
        </div>
        <button type="submit" className="outline" disabled={busy}>
          {busy && !confirming ? 'Working out…' : 'Preview'}
        </button>
      </form>
      <p className="hint">
        Closing a year removes what each user holds above the carry-forward limit, as a lapse or for encashment. It can be done once per year, and a
        closed year accepts no more changes.
      </p>
      <ErrorNote>{error}</ErrorNote>
      {preview && (
        <div className="panel">
          <h2>
            {formatDay(preview.from)} to {formatDay(preview.to)}
          </h2>
          {preview.closed ? (
            <p>This leave year has been closed.</p>
          ) : (
            <>
              <div className="tiles">
                <div className="tile">
                  <div className="tile-label">Days that lapse</div>
                  <div className="tile-value">{Number(preview.lapsed)}</div>
                </div>
                <div className="tile">
                  <div className="tile-label">Days to encash</div>
                  <div className="tile-value">{Number(preview.encashed)}</div>
                </div>
                <div className="tile">
                  <div className="tile-label">Balances affected</div>
                  <div className="tile-value">{preview.rows.length}</div>
                </div>
              </div>
              {preview.rows.length === 0 ? (
                <Empty>Nobody holds more than the carry-forward limit, so closing the year changes no balance.</Empty>
              ) : (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>User</th>
                        <th>Leave type</th>
                        <th>Balance at year end</th>
                        <th>Carries forward</th>
                        <th>Removed</th>
                      </tr>
                    </thead>
                    <tbody>
                      {preview.rows.map((r) => (
                        <tr key={`${r.membershipId}-${r.leaveType}`}>
                          <td>{r.email}</td>
                          <td>{r.leaveType}</td>
                          <td>{Number(r.closingBalance)}</td>
                          <td>{Number(r.carryForward)}</td>
                          <td>
                            {Number(r.excess)} <Badge>{r.action === 'encash' ? 'encash' : 'lapse'}</Badge>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {preview.blocked ? (
                <p className="hint" role="status">
                  {preview.blocked}. The year cannot be closed yet.
                </p>
              ) : canManage && !confirming ? (
                <button type="button" className="secondary" onClick={() => setConfirming(true)}>
                  Close this leave year…
                </button>
              ) : canManage ? (
                <div className="notice" role="alert">
                  <p>
                    <strong>Close the leave year {preview.year}?</strong> This writes the entries above and cannot be undone. Later corrections are
                    made as adjustments in the current year.
                  </p>
                  <div className="form-actions">
                    <button type="button" className="secondary" disabled={busy} onClick={close}>
                      {busy ? 'Closing…' : 'Yes, close the year'}
                    </button>
                    <button type="button" className="quiet" onClick={() => setConfirming(false)}>
                      Not now
                    </button>
                  </div>
                </div>
              ) : null}
            </>
          )}
        </div>
      )}
    </>
  )
}

function Setup({ canManage }) {
  const [version, setVersion] = useState(0)
  return (
    <>
      <Types canManage={canManage} />
      <LeaveYear canManage={canManage} onChanged={() => setVersion((v) => v + 1)} />
      {canManage && <YearEnd key={version} canManage={canManage} />}
    </>
  )
}

/** Everyone's leave, for the roles that may see beyond their own. */
export default function Leave({ me }) {
  const scopes = me.scopes || {}
  const everyone = ['tenant', 'assigned'].includes(scopes['leave.read'])
  const canApprove = Boolean(scopes['leave.approve']) && scopes['leave.approve'] !== 'self'
  const canDecide = canApprove && me.kind === 'tenant'
  const canManage = scopes['leave.manage'] === 'tenant'
  const views = [
    canApprove && { id: 'requests', label: 'Requests' },
    { id: 'calendar', label: 'Who is away' },
    everyone && { id: 'balances', label: 'Balances' },
    everyone && { id: 'setup', label: 'Setup' },
  ].filter(Boolean)
  const [view, setView] = useState(views[0].id)

  return (
    <>
      <Tabs tabs={views} current={view} onChange={setView} />
      {view === 'requests' && <Requests canDecide={canDecide} canManage={canManage && me.kind === 'tenant'} />}
      {view === 'calendar' && <Calendar />}
      {view === 'balances' && <Balances canManage={canManage} me={me} />}
      {view === 'setup' && <Setup canManage={canManage} />}
    </>
  )
}
