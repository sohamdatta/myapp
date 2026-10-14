import { useState } from 'react'
import { exitTasks, exits, org, people } from '../api.js'
import { formatDay, localToday, useAction, useLoad, handoff } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { KINDS, REASONS, REHIRE, SHORTFALL, STAGES, daysBetween } from './exitText.js'
import { Checklist } from './TaskList.jsx'
import { ExitLettersPanel, InterviewPanel } from './ExitLetters.jsx'
import { ExitProgress } from './ExitProgress.jsx'
import { Settlement } from './Settlement.jsx'

/**
 * Exits: every exit for HR, the team's for a manager. A list by stage, and one exit at a time
 * with what the caller may do next: recommend, accept, decide a withdrawal, cancel or close.
 */
export default function Exits({ me }) {
  const canManage = (me.scopes || {})['exit.manage'] === 'tenant' && me.kind === 'tenant'
  // Sent here from an employee's record: start an exit for them, or show theirs.
  const [asked] = useState(() => handoff.take())
  const [open, setOpen] = useState(asked?.open || (asked?.startFor ? 'new' : null)) // an exit id, or 'new'
  if (open === 'new') {
    return <StartExit employmentId={asked?.startFor} onSaved={(id) => setOpen(id)} onCancel={() => setOpen(null)} />
  }
  if (open) return <ExitPage key={open} id={open} onBack={() => setOpen(null)} />
  return <ExitList canManage={canManage} onOpen={setOpen} onStart={() => setOpen('new')} />
}

const nameOf = (x) => x.preferredName || x.legalName

