import { useState } from 'react'
import { exits, people } from '../api.js'
import { formatDay, localToday, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { KINDS, REASONS, REHIRE, SHORTFALL, STAGES, daysBetween } from './exitText.js'

/**
 * Exits: every exit for HR, the team's for a manager. A list by stage, and one exit at a time
 * with what the caller may do next: recommend, accept, decide a withdrawal, cancel or close.
 */
export default function Exits({ me }) {
  const canManage = (me.scopes || {})['exit.manage'] === 'tenant' && me.kind === 'tenant'
  const [open, setOpen] = useState(null) // an exit id, or 'new'
  if (open === 'new') return <StartExit onSaved={(id) => setOpen(id)} onCancel={() => setOpen(null)} />
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
          <table>
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
                      <button type="button" className="quiet plain" onClick={() => onOpen(x.id)}>
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
                <dt>Asked to leave on</dt>
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
            {x.cancelledReason && (
              <>
                <dt>Cancelled because</dt>
                <dd>{x.cancelledReason}</dd>
              </>
            )}
          </dl>
        </section>
        {x.closeBlockers?.length > 0 && (
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
    </>
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
function LastDayFields({ value, set, noticeEnd, kind, employees, exclude, prefix }) {
  const shortfall = noticeEnd && value.lastWorkingDay && value.lastWorkingDay < noticeEnd ? daysBetween(value.lastWorkingDay, noticeEnd) : 0
  return (
    <div className="grid">
      <div>
        <label htmlFor={`${prefix}-day`}>Last working day</label>
        <input id={`${prefix}-day`} type="date" value={value.lastWorkingDay} onChange={(e) => set({ lastWorkingDay: e.target.value })} />
      </div>
      {(shortfall > 0 || (kind && kind !== 'resignation')) && (
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
          <option value="">Their own manager</option>
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
      <LastDayFields
        value={value}
        set={(patch) => setValue({ ...value, ...patch })}
        noticeEnd={x.noticeEnd}
        kind={x.kind}
        employees={employees.data}
        exclude={x.employmentId}
        prefix="acc"
      />
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

function StartExit({ onSaved, onCancel }) {
  const employees = useLoad(() => people.employees({ status: 'active' }))
  const [form, setForm] = useState({
    employmentId: '',
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
      <LastDayFields value={form} set={set} kind={form.kind} employees={employees.data} exclude={form.employmentId} prefix="se" />
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
