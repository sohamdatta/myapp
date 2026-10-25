import { useState } from 'react'
import { performance } from '../api.js'
import { formatDate, useAction, useLoad } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'

/** A peer review the caller was asked for: the peer questions, saved as a draft or sent; or declined with a reason. */
export default function PeerReview({ id, onBack }) {
  const loaded = useLoad(() => performance.peerReview(id))
  const [view, setView] = useState(null)
  const [answers, setAnswers] = useState(null)
  const [declining, setDeclining] = useState(false)
  const [reason, setReason] = useState('')
  const [saved, setSaved] = useState(false)
  const { busy, error, run } = useAction()
  const p = view || loaded.data
  if (loaded.loading && !p) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  const values = answers ?? p.answers ?? {}
  const save = async (submit) => {
    setSaved(false)
    const next = await run(() => performance.savePeerReview(p.id, values, submit))
    if (next) {
      setView(next)
      setAnswers(null)
      setSaved(!submit)
    }
  }
  return (
    <div data-testid="peer-review">
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← My reviews
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            Peer review: {p.employee.name}{' '}
            <Badge tone={p.status === 'submitted' ? 'good' : 'neutral'}>
              {{ open: 'To write', submitted: 'Sent', declined: 'Declined', expired: 'Closed' }[p.status]}
            </Badge>
          </h2>
          <p className="muted">
            {[p.cycle, p.employee.designation, p.employee.department].filter(Boolean).join(' · ')}
          </p>
        </div>
      </div>
      <section className="panel">
        <p className="hint">
          {p.anonymous
            ? `${p.employee.name} reads peer reviews without names, and only when two or more are written. Their manager and HR see who wrote each.`
            : `${p.employee.name}, their manager and HR read what you write, with your name.`}
        </p>
        {p.questions.map((q) => (
          <div key={q.id}>
            <label htmlFor={`peer-q-${q.id}`}>
              {q.text} {!q.required && <span className="hint">(optional)</span>}
            </label>
            <textarea
              id={`peer-q-${q.id}`}
              rows={3}
              disabled={!p.canWrite}
              value={values[q.id] || ''}
              onChange={(e) => setAnswers({ ...values, [q.id]: e.target.value })}
            />
          </div>
        ))}
        {p.submittedAt && <p className="hint">Sent {formatDate(p.submittedAt)}.</p>}
        <ErrorNote>{error}</ErrorNote>
        {saved && <p className="notice">Saved as a draft.</p>}
        {p.canWrite && !declining && (
          <div className="form-actions">
            <button type="button" className="primary" disabled={busy} onClick={() => save(true)}>
              Send
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => save(false)}>
              Save a draft
            </button>
            <button type="button" className="quiet plain" onClick={() => setDeclining(true)}>
              Decline…
            </button>
          </div>
        )}
        {declining && (
          <div className="inline-form">
            <div>
              <label htmlFor="peer-decline">Why you are declining</label>
              <input id="peer-decline" value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <div className="form-actions">
              <button
                type="button"
                className="secondary"
                disabled={busy}
                onClick={async () => {
                  const next = await run(() => performance.declinePeerReview(p.id, reason))
                  if (next) {
                    setView(next)
                    setDeclining(false)
                  }
                }}
              >
                Decline
              </button>
              <button type="button" className="quiet" onClick={() => setDeclining(false)}>
                Cancel
              </button>
            </div>
          </div>
        )}
      </section>
    </div>
  )
}
