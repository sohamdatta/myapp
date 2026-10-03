import { useState } from 'react'
import { attendance } from '../api.js'
import { formatDay, formatMinutes, formatTime, localMonth, localToday, useAction, useLoad } from '../hooks.js'
import { Badge, DayStatus, Empty, ErrorNote, StatusBadge, Trail } from '../ui.jsx'

const KINDS = {
  regularization: 'Correct my punches',
  on_duty: 'On duty away from the office',
  work_from_home: 'Work from home',
}

const SOURCES = { web: 'punched here', import: 'from a device', manual: 'entered by HR', request: 'from a request' }

/** Asks the browser where the device is. Resolves to null when it cannot or may not say. */
function currentPosition() {
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve(null)
      return
    }
    navigator.geolocation.getCurrentPosition(
      (p) =>
        resolve({
          latitude: p.coords.latitude,
          longitude: p.coords.longitude,
          accuracyM: Math.round(p.coords.accuracy),
        }),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 },
    )
  })
}

function PunchPanel({ today, onPunched }) {
  const { busy, error, run } = useAction()
  const data = today.data

  async function punch() {
    const result = await run(async () => {
      // The server decides whether the office network is enough; the position is sent only when it may be needed.
      const position = data.wantsPosition ? await currentPosition() : null
      return attendance.punch(position)
    })
    if (result) onPunched()
  }

  if (!data.assigned) {
    return (
      <section className="panel">
        <h2>Today</h2>
        <p>No work location and shift are assigned to you yet. Ask HR to set them up, then you can punch here.</p>
      </section>
    )
  }

  return (
    <section className="panel">
      <div className="punch-panel">
        <div>
          <h2>{formatDay(data.workDate)}</h2>
          <p>
            {data.location} · {data.shift}
          </p>
          <p className="hint">
            {data.punchCheck === 'none' && 'You can punch from anywhere.'}
            {data.punchCheck === 'ip' && 'You must be on the office network to punch.'}
            {data.punchCheck === 'location' && 'Your browser will ask for your location, to check you are at the office.'}
            {data.punchCheck === 'either' &&
              'You must be on the office network, or allow your browser to share your location at the office.'}
          </p>
        </div>
        <button type="button" className="secondary punch-button" disabled={busy} onClick={punch}>
          {busy ? 'Checking…' : data.nextDirection === 'in' ? 'Punch in' : 'Punch out'}
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
      {data.punches.length > 0 && (
        <ul className="punch-list" aria-label="Today's punches">
          {data.punches.map((p) => (
            <li key={p.at + p.direction}>
              <Badge tone={p.direction === 'in' ? 'good' : 'neutral'}>
                {p.direction} {formatTime(p.at, data.timezone)}
              </Badge>{' '}
              <span className="hint">{SOURCES[p.source]}</span>
            </li>
          ))}
        </ul>
      )}
      {data.day && data.day.workedMinutes > 0 && (
        <p className="hint">Worked so far: {formatMinutes(data.day.workedMinutes)}</p>
      )}
    </section>
  )
}

