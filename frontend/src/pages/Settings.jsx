import { useState } from 'react'
import { orgSettings } from '../api.js'
import { useConfirm, useToast } from '../feedbackContext.js'
import { formatDate, formatDay, localToday, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote, Tabs } from '../ui.jsx'

const HISTORY = 'history'

const STATUS = {
  applied: ['good', 'Applied'],
  pending: ['warn', 'Waiting for approval'],
  rejected: ['bad', 'Rejected'],
  withdrawn: ['neutral', 'Withdrawn'],
  undone: ['neutral', 'Undone'],
}

/** A value as people read it: "On", "7 days", "Reset to default". */
export function shown(setting, value) {
  if (value === null || value === undefined) return 'Default'
  if (setting.type === 'boolean') return value ? 'On' : 'Off'
  return setting.unit ? `${value} ${setting.unit}` : String(value)
}

/** Where a value comes from, in words. */
function sourceText(source) {
  if (!source || source === 'default') return 'Default'
  if (source === 'platform') return 'Set by the platform'
  if (source.startsWith('organization')) return source.replace('organization', 'Set for the Organization')
  return source.replace(/^location/, 'Set for this location').replace(/^pay_group/, 'Set for this pay group')
}

/**
 * The Organization's settings, one group per tab: each with its value, where the value comes from and
 * the range allowed. A change asks for a reason; a change that lowers safety asks for the password, and
 * the riskiest wait for a second person. Every change is in the history, and can be undone for 30 days.
 */
export default function Settings() {
  const [group, setGroup] = useState(null)
  const list = useLoad(() => orgSettings.list(group === HISTORY ? null : group))
  if (list.loading && !list.data) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  const d = list.data
  const current = group || d.group
  if (!d.groups.length) return <Empty>There are no settings for you here.</Empty>
  const tabs = [...d.groups.map((g) => ({ id: g.key, label: g.title })), { id: HISTORY, label: d.pendingCount ? `History (${d.pendingCount} waiting)` : 'History' }]
  return (
    <>
      <div className="section-head">
        <div>
          <p className="muted">How this Organization works. Each value says where it comes from; changes are recorded and can be undone for 30 days.</p>
        </div>
      </div>
      <Tabs
        tabs={tabs}
        current={current}
        onChange={(id) => {
          setGroup(id)
          list.reload()
        }}
      />
      {current === HISTORY ? (
        <History onChanged={list.reload} />
      ) : (
        <div className="settings-list">
          {d.settings.map((s) => (
            <SettingRow key={s.key} setting={s} onChanged={list.reload} />
          ))}
        </div>
      )}
    </>
  )
}

