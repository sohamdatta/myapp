import { useState } from 'react'
import { attendance } from '../api.js'
import { formatDay, formatMinutes, formatMonth, formatTime, liveStatus, localToday, shiftDay, useAction, useLoad, whoName, formatLate } from '../hooks.js'
import { Badge, DayStatus, Empty, ErrorNote, StatusBadge, Tabs, Trail, Who } from '../ui.jsx'
import { useConfirm, useToast } from '../feedbackContext.js'
import { DeviceImport, People, Setup } from './AttendanceSetup.jsx'

const KINDS = { regularization: 'Punch correction', on_duty: 'On duty', work_from_home: 'Work from home' }

/** not_in_yet, work_from_home → Not in yet, Work from home. */
function label(status) {
  const words = status.replaceAll('_', ' ')
  return words[0].toUpperCase() + words.slice(1)
}

function download(fileName, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  URL.revokeObjectURL(url)
}

/** HR enters a punch for someone, with a reason. The original punches are never changed. */
function ManualPunch({ row, date, onSaved }) {
  const [time, setTime] = useState('18:30')
  const [direction, setDirection] = useState('out')
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    const done = await run(async () => {
      await attendance.manualPunch(row.membershipId, { at: `${date}T${time}`, direction, reason })
      return true
    })
    if (done) onSaved()
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="toolbar">
        <div>
          <label htmlFor={`mp-time-${row.membershipId}`}>Add a punch at</label>
          <input id={`mp-time-${row.membershipId}`} type="time" value={time} onChange={(e) => setTime(e.target.value)} />
        </div>
        <div>
          <label htmlFor={`mp-dir-${row.membershipId}`}>Direction</label>
          <select id={`mp-dir-${row.membershipId}`} value={direction} onChange={(e) => setDirection(e.target.value)}>
            <option value="in">In</option>
            <option value="out">Out</option>
          </select>
        </div>
        <div>
          <label htmlFor={`mp-reason-${row.membershipId}`}>Reason</label>
          <input id={`mp-reason-${row.membershipId}`} value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Add punch'}
        </button>
      </div>
      <p className="hint">The time is local to the user's work location. The entry is recorded in the audit log.</p>
      <ErrorNote>{error}</ErrorNote>
    </form>
  )
}

const MARKS = { absent: 'Absent', half_day: 'Half day', present: 'Present (take back the mark)' }

/**
 * Where nobody punches, a working day counts as present; HR, or the manager for their team,
 * marks the exceptions. Each mark needs a reason, and a later one replaces it.
 */
