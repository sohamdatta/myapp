import { useState } from 'react'
import { filings } from '../api.js'
import { useConfirm, useToast } from '../feedbackContext.js'
import { formatDay, localToday, numeric, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { KINDS as EXIT_KINDS } from './exitText.js'
import { ESI_CODES, FILING_KINDS, FILING_STATES, canDo, rupees } from './payrollText.js'

const LEVELS = { blocking: ['danger', 'Blocking'], warning: ['warn', 'Warning'], info: ['neutral', 'Note'] }
const lastMonth = () => {
  const d = new Date(`${localToday()}T00:00:00`)
  d.setMonth(d.getMonth() - 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
const validMonth = (m) => /^\d{4}-(0[1-9]|1[0-2])$/.test(m) && Number(m.slice(0, 4)) >= 2000

const TOTALS = [
  ['members', 'Members', (v) => v],
  ['wages', 'Wages', rupees],
  ['employeeShare', 'Employee share', rupees],
  ['pension', 'Pension', rupees],
  ['employerShare', 'Employer share', rupees],
  ['contributions', 'Contributions', rupees],
  ['runTotal', 'In the run', rupees],
]

function Version({ f, slot, newest, me, onChanged }) {
  const [recording, setRecording] = useState(null)
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()
  const canPrepare = canDo(me, 'payroll.prepare')
  const canApprove = canDo(me, 'payroll.approve')
  const blocking = f.checks.filter((c) => c.level === 'blocking').length

  async function download() {
    if (await run(() => filings.file(f.id, f.fileName))) onChanged()
  }

  async function record(e) {
    e.preventDefault()
    try {
      const amount = numeric(recording.amount, 'Amount')
      if (!recording.reference.trim() || !recording.paidOn || amount === null) throw new Error('Give the reference number, the date paid and the amount')
      if (await run(() => filings.record(f.id, { referenceNumber: recording.reference.trim(), paidOn: recording.paidOn, amount }))) {
        setRecording(null)
        toast('Filing recorded')
        onChanged()
      }
    } catch (err) {
      setRecording({ ...recording, problem: err.message })
    }
  }

  async function approve() {
    if (!(await confirm({ title: 'Approve this filing?', body: `Reference ${f.referenceNumber}, ${rupees(f.amountPaid, true)}.`, confirmLabel: 'Approve' }))) return
    if (await run(() => filings.approve(f.id))) {
      toast('Filing approved')
      onChanged()
    }
  }

  return (
    <div className="version" data-version={f.version}>
      <p>
        <strong>Version {f.version}</strong> <Badge tone={FILING_STATES[f.status]?.[0]}>{FILING_STATES[f.status]?.[1] ?? f.status}</Badge>{' '}
        <span className="hint">built {formatDay(f.generatedAt.slice(0, 10))}</span>
      </p>
      <p className="hint">
        {TOTALS.filter(([k]) => f.totals[k] !== null && f.totals[k] !== undefined)
          .map(([k, label, fmt]) => `${label} ${fmt(f.totals[k])}`)
          .join(' · ')}
      </p>
      {f.checks.length > 0 && (
        <ul className="checks">
          {[...f.checks]
            .sort((a, b) => (a.level === 'blocking' ? 0 : 1) - (b.level === 'blocking' ? 0 : 1))
            .map((c, i) => (
              <li key={`${c.code}-${i}`}>
                <Badge tone={LEVELS[c.level]?.[0]}>{LEVELS[c.level]?.[1] ?? c.level}</Badge> {c.who ? <strong>{c.who}: </strong> : null}
                {c.message}
              </li>
            ))}
        </ul>
      )}
      {f.referenceNumber && (
        <p className="hint">
          Reference {f.referenceNumber} · paid {formatDay(f.paidOn)} · {rupees(f.amountPaid, true)}
          {f.daysLate > 0 ? ` · ${f.daysLate} days late` : ''}
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      {newest && canPrepare && ['draft', 'downloaded'].includes(f.status) && !recording && (
        <div className="form-actions">
          <button type="button" className="outline" disabled={busy || blocking > 0} onClick={download}>
            Download file
          </button>
          {blocking > 0 && <span className="hint">Fix the blocking checks and build again.</span>}
          {f.status === 'downloaded' && (
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                setRecording({ reference: '', paidOn: localToday(), amount: String(f.totals.contributions ?? f.totals.runTotal ?? ''), problem: '' })
              }
            >
              Record filing…
            </button>
          )}
        </div>
      )}
      {recording && (
        <form className="inline-form" onSubmit={record} aria-label="Record the filing">
          <label>
            {slot.kind === 'pf_ecr' ? 'TRRN' : 'Challan number'}
            <input value={recording.reference} onChange={(e) => setRecording({ ...recording, reference: e.target.value })} autoComplete="off" />
          </label>
          <label>
            Paid on
            <input type="date" max={localToday()} value={recording.paidOn} onChange={(e) => setRecording({ ...recording, paidOn: e.target.value })} />
          </label>
          <label>
            Amount paid
            <input inputMode="decimal" value={recording.amount} onChange={(e) => setRecording({ ...recording, amount: e.target.value })} />
          </label>
          <ErrorNote>{recording.problem}</ErrorNote>
          <div className="form-actions">
            <button type="submit" disabled={busy}>
              Record filing
            </button>
            <button type="button" className="quiet" onClick={() => setRecording(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
      {newest && canApprove && f.status === 'recorded' && f.recordedByMe && <p className="hint">Another person approves what you recorded.</p>}
      {newest && canApprove && f.status === 'recorded' && !f.recordedByMe && (
        <button type="button" disabled={busy} onClick={approve}>
          Approve
        </button>
      )}
    </div>
  )
}

function Slot({ slot, month, me, onChanged }) {
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()
  const canPrepare = canDo(me, 'payroll.prepare')
  const [latest, ...older] = slot.versions

  async function build() {
    if (latest && ['recorded', 'filed'].includes(latest.status)) {
      if (!(await confirm({ title: 'Build a new version?', body: 'This month already has a recorded filing. A new version replaces it for the log.', confirmLabel: 'Build again' }))) return
    }
    const result = await run(() => filings.build(slot.kind, month))
    if (result) {
      toast(`Version ${result.version} built`)
      onChanged()
    }
  }

  return (
    <section className="panel" data-kind={slot.kind}>
      <div className="section-head">
        <h3>
          {FILING_KINDS[slot.kind]} <span className="hint">{slot.registration ? `registration ${slot.registration}` : ''}</span>
        </h3>
        <span className="hint">Due {formatDay(slot.dueOn)}</span>
      </div>
      {!slot.registration && <p className="hint">Add the registration under Setup to build this return.</p>}
      <ErrorNote>{error}</ErrorNote>
      {canPrepare && slot.registration && (
        <button type="button" className={latest ? 'outline' : undefined} disabled={busy} onClick={build}>
          {latest ? 'Build again' : 'Build and check'}
        </button>
      )}
      {!latest && <Empty>Nothing built for this month.</Empty>}
      {latest && <Version f={latest} slot={slot} newest me={me} onChanged={onChanged} />}
      {older.length > 0 && (
        <details>
          <summary>Earlier versions ({older.length})</summary>
          <table>
            <thead>
              <tr>
                <th>Version</th>
                <th>Status</th>
                <th>Built</th>
                <th className="num">Members</th>
              </tr>
            </thead>
            <tbody>
              {older.map((f) => (
                <tr key={f.id}>
                  <td>{f.version}</td>
                  <td>{FILING_STATES[f.status]?.[1] ?? f.status}</td>
                  <td>{formatDay(f.generatedAt.slice(0, 10))}</td>
                  <td className="num">{f.totals.members}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
    </section>
  )
}

function Month({ month, me }) {
  const load = useLoad(() => filings.month(month))
  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  return (
    <div className="run-groups">
      {load.data.filings.map((slot) => (
        <Slot key={`${slot.kind}-${slot.registration}`} slot={slot} month={month} me={me} onChanged={load.reload} />
      ))}
      {load.data.filings.length === 0 && <Empty>No PF or ESI registration is set up. Add one under Setup.</Empty>}
    </div>
  )
}

/** The ESIC reason code used for each way of leaving, in the file's column for people with no wages. */
function EsiReasons({ me }) {
  const load = useLoad(filings.reasons)
  const [draft, setDraft] = useState({})
  const { busy, error, run } = useAction()
  const toast = useToast()
  const canPrepare = canDo(me, 'payroll.prepare')
  const [rows, setRows] = useState(null)
  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  const list = rows || load.data

  async function save(kind) {
    const code = Number(draft[kind])
    if (!Number.isInteger(code) || code < 0 || code > 99) return
    const next = await run(() => filings.setReason(kind, code))
    if (next) {
      setRows(next)
      setDraft({ ...draft, [kind]: undefined })
      toast('Saved')
    }
  }

  return (
    <section className="panel" data-testid="esi-reasons">
      <h3>ESI reason codes</h3>
      <p className="hint">Used in the ESI file when someone has no wages in the month and has left.</p>
      <ErrorNote>{error}</ErrorNote>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>How they left</th>
              <th>ESIC code</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {list.map((r) => {
              const value = draft[r.exitKind] ?? String(r.esicCode)
              return (
                <tr key={r.exitKind} data-kind={r.exitKind}>
                  <td>{EXIT_KINDS[r.exitKind] ?? r.exitKind}</td>
                  <td>
                    <input
                      type="number"
                      min={0}
                      max={99}
                      value={value}
                      disabled={!canPrepare}
                      aria-label={`ESIC code for ${EXIT_KINDS[r.exitKind] ?? r.exitKind}`}
                      onChange={(e) => setDraft({ ...draft, [r.exitKind]: e.target.value })}
                    />{' '}
                    <span className="hint">{ESI_CODES[Number(value)] ?? ''}</span>
                  </td>
                  <td>
                    {canPrepare && draft[r.exitKind] !== undefined && draft[r.exitKind] !== String(r.esicCode) && (
                      <button type="button" className="outline" disabled={busy} onClick={() => save(r.exitKind)}>
                        Save
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

/** Payroll → Statutory filing: the monthly PF (ECR) and ESI files, checked before download, and the log of when each was filed. */
export default function StatutoryFiling({ me }) {
  const [month, setMonth] = useState(lastMonth)
  return (
    <>
      <section className="panel">
        <div className="section-head">
          <h3>PF and ESI returns</h3>
          <label className="muted">
            Month
            <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month of the return" />
          </label>
        </div>
        <p className="hint">Both are due by the 15th of the next month. Build from the approved run, fix any blocking checks, download, file on the portal, then record it here.</p>
        {validMonth(month) ? <Month key={month} month={month} me={me} /> : <p className="hint">Choose a month.</p>}
      </section>
      <EsiReasons me={me} />
    </>
  )
}
