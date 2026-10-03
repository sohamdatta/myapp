import { useState } from 'react'

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
  half_day: 'warn',
  absent: 'warn',
  pending: 'warn',
  holiday: 'neutral',
  weekly_off: 'neutral',
  not_assigned: 'neutral',
  cancelled: 'neutral',
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
  const unsettled = isToday && ['absent', 'half_day'].includes(day.status)
  if (unsettled && day.incomplete) return <Badge tone="good">working</Badge>
  if (unsettled && !day.firstIn) return <Badge>not in yet</Badge>
  return (
    <>
      <StatusBadge status={day.status} />
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
export function Trail({ steps }) {
  if (!steps || steps.length === 0) return <p className="hint">Nothing has been worked out for this day yet.</p>
  return (
    <ol className="trail">
      {steps.map((step, index) => (
        <li key={index}>{step}</li>
      ))}
    </ol>
  )
}

/** A link the user needs to pass on by hand, with a copy button. */
export function CopyLink({ label, link, onDismiss }) {
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
        <strong>{label}</strong> No email is sent yet, so copy this link and send it yourself. It works once
        and expires in 7 days.
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
