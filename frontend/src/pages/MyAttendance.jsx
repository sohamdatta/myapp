import { useState } from 'react'
import { attendance } from '../api.js'
import { formatDay, formatMonth, formatMinutes, formatTime, localMonth, localToday, useAction, useLoad, useNow, formatLate } from '../hooks.js'
import { Badge, DayStatus, Empty, ErrorNote, StatusBadge, Trail } from '../ui.jsx'

const KINDS = {
  regularization: 'Correct my punches',
  on_duty: 'On duty away from the office',
  work_from_home: 'Work from home',
}

const CHECKS = {
  none: 'You can punch from anywhere',
  ip: 'You must be on the office network',
  location: 'Your browser will ask for your location',
  either: 'Office network, or your location at the office',
}

/** Where nobody punches, the day's result is the headline, in words. */
const PRESENT_DAY = { present: 'Present', absent: 'Absent', half_day: 'Half day' }

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

const SOURCES = { web: 'in the app', import: 'from a device file', device: 'from the attendance device', manual: 'entered by HR', request: 'from a request' }

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
  const now = useNow()
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

  // While punched in, the time since the last punch in counts too, so the figure moves through the day.
  const open = data.nextDirection === 'out' && data.punches.length > 0
  const lastIn = open ? new Date(data.punches[data.punches.length - 1].at).getTime() : 0
  const worked = (data.day?.workedMinutes || 0) + (open ? Math.max(0, Math.floor((now - lastIn) / 60000)) : 0)
  const firstIn = data.punches.find((p) => p.direction === 'in')

  return (
    <section className="panel" style={{ padding: 0 }}>
      <div className="hero">
        <div>
          <div className="hero-label">Today · {formatDay(data.workDate)}</div>
          <div className="hero-value">
            {data.mode === 'present'
              ? PRESENT_DAY[data.day?.status] || ''
              : worked > 0
                ? formatMinutes(worked)
                : '0 h 00 min'}
            {data.day && (data.mode !== 'present' || !PRESENT_DAY[data.day.status]) && <DayStatus day={data.day} isToday />}
          </div>
          <p>
            {data.remote ? 'Working from home' : data.location} · {data.shift}
            {firstIn && ` · In at ${formatTime(firstIn.at, data.timezone)}`}
          </p>
        </div>
        {data.mode === 'present' ? (
          <div className="hero-action hero-note">
            <strong>No punching needed</strong>
            <p className="hint">Each working day counts as present unless your manager or HR marks it otherwise.</p>
          </div>
        ) : data.mode === 'device' && !data.remote ? (
          <div className="hero-action hero-note">
            <strong>Punch at the attendance device</strong>
            <p className="hint">Your punches at {data.location} come from the device by themselves, usually within a minute.</p>
          </div>
        ) : (
          <div className="hero-action">
            <button type="button" className="secondary punch-button" disabled={busy} onClick={punch}>
              {busy ? 'Checking…' : data.nextDirection === 'in' ? 'Punch in' : 'Punch out'}
            </button>
            <p className="hint">{data.remote ? 'Working from home today: no location check' : CHECKS[data.punchCheck]}</p>
          </div>
        )}
      </div>
      {error && (
        <div style={{ padding: '0 24px' }}>
          <ErrorNote>{error}</ErrorNote>
        </div>
      )}
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
    </section>
  )
}

/** A month of days. Each row opens to show how its result was worked out. */
export function DaysTable({ days, today, timeZone }) {
  const [open, setOpen] = useState(null)
  if (days.length === 0) return <Empty>No days to show for this month.</Empty>
  return (
    <div className="table-wrap">
      <table className="day-table">
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
        <td className="day-date">{formatDay(day.workDate)}</td>
        <td className="day-result">
          <DayStatus day={day} isToday={day.workDate === today} />
        </td>
        <td className="day-minor" data-label="In">
          {formatTime(day.firstIn, timeZone)}
        </td>
        <td className="day-minor" data-label="Out">
          {formatTime(day.lastOut, timeZone)}
        </td>
        <td className="day-minor" data-label="Worked">
          {formatMinutes(day.workedMinutes)}
        </td>
        <td className="day-minor" data-label="Late">
          {day.lateMinutes ? <Badge tone="warn">{formatLate(day.lateMinutes)}</Badge> : '—'}
        </td>
        <td className="actions">
          <button type="button" className="quiet" aria-expanded={open} onClick={onToggle}>
            {open ? 'Hide' : 'Why'}
          </button>
        </td>
      </tr>
      {open && (
        <tr className="detail-row">
          <td colSpan={7}>
            <Trail steps={day.trail} inProgress={day.workDate === today} />
          </td>
        </tr>
      )}
    </>
  )
}

/** How many work-from-home days are used against a limit, or nothing when there is no limit. */
function used(count, limit, period) {
  return limit ? `${Number(count)} of ${limit} used this ${period}` : null
}

