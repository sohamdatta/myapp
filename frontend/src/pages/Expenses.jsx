import { useState } from 'react'
import { expenses, saveBlob } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote, Tabs } from '../ui.jsx'
import { useToast } from '../feedbackContext.js'

const rupees = (value) => `₹${Number(value || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`

const STATUS = {
  draft: ['Draft', 'neutral'],
  submitted: ['With your manager', 'info'],
  returned: ['Returned to you', 'warn'],
  evidence_requested: ['Finance needs more', 'warn'],
  approved: ['With finance', 'info'],
  verified: ['Verified', 'ok'],
  scheduled: ['Scheduled for payment', 'ok'],
  paid: ['Paid', 'ok'],
  rejected: ['Rejected', 'bad'],
  withdrawn: ['Withdrawn', 'neutral'],
}

function ClaimStatus({ status }) {
  const [label, tone] = STATUS[status] || [status, 'neutral']
  return <Badge tone={tone}>{label}</Badge>
}

/** What a person can do on Expenses depends on what they hold: their own claims, their team's, finance's, or setup. */
export default function Expenses({ me }) {
  const can = (p) => me.permissions.includes(p)
  const tabs = [
    can('expense.claim') && { id: 'mine', label: 'My claims' },
    can('expense.approve') && { id: 'approvals', label: 'Approvals' },
    can('expense.finance') && { id: 'finance', label: 'Finance' },
    can('expense.finance') && { id: 'payments', label: 'Payments' },
    can('expense.setup') && { id: 'categories', label: 'Categories' },
    can('expense.setup') && { id: 'approvers', label: 'Approvers' },
  ].filter(Boolean)
  const [tab, setTab] = useState(tabs[0]?.id)
  if (tabs.length === 0) return <Empty>You do not have anything to do in Expenses.</Empty>
  return (
    <>
      <h1>Expenses</h1>
      {tabs.length > 1 && <Tabs tabs={tabs} current={tab} onChange={setTab} />}
      {tab === 'mine' && <MyClaims />}
      {tab === 'approvals' && <Queue load={expenses.approvals} empty="Nothing is waiting for you." mode="manager" />}
      {tab === 'finance' && <Queue load={expenses.queue} empty="No claims are waiting for finance." mode="finance" />}
      {tab === 'payments' && <Payments />}
      {tab === 'categories' && <Categories />}
      {tab === 'approvers' && <Approvers />}
    </>
  )
}

function MyClaims() {
  const claims = useLoad(expenses.mine)
  const { busy, error, run } = useAction()
  const toast = useToast()
  const [title, setTitle] = useState('')
  const [open, setOpen] = useState(null)

  async function create(e) {
    e.preventDefault()
    const made = await run(() => expenses.create(title))
    if (made) {
      setTitle('')
      claims.reload()
      setOpen(made.id)
      toast('Draft claim started')
    }
  }

  if (open) return <ClaimView id={open} mine onClose={() => { setOpen(null); claims.reload() }} />
  return (
    <>
      <form onSubmit={create} className="inline-form">
        <label htmlFor="new-claim">New claim</label>
        <input id="new-claim" value={title} maxLength={120} placeholder="What is it for? e.g. Client visit, Pune" onChange={(e) => setTitle(e.target.value)} />
        <button type="submit" disabled={busy || !title.trim()}>Start</button>
      </form>
      <ErrorNote>{error || claims.error}</ErrorNote>
      <ClaimTable rows={claims.data || []} onOpen={setOpen} empty="You have no claims yet." />
    </>
  )
}

