import { useState } from 'react'
import { performance } from '../api.js'
import { formatDay, localToday, useAction, useLoad } from '../hooks.js'
import { Empty, ErrorNote } from '../ui.jsx'

/**
 * One-to-ones with one person (a report, or the caller's manager): the shared agenda and notes,
 * the caller's own private notes, and the actions either side ticks off.
 */
export default function OneToOnePanel({ withId }) {
  const data = useLoad(() => performance.oneToOnes(withId))
  const [view, setView] = useState(null)
  const [adding, setAdding] = useState(false)
  const o = view || data.data
  if (data.loading && !o) return <p className="muted">Loading…</p>
  if (data.error) return <ErrorNote>{data.error}</ErrorNote>
  const changed = (next) => {
    setView(next)
    setAdding(false)
  }
  return (
    <section className="panel" data-testid="one-to-ones">
      <div className="section-head">
        <h3>One-to-ones with {o.with.name}</h3>
        {!adding && (
          <button type="button" className="outline" onClick={() => setAdding(true)}>
            New one-to-one
          </button>
        )}
      </div>
      <p className="hint">Only the two of you see these. Your private notes are for you alone.</p>
      {o.openItems.length > 0 && (
        <div className="open-items">
          <h4>Open actions</h4>
          <ul className="checklist">
            {o.openItems.map((i) => (
              <Item key={i.id} item={i} youAre={o.youAre} onChanged={changed} />
            ))}
          </ul>
        </div>
      )}
      {adding && <Meeting youAre={o.youAre} withId={withId} onSaved={changed} onCancel={() => setAdding(false)} />}
      {o.meetings.length === 0 && !adding && <Empty>No one-to-ones yet.</Empty>}
      {o.meetings.map((m) => (
        <Meeting key={m.id} meeting={m} youAre={o.youAre} withId={withId} onSaved={changed} />
      ))}
    </section>
  )
}

function Item({ item, youAre, onChanged }) {
  const { busy, run } = useAction()
  const whose = item.owner === youAre ? 'yours' : youAre === 'manager' ? 'theirs' : "your manager's"
  return (
    <li>
      <label className="check">
        <input
          type="checkbox"
          checked={Boolean(item.doneAt)}
          disabled={busy}
          onChange={async (e) => {
            const next = await run(() => performance.markItem(item.id, e.target.checked))
            if (next) onChanged(next)
          }}
        />
        {item.text} <span className="hint">({whose})</span>
      </label>
    </li>
  )
}

function Meeting({ meeting, youAre, withId, onSaved, onCancel }) {
  const isNew = !meeting
  const [editing, setEditing] = useState(isNew)
  const [date, setDate] = useState(meeting?.meetingDate || localToday())
  const [shared, setShared] = useState(meeting?.sharedNotes || '')
  const [own, setOwn] = useState(meeting?.privateNotes || '')
  const [action, setAction] = useState('')
  const [owner, setOwner] = useState(youAre)
  const { busy, error, run } = useAction()
  const prefix = meeting ? `m-${meeting.id}` : 'm-new'

  async function save(e) {
    e.preventDefault()
    const body = { withEmploymentId: withId, meetingDate: date, sharedNotes: shared, privateNotes: own }
    const next = await run(() => (isNew ? performance.addOneToOne(body) : performance.changeOneToOne(meeting.id, body)))
    if (next) {
      setEditing(false)
      onSaved(next)
    }
  }

  if (!editing) {
    return (
      <div className="meeting" data-meeting={meeting.meetingDate}>
        <div className="checkin-head">
          <strong>{formatDay(meeting.meetingDate)}</strong>
          <button type="button" className="quiet plain" onClick={() => setEditing(true)}>
            Edit notes
          </button>
        </div>
        {meeting.sharedNotes ? <p className="pre">{meeting.sharedNotes}</p> : <p className="hint">No shared notes.</p>}
        {meeting.privateNotes && (
          <div className="reply">
            <span className="hint">Your private notes</span>
            <p className="pre">{meeting.privateNotes}</p>
          </div>
        )}
        {meeting.items.length > 0 && (
          <ul className="checklist">
            {meeting.items.map((i) => (
              <Item key={i.id} item={i} youAre={youAre} onChanged={onSaved} />
            ))}
          </ul>
        )}
        <div className="inline-form">
          <div>
            <label htmlFor={`${prefix}-action`}>Add an action</label>
            <input id={`${prefix}-action`} value={action} onChange={(e) => setAction(e.target.value)} />
          </div>
          <div>
            <label htmlFor={`${prefix}-owner`}>Whose</label>
            <select id={`${prefix}-owner`} value={owner} onChange={(e) => setOwner(e.target.value)}>
              <option value={youAre}>Mine</option>
              <option value={youAre === 'manager' ? 'report' : 'manager'}>{youAre === 'manager' ? 'Theirs' : "My manager's"}</option>
            </select>
          </div>
          <button
            type="button"
            className="outline"
            disabled={busy || !action.trim()}
            onClick={async () => {
              const next = await run(() => performance.addItem(meeting.id, { text: action, owner }))
              if (next) {
                setAction('')
                onSaved(next)
              }
            }}
          >
            Add
          </button>
        </div>
        <ErrorNote>{error}</ErrorNote>
      </div>
    )
  }

  return (
    <form className="meeting" onSubmit={save}>
      <div className="grid">
        <div>
          <label htmlFor={`${prefix}-date`}>Date</label>
          <input id={`${prefix}-date`} type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </div>
      </div>
      <label htmlFor={`${prefix}-shared`}>
        Agenda and notes <span className="hint">(you both read these)</span>
      </label>
      <textarea id={`${prefix}-shared`} rows={4} value={shared} onChange={(e) => setShared(e.target.value)} />
      <label htmlFor={`${prefix}-private`}>
        Private notes <span className="hint">(only you)</span>
      </label>
      <textarea id={`${prefix}-private`} rows={2} value={own} onChange={(e) => setOwn(e.target.value)} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save
        </button>
        <button type="button" className="quiet" onClick={() => (isNew ? onCancel() : setEditing(false))}>
          Cancel
        </button>
      </div>
    </form>
  )
}
