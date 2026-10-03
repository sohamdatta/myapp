import { useState } from 'react'
import { attendance } from '../api.js'
import { formatDate, formatDay, localToday, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

const PUNCH_CHECKS = {
  either: 'Office network or location',
  ip: 'Office network only',
  location: 'Location only',
  none: 'No check',
}

const number = (value) => (value === '' || value === null || value === undefined ? null : Number(value))

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
        aria-label={`Employee code for ${person.email}`}
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

export function People({ canManage }) {
  const people = useLoad(attendance.people)
  const locations = useLoad(attendance.locations)
  const shifts = useLoad(attendance.shifts)
  const [selected, setSelected] = useState([])
  const [locationId, setLocationId] = useState('')
  const [shiftId, setShiftId] = useState('')
  const [offs, setOffs] = useState([0])
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
          <p>Add a work location and a shift first, under “Locations, shifts and holidays”. Then assign them to users here.</p>
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
                      aria-label={`Select ${p.email}`}
                      checked={selected.includes(p.membershipId)}
                      onChange={() => setSelected(toggle(selected, p.membershipId))}
                    />
                  </td>
                )}
                <td>{p.email}</td>
                <td>
                  <CodeCell person={p} canManage={canManage} onSaved={people.reload} />
                </td>
                <td>{p.locationName || <Badge tone="warn">not assigned</Badge>}</td>
                <td>{p.shiftName || '—'}</td>
                <td>{p.weeklyOffs ? p.weeklyOffs.map((d) => WEEKDAYS[d].slice(0, 3)).join(', ') || 'None' : '—'}</td>
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

function LocationForm({ location, onSaved, onCancel }) {
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
        latitude: number(form.latitude),
        longitude: number(form.longitude),
        radiusM: number(form.radiusM),
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
        <div>
          <label htmlFor="loc-tz">Time zone</label>
          <input id="loc-tz" value={form.timezone} onChange={set('timezone')} />
        </div>
        <div>
          <label htmlFor="loc-state">State code</label>
          <input id="loc-state" value={form.stateCode} placeholder="WB" onChange={set('stateCode')} />
        </div>
      </div>
      <label htmlFor="loc-ips">Office network addresses</label>
      <input id="loc-ips" value={form.allowedIps} placeholder="203.0.113.0/24, 198.51.100.7" onChange={set('allowedIps')} />
      <p className="hint">The public addresses your office reaches the internet from, separated by commas. Ask whoever runs the office network.</p>
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
      <label className="check">
        <input type="checkbox" checked={form.active} onChange={set('active')} />
        Active (can be assigned to users)
      </label>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Save location'}
        </button>
        <button type="button" className="quiet" onClick={useMyPosition}>
          Use where I am now
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
        graceMinutes: number(form.graceMinutes),
        fullDayMinutes: Math.round(Number(form.fullDayHours) * 60),
        halfDayMinutes: Math.round(Number(form.halfDayHours) * 60),
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
  const [year] = useState(() => new Date().getFullYear())
  const holidays = useLoad(() => attendance.holidays(year))
  const [date, setDate] = useState('')
  const [name, setName] = useState('')
  const [locationId, setLocationId] = useState('')
  const { busy, error, run } = useAction()

  async function add(event) {
    event.preventDefault()
    const added = await run(() => attendance.addHoliday({ date, name, workLocationId: locationId || null }))
    if (added) {
      setDate('')
      setName('')
      holidays.reload()
    }
  }

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

export function Setup({ canManage }) {
  const locations = useLoad(attendance.locations)
  const shifts = useLoad(attendance.shifts)
  const [editing, setEditing] = useState(null) // { kind: 'location' | 'shift', item } or null

  if (locations.loading || shifts.loading) return <p className="muted">Loading…</p>
  if (locations.error || shifts.error) return <ErrorNote>{locations.error || shifts.error}</ErrorNote>

  function saved() {
    setEditing(null)
    locations.reload()
    shifts.reload()
  }

  return (
    <>
      <h2>Work locations</h2>
      {locations.data.length === 0 ? (
        <Empty>No work locations yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Location</th>
                <th>Punch check</th>
                <th>Office network</th>
                <th>Position</th>
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
                  <td>{PUNCH_CHECKS[l.punchCheck]}</td>
                  <td>{l.allowedIps.length ? l.allowedIps.join(', ') : '—'}</td>
                  <td>{l.latitude !== null ? `${l.latitude}, ${l.longitude} within ${l.radiusM} m` : '—'}</td>
                  <td className="actions">
                    {canManage && (
                      <button type="button" className="quiet" onClick={() => setEditing({ kind: 'location', item: l })}>
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
      {canManage && editing?.kind === 'location' && (
        <LocationForm key={editing.item.id} location={editing.item} onSaved={saved} onCancel={() => setEditing(null)} />
      )}
      {canManage && !editing && <LocationForm key={`new-location-${locations.data.length}`} onSaved={saved} />}

      <h2>Shifts</h2>
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
        <ShiftForm key={editing.item.id} shift={editing.item} onSaved={saved} onCancel={() => setEditing(null)} />
      )}
      {canManage && !editing && <ShiftForm key={`new-shift-${shifts.data.length}`} onSaved={saved} />}

      <Holidays locations={locations.data} canManage={canManage} />
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
                <strong>{file.name}</strong>: {preview.rows} rows.{' '}
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
                    <option value="">Work it out (day before month)</option>
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
              {busy ? 'Importing…' : `Import ${preview.rows} rows`}
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
          <h2>Devices</h2>
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
