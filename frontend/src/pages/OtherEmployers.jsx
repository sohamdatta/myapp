import { useState } from 'react'
import { otherEmployers } from '../api.js'
import { useConfirm, useToast } from '../feedbackContext.js'
import { numeric, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { rupees } from './payrollText.js'
import { OTHER_EMPLOYER } from './taxText.js'

const TAN = /^[A-Z]{4}[0-9]{5}[A-Z]$/
const BLANK = { employerName: '', employerTan: '', monthsWorked: '', taxableSalary: '', pf: '', professionalTax: '', tds: '' }

/** What the form holds, as the server takes it; throws a sentence the form shows when something is wrong. */
function otherEmployerBody(form, taxYear) {
  const employerName = form.employerName.trim()
  if (employerName.length < 2) throw new Error('Give the employer’s name')
  const employerTan = form.employerTan.trim().toUpperCase()
  if (employerTan && !TAN.test(employerTan)) throw new Error('A TAN is four letters, five digits and a letter, like BLRA12345B')
  const monthsWorked = numeric(form.monthsWorked, 'Months worked', { whole: true })
  if (!monthsWorked || monthsWorked < 1 || monthsWorked > 12) throw new Error('Months worked is between 1 and 12')
  const taxableSalary = numeric(form.taxableSalary, 'Taxable salary')
  if (taxableSalary === null || taxableSalary < 0) throw new Error('Give the taxable salary from that employer')
  const pf = numeric(form.pf, 'PF') ?? 0
  const professionalTax = numeric(form.professionalTax, 'Professional tax') ?? 0
  const tds = numeric(form.tds, 'Tax deducted') ?? 0
  if (tds > taxableSalary) throw new Error('Tax deducted cannot be more than the taxable salary')
  return { taxYear, employerName, employerTan: employerTan || null, monthsWorked, taxableSalary, pf, professionalTax, tds }
}

function Figures({ e }) {
  return (
    <span className="hint">
      {e.employerTan ? `TAN ${e.employerTan} · ` : ''}
      {e.monthsWorked} {e.monthsWorked === 1 ? 'month' : 'months'} · taxable salary {rupees(e.taxableSalary)} · PF {rupees(e.pf)} · professional tax{' '}
      {rupees(e.professionalTax)} · tax deducted {rupees(e.tds)}
    </span>
  )
}

function EntryForm({ taxYear, entry, onDone, onCancel }) {
  const [form, setForm] = useState(
    entry
      ? {
          employerName: entry.employerName,
          employerTan: entry.employerTan || '',
          monthsWorked: String(entry.monthsWorked),
          taxableSalary: String(entry.taxableSalary),
          pf: String(entry.pf),
          professionalTax: String(entry.professionalTax),
          tds: String(entry.tds),
        }
      : BLANK,
  )
  const [problem, setProblem] = useState('')
  const { busy, error, run } = useAction()
  const set = (key) => (e) => setForm({ ...form, [key]: e.target.value })

  async function save(e) {
    e.preventDefault()
    setProblem('')
    let body
    try {
      body = otherEmployerBody(form, taxYear)
    } catch (err) {
      setProblem(err.message)
      return
    }
    const saved = await run(() => (entry ? otherEmployers.update(entry.id, body) : otherEmployers.add(body)))
    if (saved) onDone()
  }

  return (
    <form className="inline-form" onSubmit={save} aria-label={entry ? 'Change earlier employer' : 'Add earlier employer'}>
      <label>
        Employer
        <input value={form.employerName} onChange={set('employerName')} autoComplete="off" />
      </label>
      <label>
        TAN <span className="hint">(optional)</span>
        <input value={form.employerTan} onChange={set('employerTan')} autoCapitalize="characters" autoComplete="off" maxLength={10} />
      </label>
      <label>
        Months worked there
        <select value={form.monthsWorked} onChange={set('monthsWorked')}>
          <option value="">Choose…</option>
          {Array.from({ length: 12 }, (_, i) => i + 1).map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
      </label>
      <label>
        Taxable salary
        <input inputMode="decimal" value={form.taxableSalary} onChange={set('taxableSalary')} />
      </label>
      <label>
        PF
        <input inputMode="decimal" value={form.pf} onChange={set('pf')} />
      </label>
      <label>
        Professional tax
        <input inputMode="decimal" value={form.professionalTax} onChange={set('professionalTax')} />
      </label>
      <label>
        Tax deducted (TDS)
        <input inputMode="decimal" value={form.tds} onChange={set('tds')} />
      </label>
      <ErrorNote>{problem || error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" disabled={busy}>
          {entry ? 'Save changes' : 'Add employer'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/**
 * My pay → Tax declaration: salary from earlier employers this tax year (Form 122). Payroll counts it as income and as tax
 * already paid, so the months left are not under-deducted. It counts under both regimes.
 */
export function OtherEmployers({ taxYear }) {
  const load = useLoad(() => otherEmployers.mine(taxYear))
  const [editing, setEditing] = useState(null) // 'new' or an entry
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()

  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  const entries = load.data

  async function remove(entry) {
    if (!(await confirm({ title: `Remove ${entry.employerName}?`, body: 'Payroll stops counting it.', confirmLabel: 'Remove', danger: true }))) return
    if (await run(() => otherEmployers.remove(entry.id))) {
      toast(`Removed ${entry.employerName}`)
      load.reload()
    }
  }

  return (
    <section className="panel" data-testid="other-employers">
      <div className="section-head">
        <h3>Salary from an earlier employer this year (Form 122)</h3>
        {editing === null && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            Declare an employer
          </button>
        )}
      </div>
      <p className="hint">
        If you joined part-way through the year, payroll counts what that employer paid and deducted, so your remaining months are taxed
        correctly. HR checks it against your relieving statement or last payslips.
      </p>
      <ErrorNote>{error}</ErrorNote>
      {entries.length === 0 && editing === null && <Empty>No earlier employer declared for {taxYear}. Add one if you joined mid-year.</Empty>}
      <div className="doc-list">
        {entries.map((e) =>
          editing && editing !== 'new' && editing.id === e.id ? (
            <EntryForm
              key={e.id}
              taxYear={taxYear}
              entry={e}
              onCancel={() => setEditing(null)}
              onDone={() => {
                setEditing(null)
                toast('Saved')
                load.reload()
              }}
            />
          ) : (
            <div key={e.id} className="doc-row" data-employer={e.employerName}>
              <div>
                <strong>{e.employerName}</strong> <Badge tone={OTHER_EMPLOYER[e.status]?.[0]}>{OTHER_EMPLOYER[e.status]?.[1] ?? e.status}</Badge>
                <br />
                <Figures e={e} />
                {e.status === 'returned' && e.reviewNote && <p className="warn-notice">HR returned this: {e.reviewNote}</p>}
                {e.status === 'accepted' && <p className="hint">Accepted by HR. Ask HR to return it if it must change.</p>}
              </div>
              {e.status !== 'accepted' && (
                <div className="form-actions">
                  <button type="button" className="outline" disabled={busy} onClick={() => setEditing(e)}>
                    Change
                  </button>
                  <button type="button" className="quiet" disabled={busy} onClick={() => remove(e)}>
                    Remove
                  </button>
                </div>
              )}
            </div>
          ),
        )}
      </div>
      {editing === 'new' && (
        <EntryForm
          taxYear={taxYear}
          onCancel={() => setEditing(null)}
          onDone={() => {
            setEditing(null)
            toast('Added')
            load.reload()
          }}
        />
      )}
    </section>
  )
}

/** On an employee's tax page for HR: their Form 122 entries, to accept or send back with a note. */
export function OtherEmployerReview({ employmentId, taxYear, canReview }) {
  const load = useLoad(() => otherEmployers.ofEmployee(employmentId, taxYear))
  const [returning, setReturning] = useState(null) // { entry, note }
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()

  if (load.loading || load.error || load.data.length === 0) return load.error ? <ErrorNote>{load.error}</ErrorNote> : null

  async function accept(entry) {
    if (!(await confirm({ title: `Accept ${entry.employerName}?`, body: 'Payroll counts these figures from the next run.', confirmLabel: 'Accept' }))) return
    if (await run(() => otherEmployers.accept(entry.id, entry.updatedAt))) {
      toast('Accepted')
      load.reload()
    }
  }

  async function sendBack(e) {
    e.preventDefault()
    const note = returning.note.trim()
    if (note.length < 3 || note.length > 400) return
    if (await run(() => otherEmployers.sendBack(returning.entry.id, note, returning.entry.updatedAt))) {
      setReturning(null)
      toast('Sent back')
      load.reload()
    }
  }

  const noteLength = returning?.note.trim().length ?? 0
  return (
    <section className="panel" data-testid="other-employer-review">
      <h3>Earlier employers (Form 122)</h3>
      <ErrorNote>{error}</ErrorNote>
      <div className="doc-list">
        {load.data.map((e) => (
          <div key={e.id} className="doc-row" data-employer={e.employerName}>
            <div>
              <strong>{e.employerName}</strong> <Badge tone={OTHER_EMPLOYER[e.status]?.[0]}>{OTHER_EMPLOYER[e.status]?.[2] ?? e.status}</Badge>
              <br />
              <Figures e={e} />
              {e.status === 'returned' && e.reviewNote && <p className="hint">Sent back: {e.reviewNote}</p>}
            </div>
            {canReview && e.status === 'declared' && returning?.entry.id !== e.id && (
              <div className="form-actions">
                <button type="button" disabled={busy} onClick={() => accept(e)}>
                  Accept…
                </button>
                <button type="button" className="outline" disabled={busy} onClick={() => setReturning({ entry: e, note: '' })}>
                  Return…
                </button>
              </div>
            )}
            {returning?.entry.id === e.id && (
              <form className="inline-form" onSubmit={sendBack} aria-label="Return to the employee">
                <label>
                  What needs to change
                  <textarea
                    rows={2}
                    value={returning.note}
                    maxLength={400}
                    onChange={(ev) => setReturning({ ...returning, note: ev.target.value })}
                  />
                </label>
                <div className="form-actions">
                  <button type="submit" disabled={busy || noteLength < 3}>
                    Send back
                  </button>
                  <button type="button" className="quiet" onClick={() => setReturning(null)}>
                    Cancel
                  </button>
                </div>
              </form>
            )}
          </div>
        ))}
      </div>
    </section>
  )
}