function ExitList({ canManage, onOpen, onStart }) {
  const [status, setStatus] = useState('open')
  const list = useLoad(() => exits.list(status))
  return (
    <>
      <div className="filters">
        {[
          ['open', 'In progress'],
          ['closed', 'Done'],
          ['all', 'All'],
        ].map(([value, label]) => (
          <button
            key={value}
            type="button"
            className={status === value ? 'chip chip-current' : 'chip'}
            onClick={() => {
              setStatus(value)
              list.reload()
            }}
          >
            {label}
          </button>
        ))}
        <span className="spacer" />
        {canManage && (
          <button type="button" className="outline" onClick={onStart}>
            Start an exit
          </button>
        )}
      </div>
      {list.loading && <p className="muted">Loading…</p>}
      <ErrorNote>{list.error}</ErrorNote>
      {list.data && list.data.length === 0 && <Empty>{status === 'open' ? 'Nobody is leaving.' : 'No exits here.'}</Empty>}
      {list.data && list.data.length > 0 && (
        <div className="table-wrap">
          <table className="stack">
            <thead>
              <tr>
                <th>Name</th>
                <th>Kind</th>
                <th>Stage</th>
                <th>Last day</th>
                <th>Open tasks</th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((x) => {
                const [tone, words] = STAGES[x.stage] || ['neutral', x.stage]
                const day = x.lastWorkingDay || x.recommendedLastDay || x.requestedLastDay || x.noticeEnd
                return (
                  <tr key={x.id} data-exit={nameOf(x)}>
                    <td>
                      <button type="button" className="text-link" onClick={() => onOpen(x.id)}>
                        {nameOf(x)}
                      </button>
                      <div className="hint">{[x.designation, x.departmentName, x.employeeCode].filter(Boolean).join(' · ')}</div>
                    </td>
                    <td>{KINDS[x.kind]}</td>
                    <td>
                      <Badge tone={tone}>{words}</Badge>
                    </td>
                    <td>
                      {day ? formatDay(day) : '—'}
                      {!x.lastWorkingDay && day && <div className="hint">asked for</div>}
                    </td>
                    <td>{x.openTasks || '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function ExitPage({ id, onBack }) {
  const exit = useLoad(() => exits.get(id))
  const [form, setForm] = useState(null) // recommend | accept | cancel | close
  if (exit.loading) return <p className="muted">Loading…</p>
  if (exit.error) return <ErrorNote>{exit.error}</ErrorNote>
  const x = exit.data
  const [tone, words] = STAGES[x.stage] || ['neutral', x.stage]
  const done = () => {
    setForm(null)
    exit.reload()
  }
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← All exits
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            {nameOf(x)} <Badge tone={tone}>{words}</Badge>
          </h2>
          <p className="muted">
            {[KINDS[x.kind], x.designation, x.departmentName, x.employeeCode].filter(Boolean).join(' · ')}
          </p>
        </div>
        {!form && (
          <div className="form-actions">
            {x.canRecommend && (
              <button type="button" className="primary" onClick={() => setForm('recommend')}>
                Recommend
              </button>
            )}
            {x.canAccept && (
              <button type="button" className="primary" onClick={() => setForm('accept')}>
                {x.status === 'proposed' ? 'Confirm' : 'Accept'}
              </button>
            )}
            {x.canClose && (
              <button type="button" className="secondary" onClick={() => setForm('close')}>
                {x.status === 'closed' ? 'Change rehire' : 'Close'}
              </button>
            )}
            {x.canCancel && (
              <button type="button" className="quiet danger" onClick={() => setForm('cancel')}>
                Cancel exit
              </button>
            )}
          </div>
        )}
      </div>

      {form === 'recommend' && <Recommend exit={x} onDone={done} onCancel={() => setForm(null)} />}
      {form === 'accept' && <Accept exit={x} onDone={done} onCancel={() => setForm(null)} />}
      {form === 'cancel' && <CancelExit exit={x} onDone={done} onCancel={() => setForm(null)} />}
      {form === 'close' && <CloseExit exit={x} onDone={done} onCancel={() => setForm(null)} />}
      {x.canDecideWithdrawal && <WithdrawalDecision exit={x} onDone={done} />}
      {!form && <ExitProgress exit={x} />}

      <div className="panels">
        <section className="panel">
          <h3>The exit</h3>
          <dl className="facts">
            <dt>{x.kind === 'resignation' ? 'Resigned on' : 'Started on'}</dt>
            <dd>{formatDay(x.startedOn)}</dd>
            <dt>Reason</dt>
            <dd>{REASONS[x.reasonCategory] || '—'}</dd>
            {x.reasonNote && (
              <>
                <dt>In their words</dt>
                <dd className="pre">{x.reasonNote}</dd>
              </>
            )}
            {x.requestedLastDay && (
              <>
                <dt>{x.kind === 'absconding' ? 'Last day at work' : 'Asked to leave on'}</dt>
                <dd>{formatDay(x.requestedLastDay)}</dd>
              </>
            )}
            {x.noticeDays > 0 && (
              <>
                <dt>Notice</dt>
                <dd>
                  {x.noticeDays} days, to {formatDay(x.noticeEnd)}
                </dd>
              </>
            )}
            {x.recommendedLastDay && (
              <>
                <dt>Manager agreed</dt>
                <dd>
                  {formatDay(x.recommendedLastDay)}
                  {x.recommendationNote && <div className="hint">{x.recommendationNote}</div>}
                </dd>
              </>
            )}
            <dt>Last working day</dt>
            <dd data-testid="exit-last-day">{x.lastWorkingDay ? formatDay(x.lastWorkingDay) : 'Not fixed yet'}</dd>
            {x.shortfallDays > 0 && (
              <>
                <dt>Notice not served</dt>
                <dd>
                  {x.shortfallDays} days · {SHORTFALL[x.shortfallAction]}
                </dd>
              </>
            )}
            {x.shortfallAction === 'paid_in_lieu' && x.shortfallDays === 0 && (
              <>
                <dt>Notice</dt>
                <dd>{SHORTFALL.paid_in_lieu}</dd>
              </>
            )}
            {x.newManagerName && (
              <>
                <dt>Reports move to</dt>
                <dd>{x.newManagerName}</dd>
              </>
            )}
            {x.formerAccessUntil && (
              <>
                <dt>Former-employee access until</dt>
                <dd>{formatDay(x.formerAccessUntil)}</dd>
              </>
            )}
            {x.rehire && (
              <>
                <dt>Could be hired again</dt>
                <dd>
                  {REHIRE[x.rehire]}
                  {x.rehireNote && <div className="hint">{x.rehireNote}</div>}
                </dd>
              </>
            )}
            {(x.legalHold || x.canHold || x.purgedAt) && (
              <>
                <dt>Legal hold</dt>
                <dd>
                  <LegalHold exit={x} />
                </dd>
              </>
            )}
            {x.cancelledReason && (
              <>
                <dt>Cancelled because</dt>
                <dd>{x.cancelledReason}</dd>
              </>
            )}
          </dl>
        </section>
        {x.absconding && <AbscondingSteps steps={x.absconding} />}
        {x.nominees && (
          <section className="panel" data-testid="nominees">
            <h3>Nominees</h3>
            {x.nominees.length === 0 ? (
              <p className="hint">No nominees were recorded. The settlement goes to the legal heirs; ask for proof of succession.</p>
            ) : (
              <ul className="blockers">
                {x.nominees.map((n) => (
                  <li key={`${n.scheme}-${n.name}`}>
                    {n.scheme.toUpperCase()}: {n.name} ({n.relationship.replaceAll('_', ' ')}), {Number(n.sharePct)}%
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
        {x.closeBlockers?.length > 0 && x.stage !== 'notice' && (
          <section className="panel">
            <h3>Before closing</h3>
            <ul className="blockers">
              {x.closeBlockers.map((b) => (
                <li key={b}>{b}</li>
              ))}
            </ul>
          </section>
        )}
      </div>
      {(x.tasks?.length > 0 || x.canManageTasks) && <Clearance exit={x} onChanged={exit.reload} />}
      <Settlement exit={x} onChanged={exit.reload} />
      <ExitLettersPanel exit={x} onChanged={exit.reload} />
      <InterviewPanel exit={x} onChanged={exit.reload} />
    </>
  )
}

/** The clearance checklist: tasks by owner, with HR able to add, reassign and skip. */
function Clearance({ exit: x, onChanged }) {
  const users = useLoad(() => org.users().catch(() => []))
  const [adding, setAdding] = useState(false)
  const active = (users.data || []).filter((u) => u.status === 'active')
  return (
    <section className="clearance">
      <div className="toolbar">
        <h3>
          Clearance{' '}
          <span className="hint">
            {x.clearance.done} of {x.clearance.total} done
          </span>
        </h3>
        <span className="spacer" />
        {x.canManageTasks && !adding && (
          <button type="button" className="outline" onClick={() => setAdding(true)}>
            Add a task
          </button>
        )}
      </div>
      {adding && (
        <AddExitTask
          id={x.id}
          users={active}
          onDone={() => {
            setAdding(false)
            onChanged()
          }}
          onCancel={() => setAdding(false)}
        />
      )}
      <Checklist tasks={x.tasks} hr={x.canManageTasks} users={active} onChanged={onChanged} taskApi={exitTasks} exit />
    </section>
  )
}

function AddExitTask({ id, users, onDone, onCancel }) {
  const [form, setForm] = useState({ title: '', assigneeId: '', dueDate: '', required: true })
  const { busy, error, run } = useAction()
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => exits.addTask(id, form))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="xt-title">Task</label>
          <input id="xt-title" value={form.title} placeholder="Collect the company phone" onChange={(e) => set({ title: e.target.value })} />
        </div>
        <div>
          <label htmlFor="xt-assignee">Who does it</label>
          <select id="xt-assignee" value={form.assigneeId} onChange={(e) => set({ assigneeId: e.target.value })}>
            <option value="">Me</option>
            {users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.email}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="xt-due">Due</label>
          <input id="xt-due" type="date" value={form.dueDate} onChange={(e) => set({ dueDate: e.target.value })} />
        </div>
      </div>
      <label className="check">
        <input type="checkbox" checked={form.required} onChange={(e) => set({ required: e.target.checked })} />
        Needed before the exit can be closed
      </label>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          Add task
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function Recommend({ exit: x, onDone, onCancel }) {
  const [lastDay, setLastDay] = useState(x.requestedLastDay || x.noticeEnd || localToday())
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => exits.recommend(x.id, { lastWorkingDay: lastDay, note }))) onDone()
      }}
    >
      <h3>Recommend a last working day</h3>
      <div className="grid">
        <div>
          <label htmlFor="rec-day">Last working day</label>
          <input id="rec-day" type="date" min={localToday()} value={lastDay} onChange={(e) => setLastDay(e.target.value)} />
          {x.noticeEnd && lastDay < x.noticeEnd && (
            <p className="hint">{daysBetween(lastDay, x.noticeEnd)} days before the notice ends: an early release.</p>
          )}
        </div>
        <div>
          <label htmlFor="rec-note">Note for HR (optional)</label>
          <input id="rec-note" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Send to HR
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/** The fields for fixing a last working day: the date, how a shortfall is settled, and who takes over the reports. */
const DAY_LABEL = { death: 'Date of passing', absconding: 'Last day at work' }

function LastDayFields({ value, set, noticeEnd, kind, employees, exclude, prefix, managerName }) {
  const shortfall = noticeEnd && value.lastWorkingDay && value.lastWorkingDay < noticeEnd ? daysBetween(value.lastWorkingDay, noticeEnd) : 0
  return (
    <div className="grid">
      <div>
        <label htmlFor={`${prefix}-day`}>{DAY_LABEL[kind] || 'Last working day'}</label>
        <input id={`${prefix}-day`} type="date" value={value.lastWorkingDay} onChange={(e) => set({ lastWorkingDay: e.target.value })} />
      </div>
      {(shortfall > 0 || kind === 'termination') && (
        <div>
          <label htmlFor={`${prefix}-shortfall`}>{shortfall > 0 ? `${shortfall} days of notice not served` : 'Notice'}</label>
          <select id={`${prefix}-shortfall`} value={value.shortfallAction} onChange={(e) => set({ shortfallAction: e.target.value })}>
            <option value="">{shortfall > 0 ? 'The Organization’s default' : 'None owed'}</option>
            {Object.entries(SHORTFALL)
              .filter(([k]) => (shortfall > 0 ? k !== 'paid_in_lieu' || kind !== 'resignation' : k === 'paid_in_lieu'))
              .map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
          </select>
        </div>
      )}
      <div>
        <label htmlFor={`${prefix}-successor`}>Their reports move to</label>
        <select id={`${prefix}-successor`} value={value.newManagerId} onChange={(e) => set({ newManagerId: e.target.value })}>
          <option value="">{managerName ? `Their own manager, ${managerName}` : 'Their own manager'}</option>
          {(employees || [])
            .filter((e) => e.id !== exclude)
            .map((e) => (
              <option key={e.id} value={e.id}>
                {e.preferredName || e.legalName}
                {e.designation ? `, ${e.designation}` : ''}
              </option>
            ))}
        </select>
      </div>
    </div>
  )
}

function Accept({ exit: x, onDone, onCancel }) {
  const employees = useLoad(() => people.employees({ status: 'active' }))
  const [value, setValue] = useState({
    lastWorkingDay: x.recommendedLastDay || x.requestedLastDay || x.noticeEnd || localToday(),
    shortfallAction: '',
    newManagerId: '',
  })
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => exits.accept(x.id, value))) onDone()
      }}
    >
      <h3>{x.status === 'proposed' ? 'Confirm the exit' : 'Accept the resignation'}</h3>
      {x.kind === 'absconding' && <p className="hint">The employment ends with the last day they came to work.</p>}
      <LastDayFields
        value={value}
        set={(patch) => setValue({ ...value, ...patch })}
        noticeEnd={x.noticeEnd}
        kind={x.kind}
        employees={employees.data}
        exclude={x.employmentId}
        prefix="acc"
        managerName={x.managerName}
      />
      <p className="hint">
        {x.kind === 'resignation'
          ? `Accepting tells ${nameOf(x)} and their manager, sends the acceptance letter, and starts the clearance checklist. The employment ends after the last working day.`
          : `Confirming tells their manager and starts the clearance checklist. The employment ends after the last working day.`}
      </p>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {busy ? 'Saving…' : x.status === 'proposed' ? 'Confirm' : 'Accept'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function WithdrawalDecision({ exit: x, onDone }) {
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  return (
    <section className="panel notice-panel">
      <h3>{nameOf(x)} asks to withdraw the resignation</h3>
      {x.withdrawalNote && <p className="pre">{x.withdrawalNote}</p>}
      <div className="inline-form">
        <div>
          <label htmlFor="wd-note">Note if you refuse (optional)</label>
          <input id="wd-note" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
        <div className="form-actions">
          <button type="button" className="primary" disabled={busy} onClick={async () => (await run(() => exits.decideWithdrawal(x.id, true))) && onDone()}>
            Approve: they stay
          </button>
          <button
            type="button"
            className="quiet danger"
            disabled={busy}
            onClick={async () => (await run(() => exits.decideWithdrawal(x.id, false, note))) && onDone()}
          >
            Refuse
          </button>
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

function CancelExit({ exit: x, onDone, onCancel }) {
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  return (
    <div className="panel inline-form">
      <div>
        <label htmlFor="cancel-note">Why cancel this exit</label>
        <input id="cancel-note" value={note} placeholder="The contract was extended" onChange={(e) => setNote(e.target.value)} />
      </div>
      <div className="form-actions">
        <button type="button" className="secondary danger" disabled={busy} onClick={async () => (await run(() => exits.cancel(x.id, note))) && onDone()}>
          Cancel the exit
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Keep it
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </div>
  )
}

function CloseExit({ exit: x, onDone, onCancel }) {
  const [rehire, setRehire] = useState(x.rehire || '')
  const [note, setNote] = useState(x.rehireNote || '')
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => exits.close(x.id, { rehire, rehireNote: note }))) onDone()
      }}
    >
      <h3>{x.status === 'closed' ? 'Could they be hired again?' : 'Close the exit'}</h3>
      <div className="grid">
        <div>
          <label htmlFor="close-rehire">Could they be hired again</label>
          <select id="close-rehire" value={rehire} onChange={(e) => setRehire(e.target.value)}>
            <option value="">Choose…</option>
            {Object.entries(REHIRE).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="close-note">Note (only HR sees it)</label>
          <input id="close-note" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {x.status === 'closed' ? 'Save' : 'Close the exit'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function StartExit({ employmentId, onSaved, onCancel }) {
  const employees = useLoad(() => people.employees({ status: 'active' }))
  const [form, setForm] = useState({
    employmentId: employmentId || '',
    kind: 'resignation',
    reasonCategory: '',
    note: '',
    lastWorkingDay: '',
    shortfallAction: '',
    newManagerId: '',
  })
  const { busy, error, run } = useAction()
  const set = (patch) => setForm({ ...form, ...patch })
  async function submit(event) {
    event.preventDefault()
    const saved = await run(() => exits.start(form))
    if (saved) onSaved(saved.id)
  }
  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>Start an exit</h2>
      <div className="grid">
        <div>
          <label htmlFor="se-employee">Employee</label>
          <select id="se-employee" value={form.employmentId} onChange={(e) => set({ employmentId: e.target.value })}>
            <option value="">Choose…</option>
            {(employees.data || []).map((e) => (
              <option key={e.id} value={e.id}>
                {e.preferredName || e.legalName}
                {e.employeeCode ? ` (${e.employeeCode})` : ''}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="se-kind">Kind</label>
          <select id="se-kind" value={form.kind} onChange={(e) => set({ kind: e.target.value, reasonCategory: '' })}>
            {Object.entries(KINDS).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="se-reason">Reason</label>
          <select id="se-reason" value={form.reasonCategory} onChange={(e) => set({ reasonCategory: e.target.value })}>
            <option value="">{form.kind === 'resignation' ? 'Choose…' : 'As the kind says'}</option>
            {Object.entries(REASONS).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </div>
      </div>
      <label htmlFor="se-note">Note (only HR and the employee see it)</label>
      <textarea id="se-note" rows={2} value={form.note} onChange={(e) => set({ note: e.target.value })} />
      {form.kind === 'resignation' && (
        <p className="hint">Leave the last working day empty to record a resignation handed in on paper; it then waits for acceptance as usual.</p>
      )}
      {form.kind === 'absconding' && (
        <p className="hint">
          The exit opens and waits: send the show-cause notice, then the final notice, each with the days to reply set in Setup. With no word
          back, confirm it.
        </p>
      )}
      {form.kind === 'death' && (
        <p className="hint">The employment ends at once. The settlement, with gratuity whatever the length of service, goes to the nominees.</p>
      )}
      <LastDayFields
        value={form}
        set={set}
        kind={form.kind}
        employees={employees.data}
        exclude={form.employmentId}
        prefix="se"
        managerName={(employees.data || []).find((e) => e.id === form.employmentId)?.managerName}
      />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          {busy ? 'Saving…' : 'Start the exit'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/** Absence without leave: the notices sent, and what the exit waits for. */
function AbscondingSteps({ steps }) {
  return (
    <section className="panel" data-testid="absconding">
      <h3>Absent without leave</h3>
      <dl className="facts">
        <dt>Last day at work</dt>
        <dd>{formatDay(steps.lastAtWork)}</dd>
        <dt>Show-cause notice</dt>
        <dd>{steps.showCauseOn ? `Sent ${formatDay(steps.showCauseOn)}` : 'Not sent'}</dd>
        <dt>Final notice</dt>
        <dd>{steps.finalNoticeOn ? `Sent ${formatDay(steps.finalNoticeOn)}` : 'Not sent'}</dd>
        <dt>Days to reply</dt>
        <dd>{steps.responseDays}</dd>
      </dl>
      {steps.waitingFor && <p className="hint">{steps.waitingFor}.</p>}
      <p className="hint">If they come back or reply, cancel the exit.</p>
    </section>
  )
}

/** Legal hold keeps the person's data past the retention period while a dispute or claim is open. */
function LegalHold({ exit: x }) {
  const [editing, setEditing] = useState(false)
  const [note, setNote] = useState('')
  const [held, setHeld] = useState(!!x.legalHold)
  const { busy, error, run } = useAction()
  if (x.purgedAt) return <span className="hint">Personal data removed on {formatDay(x.purgedAt.slice(0, 10))}, after the retention period</span>
  const set = async (hold) => {
    const saved = await run(() => exits.legalHold(x.id, { hold, note }))
    if (saved) {
      setHeld(!!saved.legalHold)
      setEditing(false)
      setNote('')
    }
  }
  return (
    <>
      {held ? <Badge tone="warn">On hold</Badge> : <span className="hint">None. Data goes after the retention period.</span>}{' '}
      {x.canHold && !editing && (
        <button type="button" className="quiet plain" onClick={() => (held ? set(false) : setEditing(true))} disabled={busy}>
          {held ? 'Lift the hold' : 'Put on hold…'}
        </button>
      )}
      {editing && (
        <div className="inline-form">
          <div>
            <label htmlFor="hold-note">For which dispute or claim</label>
            <input id="hold-note" value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="form-actions">
            <button type="button" className="secondary" disabled={busy} onClick={() => set(true)}>
              Hold
            </button>
            <button type="button" className="quiet" onClick={() => setEditing(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
    </>
  )
}