function RequestForm({ home, onCreated, onCancel }) {
  const [kind, setKind] = useState('regularization')
  const [fromDate, setFromDate] = useState(localToday())
  const [toDate, setToDate] = useState(localToday())
  const [half, setHalf] = useState('')
  const [inTime, setInTime] = useState('09:30')
  const [outTime, setOutTime] = useState('18:30')
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()
  const correction = kind === 'regularization'
  const atHome = kind === 'work_from_home'
  const single = correction || toDate === fromDate
  const limits = [used(home.usedThisWeek, home.limitPerWeek, 'week'), used(home.usedThisMonth, home.limitPerMonth, 'month')].filter(Boolean)

  async function submit(event) {
    event.preventDefault()
    const body = correction
      ? { kind, workDate: fromDate, inTime, outTime, reason }
      : { kind, fromDate, toDate, half: atHome && single && half ? half : null, reason }
    const created = await run(() => attendance.createRequest(body))
    if (created) {
      setReason('')
      onCreated()
    }
  }

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>New request</h2>
      <div className="grid">
        <div>
          <label htmlFor="req-kind">What for</label>
          <select id="req-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            {Object.entries(KINDS)
              .filter(([id]) => id !== 'work_from_home' || home.allowed)
              .map(([id, label]) => (
                <option key={id} value={id}>
                  {label}
                </option>
              ))}
          </select>
        </div>
        <div>
          <label htmlFor="req-date">{correction ? 'Date' : 'From'}</label>
          <input
            id="req-date"
            type="date"
            value={fromDate}
            max={correction ? localToday() : undefined}
            onChange={(e) => {
              setFromDate(e.target.value)
              if (toDate < e.target.value) setToDate(e.target.value)
            }}
          />
        </div>
        {!correction && (
          <div>
            <label htmlFor="req-to">To</label>
            <input id="req-to" type="date" value={toDate} min={fromDate} onChange={(e) => setToDate(e.target.value)} />
          </div>
        )}
        {atHome && (
          <div>
            <label htmlFor="req-half">How much of the day</label>
            <select id="req-half" value={single ? half : ''} disabled={!single} onChange={(e) => setHalf(e.target.value)}>
              <option value="">Whole day</option>
              <option value="first">First half</option>
              <option value="second">Second half</option>
            </select>
          </div>
        )}
        {correction && (
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
      <p className="hint">
        {correction
          ? 'A correction can be for today or the last 31 days. HR decides it; your original punches are kept.'
          : atHome
            ? `One request is made for each working day in the range.${limits.length ? ` ${limits.join(', ')}.` : ''}${
                home.requiresPunch ? ' You still punch in and out on a day at home.' : ''
              }`
            : 'One request is made for each working day in the range, up to 31 days at a time.'}
      </p>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Sending…' : 'Send request'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
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
        <table className="stack">
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
                  {r.half && <div className="hint">{r.half} half</div>}
                  {r.requestedIn && (
                    <div className="hint">
                      {formatTime(r.requestedIn, timeZone)} to {formatTime(r.requestedOut, timeZone)}
                    </div>
                  )}
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

function MyMonth({ month, today, timeZone }) {
  const days = useLoad(() => attendance.myDays(month))
  if (days.loading) return <p className="muted">Loading…</p>
  if (days.error) return <ErrorNote>{days.error}</ErrorNote>
  // Today is left out of the totals until it is over.
  const settled = days.data.filter((d) => d.workDate !== today)
  const count = (status) => settled.filter((d) => d.status === status).length
  return (
    <>
      <div className="tiles">
        <div className="tile">
          <div className="tile-label">Present</div>
          <div className="tile-value">{count('present') + count('on_duty') + count('work_from_home')}</div>
        </div>
        <div className="tile">
          <div className="tile-label">Half days</div>
          <div className="tile-value">{count('half_day')}</div>
        </div>
        <div className="tile">
          <div className="tile-label">Leave</div>
          <div className="tile-value">{settled.reduce((sum, d) => sum + Number(d.leaveDays || 0), 0)}</div>
        </div>
        <div className="tile">
          <div className="tile-label">Absent</div>
          <div className="tile-value">{count('absent')}</div>
        </div>
        <div className="tile">
          <div className="tile-label">Late arrivals</div>
          <div className="tile-value">{days.data.filter((d) => d.lateMinutes > 0).length}</div>
        </div>
      </div>
      <p className="hint">Today is counted once it is over. Present includes days on duty and at home.</p>
      <DaysTable days={days.data} today={today} timeZone={timeZone} />
    </>
  )
}

/** The signed-in user's own attendance: punch, this month's days, and requests. */
export default function MyAttendance() {
  const today = useLoad(attendance.today)
  const requests = useLoad(attendance.myRequests)
  const home = useLoad(attendance.homeWorking)
  const [month, setMonth] = useState(localMonth())
  const [version, setVersion] = useState(0)
  const [requesting, setRequesting] = useState(false)

  if (today.loading || requests.loading || home.loading) return <p className="muted">Loading…</p>
  if (today.error || requests.error || home.error) return <ErrorNote>{today.error || requests.error || home.error}</ErrorNote>
  const timeZone = today.data.timezone

  function refresh() {
    today.reload()
    requests.reload()
    home.reload()
    setVersion((v) => v + 1)
  }

  return (
    <>
      <PunchPanel today={today} onPunched={refresh} />

      <div className="section-head">
        <h2>{formatMonth(month)}</h2>
        <div className="form-actions">
          <label htmlFor="my-month" className="sr-only">
            Month
          </label>
          <input id="my-month" type="month" value={month} max={localMonth()} onChange={(e) => setMonth(e.target.value || localMonth())} />
        </div>
      </div>
      <MyMonth key={`${month}-${version}`} month={month} today={today.data.workDate} timeZone={timeZone} />

      {today.data.assigned && (
        <>
          {home.data.remoteDays.length > 0 && (
            <p className="hint">
              Your regular days at home: {home.data.remoteDays.map((d) => WEEKDAYS[d]).join(', ')}. They need no request.
            </p>
          )}
          <div className="section-head">
            <h2>My requests</h2>
            {!requesting && (
              <button type="button" className="outline" onClick={() => setRequesting(true)}>
                New request
              </button>
            )}
          </div>
          {requesting && (
            <RequestForm
              home={home.data}
              onCreated={() => {
                setRequesting(false)
                refresh()
              }}
              onCancel={() => setRequesting(false)}
            />
          )}
          <MyRequests requests={requests.data} timeZone={timeZone} onChanged={refresh} />
        </>
      )}
    </>
  )
}
