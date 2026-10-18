import { useState } from 'react'
import { performance } from '../api.js'
import { formatDate, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'

/** A list of feedback, with Change and Withdraw on what the caller gave. */
export function FeedbackList({ items, onChanged, showReceiver = false, empty }) {
  if (!items?.length) return <Empty>{empty || 'No feedback yet.'}</Empty>
  return (
    <ul className="feedback-list">
      {items.map((f) => (
        <FeedbackItem key={f.id} item={f} onChanged={onChanged} showReceiver={showReceiver} />
      ))}
    </ul>
  )
}

function FeedbackItem({ item: f, onChanged, showReceiver }) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(f.text || '')
  const { busy, error, run } = useAction()
  return (
    <li className="feedback" data-feedback={f.id}>
      <div className="checkin-head">
        <strong>{showReceiver ? `To ${f.receiver}` : f.giver}</strong>
        {f.kind === 'private' ? <Badge tone="warn">Private, for the manager</Badge> : <Badge tone="good">Praise</Badge>}
        {f.shareWithManager && <Badge>shared with the manager</Badge>}
        <span className="hint">{formatDate(f.createdAt)}</span>
      </div>
      {f.withdrawnAt ? (
        <p className="hint">Withdrawn {formatDate(f.withdrawnAt)}.</p>
      ) : editing ? (
        <div className="inline-form">
          <div>
            <label htmlFor={`fb-edit-${f.id}`}>Feedback</label>
            <input id={`fb-edit-${f.id}`} value={text} onChange={(e) => setText(e.target.value)} />
          </div>
          <div className="form-actions">
            <button
              type="button"
              className="primary"
              disabled={busy}
              onClick={async () => {
                if (await run(() => performance.editFeedback(f.id, text))) {
                  setEditing(false)
                  onChanged?.()
                }
              }}
            >
              Save
            </button>
            <button type="button" className="quiet" onClick={() => setEditing(false)}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <p className="pre">{f.text}</p>
      )}
      {(f.goal || f.competency) && <p className="hint">About {f.goal ? `the goal “${f.goal}”` : f.competency}</p>}
      {!editing && (f.canEdit || f.canWithdraw) && (
        <div className="goal-actions">
          {f.canEdit && (
            <button type="button" className="quiet plain" onClick={() => setEditing(true)}>
              Change
            </button>
          )}
          {f.canWithdraw && (
            <button
              type="button"
              className="quiet plain danger"
              disabled={busy}
              onClick={async () => {
                if (await run(() => performance.withdrawFeedback(f.id))) onChanged?.()
              }}
            >
              Withdraw
            </button>
          )}
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
    </li>
  )
}

/** Give feedback: to a chosen colleague, or to one person when {@code to} is given. */
export function GiveFeedback({ to, toName, onGiven, allowPrivate = true }) {
  const colleagues = useLoad(() => (to ? Promise.resolve([]) : performance.colleagues()))
  const [who, setWho] = useState(to || '')
  const [kind, setKind] = useState('praise')
  const [share, setShare] = useState(false)
  const [text, setText] = useState('')
  const [sent, setSent] = useState(false)
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      data-testid="give-feedback"
      onSubmit={async (e) => {
        e.preventDefault()
        setSent(false)
        if (await run(() => performance.giveFeedback({ employmentId: who, kind, text, shareWithManager: share }))) {
          setText('')
          setSent(true)
          onGiven?.()
        }
      }}
    >
      <h3>Give feedback{toName ? ` to ${toName}` : ''}</h3>
      {!to && (
        <>
          <label htmlFor="fb-who">To</label>
          <select id="fb-who" value={who} onChange={(e) => setWho(e.target.value)}>
            <option value="">Choose a colleague…</option>
            {(colleagues.data || []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
                {c.designation ? ` · ${c.designation}` : ''}
              </option>
            ))}
          </select>
        </>
      )}
      {allowPrivate && (
        <fieldset className="choice-list perf-choices">
          <legend>Kind</legend>
          <label className={kind === 'praise' ? 'choice choice-current' : 'choice'}>
            <input type="radio" name="fb-kind" checked={kind === 'praise'} onChange={() => setKind('praise')} />
            <span>
              Praise <span className="hint">they read it</span>
            </span>
          </label>
          <label className={kind === 'private' ? 'choice choice-current' : 'choice'}>
            <input type="radio" name="fb-kind" checked={kind === 'private'} onChange={() => setKind('private')} />
            <span>
              Private <span className="hint">only their manager and HR read it</span>
            </span>
          </label>
        </fieldset>
      )}
      <label htmlFor="fb-text">Feedback</label>
      <textarea id="fb-text" rows={3} value={text} onChange={(e) => setText(e.target.value)} />
      {kind === 'praise' && (
        <label className="check">
          <input type="checkbox" checked={share} onChange={(e) => setShare(e.target.checked)} /> Share it with their manager too
        </label>
      )}
      <p className="hint">You can change it for a day. After that you can only withdraw it.</p>
      <ErrorNote>{error}</ErrorNote>
      {sent && <p className="notice">Sent.</p>}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy || !who}>
          Send
        </button>
      </div>
    </form>
  )
}
