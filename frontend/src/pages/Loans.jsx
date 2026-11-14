import { useState } from 'react'
import { loans } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { useToast } from '../feedbackContext.js'

const rupees = (value) => `₹${Number(value || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const monthName = (iso) => new Date(iso).toLocaleDateString(undefined, { month: 'short', year: 'numeric' })

const STATUS = {
  requested: ['With your manager', 'info'],
  manager_approved: ['With finance', 'info'],
  approved: ['Approved, to be paid out', 'ok'],
  active: ['Being repaid', 'ok'],
  closed: ['Repaid', 'ok'],
  rejected: ['Rejected', 'bad'],
  withdrawn: ['Withdrawn', 'neutral'],
  written_off: ['Written off', 'neutral'],
}

function LoanStatus({ status }) {
  const [label, tone] = STATUS[status] || [status, 'neutral']
  return <Badge tone={tone}>{label}</Badge>
}

function LoanTable({ rows, onOpen, empty, showWho }) {
  if (!rows.length) return <Empty>{empty}</Empty>
  return (
    <div className="table-wrap">
      <table className="stack">
        <thead>
          <tr>
            <th>Request</th>
            {showWho && <th>Person</th>}
            <th className="num">Amount</th>
            <th className="num">Still owed</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td data-label="Request">
                <button type="button" className="link" onClick={() => onOpen(r.id)}>{r.number}</button> <span className="hint">{r.type}</span>
              </td>
              {showWho && <td data-label="Person">{r.person}</td>}
              <td className="num" data-label="Amount">{rupees(r.amount)}</td>
              <td className="num" data-label="Still owed">{rupees(r.outstanding)}</td>
              <td data-label="Status"><LoanStatus status={r.status} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** One loan with its schedule, and the buttons the viewer's role allows at this stage. */
function LoanDetail({ id, mode, onClose }) {
  const loan = useLoad(() => loans.loan(id))
  const { busy, error, run } = useAction()
  const toast = useToast()
  const [note, setNote] = useState('')
  const [amount, setAmount] = useState('')
  const [reference, setReference] = useState('')
  const l = loan.data
  if (loan.loading) return <p className="muted">Loading…</p>
  if (loan.error) return <ErrorNote>{loan.error}</ErrorNote>

  async function act(fn, message) {
    const done = await run(async () => {
      await fn()
      return true
    })
    if (done) {
      toast(message)
      setNote('')
      setAmount('')
      setReference('')
      loan.reload()
    }
  }

  return (
    <div className="card wide stack">
      <p><button type="button" className="secondary" onClick={onClose}>Back</button></p>
      <h2>{l.number}: {l.type} <LoanStatus status={l.status} /></h2>
      <p>
        {l.person} · {rupees(l.amount)} over {l.months} {l.months === 1 ? 'month' : 'months'}
        {Number(l.annualRate) > 0 && <> at {l.annualRate}% a year</>} · for {l.purpose}
      </p>
      {l.decisionNote && <p className="hint">Reason given: {l.decisionNote}</p>}
      {l.disbursedOn && <p className="hint">Paid out {formatDay(l.disbursedOn)}, reference {l.disbursalRef}. Still owed: {rupees(l.outstanding)}.</p>}
      <ErrorNote>{error}</ErrorNote>
      {l.schedule.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Month</th><th className="num">Principal</th><th className="num">Interest</th><th>Status</th><th /></tr>
            </thead>
            <tbody>
              {l.schedule.map((r) => (
                <tr key={r.id}>
                  <td>{monthName(r.month)}</td>
                  <td className="num">{rupees(r.principal)}</td>
                  <td className="num">{rupees(r.interest)}</td>
                  <td>{{ due: 'Due', taken: 'Taken from pay', skipped: 'Skipped', cancelled: 'Cancelled' }[r.status] || r.status}</td>
                  <td>
                    {mode === 'finance' && r.status === 'due' && l.status === 'active' && (
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy || note.trim().length < 3}
                        title={note.trim().length < 3 ? 'Write the reason below first' : undefined}
                        onClick={() => act(() => loans.skip(r.id, note), 'Month skipped')}
                      >
                        Skip
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {l.settlements.length > 0 && (
        <ul>
          {l.settlements.map((s, i) => (
            <li key={i}>{s.kind === 'prepayment' ? 'Repaid early' : 'Written off'}: {rupees(s.amount)} on {formatDay(s.createdAt)} ({s.note})</li>
          ))}
        </ul>
      )}

      {l.status === 'requested' && mode === 'manager' && (
        <Decision busy={busy} note={note} setNote={setNote} onApprove={() => act(() => loans.managerDecide(id, 'approve', note, l.version), 'Approved, now with finance')}
          onReject={() => act(() => loans.managerDecide(id, 'reject', note, l.version), 'Rejected')} />
      )}
      {l.status === 'manager_approved' && mode === 'finance' && (
        <>
          {l.financeApprovals === 2 && (
            <p className="hint">
              {l.firstApproved
                ? (l.iApprovedFirst ? 'You approved this first. A second person in finance must approve it too.' : 'One person in finance has approved. Your approval completes it.')
                : 'A loan this large needs two different people in finance to approve it. This is the first approval.'}
            </p>
          )}
          <Decision busy={busy} approveDisabled={l.financeApprovals === 2 && l.iApprovedFirst} note={note} setNote={setNote}
            onApprove={() => act(() => loans.financeDecide(id, 'approve', note, l.version), l.financeApprovals === 2 && !l.firstApproved ? 'First approval recorded' : 'Approved')}
            onReject={() => act(() => loans.financeDecide(id, 'reject', note, l.version), 'Rejected')} />
        </>
      )}
      {l.status === 'approved' && mode === 'finance' && (
        <form className="stack" onSubmit={(e) => { e.preventDefault(); act(() => loans.disburse(id, { reference, version: l.version }), 'Payout recorded and the schedule is set') }}>
          <p className="hint">Record the payout once the bank has sent it. Repayment starts the month after.</p>
          <label htmlFor="loan-ref">Bank reference of the payout</label>
          <input id="loan-ref" value={reference} minLength={3} maxLength={60} onChange={(e) => setReference(e.target.value)} required />
          <div><button type="submit" disabled={busy}>Record payout</button></div>
        </form>
      )}
      {l.status === 'active' && mode === 'finance' && (
        <div className="stack">
          <label htmlFor="loan-note">Reason or reference (needed to skip a month, repay early or write off)</label>
          <input id="loan-note" value={note} onChange={(e) => setNote(e.target.value)} />
          <label htmlFor="loan-amount">Amount received (₹), for an early repayment</label>
          <input id="loan-amount" type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} />
          <div className="actions">
            <button type="button" disabled={busy || !amount || note.trim().length < 3} onClick={() => act(() => loans.prepay(id, amount, note), 'Repayment recorded')}>
              Record early repayment
            </button>
            <button type="button" className="secondary" disabled={busy || note.trim().length < 10}
              onClick={() => act(() => loans.writeOff(id, note), 'Written off')}>
              Write off what is owed
            </button>
          </div>
        </div>
      )}
      {mode === 'mine' && ['requested', 'manager_approved', 'approved'].includes(l.status) && (
        <div><button type="button" className="secondary" disabled={busy} onClick={() => act(() => loans.withdraw(id, l.version), 'Withdrawn')}>Withdraw this request</button></div>
      )}
    </div>
  )
}

function Decision({ busy, note, setNote, onApprove, onReject, approveDisabled }) {
  return (
    <div className="stack">
      <label htmlFor="loan-decision-note">Note (needed to reject)</label>
      <input id="loan-decision-note" value={note} onChange={(e) => setNote(e.target.value)} />
      <div className="actions">
        <button type="button" disabled={busy || approveDisabled} onClick={onApprove}>Approve</button>
        <button type="button" className="secondary" disabled={busy || note.trim().length < 3} onClick={onReject}>Reject</button>
      </div>
    </div>
  )
}

export function MyLoans() {
  const mine = useLoad(loans.mine)
  const types = useLoad(() => loans.types(false))
  const { busy, error, run } = useAction()
  const toast = useToast()
  const [open, setOpen] = useState(null)
  const [f, setF] = useState({ typeId: '', amount: '', months: '3', purpose: '' })
  const [preview, setPreview] = useState(null)

  if (open) return <LoanDetail id={open} mode="mine" onClose={() => { setOpen(null); mine.reload() }} />

  async function show() {
    const rows = await run(() => loans.preview(f.typeId, f.amount, f.months))
    if (rows) setPreview(rows)
  }
  async function ask(e) {
    e.preventDefault()
    const made = await run(() => loans.request({ typeId: f.typeId, amount: f.amount, months: Number(f.months), purpose: f.purpose }))
    if (made) {
      toast(`Request ${made.number} sent.`)
      setF({ typeId: '', amount: '', months: '3', purpose: '' })
      setPreview(null)
      mine.reload()
    }
  }
  const type = (types.data || []).find((t) => t.id === f.typeId)
  return (
    <>
      <ErrorNote>{error || mine.error || types.error}</ErrorNote>
      <LoanTable rows={mine.data || []} onOpen={setOpen} empty="You have not asked for an advance or a loan." />
      {(types.data || []).length > 0 && (
        <form onSubmit={ask} className="card stack">
          <h3>Ask for an advance or a loan</h3>
          <label htmlFor="loan-type">Type</label>
          <select id="loan-type" value={f.typeId} onChange={(e) => { setF({ ...f, typeId: e.target.value }); setPreview(null) }} required>
            <option value="">Choose…</option>
            {types.data.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
          {type && <p className="hint">Up to {rupees(type.maxAmount)} over up to {type.maxMonths} months{Number(type.annualRate) > 0 ? `, at ${type.annualRate}% a year` : ', with no interest'}.</p>}
          <label htmlFor="loan-amount-ask">Amount (₹)</label>
          <input id="loan-amount-ask" type="number" min="1" step="0.01" value={f.amount} onChange={(e) => { setF({ ...f, amount: e.target.value }); setPreview(null) }} required />
          <label htmlFor="loan-months">Months to repay</label>
          <input id="loan-months" type="number" min="1" max={type?.maxMonths || 60} value={f.months} onChange={(e) => { setF({ ...f, months: e.target.value }); setPreview(null) }} required />
          <label htmlFor="loan-purpose">What it is for</label>
          <input id="loan-purpose" value={f.purpose} maxLength={400} onChange={(e) => setF({ ...f, purpose: e.target.value })} required minLength={3} />
          <div className="actions">
            <button type="button" className="secondary" disabled={busy || !f.typeId || !f.amount} onClick={show}>Show the repayments</button>
            <button type="submit" disabled={busy || !f.typeId}>Send request</button>
          </div>
          {preview && (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Month</th><th className="num">Principal</th><th className="num">Interest</th><th className="num">Taken from pay</th></tr></thead>
                <tbody>
                  {preview.map((r) => (
                    <tr key={r.month}>
                      <td>{monthName(r.month)}</td>
                      <td className="num">{rupees(r.principal)}</td>
                      <td className="num">{rupees(r.interest)}</td>
                      <td className="num">{rupees(Number(r.principal) + Number(r.interest))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </form>
      )}
    </>
  )
}

export function LoanApprovals() {
  const rows = useLoad(loans.approvals)
  const [open, setOpen] = useState(null)
  if (open) return <LoanDetail id={open} mode="manager" onClose={() => { setOpen(null); rows.reload() }} />
  return (
    <>
      <ErrorNote>{rows.error}</ErrorNote>
      <LoanTable rows={rows.data || []} onOpen={setOpen} empty="No advance or loan requests are waiting for you." showWho />
    </>
  )
}

export function LoanQueue() {
  const rows = useLoad(loans.queue)
  const [open, setOpen] = useState(null)
  if (open) return <LoanDetail id={open} mode="finance" onClose={() => { setOpen(null); rows.reload() }} />
  return (
    <>
      <ErrorNote>{rows.error}</ErrorNote>
      <LoanTable rows={rows.data || []} onOpen={setOpen} empty="No advances or loans are waiting or being repaid." showWho />
    </>
  )
}

export function LoanTypes() {
  const types = useLoad(() => loans.types(true))
  const { busy, error, run } = useAction()
  const toast = useToast()
  const blank = { name: '', kind: 'loan', maxAmount: '', maxMonths: '12', annualRate: '0', minServiceMonths: '0', maxOpen: '1' }
  const [f, setF] = useState(blank)
  async function add(e) {
    e.preventDefault()
    const done = await run(async () => {
      await loans.saveType(null, {
        name: f.name, kind: f.kind, maxAmount: f.maxAmount, maxMonths: Number(f.maxMonths), annualRate: f.kind === 'loan' ? f.annualRate : '0',
        minServiceMonths: Number(f.minServiceMonths), maxOpen: Number(f.maxOpen), active: true,
      })
      return true
    })
    if (done) {
      setF(blank)
      types.reload()
      toast('Type added')
    }
  }
  async function toggle(t) {
    const done = await run(async () => {
      await loans.saveType(t.id, { ...t, active: !t.active })
      return true
    })
    if (done) types.reload()
  }
  return (
    <>
      <ErrorNote>{error || types.error}</ErrorNote>
      {(types.data || []).length === 0 ? (
        <Empty>No types yet. Add the first below. People can ask for advances and loans once there is one.</Empty>
      ) : (
        <div className="table-wrap">
          <table className="stack">
            <thead><tr><th>Type</th><th>Terms</th><th /></tr></thead>
            <tbody>
              {types.data.map((t) => (
                <tr key={t.id}>
                  <td data-label="Type">{t.name} {!t.active && <Badge>Off</Badge>}</td>
                  <td data-label="Terms">
                    Up to {rupees(t.maxAmount)}, {t.maxMonths} months, {Number(t.annualRate) > 0 ? `${t.annualRate}% a year` : 'no interest'}
                    {t.minServiceMonths > 0 && `, after ${t.minServiceMonths} months of service`}, {t.maxOpen} open at a time
                  </td>
                  <td><button type="button" disabled={busy} onClick={() => toggle(t)}>{t.active ? 'Turn off' : 'Turn on'}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form onSubmit={add} className="grid">
        <div><label htmlFor="lt-name">Name</label><input id="lt-name" value={f.name} maxLength={60} onChange={(e) => setF({ ...f, name: e.target.value })} /></div>
        <div>
          <label htmlFor="lt-kind">Kind</label>
          <select id="lt-kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value, maxMonths: e.target.value === 'loan' ? '12' : '3' })}>
            <option value="loan">Loan (up to 60 months)</option>
            <option value="salary_advance">Salary advance (up to 3 months, no interest)</option>
          </select>
        </div>
        <div><label htmlFor="lt-amount">Largest amount (₹)</label><input id="lt-amount" type="number" min="1" value={f.maxAmount} onChange={(e) => setF({ ...f, maxAmount: e.target.value })} /></div>
        <div><label htmlFor="lt-months">Longest repayment (months)</label><input id="lt-months" type="number" min="1" max="60" value={f.maxMonths} onChange={(e) => setF({ ...f, maxMonths: e.target.value })} /></div>
        {f.kind === 'loan' && <div><label htmlFor="lt-rate">Interest a year (%)</label><input id="lt-rate" type="number" min="0" max="30" step="0.01" value={f.annualRate} onChange={(e) => setF({ ...f, annualRate: e.target.value })} /></div>}
        <div><label htmlFor="lt-service">Months of service needed</label><input id="lt-service" type="number" min="0" max="120" value={f.minServiceMonths} onChange={(e) => setF({ ...f, minServiceMonths: e.target.value })} /></div>
        <div><label htmlFor="lt-open">Open at once, each person</label><input id="lt-open" type="number" min="1" max="10" value={f.maxOpen} onChange={(e) => setF({ ...f, maxOpen: e.target.value })} /></div>
        <div><button type="submit" disabled={busy || !f.name.trim() || !f.maxAmount}>Add type</button></div>
      </form>
    </>
  )
}
