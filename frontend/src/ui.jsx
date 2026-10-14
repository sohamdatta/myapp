import { useState } from 'react'
import { liveStatus } from './hooks.js'

export function ErrorNote({ children }) {
  if (!children) return null
  return (
    <p className="error" role="alert">
      {children}
    </p>
  )
}

export function Badge({ tone = 'neutral', children }) {
  return <span className={`badge badge-${tone}`}>{children}</span>
}

const STATUS_TONES = {
  active: 'good',
  approved: 'good',
  provisioning: 'warn',
  requested: 'warn',
  suspended: 'warn',
  ended: 'neutral',
  closed: 'neutral',
  rejected: 'neutral',
  revoked: 'neutral',
  present: 'good',
  on_duty: 'good',
  work_from_home: 'good',
  on_leave: 'neutral',
  half_day: 'warn',
  absent: 'warn',
  pending: 'warn',
  holiday: 'neutral',
  weekly_off: 'neutral',
  not_assigned: 'neutral',
  cancelled: 'neutral',
  verified: 'good',
  submitted: 'warn',
  returned: 'warn',
  invited: 'neutral',
  in_progress: 'neutral',
  draft: 'neutral',
  not_started: 'neutral',
  withdrawn: 'neutral',
}

export function StatusBadge({ status }) {
  if (!status) return <Badge>—</Badge>
  return <Badge tone={STATUS_TONES[status] || 'neutral'}>{status.replaceAll('_', ' ')}</Badge>
}

/**
 * A day's result. While the day is still going, "absent" would be alarming and
 * premature, so today shows where things stand instead.
 */
export function DayStatus({ day, isToday }) {
  const live = liveStatus(day, isToday)
  if (live === 'working') {
    return (
      <>
        <Badge tone="good">working</Badge>
        {day.remote && (
          <>
            {' '}
            <Badge>remote</Badge>
          </>
        )}
      </>
    )
  }
  if (live === 'not in yet') return <Badge>not in yet</Badge>
  return (
    <>
      <StatusBadge status={day.status} />
      {day.remote && day.status !== 'work_from_home' && (
        <>
          {' '}
          <Badge>remote</Badge>
        </>
      )}
      {Number(day.leaveDays) === 0.5 && (
        <>
          {' '}
          <Badge>half day leave</Badge>
        </>
      )}
      {day.incomplete && (
        <>
          {' '}
          <Badge tone="warn">missing punch</Badge>
        </>
      )}
    </>
  )
}

/** The steps that led to a day's result, oldest first. */
export function Trail({ steps, inProgress }) {
  if (!steps || steps.length === 0) return <p className="hint">Nothing has been worked out for this day yet.</p>
  return (
    <>
      {inProgress && <p className="hint">The day is not over yet: this is how it would count if it ended now.</p>}
      <ol className="trail">
        {steps.map((step, index) => (
          <li key={index}>{step}</li>
        ))}
      </ol>
    </>
  )
}

/** A link the user needs to pass on by hand, with a copy button. */
export function CopyLink({ label, link, onDismiss, note = 'It works once and expires in 7 days.' }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="notice" role="status">
      <p>
        <strong>{label}</strong> No email is sent yet, so copy this link and send it yourself. {note}
      </p>
      <div className="copy-row">
        <input readOnly value={link} aria-label="Invitation link" onFocus={(e) => e.target.select()} />
        <button type="button" className="secondary" onClick={copy}>
          {copied ? 'Copied' : 'Copy'}
        </button>
        {onDismiss && (
          <button type="button" className="quiet" onClick={onDismiss}>
            Done
          </button>
        )}
      </div>
    </div>
  )
}

export function Tabs({ tabs, current, onChange }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          aria-selected={current === tab.id}
          className={current === tab.id ? 'tab tab-current' : 'tab'}
          onClick={() => onChange(tab.id)}
        >
          {tab.label}
        </button>
      ))}
    </div>
  )
}

export function Empty({ children }) {
  return <p className="empty">{children}</p>
}

/** A person in a list: their name when their record is linked, with the email (and code) underneath; the email alone otherwise. */
export function Who({ person, code }) {
  const extra = [code, person.name ? person.email : null].filter(Boolean).join(' · ')
  return (
    <>
      {person.name || person.email}
      {extra && <div className="hint">{extra}</div>}
    </>
  )
}

const SECTION_BADGES = {
  not_started: ['neutral', 'To do', 'Not started'],
  draft: ['neutral', 'In progress', 'In progress'],
  submitted: ['warn', 'Sent for checking', 'To check'],
  returned: ['warn', 'Needs a change', 'Returned to the joiner'],
  verified: ['good', 'Checked', 'Checked'],
}

/** Where an onboarding section stands, in the joiner's words or HR's. */
export function SectionBadge({ status, hr }) {
  const [tone, joiner, forHr] = SECTION_BADGES[status] || ['neutral', status, status]
  return <Badge tone={tone}>{hr ? forHr : joiner}</Badge>
}

/** The cue that a row opens: a chevron that turns when it is open. */
export function Chevron({ open }) {
  return (
    <span className={open ? 'chevron chevron-open' : 'chevron'} aria-hidden="true">
      ›
    </span>
  )
}
