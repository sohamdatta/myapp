import { Fragment, useState } from 'react'
import { attendance } from '../api.js'
import { formatDate, formatDay, localToday, useAction, useLoad, useNow, whoName, numeric, wholeNumber } from '../hooks.js'
import { Badge, Empty, ErrorNote, Who } from '../ui.jsx'

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

const PUNCH_CHECKS = {
  either: 'Office network or location',
  ip: 'Office network only',
  location: 'Location only',
  none: 'No check',
}

const MODES = {
  app: {
    label: 'Punch in the app',
    hint: 'People punch in and out on their phone or computer. The punch check for the location applies.',
  },
  device: {
    label: 'Biometric device',
    hint: 'A registered device at the door sends each punch by itself. The app takes punches only on work-from-home days.',
  },
  present: {
    label: 'Present by default',
    hint: 'Nobody punches. Every working day counts as present unless HR, or the manager, marks it absent or a half day.',
  },
}

const number = (value, what) => numeric(value, what)
const minutes = (hours, what) => {
  const h = numeric(hours, what)
  return h === null ? null : Math.round(h * 60)
}

// ── people: employee codes and who works where ──────────────────────

function CodeCell({ person, canManage, onSaved }) {
  const [code, setCode] = useState(person.employeeCode || '')
  const { busy, error, run } = useAction()
  if (!canManage) return person.employeeCode || '—'

  async function save() {
    if (code.trim() === (person.employeeCode || '')) return
    const done = await run(async () => {
      await attendance.setEmployeeCode(person.membershipId, code)
      return true
    })
    if (done) onSaved()
  }

  return (
    <>
      <input
        className="cell-input"
        aria-label={`Employee code for ${whoName(person)}`}
        value={code}
        disabled={busy}
        onChange={(e) => setCode(e.target.value)}
        onBlur={save}
        onKeyDown={(e) => e.key === 'Enter' && e.target.blur()}
      />
      {error && <div className="hint">{error}</div>}
    </>
  )
}

/** Whether a user may ask to work from home, with their regular days at home if any. */
function HomeCell({ person, canManage, onSaved }) {
  const { busy, error, run } = useAction()

  async function change(event) {
    const allowed = event.target.checked
    const done = await run(async () => {
      await attendance.setHomeWorking(person.membershipId, allowed)
      return true
    })
    if (done) onSaved()
  }

  return (
    <>
      <label className="check">
        <input
          type="checkbox"
          aria-label={`${whoName(person)} may work from home`}
          checked={Boolean(person.wfhAllowed)}
          disabled={!canManage || busy}
          onChange={change}
        />
        {person.wfhAllowed ? 'Can request' : 'Not allowed'}
      </label>
      {person.remoteDays?.length > 0 && (
        <div className="hint">Regular: {person.remoteDays.map((d) => WEEKDAYS[Number(d)].slice(0, 3)).join(', ')}</div>
      )}
      <ErrorNote>{error}</ErrorNote>
    </>
  )
}

/**
 * Who works where, on which shift and weekly offs. Without the Attendance module this is still
 * the work calendar payroll needs (the state, and working days), without the work-from-home parts.
 */
