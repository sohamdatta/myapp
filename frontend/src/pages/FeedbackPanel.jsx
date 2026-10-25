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
export function GiveFeedback({ to, toName, onGiven, allowPrivate = true, requestId = null, idPrefix = 'fb' }) {
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
      data-testid={requestId ? 'answer-feedback' : 'give-feedback'}
      onSubmit={async (e) => {
        e.preventDefault()
        setSent(false)
        if (await run(() => performance.giveFeedback({ employmentId: who, kind, text, shareWithManager: share, requestId }))) {
          setText('')
          setSent(true)
          onGiven?.()
        }
      }}
    >
      <h3>Give feedback{toName ? ` to ${toName}` : ''}</h3>
      {!to && (
        <>
          <label htmlFor={`${idPrefix}-who`}>To</label>
          <select id={`${idPrefix}-who`} value={who} onChange={(e) => setWho(e.target.value)}>
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
            <input type="radio" name={`${idPrefix}-kind`} checked={kind === 'praise'} onChange={() => setKind('praise')} />
            <span>
              Praise <span className="hint">they read it</span>
            </span>
          </label>
          <label className={kind === 'private' ? 'choice choice-current' : 'choice'}>
            <input type="radio" name={`${idPrefix}-kind`} checked={kind === 'private'} onChange={() => setKind('private')} />
            <span>
              Private <span className="hint">only their manager and HR read it</span>
            </span>
          </label>
        </fieldset>
      )}
      <label htmlFor={`${idPrefix}-text`}>Feedback</label>
      <textarea id={`${idPrefix}-text`} rows={3} value={text} onChange={(e) => setText(e.target.value)} />
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

/** Ask up to five colleagues for feedback about yourself. */
export function AskForFeedback({ onAsked }) {
  const colleagues = useLoad(performance.colleagues)
  const [who, setWho] = useState([])
  const [note, setNote] = useState('')
  const [sent, setSent] = useState(false)
  const { busy, error, run } = useAction()
  const toggle = (id) => setWho(who.includes(id) ? who.filter((x) => x !== id) : [...who, id])
  return (
    <form
      className="panel"
      data-testid="ask-feedback"
      onSubmit={async (e) => {
        e.preventDefault()
        setSent(false)
        if (await run(() => performance.askForFeedback({ giverIds: who, note }))) {
          setWho([])
          setNote('')
          setSent(true)
          onAsked?.()
        }
      }}
    >
      <h3>Ask for feedback</h3>
      <p className="hint">Ask up to five colleagues how you are doing. Each is told and can answer or decline.</p>
      <label htmlFor="ask-who">Colleagues</label>
      <select
        id="ask-who"
        value=""
        onChange={(e) => {
          if (e.target.value && who.length < 5) toggle(e.target.value)
        }}
      >
        <option value="">Add a colleague…</option>
        {(colleagues.data || [])
          .filter((c) => !who.includes(c.id))
          .map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
              {c.designation ? ` · ${c.designation}` : ''}
            </option>
          ))}
      </select>
      {who.length > 0 && (
        <div className="filters">
          {who.map((id) => (
            <button key={id} type="button" className="chip chip-current" onClick={() => toggle(id)} aria-label={`Remove ${nameOf(colleagues.data, id)}`}>
              {nameOf(colleagues.data, id)} ×
            </button>
          ))}
        </div>
      )}
      <label htmlFor="ask-note">What about (optional)</label>
      <input id="ask-note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="The dashboard launch" />
      <ErrorNote>{error}</ErrorNote>
      {sent && <p className="notice">Asked.</p>}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy || who.length === 0}>
          Ask
        </button>
      </div>
    </form>
  )
}

const nameOf = (list, id) => (list || []).find((c) => c.id === id)?.name || ''

/** Requests for feedback waiting for the caller: answer one (which opens the form) or decline it. */
export function FeedbackAskedOfMe({ requests, onChanged }) {
  const [answering, setAnswering] = useState(null)
  const [declining, setDeclining] = useState(null)
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()
  if (requests.length === 0) return null
  return (
    <section className="panel" data-testid="feedback-asked-of-me">
      <h3>Asked of you</h3>
      <ul className="bullet-list">
        {requests.map((q) => (
          <li key={q.id} data-feedback-request={q.about}>
            <strong>{q.about}</strong>{' '}
            <span className="hint">
              {q.aboutThemselves ? 'asked about themselves' : `${q.requestedByName} asked`}, {formatDate(q.createdAt)}
              {q.note && ` · “${q.note}”`}
            </span>
            {answering === q.id ? (
              <GiveFeedback
                to={q.aboutId}
                toName={q.about}
                requestId={q.id}
                idPrefix={`fb-${q.id}`}
                onGiven={() => {
                  setAnswering(null)
                  onChanged()
                }}
              />
            ) : declining === q.id ? (
              <div className="inline-form">
                <div>
                  <label htmlFor={`decline-${q.id}`}>Why you are declining</label>
                  <input id={`decline-${q.id}`} value={reason} onChange={(e) => setReason(e.target.value)} />
                </div>
                <div className="form-actions">
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={async () => {
                      if (await run(() => performance.declineFeedbackRequest(q.id, reason))) {
                        setDeclining(null)
                        onChanged()
                      }
                    }}
                  >
                    Decline
                  </button>
                  <button type="button" className="quiet" onClick={() => setDeclining(null)}>
                    Cancel
                  </button>
                </div>
              </div>
            ) : (
              <div className="goal-actions">
                <button type="button" className="outline" onClick={() => setAnswering(q.id)}>
                  Give feedback
                </button>
                <button type="button" className="quiet plain" onClick={() => setDeclining(q.id)}>
                  Decline
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

const ASKED_STATUS = { open: ['neutral', 'Waiting'], given: ['good', 'Given'], declined: ['neutral', 'Declined'] }

/** What the caller has asked for, and whether each was answered. */
export function FeedbackYouAsked({ asked }) {
  if (asked.length === 0) return null
  return (
    <section className="panel" data-testid="feedback-you-asked">
      <h3>You asked</h3>
      <ul className="bullet-list">
        {asked.map((q) => {
          const [tone, words] = ASKED_STATUS[q.status] || ['neutral', q.status]
          return (
            <li key={q.id}>
              {q.giver} <Badge tone={tone}>{words}</Badge>{' '}
              <span className="hint">
                {formatDate(q.createdAt)}
                {q.declinedReason && ` · “${q.declinedReason}”`}
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