function ClaimTable({ rows, onOpen, empty, showWho }) {
  if (rows.length === 0) return <Empty>{empty}</Empty>
  return (
    <div className="table-wrap">
      <table className="stack">
        <thead>
          <tr>
            <th>Claim</th>
            {showWho && <th>From</th>}
            <th>Status</th>
            <th>Lines</th>
            <th>Claimed</th>
            <th>Payable</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td data-label="Claim">
                <button type="button" className="link" onClick={() => onOpen(r.id)}>{r.number}: {r.title}</button>
              </td>
              {showWho && <td data-label="From">{r.claimant}</td>}
              <td data-label="Status"><ClaimStatus status={r.status} /></td>
              <td data-label="Lines">{r.lines}</td>
              <td data-label="Claimed">{rupees(r.total)}</td>
              <td data-label="Payable">{rupees(r.payable)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Queue({ load, empty, mode }) {
  const rows = useLoad(load)
  const [open, setOpen] = useState(null)
  if (open) return <ClaimView id={open} mode={mode} onClose={() => { setOpen(null); rows.reload() }} />
  return (
    <>
      <ErrorNote>{rows.error}</ErrorNote>
      <ClaimTable rows={rows.data || []} onOpen={setOpen} empty={empty} showWho />
    </>
  )
}

/** Verified claims waiting to be paid, and the batches that pay them by bank file. */
function Payments() {
  const payable = useLoad(expenses.payable)
  const batches = useLoad(expenses.batches)
  const { busy, error, run } = useAction()
  const toast = useToast()
  const [chosen, setChosen] = useState([])
  const [paying, setPaying] = useState(null)
  const [reference, setReference] = useState('')

  function reload() {
    payable.reload()
    batches.reload()
  }

  async function make() {
    const made = await run(() => expenses.createBatch(chosen))
    if (made) {
      toast(`Batch ${made.number} made for ${rupees(made.total)}.`)
      setChosen([])
      reload()
    }
  }

  async function download(batch) {
    await run(async () => {
      saveBlob(await expenses.bankFile(batch.id), `Expense payments ${batch.number}.csv`)
      return true
    })
  }

  async function markPaid(event) {
    event.preventDefault()
    const done = await run(async () => {
      await expenses.markPaid(paying.id, null, reference)
      return true
    })
    if (done) {
      toast(`Batch ${paying.number} marked paid.`)
      setPaying(null)
      setReference('')
      reload()
    }
  }

  async function cancel(batch) {
    const done = await run(async () => {
      await expenses.cancelBatch(batch.id)
      return true
    })
    if (done) {
      toast(`Batch ${batch.number} cancelled. Its claims are waiting again.`)
      reload()
    }
  }

  const rows = payable.data || []
  const total = rows.filter((r) => chosen.includes(r.id)).reduce((sum, r) => sum + Number(r.payable), 0)
  return (
    <>
      <ErrorNote>{error || payable.error || batches.error}</ErrorNote>
      <h2>Waiting for payment</h2>
      {rows.length === 0 ? (
        <Empty>No verified claims are waiting.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>
                  <span className="sr-only">Include</span>
                </th>
                <th>Claim</th>
                <th>Claimant</th>
                <th className="num">To pay</th>
                <th>Bank account</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Include ${r.number}`}
                      checked={chosen.includes(r.id)}
                      disabled={!r.hasBank}
                      onChange={() => setChosen(chosen.includes(r.id) ? chosen.filter((x) => x !== r.id) : [...chosen, r.id])}
                    />
                  </td>
                  <td>
                    {r.number} <span className="hint">{r.title}</span>
                  </td>
                  <td>{r.claimant}</td>
                  <td className="num">{rupees(r.payable)}</td>
                  <td>{r.hasBank ? 'On record' : <Badge tone="warn">Missing</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {chosen.length > 0 && (
        <p>
          <button type="button" disabled={busy} onClick={make}>
            Make a batch of {chosen.length} for {rupees(total)}
          </button>
        </p>
      )}
      <h2>Batches</h2>
      {(batches.data || []).length === 0 ? (
        <Empty>No batches yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Batch</th>
                <th>Status</th>
                <th className="num">Claims</th>
                <th className="num">Total</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {batches.data.map((b) => (
                <tr key={b.id}>
                  <td>
                    {b.number}
                    {b.paidOn && <div className="hint">Paid {formatDay(b.paidOn)}, ref {b.bankReference}</div>}
                  </td>
                  <td>
                    <Badge tone={b.status === 'paid' ? 'ok' : b.status === 'open' ? 'info' : 'neutral'}>
                      {b.status === 'open' ? 'Waiting for the bank' : b.status === 'paid' ? 'Paid' : 'Cancelled'}
                    </Badge>
                  </td>
                  <td className="num">{b.claims}</td>
                  <td className="num">{rupees(b.total)}</td>
                  <td>
                    {b.status !== 'cancelled' && (
                      <button type="button" className="secondary" disabled={busy} onClick={() => download(b)}>
                        Bank file
                      </button>
                    )}{' '}
                    {b.status === 'open' && (
                      <>
                        <button type="button" disabled={busy} onClick={() => setPaying(b)}>
                          Mark paid
                        </button>{' '}
                        <button type="button" className="secondary" disabled={busy} onClick={() => cancel(b)}>
                          Cancel
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {paying && (
        <form className="card stack" onSubmit={markPaid}>
          <h3>Mark {paying.number} paid</h3>
          <p className="hint">Do this after the bank has paid. Every claim in the batch becomes paid.</p>
          <label htmlFor="bank-reference">Bank reference</label>
          <input id="bank-reference" value={reference} onChange={(e) => setReference(e.target.value)} required minLength={3} maxLength={60} />
          <div className="actions">
            <button type="submit" disabled={busy}>
              Mark paid
            </button>
            <button type="button" className="secondary" onClick={() => setPaying(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </>
  )
}

const KINDS = {
  receipt: 'Receipt',
  travel_proof: 'Proof of travel',
  pre_approval: 'Pre-approval',
  missing_receipt_declaration: 'Missing-receipt statement',
}

/** One claim: its lines and files, and the buttons the viewer's role allows at this status. */
function ClaimView({ id, mine, mode, onClose }) {
  const claim = useLoad(() => expenses.claim(id))
  const { busy, error, run } = useAction()
  const toast = useToast()
  const [note, setNote] = useState('')
  const c = claim.data

  async function act(label, fn) {
    const done = await run(async () => {
      await fn()
      return true
    })
    if (done) {
      toast(label)
      setNote('')
      claim.reload()
    }
  }

  if (!c) return <><ErrorNote>{claim.error}</ErrorNote><button type="button" onClick={onClose}>Back</button></>
  const v = c.version
  const editable = mine && c.status === 'draft'
  const canAddFiles = mine && ['draft', 'evidence_requested'].includes(c.status)
  return (
    <section aria-label={c.number}>
      <p><button type="button" className="link" onClick={onClose}>← All claims</button></p>
      <h2>{c.number}: {c.title} <ClaimStatus status={c.status} /></h2>
      {c.batchNumber && (
        <p className="hint">
          {c.status === 'paid' ? `Paid on ${formatDay(c.paidOn)}, bank reference ${c.bankReference}` : 'Scheduled for payment'} (batch {c.batchNumber}).
        </p>
      )}
      {c.decisionNote && <p className="hint">Note: {c.decisionNote}</p>}
      {c.financeApprovals === 2 && ['approved', 'verified'].includes(c.status) && (
        <p className="hint">{c.status === 'verified' ? 'Verified by two people.' : c.firstVerified ? 'Verified once. A second person must verify it.' : 'This claim is large: two different people must verify it.'}</p>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="table-wrap">
        <table className="stack">
          <thead>
            <tr><th>Date</th><th>Category</th><th>Claimed</th><th>Approved</th><th>Evidence</th><th /></tr>
          </thead>
          <tbody>
            {c.lines.map((l) => (
              <tr key={l.id}>
                <td data-label="Date">{formatDay(l.spentOn)}</td>
                <td data-label="Category">
                  {l.category}
                  {l.merchant ? `, ${l.merchant}` : ''}
                  {l.flags.map((f) => <div key={f.code}><Badge tone="warn">{f.message}</Badge></div>)}
                </td>
                <td data-label="Claimed">{rupees(l.amount)}{l.km ? ` (${l.km} km)` : ''}</td>
                <td data-label="Approved">
                  {l.approvedAmount == null ? '—' : rupees(l.approvedAmount)}
                  {l.cutReason && <div className="hint">{l.cutReason}</div>}
                </td>
                <td data-label="Evidence">
                  <FileList files={l.files} />
                  {l.review && <div><Badge tone={l.review === 'accepted' ? 'ok' : 'warn'}>{l.review.replace('_', ' ')}</Badge> {l.reviewNote}</div>}
                  {canAddFiles && <AddFile lineId={l.id} onDone={claim.reload} />}
                  {mode === 'finance' && (
                    <ReviewButtons onReview={(status, why) => act('Review saved', () => expenses.review(l.id, status, why, v))} busy={busy} />
                  )}
                </td>
                <td>{editable && <button type="button" onClick={() => act('Line removed', () => expenses.removeLine(l.id))}>Remove</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editable && <AddLine claimId={c.id} onDone={claim.reload} />}

      {(mine || mode) && (
        <div className="actions">
          {(mode === 'manager' || mode === 'finance' || (mine && !['draft', 'returned'].includes(c.status))) && (
            <>
              <label htmlFor="claim-note">Note</label>
              <input id="claim-note" value={note} onChange={(e) => setNote(e.target.value)} />
            </>
          )}
          {editable && <button type="button" disabled={busy} onClick={() => act('Claim submitted', () => expenses.submit(c.id, v))}>Submit</button>}
          {editable && <button type="button" disabled={busy} onClick={() => act('Draft deleted', async () => { await expenses.remove(c.id); onClose() })}>Delete draft</button>}
          {mine && ['draft', 'submitted'].includes(c.status) && c.status !== 'draft' && (
            <button type="button" disabled={busy} onClick={() => act('Claim withdrawn', () => expenses.withdraw(c.id, v))}>Withdraw</button>
          )}
          {mine && c.status === 'returned' && <button type="button" disabled={busy} onClick={() => act('Back to draft', () => expenses.reopen(c.id, v))}>Edit and resubmit</button>}
          {mine && c.status === 'evidence_requested' && <button type="button" disabled={busy} onClick={() => act('Sent back to finance', () => expenses.sendBack(c.id, v))}>Send back to finance</button>}
          {mode === 'manager' && ['approve', 'return', 'reject'].map((action) => (
            <button key={action} type="button" disabled={busy} onClick={() => act(`Claim ${action === 'approve' ? 'approved' : action === 'return' ? 'returned' : 'rejected'}`, () => expenses.decide(c.id, { action, note, version: v }).then(onClose))}>
              {action[0].toUpperCase() + action.slice(1)}
            </button>
          ))}
          {mode === 'finance' && <button type="button" disabled={busy} onClick={() => act('Claim verified', () => expenses.verify(c.id, v, note))}>Verify</button>}
          {mode === 'finance' && <button type="button" disabled={busy} onClick={() => act('Claim rejected', () => expenses.financeReject(c.id, v, note))}>Reject</button>}
        </div>
      )}
    </section>
  )
}

function FileList({ files }) {
  const { error, run } = useAction()
  return (
    <>
      {files.map((f) => (
        <div key={f.id}>
          {f.declaration ? (
            <>
              <strong>{KINDS[f.kind]}</strong>
              <div className="hint">{f.declaration}</div>
            </>
          ) : (
            <button type="button" className="link" onClick={() => run(async () => saveBlob(await expenses.file(f.id), f.fileName))}>
              {KINDS[f.kind] || f.kind}: {f.fileName}
            </button>
          )}
        </div>
      ))}
      <ErrorNote>{error}</ErrorNote>
    </>
  )
}

function AddFile({ lineId, onDone }) {
  const { busy, error, run } = useAction()
  const [kind, setKind] = useState('receipt')
  const [statement, setStatement] = useState('')
  const [file, setFile] = useState(null)
  async function add() {
    const done = await run(async () => {
      await expenses.upload(lineId, kind, kind === 'missing_receipt_declaration' ? null : file, statement || undefined)
      return true
    })
    if (done) {
      setFile(null)
      setStatement('')
      onDone()
    }
  }
  const declaration = kind === 'missing_receipt_declaration'
  return (
    <div>
      <label htmlFor={`kind-${lineId}`}>Add</label>
      <select id={`kind-${lineId}`} value={kind} onChange={(e) => setKind(e.target.value)}>
        {Object.entries(KINDS).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
      </select>
      {declaration ? (
        <input aria-label="Why the receipt is missing" value={statement} placeholder="Why is the receipt missing?" onChange={(e) => setStatement(e.target.value)} />
      ) : (
        <input aria-label="File" type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] || null)} />
      )}
      <button type="button" disabled={busy || (declaration ? statement.trim().length < 10 : !file)} onClick={add}>Attach</button>
      <ErrorNote>{error}</ErrorNote>
    </div>
  )
}

function ReviewButtons({ onReview, busy }) {
  const [why, setWhy] = useState('')
  return (
    <div>
      <input aria-label="Reason" value={why} placeholder="Reason, if not accepted" onChange={(e) => setWhy(e.target.value)} />
      <button type="button" disabled={busy} onClick={() => onReview('accepted', '')}>Accept</button>
      <button type="button" disabled={busy || why.trim().length < 3} onClick={() => onReview('more_needed', why)}>Need more</button>
      <button type="button" disabled={busy || why.trim().length < 3} onClick={() => onReview('rejected', why)}>Reject</button>
    </div>
  )
}

function AddLine({ claimId, onDone }) {
  const cats = useLoad(() => expenses.categories(false))
  const { busy, error, run } = useAction()
  const [f, setF] = useState({ categoryId: '', spentOn: '', amount: '', km: '', merchant: '', overLimitReason: '' })
  const cat = (cats.data || []).find((c) => c.id === f.categoryId)
  const byKm = Boolean(cat?.perKmRate)
  async function add(e) {
    e.preventDefault()
    const done = await run(async () => {
      await expenses.addLine(claimId, {
        categoryId: f.categoryId,
        spentOn: f.spentOn,
        amount: byKm ? 1 : f.amount,
        km: byKm ? f.km : null,
        merchant: f.merchant,
        overLimitReason: f.overLimitReason,
      })
      return true
    })
    if (done) {
      setF({ ...f, amount: '', km: '', merchant: '', overLimitReason: '' })
      onDone()
    }
  }
  return (
    <form onSubmit={add} className="grid">
      <div>
        <label htmlFor="line-cat">Category</label>
        <select id="line-cat" value={f.categoryId} onChange={(e) => setF({ ...f, categoryId: e.target.value })}>
          <option value="">Choose…</option>
          {(cats.data || []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        {cat && <div className="hint">{rule(cat)}</div>}
      </div>
      <div>
        <label htmlFor="line-date">Date of the expense</label>
        <input id="line-date" type="date" value={f.spentOn} onChange={(e) => setF({ ...f, spentOn: e.target.value })} />
      </div>
      {byKm ? (
        <div>
          <label htmlFor="line-km">Distance (km)</label>
          <input id="line-km" type="number" min="0.1" step="0.1" value={f.km} onChange={(e) => setF({ ...f, km: e.target.value })} />
        </div>
      ) : (
        <div>
          <label htmlFor="line-amount">Amount (₹)</label>
          <input id="line-amount" type="number" min="0.01" step="0.01" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />
        </div>
      )}
      <div>
        <label htmlFor="line-merchant">Paid to</label>
        <input id="line-merchant" value={f.merchant} maxLength={120} onChange={(e) => setF({ ...f, merchant: e.target.value })} />
      </div>
      {cat?.limitPerExpense && (
        <div>
          <label htmlFor="line-why">Why is it over the limit? (only if it is)</label>
          <input id="line-why" value={f.overLimitReason} onChange={(e) => setF({ ...f, overLimitReason: e.target.value })} />
        </div>
      )}
      <div>
        <button type="submit" disabled={busy || !f.categoryId || !f.spentOn || !(byKm ? f.km : f.amount)}>Add line</button>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </form>
  )
}

function rule(cat) {
  const parts = []
  if (cat.perKmRate) parts.push(`${rupees(cat.perKmRate)} per km`)
  if (cat.limitPerExpense) parts.push(`up to ${rupees(cat.limitPerExpense)} an expense`)
  if (cat.limitPerMonth) parts.push(`${rupees(cat.limitPerMonth)} a month`)
  parts.push(`receipt needed above ${rupees(cat.receiptAbove)}`)
  if (cat.needsTravelProof) parts.push('proof of travel needed')
  return parts.join(', ')
}

function Categories() {
  const cats = useLoad(() => expenses.categories(true))
  const { busy, error, run } = useAction()
  const toast = useToast()
  const blank = { name: '', limitPerExpense: '', limitPerMonth: '', receiptAbove: '500', perKmRate: '', needsTravelProof: false, ledger: '' }
  const [f, setF] = useState(blank)
  const num = (v) => (v === '' ? null : v)
  async function add(e) {
    e.preventDefault()
    const done = await run(async () => {
      await expenses.saveCategory(null, {
        name: f.name,
        limitPerExpense: num(f.limitPerExpense),
        limitPerMonth: num(f.limitPerMonth),
        receiptAbove: num(f.receiptAbove),
        perKmRate: num(f.perKmRate),
        needsTravelProof: f.needsTravelProof,
        needsApprovalFirst: false,
        ledger: f.ledger,
        active: true,
      })
      return true
    })
    if (done) {
      setF(blank)
      cats.reload()
      toast('Category added')
    }
  }
  async function toggle(c) {
    const done = await run(async () => {
      await expenses.saveCategory(c.id, { ...c, active: !c.active })
      return true
    })
    if (done) cats.reload()
  }
  return (
    <>
      <ErrorNote>{error || cats.error}</ErrorNote>
      {(cats.data || []).length === 0 ? (
        <Empty>No categories yet. Add the first below.</Empty>
      ) : (
        <div className="table-wrap">
          <table className="stack">
            <thead><tr><th>Category</th><th>Rules</th><th /></tr></thead>
            <tbody>
              {cats.data.map((c) => (
                <tr key={c.id}>
                  <td data-label="Category">{c.name} {!c.active && <Badge>Off</Badge>}</td>
                  <td data-label="Rules">{rule(c)}</td>
                  <td><button type="button" disabled={busy} onClick={() => toggle(c)}>{c.active ? 'Turn off' : 'Turn on'}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form onSubmit={add} className="grid">
        <div><label htmlFor="cat-name">Name</label><input id="cat-name" value={f.name} maxLength={60} onChange={(e) => setF({ ...f, name: e.target.value })} /></div>
        <div><label htmlFor="cat-limit">Limit per expense (₹)</label><input id="cat-limit" type="number" min="0" value={f.limitPerExpense} onChange={(e) => setF({ ...f, limitPerExpense: e.target.value })} /></div>
        <div><label htmlFor="cat-month">Limit per month (₹)</label><input id="cat-month" type="number" min="0" value={f.limitPerMonth} onChange={(e) => setF({ ...f, limitPerMonth: e.target.value })} /></div>
        <div><label htmlFor="cat-receipt">Receipt needed above (₹)</label><input id="cat-receipt" type="number" min="0" value={f.receiptAbove} onChange={(e) => setF({ ...f, receiptAbove: e.target.value })} /></div>
        <div><label htmlFor="cat-km">Rate per km (₹), if paid by distance</label><input id="cat-km" type="number" min="0" value={f.perKmRate} onChange={(e) => setF({ ...f, perKmRate: e.target.value })} /></div>
        <div><label htmlFor="cat-ledger">Ledger</label><input id="cat-ledger" value={f.ledger} onChange={(e) => setF({ ...f, ledger: e.target.value })} /></div>
        <div>
          <label htmlFor="cat-travel"><input id="cat-travel" type="checkbox" checked={f.needsTravelProof} onChange={(e) => setF({ ...f, needsTravelProof: e.target.checked })} /> Needs proof of travel</label>
        </div>
        <div><button type="submit" disabled={busy || !f.name.trim()}>Add category</button></div>
      </form>
    </>
  )
}

/** Who decides whose claims. With no rule, a claim goes to the person's reporting manager. */
function Approvers() {
  const data = useLoad(expenses.approvers)
  const { busy, error, run } = useAction()
  const toast = useToast()
  const [kind, setKind] = useState('person')
  const [who, setWho] = useState('')
  const [approver, setApprover] = useState('')
  const d = data.data
  async function save(e) {
    e.preventDefault()
    const done = await run(async () => {
      await expenses.saveApprover({ membershipId: kind === 'person' ? who : null, departmentId: kind === 'department' ? who : null, approverId: approver })
      return true
    })
    if (done) {
      setWho('')
      setApprover('')
      data.reload()
      toast('Approver saved')
    }
  }
  async function remove(id) {
    const done = await run(async () => {
      await expenses.removeApprover(id)
      return true
    })
    if (done) data.reload()
  }
  return (
    <>
      <p className="hint">
        By default a claim goes to the person's manager, then to finance. A rule here sends a person's or a department's claims to someone else instead.
        Whether the manager step exists at all, and the amount above which two finance people must verify, are under Settings → Expenses.
      </p>
      <ErrorNote>{error || data.error}</ErrorNote>
      {d && d.rules.length === 0 && <Empty>No rules. Every claim goes to the reporting manager.</Empty>}
      {d && d.rules.length > 0 && (
        <div className="table-wrap">
          <table className="stack">
            <thead><tr><th>Claims of</th><th>Go to</th><th /></tr></thead>
            <tbody>
              {d.rules.map((r) => (
                <tr key={r.id}>
                  <td data-label="Claims of">{r.person || `Department: ${r.department}`}</td>
                  <td data-label="Go to">{r.approver}</td>
                  <td><button type="button" disabled={busy} onClick={() => remove(r.id)}>Remove</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {d && (
        <form onSubmit={save} className="grid">
          <div>
            <label htmlFor="ap-kind">Rule for</label>
            <select id="ap-kind" value={kind} onChange={(e) => { setKind(e.target.value); setWho('') }}>
              <option value="person">A person</option>
              <option value="department">A department</option>
            </select>
          </div>
          <div>
            <label htmlFor="ap-who">{kind === 'person' ? 'Person' : 'Department'}</label>
            <select id="ap-who" value={who} onChange={(e) => setWho(e.target.value)}>
              <option value="">Choose…</option>
              {(kind === 'person' ? d.people : d.departments).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="ap-approver">Approver</label>
            <select id="ap-approver" value={approver} onChange={(e) => setApprover(e.target.value)}>
              <option value="">Choose…</option>
              {d.people.filter((p) => p.canApprove).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
            <div className="hint">Only people who hold the Manager role (or HR Admin) can be approvers.</div>
          </div>
          <div><button type="submit" disabled={busy || !who || !approver}>Save rule</button></div>
        </form>
      )}
    </>
  )
}
