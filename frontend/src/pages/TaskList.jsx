import { useState } from 'react'
import { tasks as api } from '../api.js'
import { formatDay } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'

/** Checklist tasks: one row each, with what the caller may do to it. Shared by a joiner's checklist and My tasks. */

const OWNERS = { hr: 'HR', it: 'IT', manager: 'Manager', buddy: 'Buddy', joiner: 'Joiner', role: 'Others', user: 'Others' }

const STATES = {
  open: ['neutral', 'To do'],
  overdue: ['warn', 'Overdue'],
  awaiting_approval: ['warn', 'Waiting for approval'],
  done: ['good', 'Done'],
  skipped: ['neutral', 'Skipped'],
}

/** Tasks grouped by owner, as HR sees one joiner's checklist. */
export function Checklist({ tasks, hr, users, onChanged }) {
  const groups = {}
  for (const t of tasks) (groups[OWNERS[t.owner]] ||= []).push(t)
  return (
    <div className="task-groups">
      {Object.entries(groups).map(([owner, list]) => (
        <section key={owner} className="panel task-group">
          <h3>
            {owner} <span className="hint">{list.filter((t) => t.doneAt).length} of {list.length} done</span>
          </h3>
          {list.map((t) => (
            <TaskRow key={t.id} task={t} hr={hr} users={users} onChanged={onChanged} />
          ))}
        </section>
      ))}
    </div>
  )
}

export function TaskRow({ task, hr, users = [], onChanged, showJoiner }) {
  const [mode, setMode] = useState(null) // 'skip' | 'reassign'
  const [reason, setReason] = useState('')
  const [assignee, setAssignee] = useState('')
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState(null)
  const [error, setError] = useState('')
  const [tone, label] = STATES[task.state]

  async function run(action) {
    setBusy(true)
    setError('')
    try {
      await action()
      setMode(null)
      onChanged()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function attach(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setProgress(0)
    await run(() => api.attach(task.id, file, setProgress))
    setProgress(null)
  }

  const open = !task.doneAt
  return (
    <div className={`task-row task-${task.state}`} data-task={task.title}>
      <div className="task-main">
        <div>
          <div className="task-title">
            {task.title}
            {!task.required && <span className="hint"> (optional)</span>}
          </div>
          <div className="hint">
            {showJoiner && (
              <>
                {task.joinerPreferredName || task.joinerName}, joining {formatDay(task.joiningDate)} ·{' '}
              </>
            )}
            {task.dueDate ? `due ${formatDay(task.dueDate)}` : 'no due date yet'}
            {!showJoiner && ` · ${task.assigneeName || task.assigneeEmail || 'nobody yet'}`}
            {task.skippedReason && ` · ${task.skippedReason}`}
            {task.attachmentName && ` · file: ${task.attachmentName}`}
          </div>
          {task.instructions && <div className="hint">{task.instructions}</div>}
        </div>
        <Badge tone={tone}>{label}</Badge>
      </div>
      {progress !== null && (
        <div className="progress" role="progressbar" aria-valuenow={Math.round(progress * 100)}>
          <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
      {(task.canDo || task.canApprove) && !mode && (
        <div className="form-actions">
          {task.canDo && task.needsAttachment && (
            <label htmlFor={`attach-${task.id}`} className="file-button outline">
              {task.attachmentId ? 'Replace file' : 'Attach file'}
              <input id={`attach-${task.id}`} type="file" accept="application/pdf,image/jpeg,image/png" className="sr-only" onChange={attach} />
            </label>
          )}
          {task.canDo && (
            <button type="button" className="secondary" disabled={busy} onClick={() => run(() => api.done(task.id))}>
              Mark done
            </button>
          )}
          {task.canApprove && (
            <button type="button" className="primary" disabled={busy} onClick={() => run(() => api.approve(task.id))}>
              Approve
            </button>
          )}
          {hr && open && (
            <>
              <button type="button" className="quiet" onClick={() => setMode('reassign')}>
                Reassign
              </button>
              <button type="button" className="quiet" onClick={() => setMode('skip')}>
                Skip
              </button>
            </>
          )}
        </div>
      )}
      {mode === 'skip' && (
        <div className="inline-form">
          <div>
            <label htmlFor={`skip-${task.id}`}>Why skip it</label>
            <input id={`skip-${task.id}`} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Not needed for this joiner" />
          </div>
          <div className="form-actions">
            <button type="button" className="secondary" disabled={busy} onClick={() => run(() => api.skip(task.id, reason))}>
              Skip task
            </button>
            <button type="button" className="quiet" onClick={() => setMode(null)}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {mode === 'reassign' && (
        <div className="inline-form">
          <div>
            <label htmlFor={`reassign-${task.id}`}>Give it to</label>
            <select id={`reassign-${task.id}`} value={assignee} onChange={(e) => setAssignee(e.target.value)}>
              <option value="">Choose…</option>
              {users.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.email}
                </option>
              ))}
            </select>
          </div>
          <div className="form-actions">
            <button type="button" className="secondary" disabled={busy || !assignee} onClick={() => run(() => api.reassign(task.id, assignee))}>
              Reassign
            </button>
            <button type="button" className="quiet" onClick={() => setMode(null)}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
