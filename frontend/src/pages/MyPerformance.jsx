import { useState } from 'react'
import { downloadDocument as download, performance } from '../api.js'
import { formatDate, formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'
import { DECIDED, RATINGS } from './performanceText.js'

/** My performance: the employee's own probation, with their manager's check-ins to read and answer, and its letters. */
export default function MyPerformance() {
  const mine = useLoad(performance.mine)
  if (mine.loading) return <p className="muted">Loading…</p>
  if (mine.error) return <ErrorNote>{mine.error}</ErrorNote>
  const m = mine.data
  const nothing = !m.probation
  return (
    <div className="my-performance">
      {m.probation && <MyProbation probation={m.probation} onChanged={mine.reload} />}
      {nothing && <p className="muted">There is nothing about your performance to show yet.</p>}
    </div>
  )
}

function MyProbation({ probation: p, onChanged }) {
  const { error, run } = useAction()
  return (
    <section className="panel" data-testid="my-probation">
      <div className="section-head">
        <h2>
          Your probation{' '}
          {p.onProbation ? <Badge tone="neutral">Until {formatDay(p.probationEnd)}</Badge> : <Badge tone="good">{DECIDED[p.decision]}</Badge>}
        </h2>
      </div>
      {p.onProbation && (
        <p className="hint">
          Your manager checks in with you during your probation. You can reply to each check-in. HR decides before your probation ends
          on {formatDay(p.probationEnd)}.
        </p>
      )}
      {p.decision === 'confirm' && <p>Your employment is confirmed from {formatDay(p.confirmedFrom)}.</p>}
      {p.decision === 'extend' && <p>Your probation is extended to {formatDay(p.newProbationEnd)}.</p>}
      {p.letters.length > 0 && (
        <ul className="letter-list">
          {p.letters.map((l) => (
            <li key={l.id} data-letter={l.title}>
              <button type="button" className="text-link" onClick={() => run(() => download(l.documentId, `${l.title}.pdf`))}>
                {l.title}
              </button>{' '}
              <span className="hint">issued {formatDate(l.issuedAt)}</span>
            </li>
          ))}
        </ul>
      )}
      <ErrorNote>{error}</ErrorNote>
      <h3>Check-ins</h3>
      {p.checkins.length === 0 && <p className="hint">Your manager has not written a check-in yet.</p>}
      {p.checkins.map((k) => (
        <MyCheckin key={k.id} checkin={k} onChanged={onChanged} />
      ))}
    </section>
  )
}

function MyCheckin({ checkin: k, onChanged }) {
  const [replying, setReplying] = useState(false)
  const [reply, setReply] = useState('')
  const { busy, error, run } = useAction()
  const [tone, words] = RATINGS[k.rating] || ['neutral', k.rating]
  return (
    <div className="checkin" data-checkin={k.day}>
      <div className="checkin-head">
        <strong>Day {k.day}</strong> <Badge tone={tone}>{words}</Badge>
        <span className="hint">
          {k.writtenByName}, {formatDate(k.writtenAt)}
        </span>
      </div>
      <p className="pre">{k.note}</p>
      {k.reply && (
        <div className="reply">
          <span className="hint">Your reply, {formatDate(k.repliedAt)}</span>
          <p className="pre">{k.reply}</p>
        </div>
      )}
      {k.canReply && !replying && (
        <button type="button" className="outline" onClick={() => setReplying(true)}>
          Reply
        </button>
      )}
      {replying && (
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            if (await run(() => performance.replyToCheckin(k.id, reply))) {
              setReplying(false)
              onChanged()
            }
          }}
        >
          <label htmlFor={`reply-${k.id}`}>
            Your reply <span className="hint">(your manager and HR read it; you can reply once)</span>
          </label>
          <textarea id={`reply-${k.id}`} rows={3} value={reply} onChange={(e) => setReply(e.target.value)} />
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              Send
            </button>
            <button type="button" className="quiet" onClick={() => setReplying(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </div>
  )
}
