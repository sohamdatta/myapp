import { useState } from 'react'
import { downloadDocument as download, performance } from '../api.js'
import { formatDate, formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote, Tabs } from '../ui.jsx'
import { FeedbackList, GiveFeedback } from './FeedbackPanel.jsx'
import GoalsPanel from './GoalsPanel.jsx'
import OneToOnePanel from './OneToOnePanel.jsx'
import { PlanView } from './Plans.jsx'
import { DECIDED, RATINGS, REVIEW_STATUS } from './performanceText.js'
import ReviewPage from './ReviewPage.jsx'

/**
 * My performance: the employee's goals, their probation (check-ins to read and answer, and its
 * letters), their one-to-ones, and feedback received and given.
 */
export default function MyPerformance() {
  const mine = useLoad(performance.mine)
  const [tab, setTab] = useState(null)
  if (mine.loading) return <p className="muted">Loading…</p>
  if (mine.error) return <ErrorNote>{mine.error}</ErrorNote>
  const m = mine.data
  // A review waiting for the employee, or one released for them to read, comes first.
  const pressing = (m.reviews || []).find((r) => ['self_review', 'released'].includes(r.status))
  const tabs = [
    (m.reviews?.length > 0 || m.outcomeLetters?.length > 0) && { id: 'reviews', label: 'Reviews' },
    m.plan && { id: 'plan', label: 'Improvement plan' },
    m.probation && { id: 'probation', label: 'Probation' },
    m.goals && { id: 'goals', label: 'Goals' },
    m.oneToOnes?.length > 0 && { id: 'one-to-ones', label: 'One-to-ones' },
    { id: 'feedback', label: 'Feedback' },
  ].filter(Boolean)
  const planPressing = m.plan && m.plan.status === 'active' && (!m.plan.acknowledgedAt || m.plan.checkins.some((k) => k.canReply))
  const current =
    tab || (planPressing ? 'plan' : pressing ? 'reviews' : m.probation?.onProbation ? 'probation' : m.goals ? 'goals' : tabs[0].id)
  return (
    <div className="my-performance">
      <Tabs tabs={tabs} current={current} onChange={setTab} />
      {current === 'reviews' && <MyReviews reviews={m.reviews} letters={m.outcomeLetters || []} onChanged={mine.reload} />}
      {current === 'plan' && m.plan && <PlanView plan={m.plan} onChanged={() => (setTab('plan'), mine.reload())} />}
      {current === 'probation' && m.probation && <MyProbation probation={m.probation} onChanged={mine.reload} />}
      {current === 'goals' && m.goals && <GoalsPanel employmentId={m.goals.employee.id} />}
      {current === 'one-to-ones' && <MyOneToOnes people={m.oneToOnes} />}
      {current === 'feedback' && <MyFeedback received={m.feedback} onChanged={mine.reload} />}
    </div>
  )
}

function MyReviews({ reviews, letters, onChanged }) {
  const [open, setOpen] = useState(() => reviews.find((r) => ['self_review', 'released'].includes(r.status))?.id || null)
  const { error, run } = useAction()
  if (open) return <ReviewPage key={open} id={open} onBack={() => (setOpen(null), onChanged())} backLabel="← All my reviews" />
  return (
    <section className="panel">
      {letters.length > 0 && (
        <>
          <h3>Your letters</h3>
          <ul className="letter-list" data-testid="outcome-letters">
            {letters.map((l) => (
              <li key={l.id} data-letter={l.title}>
                <button type="button" className="text-link" onClick={() => run(() => download(l.documentId, `${l.title}.pdf`))}>
                  {l.title}
                </button>{' '}
                <span className="hint">
                  {l.cycle}, issued {formatDate(l.issuedAt)}
                </span>
              </li>
            ))}
          </ul>
          <ErrorNote>{error}</ErrorNote>
        </>
      )}
      <h3>Your reviews</h3>
      <ul className="plain-list">
        {reviews.map((r) => {
          const [tone, , words] = REVIEW_STATUS[r.status] || ['neutral', r.status, r.status]
          return (
            <li key={r.id} data-my-review={r.cycle}>
              <button type="button" className="text-link" onClick={() => setOpen(r.id)}>
                {r.cycle}
              </button>{' '}
              <Badge tone={tone}>{words}</Badge>{' '}
              <span className="hint">
                {formatDay(r.periodStart)} to {formatDay(r.periodEnd)}
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function MyOneToOnes({ people }) {
  const [withId, setWithId] = useState(people[0].id)
  return (
    <>
      {people.length > 1 && (
        <div className="filters">
          {people.map((p) => (
            <button key={p.id} type="button" className={withId === p.id ? 'chip chip-current' : 'chip'} onClick={() => setWithId(p.id)}>
              {p.name}
              {p.relation === 'manager' ? ' (your manager)' : ''}
            </button>
          ))}
        </div>
      )}
      <OneToOnePanel key={withId} withId={withId} />
    </>
  )
}

function MyFeedback({ received, onChanged }) {
  const given = useLoad(performance.givenFeedback)
  return (
    <div className="panels">
      <section className="panel" data-testid="feedback-received">
        <h3>Feedback for you</h3>
        <FeedbackList items={received} empty="Nobody has given you feedback yet." />
      </section>
      <div>
        <GiveFeedback
          onGiven={() => {
            given.reload()
            onChanged()
          }}
        />
        <section className="panel">
          <h3>Feedback you gave</h3>
          {given.data ? <FeedbackList items={given.data} onChanged={given.reload} showReceiver empty="You have not given any feedback yet." /> : <Empty>Loading…</Empty>}
        </section>
      </div>
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