function SettingRow({ setting: s, onChanged }) {
  const [editing, setEditing] = useState(null)
  const exceptions = s.values.filter((v) => v.scope !== 'organization')
  const dated = s.values.filter((v) => v.scope === 'organization' && v.validFrom)
  const range = s.type === 'integer' && s.bound.min !== '' ? `${s.bound.min} to ${s.bound.max}${s.unit ? ` ${s.unit}` : ''}` : null
  return (
    <section className="panel setting" data-setting={s.key}>
      <div className="setting-head">
        <div>
          <h3>{s.label}</h3>
          {s.help && <p className="hint">{s.help}</p>}
        </div>
        <div className="setting-value">
          <strong data-testid={`value-${s.key}`}>{shown(s, s.value)}</strong>
          <span className="hint" data-testid={`source-${s.key}`}>
            {sourceText(s.source)}
            {s.clamped && ' · held within the platform’s range'}
          </span>
        </div>
      </div>
      <p className="hint setting-meta">
        {range && <>Allowed {range}. </>}
        Default {shown(s, s.platformDefault ?? s.default)}.{s.bound.locked && <> Fixed by the platform.</>}
        {s.approval === 'password' && <> Making this less safe asks for your password.</>}
        {s.approval === 'second_person' && <> Making this less safe needs a second person to approve.</>}
      </p>
      {dated.length > 0 && (
        <ul className="plain-list">
          {dated.map((v) => (
            <li key={v.validFrom}>
              From {formatDay(v.validFrom)}: {shown(s, v.value)}
            </li>
          ))}
        </ul>
      )}
      {exceptions.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{s.scopes.includes('location') ? 'Location' : 'Pay group'}</th>
                <th>Value</th>
                {s.dated && <th>From</th>}
                <th>Set by</th>
                {s.mayChange && <th />}
              </tr>
            </thead>
            <tbody>
              {exceptions.map((v) => (
                <tr key={`${v.scopeId}-${v.validFrom}`} data-exception={v.scopeName}>
                  <td>{v.scopeName}</td>
                  <td>{shown(s, v.value)}</td>
                  {s.dated && <td>{v.validFrom ? formatDay(v.validFrom) : '—'}</td>}
                  <td>{v.setBy}</td>
                  {s.mayChange && (
                    <td>
                      <button type="button" className="text-link" onClick={() => setEditing({ scope: v.scope, scopeId: v.scopeId, value: v.value, exception: true })}>
                        Change
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {s.pending.map((p) => (
        <Pending key={p.id} setting={s} change={p} onDone={onChanged} />
      ))}
      {editing ? (
        <ChangeForm
          setting={s}
          start={editing}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            onChanged()
          }}
        />
      ) : (
        s.mayChange &&
        !s.bound.locked && (
          <div className="form-actions">
            <button type="button" className="outline" onClick={() => setEditing({ scope: 'organization', value: s.value })}>
              Change
            </button>
            {s.scopeChoices?.length > 0 && (
              <button type="button" className="quiet" onClick={() => setEditing({ scope: s.scopes.includes('location') ? 'location' : 'pay_group', value: s.value, exception: true })}>
                Add an exception…
              </button>
            )}
          </div>
        )
      )}
    </section>
  )
}

function ChangeForm({ setting: s, start, onCancel, onSaved }) {
  const [f, setF] = useState({
    value: start.value ?? s.default,
    scopeId: start.scopeId || '',
    validFrom: s.dated ? localToday() : '',
    reason: '',
    password: '',
  })
  const [askPassword, setAskPassword] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const id = (k) => `set-${s.key.replaceAll('.', '-')}-${k}`

  async function send(reset) {
    setBusy(true)
    setError('')
    try {
      const body = {
        scope: start.scope,
        scopeId: start.exception ? f.scopeId || null : null,
        validFrom: s.dated ? f.validFrom : null,
        value: reset ? null : s.type === 'integer' ? Number(f.value) : f.value,
        reset: reset || null,
        reason: f.reason || null,
        password: askPassword ? f.password : null,
      }
      const out = await orgSettings.change(s.key, body)
      toast(out.change.status === 'pending' ? 'Sent for approval; nothing changes until someone else approves' : `${s.label}: ${shown(s, out.value)}`)
      onSaved()
    } catch (err) {
      if (err.code === 'setting.password_required') {
        setAskPassword(true)
        setError('This makes the Organization less safe. Confirm with your password.')
      } else {
        setError(err.message)
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      className="setting-form"
      onSubmit={(e) => {
        e.preventDefault()
        send(false)
      }}
    >
      <div className="grid">
        {start.exception && (
          <div>
            <label htmlFor={id('scope')}>{start.scope === 'location' ? 'Location' : 'Pay group'}</label>
            <select id={id('scope')} value={f.scopeId} onChange={set('scopeId')} disabled={Boolean(start.scopeId)} required>
              <option value="">Choose…</option>
              {s.scopeChoices.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <div>
          <label htmlFor={id('value')}>{s.label}</label>
          {s.type === 'boolean' ? (
            <input id={id('value')} type="checkbox" checked={Boolean(f.value)} onChange={set('value')} />
          ) : s.type === 'choice' ? (
            <select id={id('value')} value={f.value} onChange={set('value')}>
              {s.choices.map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          ) : (
            <div className="with-unit">
              <input id={id('value')} type="number" inputMode="numeric" step="1" min={s.bound.min} max={s.bound.max} value={f.value} onChange={set('value')} required />
              {s.unit && <span className="hint">{s.unit}</span>}
            </div>
          )}
        </div>
        {s.dated && (
          <div>
            <label htmlFor={id('from')}>Takes effect from</label>
            <input id={id('from')} type="date" value={f.validFrom} onChange={set('validFrom')} required />
          </div>
        )}
        <div className="span-2">
          <label htmlFor={id('reason')}>Reason {s.approval === 'second_person' ? '' : '(optional)'}</label>
          <input id={id('reason')} value={f.reason} onChange={set('reason')} maxLength={500} placeholder="Why, for the history" />
        </div>
        {askPassword && (
          <div>
            <label htmlFor={id('password')}>Your password</label>
            <input id={id('password')} type="password" autoComplete="current-password" value={f.password} onChange={set('password')} required autoFocus />
          </div>
        )}
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {askPassword ? 'Confirm and save' : 'Save'}
        </button>
        {(start.exception ? Boolean(start.scopeId) : s.source !== 'default' && s.source !== 'platform') && (
          <button type="button" className="outline" disabled={busy} onClick={() => send(true)}>
            {start.exception ? 'Remove exception' : 'Reset to default'}
          </button>
        )}
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/** A change waiting for a second person: the requester may withdraw it; another owner approves or rejects it. */
function Pending({ setting: s, change: p, onDone }) {
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const toast = useToast()
  const confirm = useConfirm()

  async function act(what) {
    setError('')
    try {
      if (what === 'approve') {
        await orgSettings.approve(p.id, { note: note || null })
        toast('Approved and applied')
      } else if (what === 'reject') {
        if (!note.trim()) {
          setError('Say why, so the requester knows')
          return
        }
        await orgSettings.reject(p.id, { note })
        toast('Rejected; the requester has been told')
      } else {
        const ok = await confirm({ title: 'Withdraw this change?', body: 'Nothing changes and the request is closed.', confirmLabel: 'Withdraw' })
        if (!ok) return
        await orgSettings.withdraw(p.id)
        toast('Withdrawn')
      }
      onDone()
    } catch (err) {
      setError(err.message)
    }
  }

  return (
    <div className="pending-change" data-pending={p.id}>
      <p>
        <Badge tone="warn">Waiting for approval</Badge> {p.requestedByName} asked to change{p.scopeName ? ` ${p.scopeName}` : ''} from{' '}
        <strong>{shown(s, p.oldValue)}</strong> to <strong>{shown(s, p.newValue)}</strong>
        {p.validFrom && <> from {formatDay(p.validFrom)}</>} on {formatDate(p.requestedAt)}.{p.reason && <> “{p.reason}”</>}
      </p>
      {p.mine ? (
        <button type="button" className="outline" onClick={() => act('withdraw')}>
          Withdraw
        </button>
      ) : (
        s.mayChange && (
          <div className="pending-actions">
            <input aria-label="Note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (needed to reject)" maxLength={500} />
            <button type="button" className="primary" onClick={() => act('approve')}>
              Approve
            </button>
            <button type="button" className="quiet danger" onClick={() => act('reject')}>
              Reject
            </button>
          </div>
        )
      )}
      <ErrorNote>{error}</ErrorNote>
    </div>
  )
}

/** Every change, newest first, with undo for the last 30 days. */
function History({ onChanged }) {
  const log = useLoad(() => orgSettings.changes())
  const [password, setPassword] = useState(null)
  const [error, setError] = useState('')
  const toast = useToast()
  const confirm = useConfirm()

  async function undo(row, withPassword) {
    setError('')
    if (!withPassword) {
      const ok = await confirm({
        title: `Undo this change to ${row.label}?`,
        body: `It goes back to ${row.oldValue === null ? 'the default' : row.oldValue}. The undo is recorded too.`,
        confirmLabel: 'Undo',
      })
      if (!ok) return
    }
    try {
      await orgSettings.undo(row.id, withPassword ? { password: withPassword } : null)
      setPassword(null)
      toast('Undone')
      log.reload()
      onChanged()
    } catch (err) {
      if (err.code === 'setting.password_required') {
        setPassword({ row, value: '' })
      } else {
        setError(err.message)
      }
    }
  }

  if (log.loading) return <p className="muted">Loading…</p>
  if (log.error) return <ErrorNote>{log.error}</ErrorNote>
  if (!log.data.length) return <Empty>No changes yet. Every value is the default.</Empty>
  return (
    <>
      <ErrorNote>{error}</ErrorNote>
      {password && (
        <form
          className="panel setting-form"
          onSubmit={(e) => {
            e.preventDefault()
            undo(password.row, password.value)
          }}
        >
          <label htmlFor="undo-password">Undoing this makes the Organization less safe. Your password</label>
          <input id="undo-password" type="password" autoComplete="current-password" value={password.value} onChange={(e) => setPassword({ ...password, value: e.target.value })} required autoFocus />
          <div className="form-actions">
            <button type="submit" className="primary">
              Confirm and undo
            </button>
            <button type="button" className="quiet" onClick={() => setPassword(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
      <div className="table-wrap">
        <table data-testid="settings-history">
          <thead>
            <tr>
              <th>When</th>
              <th>Setting</th>
              <th>Change</th>
              <th>By</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {log.data.map((r) => (
              <tr key={r.id} data-change={r.key}>
                <td>{formatDate(r.requestedAt)}</td>
                <td>
                  {r.label}
                  {r.scopeKind !== 'organization' && <span className="hint"> · {r.scopeKind === 'location' ? 'one location' : 'one pay group'}</span>}
                  {r.reason && <div className="hint">“{r.reason}”</div>}
                </td>
                <td>
                  {r.oldValue ?? 'default'} → {r.newValue ?? 'default'}
                  {r.validFrom && <span className="hint"> from {formatDay(r.validFrom)}</span>}
                  {r.undoes && <span className="hint"> (undo)</span>}
                </td>
                <td>
                  {r.requestedByName}
                  {r.decidedByName && r.decidedByName !== r.requestedByName && <div className="hint">decided by {r.decidedByName}</div>}
                </td>
                <td>
                  <Badge tone={STATUS[r.status][0]}>{STATUS[r.status][1]}</Badge>
                  {r.assurance === 'password' && <div className="hint">password confirmed</div>}
                </td>
                <td>
                  {r.canUndo && (
                    <button type="button" className="text-link" onClick={() => undo(r)}>
                      Undo
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