/** A month of days. Each row opens to show how its result was worked out. */
export function DaysTable({ days, today, timeZone }) {
  const [open, setOpen] = useState(null)
  if (days.length === 0) return <Empty>No days to show for this month.</Empty>
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Date</th>
            <th>Result</th>
            <th>In</th>
            <th>Out</th>
            <th>Worked</th>
            <th>Late</th>
            <th aria-label="Details" />
          </tr>
        </thead>
        <tbody>
          {days.map((d) => (
            <DayRow key={d.workDate} day={d} today={today} timeZone={timeZone} open={open === d.workDate} onToggle={() => setOpen(open === d.workDate ? null : d.workDate)} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DayRow({ day, today, timeZone, open, onToggle }) {
  return (
    <>
      <tr>
        <td>{formatDay(day.workDate)}</td>
        <td>
          <DayStatus day={day} isToday={day.workDate === today} />
        </td>
        <td>{formatTime(day.firstIn, timeZone)}</td>
        <td>{formatTime(day.lastOut, timeZone)}</td>
        <td>{formatMinutes(day.workedMinutes)}</td>
        <td>{day.lateMinutes ? `${day.lateMinutes} min` : '—'}</td>
        <td className="actions">
          <button type="button" className="quiet" aria-expanded={open} onClick={onToggle}>
            {open ? 'Hide' : 'Why'}
          </button>
        </td>
      </tr>
      {open && (
        <tr className="detail-row">
          <td colSpan={7}>
            <Trail steps={day.trail} />
          </td>
        </tr>
      )}
    </>
  )
}

function RequestForm({ onCreated }) {
  const [kind, setKind] = useState('regularization')
  const [workDate, setWorkDate] = useState(localToday())
  const [inTime, setInTime] = useState('09:30')
  const [outTime, setOutTime] = useState('18:30')
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    const body = { kind, workDate, reason }
    if (kind === 'regularization') Object.assign(body, { inTime, outTime })
    const created = await run(() => attendance.createRequest(body))
    if (created) {
      setReason('')
      onCreated()
    }
  }

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>Raise a request</h2>
      <div className="grid">
        <div>
          <label htmlFor="req-kind">What for</label>
          <select id="req-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            {Object.entries(KINDS).map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="req-date">Date</label>
          <input id="req-date" type="date" value={workDate} max={localToday()} onChange={(e) => setWorkDate(e.target.value)} />
        </div>
        {kind === 'regularization' && (
          <>
            <div>
              <label htmlFor="req-in">Came in at</label>
              <input id="req-in" type="time" value={inTime} onChange={(e) => setInTime(e.target.value)} />
            </div>
            <div>
              <label htmlFor="req-out">Left at</label>
              <input id="req-out" type="time" value={outTime} onChange={(e) => setOutTime(e.target.value)} />
            </div>
          </>
        )}
      </div>
      <label htmlFor="req-reason">Reason</label>
      <input id="req-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
      <p className="hint">A request can be for today or the last 31 days. HR decides it; your original punches are kept.</p>
      <ErrorNote>{error}</ErrorNote>
      <button type="submit" className="secondary" disabled={busy}>
        {busy ? 'Sending…' : 'Send request'}
      </button>
    </form>
  )
}

function MyRequests({ requests, timeZone, onChanged }) {
  const { busy, error, run } = useAction()

  async function cancel(id) {
    const done = await run(async () => {
      await attendance.cancelRequest(id)
      return true
    })
    if (done) onChanged()
  }

  if (requests.length === 0) return <Empty>You have not raised any requests.</Empty>
  return (
    <>
      <ErrorNote>{error}</ErrorNote>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Request</th>
              <th>Reason</th>
              <th>Status</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {requests.map((r) => (
              <tr key={r.id}>
                <td>{formatDay(r.workDate)}</td>
                <td>
                  {KINDS[r.kind]}
                  {r.requestedIn && (
                    <div className="hint">
                      {formatTime(r.requestedIn, timeZone)} to {formatTime(r.requestedOut, timeZone)}
                    </div>
                  )}
                </td>
                <td>{r.reason}</td>
                <td>
                  <StatusBadge status={r.status} />
                  {r.decisionNote && <div className="hint">{r.decisionNote}</div>}
                </td>
                <td className="actions">
                  {r.status === 'pending' && (
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

function MyMonth({ month, today, timeZone }) {
  const days = useLoad(() => attendance.myDays(month))
  if (days.loading) return <p className="muted">Loading…</p>
  if (days.error) return <ErrorNote>{days.error}</ErrorNote>
  return <DaysTable days={days.data} today={today} timeZone={timeZone} />
}

/** The signed-in user's own attendance: punch, this month's days, and requests. */
export default function MyAttendance() {
  const today = useLoad(attendance.today)
  const requests = useLoad(attendance.myRequests)
  const [month, setMonth] = useState(localMonth())
  const [version, setVersion] = useState(0)

  if (today.loading || requests.loading) return <p className="muted">Loading…</p>
  if (today.error || requests.error) return <ErrorNote>{today.error || requests.error}</ErrorNote>
  const timeZone = today.data.timezone

  function refresh() {
    today.reload()
    requests.reload()
    setVersion((v) => v + 1)
  }

  return (
    <>
      <PunchPanel today={today} onPunched={refresh} />

      <div className="toolbar">
        <div>
          <label htmlFor="my-month">Month</label>
          <input id="my-month" type="month" value={month} max={localMonth()} onChange={(e) => setMonth(e.target.value || localMonth())} />
        </div>
      </div>
      <MyMonth key={`${month}-${version}`} month={month} today={today.data.workDate} timeZone={timeZone} />

      {today.data.assigned && (
        <>
          <h2>Requests</h2>
          <RequestForm onCreated={refresh} />
          <MyRequests requests={requests.data} timeZone={timeZone} onChanged={refresh} />
        </>
      )}
    </>
  )
}
