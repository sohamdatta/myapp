import { useState } from 'react'
import { tds } from '../api.js'
import { useConfirm, useToast } from '../feedbackContext.js'
import { formatDay, localToday, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote, Tabs } from '../ui.jsx'
import { QUARTERS, canDo, rupees } from './payrollText.js'

const LEVELS = { blocking: ['danger', 'Blocking'], warning: ['warn', 'Warning'], info: ['neutral', 'Note'] }

function Statement({ s, newest, me, onChanged }) {
  const [accepting, setAccepting] = useState(null) // { check, reason }
  const [filing, setFiling] = useState(null) // { token, on }
  const { busy, error, run } = useAction()
  const toast = useToast()
  const canPrepare = canDo(me, 'payroll.prepare')
  const canApprove = canDo(me, 'payroll.approve')
  const isAccepted = (c) => s.accepted.some((a) => a.code === c.code && a.who === c.who)
  const filed = s.status === 'filed'

  async function accept(e) {
    e.preventDefault()
    const reason = accepting.reason.trim()
    if (reason.length < 3 || reason.length > 200) return
    if (await run(() => tds.accept(s.id, accepting.check.code, accepting.check.who, reason))) {
      setAccepting(null)
      toast('Accepted')
      onChanged()
    }
  }

  async function recordFiled(e) {
    e.preventDefault()
    if (!filing.token.trim() || !filing.on) return
    if (await run(() => tds.filed(s.id, filing.token.trim(), filing.on))) {
      setFiling(null)
      toast('Filing recorded')
      onChanged()
    }
  }

  return (
    <div className="panel" data-version={s.version}>
      <div className="section-head">
        <h4>
          Version {s.version} <Badge tone={filed ? 'good' : 'neutral'}>{filed ? 'Filed' : 'Draft'}</Badge>
        </h4>
        <span className="hint">
          TAN {s.tan}
          {s.correctionOf ? ' · a correction of the filed statement' : ''}
        </span>
      </div>
      <p className="hint">
        {s.totals.lines} lines · paid {rupees(s.totals.amountPaid, true)} · tax deducted {rupees(s.totals.taxDeducted, true)} · challans{' '}
        {rupees(s.totals.challanTotal, true)}
      </p>
      {s.checks.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Check</th>
                <th>What</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {s.checks.map((c) => (
                <tr key={`${c.code}-${c.who}`}>
                  <td>
                    <Badge tone={LEVELS[c.level]?.[0]}>{LEVELS[c.level]?.[1] ?? c.level}</Badge>
                  </td>
                  <td>
                    {c.who ? <strong>{c.who}: </strong> : null}
                    {c.message}
                    {isAccepted(c) && <span className="hint"> Accepted: {s.accepted.find((a) => a.code === c.code && a.who === c.who).reason}</span>}
                    {c.level === 'blocking' && !c.acceptable && !isAccepted(c) && <span className="hint"> Must be fixed.</span>}
                  </td>
                  <td>
                    {!filed && newest && canApprove && c.level === 'blocking' && c.acceptable && !isAccepted(c) && accepting?.check !== c && (
                      <button type="button" className="outline" disabled={busy} onClick={() => setAccepting({ check: c, reason: '' })}>
                        Accept…
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {accepting && (
        <form className="inline-form" onSubmit={accept} aria-label="Accept this check">
          <label>
            Why it is acceptable
            <input value={accepting.reason} maxLength={200} onChange={(e) => setAccepting({ ...accepting, reason: e.target.value })} />
          </label>
          <div className="form-actions">
            <button type="submit" disabled={busy || accepting.reason.trim().length < 3}>
              Accept
            </button>
            <button type="button" className="quiet" onClick={() => setAccepting(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
      {!filed && s.unresolved > 0 && <p className="hint">{s.unresolved} to resolve before the file can be downloaded.</p>}
      <ErrorNote>{error}</ErrorNote>
      {filed && (
        <p className="hint">
          Filed {formatDay(s.filedOn)}, token {s.tokenNumber}
          {s.daysLate > 0 ? ` · ${s.daysLate} days late` : ''}
        </p>
      )}
      {!filed && newest && canPrepare && !filing && (
        <div className="form-actions">
          <button type="button" className="outline" disabled={busy || s.unresolved > 0} onClick={() => run(() => tds.file(s.id, s.fileName))}>
            Download review spreadsheet
          </button>
          <button type="button" disabled={busy || s.unresolved > 0} onClick={() => setFiling({ token: '', on: localToday() })}>
            Record filing…
          </button>
        </div>
      )}
      {filing && (
        <form className="inline-form" onSubmit={recordFiled} aria-label="Record the filing">
          <label>
            Token number
            <input value={filing.token} onChange={(e) => setFiling({ ...filing, token: e.target.value })} autoComplete="off" />
          </label>
          <label>
            Filed on
            <input type="date" max={localToday()} value={filing.on} onChange={(e) => setFiling({ ...filing, on: e.target.value })} />
          </label>
          <div className="form-actions">
            <button type="submit" disabled={busy || !filing.token.trim() || !filing.on}>
              Record filing
            </button>
            <button type="button" className="quiet" onClick={() => setFiling(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </div>
  )
}

function Quarter({ taxYear, quarter, me, onSetup }) {
  const load = useLoad(() => tds.quarter(taxYear, quarter))
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()
  const canPrepare = canDo(me, 'payroll.prepare')
  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  const q = load.data
  const anyFiled = q.statements.some((s) => s.status === 'filed')

  async function build() {
    if (anyFiled && !(await confirm({ title: 'Build a correction?', body: 'This quarter is already filed. The new version holds only what changed.', confirmLabel: 'Build a correction' }))) return
    const result = await run(() => tds.build(taxYear, quarter))
    if (result) {
      toast(result.correction ? 'Correction built' : 'Statement built')
      load.reload()
    }
  }

  return (
    <div data-testid="tds-statements">
      <div className="section-head">
        <p className="hint">
          Form 138 for {QUARTERS[quarter]}, due {formatDay(q.dueOn)}. You file it with the department’s own tools; this is the review copy.
        </p>
        {canPrepare && (
          <button type="button" disabled={busy} onClick={build}>
            {anyFiled ? 'Build a correction' : 'Build statement'}
          </button>
        )}
      </div>
      <ErrorNote>{error}</ErrorNote>
      {error.includes('TAN') && onSetup && (
        <p>
          <button type="button" className="text-link" onClick={onSetup}>
            Open Setup to add the TAN
          </button>
        </p>
      )}
      {q.statements.length === 0 ? (
        <Empty>No statement for this quarter yet. Build it once the quarter’s runs are approved and their deposits approved.</Empty>
      ) : (
        q.statements.map((s, i) => <Statement key={s.id} s={s} newest={i === 0} me={me} onChanged={load.reload} />)
      )}
    </div>
  )
}

/** Payroll → Tax filing → Statements: the quarterly Form 138 for the year, with its checks and the filing record. */
export default function TdsStatements({ taxYear, me, onSetup }) {
  const [quarter, setQuarter] = useState(1)
  return (
    <>
      <Tabs
        tabs={[1, 2, 3, 4].map((q) => ({ id: String(q), label: `Q${q} ${QUARTERS[q]}` }))}
        current={String(quarter)}
        onChange={(id) => setQuarter(Number(id))}
      />
      <Quarter key={`${taxYear}-${quarter}`} taxYear={taxYear} quarter={quarter} me={me} onSetup={onSetup} />
    </>
  )
}