export function People({ canManage, attendanceOn = true }) {
  const people = useLoad(attendance.people)
  const locations = useLoad(attendance.locations)
  const shifts = useLoad(attendance.shifts)
  const [selected, setSelected] = useState([])
  const [locationId, setLocationId] = useState('')
  const [shiftId, setShiftId] = useState('')
  const [offs, setOffs] = useState([0])
  const [remote, setRemote] = useState([])
  const [from, setFrom] = useState(localToday())
  const { busy, error, run } = useAction()

  if (people.loading || locations.loading || shifts.loading) return <p className="muted">Loading…</p>
  if (people.error || locations.error || shifts.error) {
    return <ErrorNote>{people.error || locations.error || shifts.error}</ErrorNote>
  }
  const activeLocations = locations.data.filter((l) => l.active)
  const activeShifts = shifts.data.filter((s) => s.active)

  function toggle(list, value) {
    return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
  }

  async function assign(event) {
    event.preventDefault()
    const done = await run(async () => {
      await attendance.assign({
        membershipIds: selected,
        workLocationId: locationId || null,
        shiftId: shiftId || null,
        weeklyOffs: offs,
        remoteDays: remote.filter((d) => !offs.includes(d)),
        effectiveFrom: from,
      })
      return true
    })
    if (done) {
      setSelected([])
      people.reload()
    }
  }

  return (
    <>
      {canManage && (activeLocations.length === 0 || activeShifts.length === 0) && (
        <div className="notice" role="status">
          <p>Add a work location and a shift first, under Setup. Then assign them to users here.</p>
        </div>
      )}
      {canManage && activeLocations.length > 0 && activeShifts.length > 0 && (
        <form className="panel" onSubmit={assign} noValidate>
          <h2>Assign a location and shift</h2>
          <p className="hint">Tick the users in the table below, then choose what applies to them and from when.</p>
          <div className="grid">
            <div>
              <label htmlFor="as-location">Work location</label>
              <select id="as-location" value={locationId} onChange={(e) => setLocationId(e.target.value)}>
                <option value="">Choose…</option>
                {activeLocations.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="as-shift">Shift</label>
              <select id="as-shift" value={shiftId} onChange={(e) => setShiftId(e.target.value)}>
                <option value="">Choose…</option>
                {activeShifts.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} ({s.startTime} to {s.endTime})
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="as-from">From</label>
              <input id="as-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
          </div>
          <fieldset className="days">
            <legend>Weekly offs</legend>
            {WEEKDAYS.map((name, day) => (
              <label key={name} className="check">
                <input type="checkbox" checked={offs.includes(day)} onChange={() => setOffs(toggle(offs, day))} />
                {name}
              </label>
            ))}
          </fieldset>
          {attendanceOn && (
          <fieldset className="days">
            <legend>Regular days at home (optional)</legend>
            {WEEKDAYS.map((name, day) => (
              <label key={name} className={offs.includes(day) ? 'check check-disabled' : 'check'}>
                <input
                  type="checkbox"
                  disabled={offs.includes(day)}
                  checked={remote.includes(day) && !offs.includes(day)}
                  onChange={() => setRemote(toggle(remote, day))}
                />
                {name}
              </label>
            ))}
          </fieldset>
          )}
          <ErrorNote>{error}</ErrorNote>
          <button type="submit" className="secondary" disabled={busy || selected.length === 0}>
            {busy ? 'Assigning…' : `Assign to ${selected.length} selected`}
          </button>
        </form>
      )}

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              {canManage && <th aria-label="Select" />}
              <th>User</th>
              <th>Employee code</th>
              <th>Work location</th>
              <th>Shift</th>
              <th>Weekly offs</th>
              {attendanceOn && <th>Work from home</th>}
              <th>Since</th>
            </tr>
          </thead>
          <tbody>
            {people.data.map((p) => (
              <tr key={p.membershipId}>
                {canManage && (
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Select ${whoName(p)}`}
                      checked={selected.includes(p.membershipId)}
                      onChange={() => setSelected(toggle(selected, p.membershipId))}
                    />
                  </td>
                )}
                <td>
                  <Who person={p} />
                </td>
                <td>
                  <CodeCell person={p} canManage={canManage} onSaved={people.reload} />
                </td>
                <td>{p.locationName || <Badge tone="warn">not assigned</Badge>}</td>
                <td>{p.shiftName || '—'}</td>
                <td>{p.weeklyOffs ? p.weeklyOffs.map((d) => WEEKDAYS[d].slice(0, 3)).join(', ') || 'None' : '—'}</td>
                {attendanceOn && (
                  <td>
                    <HomeCell person={p} canManage={canManage} onSaved={people.reload} />
                  </td>
                )}
                <td>
                  {p.effectiveFrom ? formatDay(p.effectiveFrom) : '—'}
                  {p.nextChange && <div className="hint">Changes on {formatDay(p.nextChange)}</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">
        The employee code is what a biometric device calls the user; device files are matched on it. An assignment is never
        edited: to correct one, assign again from the same date.
      </p>
    </>
  )
}

// ── locations, shifts and holidays ──────────────────────────────────

const NEW_LOCATION = { name: '', timezone: 'Asia/Kolkata', stateCode: '', latitude: '', longitude: '', radiusM: '', allowedIps: '', punchCheck: 'none', active: true }

function LocationForm({ location, onSaved, onCancel, attendanceOn = true }) {
  const [form, setForm] = useState(
    location
      ? {
          ...location,
          stateCode: location.stateCode || '',
          latitude: location.latitude ?? '',
          longitude: location.longitude ?? '',
          radiusM: location.radiusM ?? '',
          allowedIps: location.allowedIps.join(', '),
        }
      : NEW_LOCATION,
  )
  const { busy, error, run } = useAction()
  const set = (field) => (e) => setForm({ ...form, [field]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })

  async function useMyPosition() {
    if (!navigator.geolocation) return
    navigator.geolocation.getCurrentPosition((p) =>
      setForm((f) => ({ ...f, latitude: p.coords.latitude.toFixed(6), longitude: p.coords.longitude.toFixed(6), radiusM: f.radiusM || 200 })),
    )
  }

  async function submit(event) {
    event.preventDefault()
    const saved = await run(() =>
      attendance.saveLocation(location?.id, {
        name: form.name,
        timezone: form.timezone,
        stateCode: form.stateCode,
        latitude: number(form.latitude, 'Latitude'),
        longitude: number(form.longitude, 'Longitude'),
        radiusM: wholeNumber(form.radiusM, 'The radius'),
        allowedIps: form.allowedIps.split(/[\s,]+/).filter(Boolean),
        punchCheck: form.punchCheck,
        active: form.active,
      }),
    )
    if (saved) onSaved()
  }

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>{location ? `Change ${location.name}` : 'Add a work location'}</h2>
      <div className="grid">
        <div>
          <label htmlFor="loc-name">Name</label>
          <input id="loc-name" value={form.name} onChange={set('name')} />
        </div>
        {attendanceOn && (
        <div>
          <label htmlFor="loc-check">To punch, a user must be on</label>
          <select id="loc-check" value={form.punchCheck} onChange={set('punchCheck')}>
            {Object.entries(PUNCH_CHECKS).map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </div>
        )}
        <div>
          <label htmlFor="loc-tz">Time zone</label>
          <input id="loc-tz" value={form.timezone} onChange={set('timezone')} />
        </div>
        <div>
          <label htmlFor="loc-state">State (optional)</label>
          <input id="loc-state" value={form.stateCode} placeholder="WB" aria-describedby="loc-state-hint" onChange={set('stateCode')} />
          <p id="loc-state-hint" className="hint">
            Two letters, such as WB or MH.{!attendanceOn && ' Payroll takes professional tax and labour welfare fund from it.'}
          </p>
        </div>
      </div>
      {attendanceOn && form.punchCheck === 'none' && <p className="hint">Anyone assigned here can punch from anywhere.</p>}
      {attendanceOn && ['ip', 'either'].includes(form.punchCheck) && (
        <>
          <label htmlFor="loc-ips">Office network addresses</label>
          <input id="loc-ips" value={form.allowedIps} placeholder="203.0.113.0/24, 198.51.100.7" onChange={set('allowedIps')} />
          <p className="hint">The public addresses your office reaches the internet from, separated by commas. Ask whoever runs the office network.</p>
        </>
      )}
      {attendanceOn && ['location', 'either'].includes(form.punchCheck) && (
      <div className="grid">
        <div>
          <label htmlFor="loc-lat">Latitude</label>
          <input id="loc-lat" inputMode="decimal" value={form.latitude} onChange={set('latitude')} />
        </div>
        <div>
          <label htmlFor="loc-lon">Longitude</label>
          <input id="loc-lon" inputMode="decimal" value={form.longitude} onChange={set('longitude')} />
        </div>
        <div>
          <label htmlFor="loc-radius">Radius in metres</label>
          <input id="loc-radius" inputMode="numeric" value={form.radiusM} onChange={set('radiusM')} />
        </div>
      </div>
      )}
      <label className="check">
        <input type="checkbox" checked={form.active} onChange={set('active')} />
        Active (can be assigned to users)
      </label>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Save location'}
        </button>
        {attendanceOn && ['location', 'either'].includes(form.punchCheck) && (
          <button type="button" className="quiet" onClick={useMyPosition}>
            Use where I am now
          </button>
        )}
        {onCancel && (
          <button type="button" className="quiet" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </form>
  )
}

const NEW_SHIFT = { name: '', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayHours: 8, halfDayHours: 4, active: true }

function ShiftForm({ shift, onSaved, onCancel }) {
  const [form, setForm] = useState(
    shift ? { ...shift, fullDayHours: shift.fullDayMinutes / 60, halfDayHours: shift.halfDayMinutes / 60 } : NEW_SHIFT,
  )
  const { busy, error, run } = useAction()
  const set = (field) => (e) => setForm({ ...form, [field]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })

  async function submit(event) {
    event.preventDefault()
    const saved = await run(() =>
      attendance.saveShift(shift?.id, {
        name: form.name,
        startTime: form.startTime,
        endTime: form.endTime,
        graceMinutes: wholeNumber(form.graceMinutes, 'Grace'),
        fullDayMinutes: minutes(form.fullDayHours, 'A full day'),
        halfDayMinutes: minutes(form.halfDayHours, 'A half day'),
        active: form.active,
      }),
    )
    if (saved) onSaved()
  }

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>{shift ? `Change ${shift.name}` : 'Add a shift'}</h2>
      <div className="grid">
        <div>
          <label htmlFor="sh-name">Name</label>
          <input id="sh-name" value={form.name} onChange={set('name')} />
        </div>
        <div>
          <label htmlFor="sh-start">Starts</label>
          <input id="sh-start" type="time" value={form.startTime} onChange={set('startTime')} />
        </div>
        <div>
          <label htmlFor="sh-end">Ends</label>
          <input id="sh-end" type="time" value={form.endTime} onChange={set('endTime')} />
        </div>
        <div>
          <label htmlFor="sh-grace">Grace, minutes</label>
          <input id="sh-grace" inputMode="numeric" value={form.graceMinutes} onChange={set('graceMinutes')} />
        </div>
        <div>
          <label htmlFor="sh-full">Full day, hours</label>
          <input id="sh-full" inputMode="decimal" value={form.fullDayHours} onChange={set('fullDayHours')} />
        </div>
        <div>
          <label htmlFor="sh-half">Half day, hours</label>
          <input id="sh-half" inputMode="decimal" value={form.halfDayHours} onChange={set('halfDayHours')} />
        </div>
      </div>
      <p className="hint">
        An end time at or before the start means the shift ends the next day. Fewer hours than a half day counts as absent.
      </p>
      <label className="check">
        <input type="checkbox" checked={form.active} onChange={set('active')} />
        Active (can be assigned to users)
      </label>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Save shift'}
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

function Holidays({ locations, canManage }) {
  // Next year's holidays are usually set in December, so the list can show last, this or next year.
  const [thisYear] = useState(() => new Date().getFullYear())
  const [year, setYear] = useState(thisYear)
  const holidays = useLoad(() => attendance.holidays(year))
  const [date, setDate] = useState('')
  const [name, setName] = useState('')
  const [locationId, setLocationId] = useState('')
  const { busy, error, run } = useAction()

  async function add(event) {
    event.preventDefault()
    const added = await run(() => attendance.addHoliday({ date, name, workLocationId: locationId || null }))
    if (added) {
      // Show the year the holiday was added to, so it never seems to vanish.
      const addedYear = Number(date.slice(0, 4))
      if (addedYear && addedYear !== year) setYear(addedYear)
      setDate('')
      setName('')
      holidays.reload()
    }
  }

  function showYear(next) {
    setYear(next)
    holidays.reload()
  }

  const years = [...new Set([thisYear - 1, thisYear, thisYear + 1, year])].sort((a, b) => a - b)

  async function remove(id) {
    const done = await run(async () => {
      await attendance.removeHoliday(id)
      return true
    })
    if (done) holidays.reload()
  }

  if (holidays.loading) return <p className="muted">Loading…</p>
  if (holidays.error) return <ErrorNote>{holidays.error}</ErrorNote>

  return (
    <>
      <h2>Holidays in {year}</h2>
      <div className="toolbar">
        <div>
          <label htmlFor="hol-year">Year</label>
          <select id="hol-year" value={year} onChange={(e) => showYear(Number(e.target.value))}>
            {years.map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        </div>
      </div>
      {canManage && (
        <form className="toolbar" onSubmit={add} noValidate>
          <div>
            <label htmlFor="hol-date">Date</label>
            <input id="hol-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </div>
          <div>
            <label htmlFor="hol-name">Name</label>
            <input id="hol-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div>
            <label htmlFor="hol-location">Applies to</label>
            <select id="hol-location" value={locationId} onChange={(e) => setLocationId(e.target.value)}>
              <option value="">Every location</option>
              {locations.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </select>
          </div>
          <button type="submit" className="secondary" disabled={busy}>
            Add holiday
          </button>
        </form>
      )}
      <ErrorNote>{error}</ErrorNote>
      {holidays.data.length === 0 ? (
        <Empty>No holidays yet for {year}.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Holiday</th>
                <th>Applies to</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {holidays.data.map((h) => (
                <tr key={h.id}>
                  <td>{formatDay(h.onDate)}</td>
                  <td>{h.name}</td>
                  <td>{h.locationName || 'Every location'}</td>
                  <td className="actions">
                    {canManage && (
                      <button type="button" className="quiet danger" disabled={busy} onClick={() => remove(h.id)}>
                        Remove
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

/** The Organization's rules for working from home. */
function HomeWorking({ canManage }) {
  const settings = useLoad(attendance.settings)
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState(false)
  const { busy, error, run } = useAction()

  if (settings.loading) return <p className="muted">Loading…</p>
  if (settings.error) return <ErrorNote>{settings.error}</ErrorNote>
  const s = form || {
    wfhEnabled: settings.data.wfhEnabled,
    wfhRequiresPunch: settings.data.wfhRequiresPunch,
    wfhLimitPerWeek: settings.data.wfhLimitPerWeek ?? '',
    wfhLimitPerMonth: settings.data.wfhLimitPerMonth ?? '',
  }
  const set = (patch) => {
    setSaved(false)
    setForm({ ...s, ...patch })
  }

  async function submit(event) {
    event.preventDefault()
    const done = await run(() =>
      attendance.saveSettings({
        leaveYearStartMonth: settings.data.leaveYearStartMonth,
        wfhEnabled: s.wfhEnabled,
        wfhRequiresPunch: s.wfhRequiresPunch,
        wfhLimitPerWeek: wholeNumber(s.wfhLimitPerWeek, 'Days a week'),
        wfhLimitPerMonth: wholeNumber(s.wfhLimitPerMonth, 'Days a month'),
      }),
    )
    if (done) {
      setForm(null)
      setSaved(true)
      settings.reload()
    }
  }

  return (
    <>
      <h2>Work from home</h2>
      <form className="panel" onSubmit={submit} noValidate>
        <div className="days">
          <label className="check">
            <input type="checkbox" checked={s.wfhEnabled} disabled={!canManage} onChange={(e) => set({ wfhEnabled: e.target.checked })} />
            Users can work from home
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={s.wfhRequiresPunch}
              disabled={!canManage || !s.wfhEnabled}
              onChange={(e) => set({ wfhRequiresPunch: e.target.checked })}
            />
            A day at home still needs a punch in and out
          </label>
        </div>
        <div className="grid">
          <div>
            <label htmlFor="wfh-week">Requested days a week, at most</label>
            <input
              id="wfh-week"
              type="number"
              min="1"
              max="7"
              placeholder="No limit"
              value={s.wfhLimitPerWeek}
              disabled={!canManage || !s.wfhEnabled}
              onChange={(e) => set({ wfhLimitPerWeek: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="wfh-month">Requested days a month, at most</label>
            <input
              id="wfh-month"
              type="number"
              min="1"
              max="31"
              placeholder="No limit"
              value={s.wfhLimitPerMonth}
              disabled={!canManage || !s.wfhEnabled}
              onChange={(e) => set({ wfhLimitPerMonth: e.target.value })}
            />
          </div>
        </div>
        <p className="hint">
          Who may ask is set per user under People, where regular days at home are also assigned. Regular days need no request and do not count
          towards these limits. A punch from home is not checked against the office network or position, and no position is stored.
        </p>
        <ErrorNote>{error}</ErrorNote>
        {saved && (
          <p className="hint" role="status">
            Saved.
          </p>
        )}
        {canManage && (
          <button type="submit" className="secondary" disabled={busy}>
            {busy ? 'Saving…' : 'Save work-from-home rules'}
          </button>
        )}
      </form>
    </>
  )
}

/** How a location records attendance, from a date. Days before the date keep the mode they had. */
function ModeForm({ location, earliest, onSaved, onCancel }) {
  const [mode, setMode] = useState(location.mode === 'present' ? 'app' : location.mode === 'app' ? 'present' : 'app')
  const [from, setFrom] = useState(earliest && earliest > localToday() ? earliest : localToday())
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    const done = await run(() => attendance.setMode(location.id, { mode, effectiveFrom: from }))
    if (done) onSaved()
  }

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>How {location.name} records attendance</h2>
      <fieldset className="choice-list">
        <legend className="sr-only">Attendance mode</legend>
        {Object.entries(MODES).map(([key, m]) => (
          <label key={key} className={mode === key ? 'choice choice-current' : 'choice'}>
            <input type="radio" name="attendance-mode" value={key} checked={mode === key} onChange={() => setMode(key)} />
            <span>
              <span>
                <strong>{m.label}</strong> {key === location.mode && <Badge>now</Badge>}
              </span>
              <span className="hint">{m.hint}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <div className="grid">
        <div>
          <label htmlFor="mode-from">Starting on</label>
          <input id="mode-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
      </div>
      <p className="hint">
        Days before this date keep the mode they had. The date cannot fall in a month whose payroll is already approved
        {earliest && earliest > localToday() ? `, so the earliest is ${formatDay(earliest)}` : ''}.
      </p>
      {mode === 'device' && location.devices === 0 && (
        <p className="hint">Next, register the device under the Devices tab, so its punches are taken.</p>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Change how attendance is recorded'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

export function Setup({ canManage, attendanceOn = true }) {
  const locations = useLoad(attendance.locations)
  const shifts = useLoad(attendance.shifts)
  const settings = useLoad(attendance.settings)
  const [editing, setEditing] = useState(null) // { kind: 'location' | 'shift' | 'mode', item } or null

  if (locations.loading || shifts.loading) return <p className="muted">Loading…</p>
  if (locations.error || shifts.error) return <ErrorNote>{locations.error || shifts.error}</ErrorNote>

  function saved() {
    setEditing(null)
    locations.reload()
    shifts.reload()
  }

  const ready = locations.data.some((l) => l.active) && shifts.data.some((s) => s.active)
  return (
    <>
      {canManage && ready && (
        <p className="notice" role="status">
          Next: assign a location and shift to each user under <strong>People</strong>, above.
          {attendanceOn ? ' Punching starts once they have one.' : ' Payroll takes the state and working days from it.'}
        </p>
      )}
      <div className="section-head">
        <h2>Work locations</h2>
        {canManage && !editing && locations.data.length > 0 && (
          <button type="button" className="outline" onClick={() => setEditing({ kind: 'location', item: null })}>
            Add a work location
          </button>
        )}
      </div>
      {locations.data.length === 0 ? (
        <Empty>No work locations yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Location</th>
                {attendanceOn && <th>Attendance</th>}
                {attendanceOn && <th>Punch check</th>}
                {attendanceOn && <th>Office network</th>}
                {attendanceOn && <th>Position</th>}
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {locations.data.map((l) => (
                <tr key={l.id}>
                  <td>
                    {l.name} {!l.active && <Badge>inactive</Badge>}
                    <div className="hint">
                      {l.timezone}
                      {l.stateCode && ` · ${l.stateCode}`}
                    </div>
                  </td>
                  {attendanceOn && (
                  <td>
                    {MODES[l.mode].label}
                    {l.mode === 'device' && <div className="hint">{l.devices === 1 ? '1 device' : `${l.devices} devices`}</div>}
                    {l.nextMode && (
                      <div className="hint">
                        {MODES[l.nextMode].label} from {formatDay(l.nextModeFrom)}
                      </div>
                    )}
                  </td>
                  )}
                  {attendanceOn && <td>{l.mode === 'app' ? PUNCH_CHECKS[l.punchCheck] : '—'}</td>}
                  {attendanceOn && <td>{l.allowedIps.length ? l.allowedIps.join(', ') : '—'}</td>}
                  {attendanceOn && <td>{l.latitude !== null ? `${l.latitude}, ${l.longitude} within ${l.radiusM} m` : '—'}</td>}
                  <td className="actions">
                    {canManage && (
                      <>
                        {attendanceOn && (
                          <button type="button" className="quiet" onClick={() => setEditing({ kind: 'mode', item: l })}>
                            Attendance mode
                          </button>
                        )}
                        <button type="button" className="quiet" onClick={() => setEditing({ kind: 'location', item: l })}>
                          Change
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {canManage && editing?.kind === 'location' && (
        <LocationForm
          key={editing.item?.id || 'new'}
          location={editing.item}
          attendanceOn={attendanceOn}
          onSaved={saved}
          onCancel={() => setEditing(null)}
        />
      )}
      {canManage && editing?.kind === 'mode' && (
        <ModeForm
          key={editing.item.id}
          location={editing.item}
          earliest={settings.data?.modeFrom}
          onSaved={saved}
          onCancel={() => setEditing(null)}
        />
      )}
      {canManage && !editing && locations.data.length === 0 && <LocationForm key="first-location" attendanceOn={attendanceOn} onSaved={saved} />}

      <div className="section-head">
        <h2>Shifts</h2>
        {canManage && !editing && shifts.data.length > 0 && (
          <button type="button" className="outline" onClick={() => setEditing({ kind: 'shift', item: null })}>
            Add a shift
          </button>
        )}
      </div>
      {shifts.data.length === 0 ? (
        <Empty>No shifts yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Shift</th>
                <th>Hours</th>
                <th>Grace</th>
                <th>Full day / half day</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {shifts.data.map((s) => (
                <tr key={s.id}>
                  <td>
                    {s.name} {!s.active && <Badge>inactive</Badge>}
                  </td>
                  <td>
                    {s.startTime} to {s.endTime}
                    {s.endTime <= s.startTime && <div className="hint">ends the next day</div>}
                  </td>
                  <td>{s.graceMinutes} min</td>
                  <td>
                    {s.fullDayMinutes / 60} h / {s.halfDayMinutes / 60} h
                  </td>
                  <td className="actions">
                    {canManage && (
                      <button type="button" className="quiet" onClick={() => setEditing({ kind: 'shift', item: s })}>
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
      {canManage && editing?.kind === 'shift' && (
        <ShiftForm key={editing.item?.id || 'new'} shift={editing.item} onSaved={saved} onCancel={() => setEditing(null)} />
      )}
      {canManage && !editing && shifts.data.length === 0 && <ShiftForm key="first-shift" onSaved={saved} />}

      <Holidays locations={locations.data} canManage={canManage} />
      {attendanceOn && <HomeWorking canManage={canManage} />}
    </>
  )
}

// ── device import ───────────────────────────────────────────────────

function ColumnSelect({ id, label, columns, value, onChange, optional }) {
  return (
    <div>
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value ?? ''} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}>
        {optional ? <option value="">{optional}</option> : <option value="">Choose…</option>}
        {columns.map((c, index) => (
          <option key={index} value={index}>
            {c}
          </option>
        ))}
      </select>
    </div>
  )
}

const OUTCOMES = {
  imported: 'Taken',
  duplicate: 'Already here',
  unknown_code: 'Nobody has this employee code',
  in_the_future: 'In the future: check the device clock',
  unreadable: 'Could not be read',
}

function DeviceLog({ device }) {
  const log = useLoad(() => attendance.deviceLog(device.id))
  if (log.loading) return <p className="muted">Loading…</p>
  if (log.error) return <ErrorNote>{log.error}</ErrorNote>
  if (log.data.length === 0) return <p className="hint">Nothing received yet.</p>
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Received</th>
            <th>User id</th>
            <th>Punch</th>
            <th>What happened</th>
          </tr>
        </thead>
        <tbody>
          {log.data.map((l, index) => (
            <tr key={index}>
              <td>{formatDate(l.receivedAt)}</td>
              <td>{l.employeeCode || '—'}</td>
              <td>{l.punchedAt ? formatDate(l.punchedAt) : <span className="hint">{l.line}</span>}</td>
              <td>
                {l.outcome === 'imported' ? <Badge tone="good">{OUTCOMES[l.outcome]}</Badge> : l.outcome === 'duplicate' ? OUTCOMES[l.outcome] : <Badge tone="warn">{OUTCOMES[l.outcome]}</Badge>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** What needs looking at on a device: never heard from, silent, turned away, or sending codes nobody has. */
function DeviceProblems({ device }) {
  const now = useNow()
  const notes = []
  const day = 24 * 60 * 60 * 1000
  if (!device.lastSeenAt) notes.push(<Badge key="never" tone="warn">not heard from yet</Badge>)
  else if (now - new Date(device.lastSeenAt).getTime() > day) notes.push(<Badge key="silent" tone="warn">silent for over a day</Badge>)
  if (device.lastRefusedAt && (!device.lastSeenAt || device.lastRefusedAt > device.lastSeenAt)) {
    notes.push(
      <div key="refused">
        <Badge tone="warn">turned away</Badge> <span className="hint">{device.lastRefused}</span>
      </div>,
    )
  }
  if (device.unknownCodes.length > 0) {
    notes.push(
      <div key="codes" className="hint">
        User ids nobody has: {device.unknownCodes.map((u) => `${u.code} (${u.lines})`).join(', ')}
      </div>,
    )
  }
  return notes.length ? notes : '—'
}

const NEW_DEVICE = { name: '', serialNumber: '', workLocationId: '' }

/** Devices that push each punch as it happens (eSSL / ZKTeco cloud push, "ADMS"). */
function PushDevices() {
  const devices = useLoad(attendance.devices)
  const locations = useLoad(attendance.locations)
  const [form, setForm] = useState(NEW_DEVICE)
  const [registered, setRegistered] = useState(null)
  const [logFor, setLogFor] = useState(null)
  const { busy, error, run } = useAction()
  const toggle = useAction()
  const host = window.location.hostname

  async function register(event) {
    event.preventDefault()
    const done = await run(() => attendance.saveDevice(null, form))
    if (done) {
      setRegistered(form.name.trim())
      setForm(NEW_DEVICE)
      devices.reload()
    }
  }

  async function switchOver(d) {
    const done = await toggle.run(() => attendance.saveDevice(d.id, { name: d.name, workLocationId: d.workLocationId, active: !d.active }))
    if (done) devices.reload()
  }

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value })
  const active = (locations.data || []).filter((l) => l.active)

  return (
    <>
      <h2>Devices that send punches</h2>
      <p className="hint">
        An eSSL or ZKTeco device with cloud push (ADMS) sends each punch here as it happens, so nobody uploads files. Punches are
        matched to people by employee code.
      </p>
      {devices.loading && <p className="muted">Loading…</p>}
      <ErrorNote>{devices.error || toggle.error}</ErrorNote>
      {devices.data &&
        (devices.data.length === 0 ? (
          <Empty>No devices registered yet.</Empty>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Device</th>
                  <th>Location</th>
                  <th>Last heard from</th>
                  <th>Punches, last day</th>
                  <th>Needs a look</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {devices.data.map((d) => (
                  <Fragment key={d.id}>
                    <tr>
                      <td>
                        {d.name} {!d.active && <Badge>switched off</Badge>}
                        <div className="hint">{d.serialNumber}</div>
                      </td>
                      <td>
                        {d.location}
                        {!d.networkLocked && <div className="hint">any network</div>}
                      </td>
                      <td>
                        {d.lastSeenAt ? formatDate(d.lastSeenAt) : '—'}
                        {d.lastIp && <div className="hint">from {d.lastIp}</div>}
                      </td>
                      <td>{d.importedLastDay}</td>
                      <td>
                        <DeviceProblems device={d} />
                      </td>
                      <td className="actions">
                        <button type="button" className="quiet" aria-expanded={logFor === d.id} onClick={() => setLogFor(logFor === d.id ? null : d.id)}>
                          {logFor === d.id ? 'Hide log' : 'Log'}
                        </button>
                        <button type="button" className="quiet" disabled={toggle.busy} onClick={() => switchOver(d)}>
                          {d.active ? 'Switch off' : 'Switch on'}
                        </button>
                      </td>
                    </tr>
                    {logFor === d.id && (
                      <tr className="detail-row">
                        <td colSpan={6}>
                          <DeviceLog device={d} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      {registered && (
        <p className="notice" role="status">
          {registered} is registered. Set it up as below; its first call shows under Last heard from.
        </p>
      )}
      <form className="panel" onSubmit={register} noValidate>
        <h2>Register a device</h2>
        <div className="grid">
          <div>
            <label htmlFor="dev-name">Name</label>
            <input id="dev-name" value={form.name} placeholder="Main gate" onChange={set('name')} />
          </div>
          <div>
            <label htmlFor="dev-serial">Serial number</label>
            <input id="dev-serial" value={form.serialNumber} placeholder="CQZ7232060123" onChange={set('serialNumber')} />
          </div>
          <div>
            <label htmlFor="dev-location">Work location</label>
            <select id="dev-location" value={form.workLocationId} onChange={set('workLocationId')}>
              <option value="">Choose…</option>
              {active.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </select>
          </div>
        </div>
        <ErrorNote>{error}</ErrorNote>
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Registering…' : 'Register device'}
        </button>
        <details className="setup-steps">
          <summary>How to connect a device</summary>
          <ol>
            <li>
              Register it here with its serial number. The device shows it under <strong>System info</strong> (or{' '}
              <strong>Device info</strong>); it is also on the label at the back.
            </li>
            <li>
              On the device, open the cloud server settings: usually <strong>Comm.</strong> → <strong>Cloud Server Setting</strong>,
              sometimes called <strong>ADMS</strong>. Turn on <strong>Enable domain name</strong> and set the server address to{' '}
              <code>{host}</code>.
            </li>
            <li>
              Set the port to <code>443</code> with HTTPS on, if the device offers HTTPS. Otherwise use port <code>8081</code> with
              HTTPS off.
            </li>
            <li>
              Make each person's user ID on the device the same as their employee code, under the People tab here.
            </li>
            <li>
              Under Setup, set the location's attendance mode to <strong>Biometric device</strong>. If the location lists office
              network addresses, the device is heard only from those.
            </li>
          </ol>
        </details>
      </form>
    </>
  )
}

export function DeviceImport({ canManage }) {
  const health = useLoad(attendance.importHealth)
  const [deviceLabel, setDeviceLabel] = useState('')
  const [file, setFile] = useState(null) // { name, content }
  const [preview, setPreview] = useState(null)
  const [mapping, setMapping] = useState(null)
  const [result, setResult] = useState(null)
  const { busy, error, run } = useAction()

  async function choose(event) {
    const chosen = event.target.files[0]
    setPreview(null)
    setResult(null)
    if (!chosen) return
    const content = await chosen.text()
    event.target.value = '' // so that choosing the same file again is noticed
    setFile({ name: chosen.name, content })
    const shown = await run(() => attendance.previewImport({ deviceLabel, content }))
    if (shown) {
      setPreview(shown)
      setMapping(shown.mapping)
    }
  }

  async function upload(event) {
    event.preventDefault()
    const done = await run(() => attendance.importFile({ deviceLabel, fileName: file.name, content: file.content, mapping }))
    if (done) {
      setResult(done)
      setPreview(null)
      setFile(null)
      health.reload()
    }
  }

  const setField = (field) => (value) => setMapping({ ...mapping, [field]: value })

  return (
    <>
      {canManage && <PushDevices />}
      {canManage && (
        <form className="panel" onSubmit={upload} noValidate>
          <h2>Upload a device file</h2>
          <p className="hint">
            Export the attendance log from the biometric device as a CSV or text file. Rows are matched to users by employee
            code, and a punch that is already here is skipped, so uploading the same file twice does no harm.
          </p>
          <div className="grid">
            <div>
              <label htmlFor="imp-device">Device name</label>
              <input id="imp-device" value={deviceLabel} placeholder="Main gate" onChange={(e) => setDeviceLabel(e.target.value)} />
            </div>
            <div>
              <label htmlFor="imp-file">File</label>
              <input id="imp-file" type="file" accept=".csv,.txt,.dat,.tsv,text/*" disabled={!deviceLabel.trim()} onChange={choose} />
            </div>
          </div>
          {!deviceLabel.trim() && <p className="hint">Name the device first, so its column settings can be remembered.</p>}
          {preview && mapping && (
            <>
              <p>
                <strong>{file.name}</strong>: {preview.rows} {preview.rows === 1 ? 'row' : 'rows'}.{' '}
                {preview.mappingSaved
                  ? 'The columns below are as you set them last time for this device.'
                  : 'Check which column holds what; this is remembered for the device.'}
              </p>
              <div className="table-wrap sample">
                <table>
                  <thead>
                    <tr>
                      {preview.columns.map((c, index) => (
                        <th key={index}>{c}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {preview.sample.map((row, r) => (
                      <tr key={r}>
                        {preview.columns.map((_, index) => (
                          <td key={index}>{row[index] ?? ''}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="grid" style={{ marginTop: 16 }}>
                <ColumnSelect id="map-code" label="Employee code" columns={preview.columns} value={mapping.codeColumn} onChange={setField('codeColumn')} />
                <ColumnSelect id="map-when" label="Date and time (or date)" columns={preview.columns} value={mapping.dateTimeColumn} onChange={setField('dateTimeColumn')} />
                <ColumnSelect id="map-time" label="Time, if separate" columns={preview.columns} value={mapping.timeColumn} onChange={setField('timeColumn')} optional="Same column as the date" />
                <ColumnSelect id="map-direction" label="In or out" columns={preview.columns} value={mapping.directionColumn} onChange={setField('directionColumn')} optional="Not in the file: alternate in, out" />
                <div>
                  <label htmlFor="map-format">Date and time format</label>
                  <select id="map-format" value={mapping.format ?? ''} onChange={(e) => setField('format')(e.target.value || null)}>
                    <option value="">Guess (day first: 13/10)</option>
                    {preview.formats.map((f) => (
                      <option key={f} value={f}>
                        {f}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="map-zone">Time zone of the device</label>
                  <input id="map-zone" value={mapping.timezone ?? 'Asia/Kolkata'} onChange={(e) => setField('timezone')(e.target.value)} />
                </div>
              </div>
            </>
          )}
          <ErrorNote>{error}</ErrorNote>
          {preview && (
            <button type="submit" className="secondary" disabled={busy}>
              {busy ? 'Importing…' : `Import ${preview.rows} ${preview.rows === 1 ? 'row' : 'rows'}`}
            </button>
          )}
        </form>
      )}
      {result && (
        <div className="notice" role="status">
          <p>
            <strong>
              {result.rowsImported} of {result.rowsTotal} rows imported.
            </strong>{' '}
            {result.rowsDuplicate} were already here and {result.rowsRejected} could not be used
            {result.rowsRejected > 0 ? '; the reasons are listed against the file below.' : '.'}
          </p>
        </div>
      )}

      {health.loading && <p className="muted">Loading…</p>}
      <ErrorNote>{health.error}</ErrorNote>
      {health.data && (
        <>
          <h2>Device files</h2>
          {health.data.devices.length === 0 ? (
            <Empty>No device files have been uploaded yet.</Empty>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Device</th>
                    <th>Last upload</th>
                    <th>Latest punch</th>
                    <th>Files</th>
                    <th>Rows imported</th>
                    <th>Rows rejected</th>
                  </tr>
                </thead>
                <tbody>
                  {health.data.devices.map((d) => (
                    <tr key={d.deviceLabel}>
                      <td>{d.deviceLabel}</td>
                      <td>{formatDate(d.lastUpload)}</td>
                      <td>
                        {formatDate(d.lastPunch)}{' '}
                        {d.daysSinceLastPunch > 2 && <Badge tone="warn">{d.daysSinceLastPunch} days behind</Badge>}
                      </td>
                      <td>{d.files}</td>
                      <td>{d.rowsImported}</td>
                      <td>{d.rowsRejected}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {health.data.batches.length > 0 && (
            <>
              <h2>Recent files</h2>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>File</th>
                      <th>Uploaded</th>
                      <th>Imported</th>
                      <th>Already here</th>
                      <th>Rejected</th>
                    </tr>
                  </thead>
                  <tbody>
                    {health.data.batches.map((b) => (
                      <tr key={b.id}>
                        <td>
                          {b.fileName}
                          <div className="hint">{b.deviceLabel}</div>
                        </td>
                        <td>
                          {formatDate(b.uploadedAt)}
                          <div className="hint">{b.uploadedBy || 'platform support'}</div>
                        </td>
                        <td>
                          {b.rowsImported} of {b.rowsTotal}
                        </td>
                        <td>{b.rowsDuplicate}</td>
                        <td>
                          {b.rowsRejected}
                          {b.rejected.length > 0 && (
                            <details>
                              <summary className="hint">Why</summary>
                              <ul className="hint">
                                {b.rejected.map((reason) => (
                                  <li key={reason}>{reason}</li>
                                ))}
                              </ul>
                              {b.rowsRejected > b.rejected.length && <p className="hint">Only the first {b.rejected.length} are kept.</p>}
                            </details>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </>
      )}
    </>
  )
}
