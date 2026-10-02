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
}

export function StatusBadge({ status }) {
  return <Badge tone={STATUS_TONES[status] || 'neutral'}>{status}</Badge>
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
