import { useState } from 'react'
import { exits } from '../api.js'
import { formatDay, localToday, useAction, useLoad } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'
import { KINDS, RESIGN_REASONS, SHORTFALL, STAGES } from './exitText.js'

/**
 * My exit: resigning, and everything about leaving once it has started: the notice, the last
 * working day, withdrawing, and later the clearance, interview, letters and settlement.
 */
export default function MyExit() {
  const mine = useLoad(exits.mine)
  if (mine.loading) return <p className="muted">Loading…</p>
  if (mine.error) return <ErrorNote>{mine.error}</ErrorNote>
  const m = mine.data
  const x = m.exit
  const open = x && ['submitted', 'recommended', 'accepted', 'closed'].includes(x.status)
  return (
    <div className="my-exit">
      {open ? <ExitSummary exit={x} onChanged={mine.reload} /> : null}
      {!open && m.employed && <Resign mine={m} previous={x} onDone={mine.reload} />}
      {!open && !m.employed && <p className="muted">There is nothing about leaving to show.</p>}
    </div>
  )
}

function Resign({ mine, previous, onDone }) {
  const [form, setForm] = useState(false)
  const [reason, setReason] = useState('')
  const [note, setNote] = useState('')
  const [lastDay, setLastDay] = useState(mine.proposedLastDay)
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    if (await run(() => exits.resign({ reasonCategory: reason, note, requestedLastDay: lastDay }))) onDone()
  }

  return (
    <section className="panel">
      <h2>Resigning</h2>
      {previous?.status === 'withdrawn' && <p className="hint">You withdrew your earlier resignation.</p>}
      <p>
        Your notice period is <strong>{mine.noticeDays} days</strong>. Resigning today, your last working day would be{' '}
        <strong>{formatDay(mine.proposedLastDay)}</strong>. You can ask for an earlier day; your manager and HR decide.
      </p>
      {!form ? (
        <button type="button" className="outline danger" onClick={() => setForm(true)}>
          Resign…
        </button>
      ) : (
        <form onSubmit={submit} noValidate>
          <div className="grid">
            <div>
              <label htmlFor="resign-reason">Main reason</label>
              <select id="resign-reason" value={reason} onChange={(e) => setReason(e.target.value)}>
                <option value="">Choose…</option>
                {Object.entries(RESIGN_REASONS).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="resign-last-day">Last day you would like</label>
              <input id="resign-last-day" type="date" min={localToday()} value={lastDay} onChange={(e) => setLastDay(e.target.value)} />
            </div>
          </div>
          <label htmlFor="resign-note">
            Anything you want to say <span className="hint">(optional; only HR and you see it)</span>
          </label>
          <textarea id="resign-note" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              {busy ? 'Sending…' : 'Send my resignation'}
            </button>
            <button type="button" className="quiet" onClick={() => setForm(false)}>
              Cancel
            </button>
          </div>
          <p className="hint">Until it is accepted you can withdraw it in one click. After that, HR decides.</p>
        </form>
      )}
    </section>
  )
}

function ExitSummary({ exit: x, onChanged }) {
  const [withdrawing, setWithdrawing] = useState(false)
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  const [tone, , words] = STAGES[x.stage] || ['neutral', x.stage, x.stage]
  const lastDay = x.lastWorkingDay || x.recommendedLastDay || x.requestedLastDay || x.noticeEnd

  async function withdraw() {
    if (await run(() => exits.withdraw(note))) {
      setWithdrawing(false)
      onChanged()
    }
  }

  return (
    <section className="panel exit-summary">
      <div className="section-head">
        <h2>
          {x.kind === 'resignation' ? 'Your resignation' : KINDS[x.kind]} <Badge tone={tone}>{words}</Badge>
        </h2>
      </div>
      <dl className="facts">
        <dt>Given on</dt>
        <dd>{formatDay(x.startedOn)}</dd>
        {x.noticeDays > 0 && (
          <>
            <dt>Notice</dt>
            <dd>
              {x.noticeDays} days, to {formatDay(x.noticeEnd)}
            </dd>
          </>
        )}
        <dt>{x.lastWorkingDay ? 'Last working day' : 'Last day asked for'}</dt>
        <dd data-testid="my-last-day">{lastDay ? formatDay(lastDay) : '—'}</dd>
        {x.shortfallDays > 0 && (
          <>
            <dt>Notice not served</dt>
            <dd>
              {x.shortfallDays} days · {SHORTFALL[x.shortfallAction]}
            </dd>
          </>
        )}
      </dl>
      {x.stage === 'withdrawal_asked' && <p className="hint">You asked to withdraw your resignation. HR will decide.</p>}
      <ErrorNote>{error}</ErrorNote>
      {x.canWithdraw && !withdrawing && (
        <button type="button" className="quiet danger" onClick={() => setWithdrawing(true)}>
          {x.status === 'accepted' ? 'Ask to withdraw my resignation' : 'Withdraw my resignation'}
        </button>
      )}
      {withdrawing && (
        <div className="inline-form">
          <div>
            <label htmlFor="withdraw-note">Why {x.status === 'accepted' ? '(HR reads this)' : '(optional)'}</label>
            <input id="withdraw-note" value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="form-actions">
            <button type="button" className="secondary" disabled={busy} onClick={withdraw}>
              {x.status === 'accepted' ? 'Ask to withdraw' : 'Withdraw'}
            </button>
            <button type="button" className="quiet" onClick={() => setWithdrawing(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
