import { useState } from 'react'
import { payroll } from '../api.js'
import { formatDate, formatDay, localToday, useAction, useLoad, numeric } from '../hooks.js'
import { Badge, Empty, ErrorNote, Tabs } from '../ui.jsx'
import { useConfirm, useToast } from '../feedbackContext.js'
import PayrollSetup from './PayrollSetup.jsx'
import PayslipView from './PayslipView.jsx'
import { RUN_STATES, RUN_STEPS, rupees } from './payrollText.js'

/**
 * Payroll: the monthly runs and the setup behind them. A run goes from inputs to frozen, calculated,
 * approved by someone else, paid and released; each step says what comes next.
 */
export default function Payroll({ me }) {
  const [tab, setTab] = useState('runs')
  const [open, setOpen] = useState(null)
  if (open) return <RunPage id={open} me={me} onBack={() => setOpen(null)} />
  return (
    <>
      <Tabs
        tabs={[
          { id: 'runs', label: 'Runs' },
          { id: 'setup', label: 'Setup' },
        ]}
        current={tab}
        onChange={setTab}
      />
      {tab === 'runs' && <RunList onOpen={setOpen} onSetup={() => setTab('setup')} />}
      {tab === 'setup' && <PayrollSetup />}
    </>
  )
}

function RunList({ onOpen, onSetup }) {
  const list = useLoad(payroll.runs)
  const { busy, error, run } = useAction()
  if (list.loading) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  const d = list.data
  return (
    <>
      {Number(d.draftRules) > 0 && (
        <p className="notice warn-notice" role="status">
          The statutory rules in use are drafts from public summaries, awaiting the payroll consultant’s sign-off. Check figures before paying real
          salaries.
        </p>
      )}
      {d.unassigned > 0 && (
        <p className="notice" role="status">
          {d.unassigned} employee{d.unassigned === 1 ? ' is' : 's are'} in no pay group and will not be paid.{' '}
          <button type="button" className="text-link" onClick={onSetup}>
            Add them under Setup → Pay groups
          </button>
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="run-groups">
        {d.groups.map((g) => {
          const o = g.open
          return (
            <section key={g.id} className="panel run-group" data-group={g.name}>
              <div className="toolbar">
                <h3>
                  {g.name} <span className="hint">{g.members} people</span>
                </h3>
              </div>
              {o ? (
                <>
                  <p>
                    <strong>{o.label}</strong> <Badge tone={RUN_STATES[o.state][0]}>{RUN_STATES[o.state][1]}</Badge>
                  </p>
                  {o.totals && (
                    <p className="hint">
                      {o.totals.people} people · net {rupees(o.totals.net)}
                      {o.totals.blocking > 0 ? ` · ${o.totals.blocking} blocking` : ''}
                    </p>
                  )}
                  <button type="button" className="primary" onClick={() => onOpen(o.id)}>
                    Open {o.label}
                  </button>
                </>
              ) : (
                <>
                  <p className="hint">Nothing open. The next month is {g.nextLabel}.</p>
                  {d.canPrepare && (
                    <button
                      type="button"
                      className="primary"
                      disabled={busy}
                      onClick={async () => {
                        const r = await run(() => payroll.open(g.id, g.nextPeriod))
                        if (r) onOpen(r.id)
                      }}
                    >
                      Start {g.nextLabel}
                    </button>
                  )}
                </>
              )}
            </section>
          )
        })}
      </div>
      <h3>All runs</h3>
      {d.runs.length === 0 ? (
        <Empty>No runs yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Month</th>
                <th>Pay group</th>
                <th>Where it is</th>
                <th className="num">People</th>
                <th className="num">Net pay</th>
              </tr>
            </thead>
            <tbody>
              {d.runs.map((r) => (
                <tr key={r.id}>
                  <td>
                    <button type="button" className="text-link" onClick={() => onOpen(r.id)}>
                      {r.label}
                    </button>
                  </td>
                  <td>{r.groupName}</td>
                  <td>
                    <Badge tone={RUN_STATES[r.state][0]}>{RUN_STATES[r.state][1]}</Badge>
                  </td>
                  <td className="num">{r.totals?.people ?? '—'}</td>
                  <td className="num">{r.totals ? rupees(r.totals.net) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

/** The row of a run's steps, done ones ticked, as on an exit's page. */
function RunSteps({ state }) {
  const at = RUN_STEPS.findIndex(([s]) => s === state)
  return (
    <ol className="exit-progress" aria-label="Where the run is">
      {RUN_STEPS.map(([s, label], i) => (
        <li key={s} className={i < at || state === 'released' ? 'step step-done' : i === at ? 'step step-next' : 'step'}>
          <span className="step-mark" aria-hidden="true">
            {i < at || state === 'released' ? '✓' : i + 1}
          </span>
          <span>
            <span className="step-label">{label}</span>
          </span>
        </li>
      ))}
    </ol>
  )
}

function RunPage({ id, me, onBack }) {
  const r = useLoad(() => payroll.run(id))
  const [person, setPerson] = useState(null)
  const [form, setForm] = useState(null)
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()
  if (r.loading) return <p className="muted">Loading…</p>
  if (r.error) return <ErrorNote>{r.error}</ErrorNote>
  const x = r.data
  const act = async (fn) => {
    if (await run(fn)) {
      setForm(null)
      r.reload()
      return true
    }
    return false
  }
  const month = x.period.slice(0, 7)
  if (person) {
    return <RunEmployee run={x} employmentId={person} onBack={() => setPerson(null)} onChanged={r.reload} />
  }
  const open = x.exceptions.filter((e) => e.level === 'blocking' && !e.acceptedAt)
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← All runs
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            {x.label} · {x.groupName} <Badge tone={RUN_STATES[x.state][0]}>{RUN_STATES[x.state][1]}</Badge>
          </h2>
          <p className="muted">
            {x.calculatedByName && `Calculated by ${x.calculatedByName}${x.attempt > 1 ? ` (attempt ${x.attempt})` : ''}`}
            {x.approvedByName && ` · approved by ${x.approvedByName}`}
            {x.paidOn && ` · paid ${formatDay(x.paidOn)} (${x.paidReference})`}
          </p>
        </div>
        <div className="form-actions">
          {x.state === 'draft' && x.canPrepare && (
            <button type="button" className="primary" disabled={busy} onClick={() => act(() => payroll.freeze(id))}>
              Freeze inputs
            </button>
          )}
          {x.state === 'frozen' && x.canPrepare && (
            <button type="button" className="primary" disabled={busy} onClick={() => act(() => payroll.calculate(id))}>
              Calculate
            </button>
          )}
          {x.state === 'calculated' && x.canPrepare && (
            <button type="button" className="outline" disabled={busy} onClick={() => act(() => payroll.calculate(id))}>
              Recalculate
            </button>
          )}
          {x.state === 'calculated' && x.canApprove && (
            <button type="button" className="primary" disabled={busy} onClick={async () => {
                const sure = await confirm({
                  title: `Approve payroll for ${x.label}?`,
                  body: `${x.people.length} ${x.people.length === 1 ? 'person' : 'people'}, net ${rupees(x.totals.net)}. Once approved, this month's inputs are locked.`,
                  confirmLabel: 'Approve payroll',
                  cancelLabel: 'Not yet',
                })
                if (!sure) return
                if (await act(() => payroll.approve(id))) toast(`Payroll for ${x.label} approved`)
              }}>
              Approve
            </button>
          )}
          {x.state === 'calculated' && x.canAccept && (
            <button type="button" className="quiet" onClick={() => setForm('send-back')}>
              Send back…
            </button>
          )}
          {['frozen', 'calculated'].includes(x.state) && x.canPrepare && (
            <button type="button" className="quiet" onClick={() => setForm('reopen')}>
              Reopen inputs…
            </button>
          )}
          {x.state === 'approved' && x.canPay && (
            <button type="button" className="primary" onClick={() => setForm('paid')}>
              Mark paid…
            </button>
          )}
          {x.state === 'paid' && (
            <button type="button" className="primary" disabled={busy} onClick={() => act(() => payroll.release(id))}>
              Release payslips
            </button>
          )}
        </div>
      </div>
      <RunSteps state={x.state} />
      {x.note && x.state === 'draft' && <p className="notice">Reopened or sent back: {x.note}</p>}
      {Number(x.draftRules) > 0 && x.state !== 'released' && (
        <p className="hint">Statutory rules in use are drafts awaiting the consultant’s sign-off.</p>
      )}
      {x.state === 'calculated' && x.approveBlockedBy && <p className="hint">Approval waits: {x.approveBlockedBy}.</p>}
      <ErrorNote>{error}</ErrorNote>
      {form === 'reopen' && <ReasonForm label="Why reopen" action="Reopen" onCancel={() => setForm(null)} onSubmit={(reason) => act(() => payroll.reopen(id, reason))} />}
      {form === 'send-back' && (
        <ReasonForm label="What needs to change" action="Send back" onCancel={() => setForm(null)} onSubmit={(reason) => act(() => payroll.sendBack(id, reason))} />
      )}
      {form === 'paid' && <PaidForm onCancel={() => setForm(null)} onSubmit={(on, ref) => act(() => payroll.paid(id, on, ref))} />}

      {x.totals?.gross != null && (
        <div className="tiles">
          <div className="tile">
            <div className="tile-label">People</div>
            <div className="tile-value">{x.totals.people}</div>
          </div>
          <div className="tile">
            <div className="tile-label">Gross</div>
            <div className="tile-value">{rupees(x.totals.gross)}</div>
          </div>
          <div className="tile">
            <div className="tile-label">Deductions</div>
            <div className="tile-value">{rupees(x.totals.deductions)}</div>
          </div>
          <div className="tile">
            <div className="tile-label">Net pay</div>
            <div className="tile-value" data-testid="run-net">
              {rupees(x.totals.net)}
            </div>
          </div>
          <div className="tile">
            <div className="tile-label">Cost to the employer</div>
            <div className="tile-value">{rupees(x.totals.employerCost)}</div>
          </div>
        </div>
      )}

      {x.state === 'draft' && <Inputs run={x} onChanged={r.reload} />}
      {x.state === 'frozen' && <p className="notice">Inputs are frozen for {x.people.length} people. Calculate to see the payslips.</p>}

      {x.exceptions.length > 0 && <Exceptions run={x} open={open} onChanged={r.reload} onOpen={setPerson} />}

      {['approved', 'paid', 'released', 'calculated'].includes(x.state) && (
        <section className="panel">
          <div className="toolbar">
            <h3>Files</h3>
          </div>
          <div className="form-actions">
            <button type="button" className="outline" onClick={() => run(() => payroll.output(id, 'register', `Salary register ${month}.csv`))}>
              Salary register
            </button>
            <button type="button" className="outline" onClick={() => run(() => payroll.output(id, 'journal', `Payroll journal ${month}.csv`))}>
              Accounting journal
            </button>
            {x.state !== 'calculated' && x.canPay && (
              <button type="button" className="outline" onClick={() => run(() => payroll.output(id, 'bank', `HDFC bank file ${month}.csv`))}>
                HDFC bank file
              </button>
            )}
          </div>
          {x.state !== 'calculated' && x.canPay && (
            <p className="hint">
              The bank file follows HDFC’s bulk payment columns; the exact layout from your HDFC corporate banking setup is still to be confirmed. Held
              pay and people without a bank account are left out.
            </p>
          )}
        </section>
      )}

      {x.state !== 'draft' && x.state !== 'frozen' && (
        <section className="panel">
          <h3>Register</h3>
          <div className="table-wrap">
            <table className="register">
              <thead>
                <tr>
                  <th>Employee</th>
                  <th className="num">Days paid</th>
                  <th className="num">Gross</th>
                  <th className="num">Deductions</th>
                  <th className="num">Net pay</th>
                </tr>
              </thead>
              <tbody>
                {x.people.map((p) => (
                  <tr key={p.id} data-employee={p.name}>
                    <td>
                      <button type="button" className="text-link" onClick={() => setPerson(p.employmentId)}>
                        {p.name}
                      </button>{' '}
                      <span className="hint">{p.employeeCode}</span> {p.held && <Badge tone="warn">Held</Badge>}
                    </td>
                    <td className="num">
                      {Number(p.daysPaid)} / {Number(p.daysInPeriod)}
                    </td>
                    <td className="num">{rupees(p.gross)}</td>
                    <td className="num">{rupees(p.deductions)}</td>
                    <td className="num">
                      <strong>{rupees(p.net)}</strong>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {x.attempts.length > 1 && (
        <details className="panel">
          <summary>Earlier calculations ({x.attempts.length - 1})</summary>
          <ul>
            {x.attempts.map((a) => (
              <li key={a.number}>
                Attempt {a.number}, {formatDate(a.at)} by {a.byName || 'someone'}: net {rupees(a.totals.net)}, {a.totals.blocking} blocking
              </li>
            ))}
          </ul>
        </details>
      )}
      {me && null}
    </>
  )
}

/** The run input that carries days without pay rather than an amount. */
const LOP_DAYS = 'LOP_DAYS'

function Inputs({ run: x, onChanged }) {
  const [form, setForm] = useState({ employmentId: '', componentCode: x.inputComponents[0]?.code || '', amount: '', note: '' })
  const { busy, error, run } = useAction()
  const lopDays = form.componentCode === LOP_DAYS
  return (
    <section className="panel" data-testid="inputs">
      <h3>
        This month’s inputs <span className="hint">{x.people.length} people in the group</span>
      </h3>
      <p className="hint">
        {x.attendanceOn && x.leaveOn
          ? 'Days paid and loss of pay come from attendance and leave when the run is frozen. '
          : x.leaveOn
            ? 'Unpaid leave counts as loss of pay when the run is frozen. Without the Attendance module, enter any other loss of pay here as days, under Loss of pay (days). '
            : x.attendanceOn
              ? 'Absence counts as loss of pay when the run is frozen, if payroll settings say so. Without the Leave module, enter unpaid leave here as days, under Loss of pay (days). '
              : 'Without the Attendance and Leave modules, enter loss of pay here as days, under Loss of pay (days). '}
        Add one-time amounts here: a bonus, an incentive, a recovery.
      </p>
      {x.inputs.length > 0 && (
        <table>
          <tbody>
            {x.inputs.map((i) => (
              <tr key={i.id}>
                <td>{i.name}</td>
                <td>{i.componentName}</td>
                <td className="num">{i.componentCode === LOP_DAYS ? `${Number(i.amount)} ${Number(i.amount) === 1 ? 'day' : 'days'}` : rupees(i.amount)}</td>
                <td className="hint">{i.note}</td>
                <td>
                  {x.canPrepare && (
                    <button type="button" className="quiet" onClick={() => run(() => payroll.removeInput(x.id, i.id)).then(onChanged)}>
                      Remove
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {x.canPrepare && (
        <form
          className="inline-form"
          onSubmit={async (e) => {
            e.preventDefault()
            if (await run(() => payroll.addInput(x.id, { ...form, amount: numeric(form.amount, lopDays ? 'The days' : 'The amount') }))) {
              setForm((f) => ({ ...f, amount: '', note: '' }))
              onChanged()
            }
          }}
        >
          <div>
            <label htmlFor="in-employee">Employee</label>
            <select id="in-employee" value={form.employmentId} onChange={(e) => setForm({ ...form, employmentId: e.target.value })}>
              <option value="">Choose…</option>
              {x.people.map((p) => (
                <option key={p.employmentId} value={p.employmentId}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="in-component">For</label>
            <select id="in-component" value={form.componentCode} onChange={(e) => setForm({ ...form, componentCode: e.target.value })}>
              {x.inputComponents.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="in-amount">{lopDays ? 'Days without pay' : 'Amount (₹)'}</label>
            <input id="in-amount" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
          </div>
          <div>
            <label htmlFor="in-note">Note</label>
            <input id="in-note" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          </div>
          <div className="form-actions">
            <button type="submit" className="secondary" disabled={busy || !form.employmentId || !form.amount}>
              Add
            </button>
          </div>
        </form>
      )}
      <ErrorNote>{error}</ErrorNote>
      <Holds run={x} onChanged={onChanged} />
    </section>
  )
}

/** Pay held this month: worked out and shown, but left out of the bank file until released. */
function Holds({ run: x, onChanged }) {
  const [form, setForm] = useState({ employmentId: '', reason: '' })
  const { busy, error, run } = useAction()
  return (
    <div className="holds" data-testid="holds">
      <h4>Held pay</h4>
      {x.holds.length === 0 ? (
        <p className="hint">Nobody’s pay is held. Hold it when someone should not be paid yet: paperwork pending, an absconding case.</p>
      ) : (
        <ul className="plain-list">
          {x.holds.map((h) => (
            <li key={h.employmentId}>
              <strong>{h.name}</strong> <span className="hint">“{h.reason}”</span>{' '}
              {x.canPrepare && (
                <button type="button" className="quiet" onClick={() => run(() => payroll.unhold(x.id, h.employmentId)).then(onChanged)}>
                  Release
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {x.canPrepare && (
        <form
          className="inline-form"
          onSubmit={async (e) => {
            e.preventDefault()
            if (await run(() => payroll.hold(x.id, form.employmentId, form.reason))) {
              setForm({ employmentId: '', reason: '' })
              onChanged()
            }
          }}
        >
          <div>
            <label htmlFor="hold-employee">Hold the pay of</label>
            <select id="hold-employee" value={form.employmentId} onChange={(e) => setForm({ ...form, employmentId: e.target.value })}>
              <option value="">Choose…</option>
              {x.people
                .filter((p) => !x.holds.some((h) => h.employmentId === p.employmentId))
                .map((p) => (
                  <option key={p.employmentId} value={p.employmentId}>
                    {p.name}
                  </option>
                ))}
            </select>
          </div>
          <div>
            <label htmlFor="hold-reason">Why</label>
            <input id="hold-reason" value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} />
          </div>
          <div className="form-actions">
            <button type="submit" className="secondary" disabled={busy || !form.employmentId || form.reason.trim().length < 3}>
              Hold pay
            </button>
          </div>
        </form>
      )}
      <ErrorNote>{error}</ErrorNote>
    </div>
  )
}

function Exceptions({ run: x, open, onChanged, onOpen }) {
  const [accepting, setAccepting] = useState(null)
  const { error, run } = useAction()
  const warnings = x.exceptions.filter((e) => e.level === 'warning')
  const blocking = x.exceptions.filter((e) => e.level === 'blocking')
  return (
    <section className="panel" data-testid="exceptions">
      <h3>
        Exceptions{' '}
        <span className="hint">
          {open.length} blocking open · {warnings.length} warning{warnings.length === 1 ? '' : 's'}
        </span>
      </h3>
      <ul className="exceptions">
        {[...blocking, ...warnings].map((e) => (
          <li key={e.id} className={e.level === 'blocking' && !e.acceptedAt ? 'exception blocking' : 'exception'} data-code={e.code}>
            <div>
              <Badge tone={e.level === 'blocking' ? (e.acceptedAt ? 'neutral' : 'bad') : 'warn'}>
                {e.level === 'blocking' ? (e.acceptedAt ? 'Accepted' : 'Blocking') : 'Warning'}
              </Badge>{' '}
              {e.name && (
                <button type="button" className="text-link" onClick={() => onOpen(e.employmentId)}>
                  {e.name}
                </button>
              )}
              {e.name ? ': ' : ''}
              {e.message}
              {e.acceptedAt && (
                <span className="hint">
                  {' '}
                  Accepted by {e.acceptedByName}: “{e.acceptedReason}”
                </span>
              )}
              {e.level === 'blocking' && !e.acceptable && !e.acceptedAt && <span className="hint"> Must be fixed.</span>}
            </div>
            {e.level === 'blocking' && e.acceptable && !e.acceptedAt && x.canAccept && x.state === 'calculated' && accepting !== e.id && (
              <button type="button" className="quiet" onClick={() => setAccepting(e.id)}>
                Accept…
              </button>
            )}
            {accepting === e.id && (
              <ReasonForm
                label="Why accept it"
                action="Accept"
                onCancel={() => setAccepting(null)}
                onSubmit={async (reason) => {
                  if (await run(() => payroll.accept(x.id, e.id, reason))) {
                    setAccepting(null)
                    onChanged()
                  }
                }}
              />
            )}
          </li>
        ))}
      </ul>
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

function ReasonForm({ label, action, onSubmit, onCancel }) {
  const [reason, setReason] = useState('')
  return (
    <form
      className="inline-form"
      onSubmit={(e) => {
        e.preventDefault()
        onSubmit(reason)
      }}
    >
      <div>
        <label htmlFor={`reason-${action}`}>{label}</label>
        <input id={`reason-${action}`} value={reason} onChange={(e) => setReason(e.target.value)} />
      </div>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={reason.trim().length < 3}>
          {action}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function PaidForm({ onSubmit, onCancel }) {
  const [on, setOn] = useState(localToday())
  const [ref, setRef] = useState('')
  return (
    <form
      className="panel inline-form"
      onSubmit={(e) => {
        e.preventDefault()
        onSubmit(on, ref)
      }}
    >
      <div>
        <label htmlFor="paid-on">Paid on</label>
        <input id="paid-on" type="date" value={on} onChange={(e) => setOn(e.target.value)} />
      </div>
      <div>
        <label htmlFor="paid-ref">Bank reference of the batch</label>
        <input id="paid-ref" value={ref} onChange={(e) => setRef(e.target.value)} />
      </div>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={!ref.trim()}>
          Mark paid
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function RunEmployee({ run: x, employmentId, onBack, onChanged }) {
  const slip = useLoad(() => payroll.employee(x.id, employmentId))
  const [holding, setHolding] = useState(false)
  const { error, run } = useAction()
  if (slip.loading) return <p className="muted">Loading…</p>
  if (slip.error) return <ErrorNote>{slip.error}</ErrorNote>
  const s = slip.data
  const editable = ['draft', 'frozen', 'calculated'].includes(x.state) && x.canPrepare
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← {x.label} register
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            {s.info.name} {s.held && <Badge tone="warn">Pay held</Badge>}
          </h2>
          <p className="muted">
            {[s.info.designation, s.info.department, s.info.code, s.info.structure && `${s.info.structure} structure`, `${rupees(s.info.annual)} a year`]
              .filter(Boolean)
              .join(' · ')}
          </p>
        </div>
        <div className="form-actions">
          <button type="button" className="outline" onClick={() => run(() => payroll.payslipPdf(x.id, employmentId, `Payslip ${x.period.slice(0, 7)} ${s.info.name}.pdf`))}>
            Payslip PDF
          </button>
          {editable && !s.held && (
            <button type="button" className="quiet" onClick={() => setHolding(true)}>
              Hold pay…
            </button>
          )}
          {editable && s.held && (
            <button
              type="button"
              className="quiet"
              onClick={async () => {
                if (await run(() => payroll.unhold(x.id, employmentId))) {
                  slip.reload()
                  onChanged()
                }
              }}
            >
              Release held pay
            </button>
          )}
        </div>
      </div>
      {holding && (
        <ReasonForm
          label="Why hold the pay"
          action="Hold"
          onCancel={() => setHolding(false)}
          onSubmit={async (reason) => {
            if (await run(() => payroll.hold(x.id, employmentId, reason))) {
              setHolding(false)
              slip.reload()
              onChanged()
            }
          }}
        />
      )}
      <ErrorNote>{error}</ErrorNote>
      {s.exceptions.length > 0 && (
        <ul className="exceptions">
          {s.exceptions.map((e) => (
            <li key={e.id} className={e.level === 'blocking' ? 'exception blocking' : 'exception'}>
              <Badge tone={e.level === 'blocking' ? 'bad' : 'warn'}>{e.level === 'blocking' ? 'Blocking' : 'Warning'}</Badge> {e.message}
            </li>
          ))}
        </ul>
      )}
      <section className="panel">
        <PayslipView slip={s} explain />
      </section>
    </>
  )
}