function MarkDay({ row, date, onSaved }) {
  const choices = Object.keys(MARKS).filter((m) => m !== (row.mark || 'present'))
  const [status, setStatus] = useState(choices[0])
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    const done = await run(() => attendance.mark(row.membershipId, { date, status, reason }))
    if (done) onSaved()
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="toolbar">
        <div>
          <label htmlFor={`mk-status-${row.membershipId}`}>Mark the day</label>
          <select id={`mk-status-${row.membershipId}`} value={status} onChange={(e) => setStatus(e.target.value)}>
            {choices.map((m) => (
              <option key={m} value={m}>
                {MARKS[m]}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`mk-reason-${row.membershipId}`}>Reason</label>
          <input id={`mk-reason-${row.membershipId}`} value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Save mark'}
        </button>
      </div>
      <p className="hint">
        Nobody punches at this location: the day counts as present unless it is marked. The mark and its reason show in the day's
        steps and in the audit log.
      </p>
      <ErrorNote>{error}</ErrorNote>
    </form>
  )
}

function Board({ date, canManage, canMark, meId, onChanged }) {
  const board = useLoad(() => attendance.board(date))
  const [open, setOpen] = useState(null)

  if (board.loading) return <p className="muted">Loading…</p>
  if (board.error) return <ErrorNote>{board.error}</ErrorNote>
  if (board.data.length === 0) {
    return <Empty>Nobody to show. A manager's team appears here once employee records carry reporting lines.</Empty>
  }

  // Counted as the table shows them: today's unfinished days read "working" or "not in yet".
  const isToday = date === localToday()
  const counts = {}
  let late = 0
  board.data.forEach((r) => {
    const key = liveStatus(r, isToday) || r.status || 'not worked out'
    counts[key] = (counts[key] || 0) + 1
    if (r.lateMinutes > 0) late += 1
  })

  return (
    <>
      <div className="tiles">
        {Object.entries(counts).map(([status, n]) => (
          <div key={status} className="tile">
            <div className="tile-label">{label(status)}</div>
            <div className="tile-value">{n}</div>
          </div>
        ))}
        <div className="tile">
          <div className="tile-label">Late</div>
          <div className="tile-value">{late}</div>
        </div>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>User</th>
              <th>Result</th>
              <th>In</th>
              <th>Out</th>
              <th>Worked</th>
              <th>Late</th>
              <th>Overtime</th>
              <th aria-label="Details" />
            </tr>
          </thead>
          <tbody>
            {board.data.map((r) => (
              <BoardRow
                key={r.membershipId}
                row={r}
                date={date}
                canManage={canManage}
                canMark={canMark && r.mode === 'present' && r.membershipId !== meId}
                open={open === r.membershipId}
                onToggle={() => setOpen(open === r.membershipId ? null : r.membershipId)}
                onChanged={() => {
                  board.reload()
                  onChanged()
                }}
              />
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

const OFF = ['holiday', 'weekly_off', 'on_leave', 'not_assigned']

function BoardRow({ row, date, canManage, canMark, open, onToggle, onChanged }) {
  return (
    <>
      <tr>
        <td>
          <Who person={row} code={row.employeeCode} />
        </td>
        <td>
          <DayStatus day={row} isToday={date === localToday()} />
          {row.mode === 'present' && !OFF.includes(row.status) && (
            <div className="hint">{row.mark ? 'marked by hand' : 'present by default'}</div>
          )}
        </td>
        <td>{formatTime(row.firstIn)}</td>
        <td>{formatTime(row.lastOut)}</td>
        <td>{formatMinutes(row.workedMinutes)}</td>
        <td>{row.lateMinutes ? <Badge tone="warn">{formatLate(row.lateMinutes)}</Badge> : '—'}</td>
        <td>{formatMinutes(row.overtimeMinutes)}</td>
        <td className="actions">
          <button type="button" className="quiet" aria-expanded={open} onClick={onToggle}>
            {open ? 'Hide' : 'Details'}
          </button>
        </td>
      </tr>
      {open && (
        <tr className="detail-row">
          <td colSpan={8}>
            <Trail steps={row.trail} inProgress={date === localToday()} />
            {canMark && !OFF.includes(row.status) && <MarkDay row={row} date={date} onSaved={onChanged} />}
            {canManage && row.status !== 'not_assigned' && row.mode !== 'present' && <ManualPunch row={row} date={date} onSaved={onChanged} />}
          </td>
        </tr>
      )}
    </>
  )
}

function Daily({ canManage, canMark, meId, canExport }) {
  const [date, setDate] = useState(localToday())
  const [version, setVersion] = useState(0)
  const { busy, error, run } = useAction()

  async function exportRegister() {
    const file = await run(() => attendance.register(date.slice(0, 7)))
    if (file) download(file.fileName, file.csv)
  }

  return (
    <>
      <div className="filters">
        <button type="button" className="quiet plain icon-button" aria-label="Previous day" onClick={() => setDate(shiftDay(date, -1))}>
          ‹
        </button>
        <label htmlFor="board-date" className="sr-only">
          Date
        </label>
        <input id="board-date" type="date" value={date} max={localToday()} onChange={(e) => setDate(e.target.value || localToday())} />
        <button
          type="button"
          className="quiet plain icon-button"
          aria-label="Next day"
          disabled={date >= localToday()}
          onClick={() => setDate(shiftDay(date, 1))}
        >
          ›
        </button>
        <span className="you">{formatDay(date)}</span>
        <span className="spacer" />
        {canExport && (
          <button type="button" className="outline" disabled={busy} onClick={exportRegister}>
            {busy ? 'Preparing…' : `Download the ${formatMonth(date.slice(0, 7))} register`}
          </button>
        )}
      </div>
      <ErrorNote>{error}</ErrorNote>
      <Board
        key={`${date}-${version}`}
        date={date}
        canManage={canManage}
        canMark={canMark}
        meId={meId}
        onChanged={() => setVersion((v) => v + 1)}
      />
      <p className="hint" style={{ marginTop: 12 }}>
        Times are shown in your own time zone.
      </p>
    </>
  )
}

function RequestRow({ request, canDecide, onDecided }) {
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()

  async function decide(action) {
    const name = whoName(request)
    if (action === 'reject') {
      const sure = await confirm({
        title: `Reject ${name}'s request?`,
        body: `${KINDS[request.kind]}, ${formatDay(request.workDate)}. ${note ? 'Your note goes with it. ' : ''}A rejection cannot be undone.`,
        confirmLabel: 'Reject request',
        danger: true,
      })
      if (!sure) return
    }
    const done = await run(async () => {
      await attendance.decideRequest(request.id, action, note)
      return true
    })
    if (done) {
      toast(`Request ${action === 'approve' ? 'approved' : 'rejected'} for ${name}`)
      onDecided()
    }
  }

  return (
    <>
      <tr>
        <td>
          <Who person={request} />
        </td>
        <td>
          {KINDS[request.kind]}
          <div className="hint">{formatDay(request.workDate)}</div>
        </td>
        <td>
          {request.requestedIn ? `${formatTime(request.requestedIn)} to ${formatTime(request.requestedOut)}` : request.half ? `${request.half[0].toUpperCase()}${request.half.slice(1)} half` : 'Whole day'}
          <div className="hint">
            Recorded that day so far: {request.dayStatus ? request.dayStatus.replaceAll('_', ' ') : 'nothing yet'}
            {request.dayFirstIn && `, in ${formatTime(request.dayFirstIn)}`}
            {request.dayLastOut && `, out ${formatTime(request.dayLastOut)}`}
          </div>
        </td>
        <td>{request.reason}</td>
        <td className="actions">
          {request.status !== 'pending' ? (
            <>
              <StatusBadge status={request.status} />
              {request.decisionNote && <div className="hint">{request.decisionNote}</div>}
            </>
          ) : request.own ? (
            <span className="hint">Your own request: someone else must decide it</span>
          ) : canDecide ? (
            <>
              <input
                className="cell-input"
                aria-label={`Note for ${whoName(request)}`}
                placeholder="Note (optional)"
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />{' '}
              <button type="button" className="quiet" disabled={busy} onClick={() => decide('approve')}>
                Approve
              </button>
              <button type="button" className="quiet danger" disabled={busy} onClick={() => decide('reject')}>
                Reject
              </button>
            </>
          ) : (
            <StatusBadge status="pending" />
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

function RequestList({ status, canDecide }) {
  const requests = useLoad(() => attendance.requests(status))
  if (requests.loading) return <p className="muted">Loading…</p>
  if (requests.error) return <ErrorNote>{requests.error}</ErrorNote>
  if (requests.data.length === 0) return <Empty>No {status} requests.</Empty>
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>User</th>
            <th>Request</th>
            <th>Asked for</th>
            <th>Reason</th>
            <th aria-label="Decision" />
          </tr>
        </thead>
        <tbody>
          {requests.data.map((r) => (
            <RequestRow key={r.id} request={r} canDecide={canDecide} onDecided={requests.reload} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Requests({ canDecide }) {
  const [status, setStatus] = useState('pending')
  return (
    <>
      <div className="filters">
        {['pending', 'approved', 'rejected'].map((s) => (
          <button key={s} type="button" className={status === s ? 'chip chip-current' : 'chip'} onClick={() => setStatus(s)}>
            {s[0].toUpperCase() + s.slice(1)}
          </button>
        ))}
      </div>
      <RequestList key={status} status={status} canDecide={canDecide} />
    </>
  )
}

/** Everyone's attendance, for the roles that may see beyond their own. */
export default function Attendance({ me }) {
  const scopes = me.scopes || {}
  const everyone = ['tenant', 'assigned'].includes(scopes['attendance.read'])
  const canManage = scopes['attendance.manage'] === 'tenant'
  const canApprove = Boolean(scopes['attendance.approve']) && scopes['attendance.approve'] !== 'self'
  const canDecide = ['tenant', 'assigned'].includes(scopes['attendance.approve']) && me.kind === 'tenant'
  const views = [
    { id: 'daily', label: 'Daily' },
    canApprove && { id: 'requests', label: 'Requests' },
    everyone && { id: 'people', label: 'People' },
    everyone && { id: 'setup', label: 'Setup' },
    everyone && { id: 'import', label: 'Devices' },
  ].filter(Boolean)
  const [view, setView] = useState('daily')

  return (
    <>
      <Tabs tabs={views} current={view} onChange={setView} />
      {view === 'daily' && (
        <Daily
          canManage={canManage && me.kind === 'tenant'}
          canMark={canApprove && me.kind === 'tenant'}
          meId={me.membershipId}
          canExport={everyone}
        />
      )}
      {view === 'requests' && <Requests canDecide={canDecide} />}
      {view === 'people' && <People canManage={canManage} />}
      {view === 'setup' && <Setup canManage={canManage} />}
      {view === 'import' && <DeviceImport canManage={canManage} />}
    </>
  )
}
