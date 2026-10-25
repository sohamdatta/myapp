import { useState } from 'react'
import { performance } from '../api.js'
import { formatDate, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'

/**
 * A cycle's calibration, for HR: the spread of ratings against the guide, the sessions, and one
 * session's reviews, where a rating is changed with a reason.
 */
export default function CalibrationPanel({ cycle }) {
  const data = useLoad(() => performance.calibration(cycle.id))
  const [open, setOpen] = useState(null)
  if (open) return <SessionPage id={open} onBack={() => (setOpen(null), data.reload())} />
  if (data.loading) return <p className="muted">Loading…</p>
  if (data.error) return <ErrorNote>{data.error}</ErrorNote>
  const c = data.data
  return (
    <div data-testid="calibration">
      <section className="panel">
        <h3>The spread of ratings</h3>
        <p className="hint">
          Every written review in the cycle, at its rating now, beside the share the guide expects. The guide is a starting point for the
          discussion, not a quota. Change it under Setup.
        </p>
        <Distribution rows={c.distribution} />
        {c.stillWriting > 0 && (
          <p className="notice">
            {c.stillWriting} {c.stillWriting === 1 ? 'review is' : 'reviews are'} still being written. A session takes them in once they are
            written: open the session again and choose “Take in new reviews”.
          </p>
        )}
      </section>
      <section className="panel">
        <div className="section-head">
          <h3>Sessions</h3>
        </div>
        {c.sessions.length === 0 && <Empty>No session yet. Open one for a department, or one for everyone.</Empty>}
        {c.sessions.length > 0 && (
          <ul className="bullet-list">
            {c.sessions.map((s) => (
              <li key={s.id} data-session={s.name}>
                <button type="button" className="text-link" onClick={() => setOpen(s.id)}>
                  {s.name}
                </button>{' '}
                <Badge tone={s.status === 'open' ? 'warn' : 'good'}>{s.status === 'open' ? 'Open' : 'Closed'}</Badge>{' '}
                <span className="hint">
                  {s.department || 'Everyone'} · {s.people} {s.people === 1 ? 'person' : 'people'} · {s.changed} changed
                </span>
              </li>
            ))}
          </ul>
        )}
        {c.unassigned > 0 && (
          <p className="hint" data-testid="unassigned">
            {c.unassigned} written {c.unassigned === 1 ? 'review is' : 'reviews are'} not in a session. Released without one, a review keeps its
            manager's rating.
          </p>
        )}
        {c.canChange && <OpenSession cycle={cycle} departments={c.departments} onOpened={(s) => setOpen(s.id)} />}
      </section>
    </div>
  )
}

function OpenSession({ cycle, departments, onOpened }) {
  const [name, setName] = useState('')
  const [department, setDepartment] = useState('')
  const { busy, error, run } = useAction()
  return (
    <form
      className="inline-form"
      onSubmit={async (e) => {
        e.preventDefault()
        const s = await run(() => performance.openSession(cycle.id, { name, departmentId: department || null }))
        if (s) onOpened(s)
      }}
    >
      <div>
        <label htmlFor="session-name">Session name</label>
        <input id="session-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Engineering calibration" />
      </div>
      <div>
        <label htmlFor="session-department">Reviews from</label>
        <select id="session-department" value={department} onChange={(e) => setDepartment(e.target.value)}>
          <option value="">Everyone not in a session yet</option>
          {departments.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </select>
      </div>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Open a session
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </form>
  )
}

/** Each rating: how many have it now, against the guide; a bar for each. */
export function Distribution({ rows }) {
  const total = rows.reduce((n, r) => n + r.count, 0)
  if (total === 0) return <Empty>No review has been written yet.</Empty>
  return (
    <div className="table-wrap">
      <table className="distribution" data-testid="distribution">
        <thead>
          <tr>
            <th>Rating</th>
            <th className="num">Now</th>
            <th className="num">Managers gave</th>
            <th className="num">Guide</th>
            <th aria-hidden="true" />
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const over = r.count > Number(r.guideCount)
            return (
              <tr key={r.value} data-level={r.value}>
                <td>
                  {r.value} · {r.label}
                </td>
                <td className="num">
                  {r.count} <span className="hint">({r.pct}%)</span>
                </td>
                <td className="num">{r.managerCount}</td>
                <td className="num">
                  {r.guideCount} <span className="hint">({Number(r.guidePct)}%)</span>
                </td>
                <td aria-hidden="true" className="bar-cell">
                  <span className={over ? 'bar bar-over' : 'bar'} style={{ width: `${r.pct}%` }} />
                  <span className="bar-guide" style={{ left: `${Math.min(100, Number(r.guidePct))}%` }} />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function SessionPage({ id, onBack }) {
  const loaded = useLoad(() => performance.session(id))
  const [view, setView] = useState(null)
  const [notes, setNotes] = useState(null)
  const [closing, setClosing] = useState(false)
  const { busy, error, run } = useAction()
  const s = view || loaded.data
  if (loaded.loading && !s) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  const act = async (action, body) => {
    const next = await run(() => performance.sessionAction(s.id, action, body))
    if (next) {
      setView(next)
      setClosing(false)
    }
    return next
  }
  return (
    <div data-testid="calibration-session">
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← Calibration
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            {s.name} <Badge tone={s.status === 'open' ? 'warn' : 'good'}>{s.status === 'open' ? 'Open' : 'Closed'}</Badge>
          </h2>
          <p className="muted">
            {s.cycle.name} · {s.department || 'Everyone'} · opened by {s.openedBy}
            {s.closedAt && `, closed ${formatDate(s.closedAt)}`}
          </p>
        </div>
        {s.canChange && (
          <div className="form-actions">
            <button type="button" className="secondary" disabled={busy} onClick={() => act('refresh')}>
              Take in new reviews
            </button>
            {!closing && (
              <button type="button" className="primary" disabled={busy} onClick={() => setClosing(true)}>
                Close the session
              </button>
            )}
          </div>
        )}
      </div>
      {closing && (
        <p className="notice">
          Closing makes these {s.reviews.length} reviews ready to release at the ratings shown. Ratings cannot change afterwards.{' '}
          <button type="button" className="primary" disabled={busy} onClick={() => act('close')}>
            Close it
          </button>{' '}
          <button type="button" className="quiet" onClick={() => setClosing(false)}>
            Not yet
          </button>
        </p>
      )}
      {view?.taken !== undefined && <p className="notice">{view.taken === 0 ? 'No new reviews to take in.' : `Took in ${view.taken}.`}</p>}
      <ErrorNote>{error}</ErrorNote>
      <section className="panel">
        <h3>Spread in this session</h3>
        <Distribution rows={s.distribution} />
      </section>
      <div className="table-wrap">
        <table className="stack">
          <thead>
            <tr>
              <th>Name</th>
              <th>Manager</th>
              <th>Manager's rating</th>
              <th>Rating now</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {s.reviews.map((r) => (
              <SessionRow key={r.id} r={r} session={s} onChanged={setView} />
            ))}
          </tbody>
        </table>
      </div>
      <section className="panel">
        <h3>Notes</h3>
        <p className="hint">What the group discussed, for HR. People and managers do not see these.</p>
        <textarea
          id="session-notes"
          aria-label="Session notes"
          rows={4}
          value={notes ?? s.notes ?? ''}
          disabled={!s.canChange}
          onChange={(e) => setNotes(e.target.value)}
        />
        {s.canChange && (
          <div className="form-actions">
            <button type="button" className="secondary" disabled={busy || notes === null} onClick={async () => (await act('notes', { notes })) && setNotes(null)}>
              Save notes
            </button>
          </div>
        )}
      </section>
    </div>
  )
}

function SessionRow({ r, session, onChanged }) {
  const [changing, setChanging] = useState(false)
  const [level, setLevel] = useState(String(r.level))
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()
  const label = (v) => session.scale.find((l) => l.value === Number(v))?.label || v
  const changed = r.calibratedLevel != null
  return (
    <tr data-calibrate={r.name}>
      <td>
        <strong>{r.name}</strong>
        <div className="hint">{[r.designation, r.department].filter(Boolean).join(' · ')}</div>
        {r.promotion && <div className="hint">Manager recommends promotion to {r.promotion}</div>}
      </td>
      <td>{r.manager || '—'}</td>
      <td>
        {r.managerLevel} · {label(r.managerLevel)}
        {r.proposedRating != null && <div className="hint">worked out {Number(r.proposedRating).toFixed(2)}</div>}
      </td>
      <td>
        <span data-testid="level-now">
          {r.level} · {label(r.level)}
        </span>{' '}
        {changed && <Badge tone="warn">Changed</Badge>}
        {r.changes.map((x) => (
          <div key={x.id} className="hint">
            {x.fromLevel} → {x.toLevel}, {x.changedBy}: {x.reason}
          </div>
        ))}
      </td>
      <td>
        {session.canChange && !r.own && r.status === 'calibration' && !changing && (
          <button type="button" className="quiet plain" onClick={() => setChanging(true)}>
            Change rating
          </button>
        )}
        {r.own && <span className="hint">Your own: someone else calibrates it</span>}
        {changing && (
          <form
            className="inline-form"
            onSubmit={async (e) => {
              e.preventDefault()
              const next = await run(() => performance.calibrate(session.id, r.id, Number(level), reason))
              if (next) {
                setChanging(false)
                setReason('')
                onChanged(next)
              }
            }}
          >
            <div>
              <label htmlFor={`cal-level-${r.id}`}>New rating</label>
              <select id={`cal-level-${r.id}`} value={level} onChange={(e) => setLevel(e.target.value)}>
                {session.scale.map((l) => (
                  <option key={l.value} value={l.value}>
                    {l.value} · {l.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor={`cal-why-${r.id}`}>
                Why <span className="hint">(at least 20 characters; the manager is told)</span>
              </label>
              <textarea id={`cal-why-${r.id}`} rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <div className="form-actions">
              <button type="submit" className="primary" disabled={busy}>
                Change
              </button>
              <button type="button" className="quiet" onClick={() => setChanging(false)}>
                Cancel
              </button>
            </div>
            <ErrorNote>{error}</ErrorNote>
          </form>
        )}
      </td>
    </tr>
  )
}
