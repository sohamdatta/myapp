import { useState } from 'react'
import { tax } from '../api.js'
import { formatDate } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'
import FileViewer from './FileViewer.jsx'
import { rupees } from './payrollText.js'
import { PROOF, detailWords } from './taxText.js'

const sizeOf = (bytes) => (bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`)

/**
 * One claim with its proofs, for the employee (edit, upload, remove a file) or for HR (open files,
 * accept an amount, return with a reason). Files are fetched with the session and shown from memory.
 */
export function ClaimRow({ item, hr, canEdit, canSendProofs, canReview, onEdit, onRemove, onChanged }) {
  const [viewing, setViewing] = useState(null)
  const [progress, setProgress] = useState(null)
  const [mode, setMode] = useState(null) // 'accept' | 'return'
  const [amount, setAmount] = useState(String(Number(item.amount)))
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [tone, mine, theirs] = PROOF[item.proofStatus] || PROOF.none
  const accepted = item.proofStatus === 'accepted'
  const inputId = `proof-${item.id}`

  async function act(fn) {
    setBusy(true)
    setError('')
    try {
      const next = await fn()
      setMode(null)
      setNote('')
      onChanged(next)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function open(doc) {
    setError('')
    try {
      const blob = await tax.proof(doc.id)
      setViewing({ name: doc.fileName, type: doc.contentType, url: URL.createObjectURL(blob) })
    } catch (err) {
      setError(err.message)
    }
  }

  async function upload(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    if (file.size > 10 * 1024 * 1024) {
      setError('The file is larger than 10 MB. Take the photo again at a lower size, or send a PDF.')
      return
    }
    setError('')
    setProgress(0)
    try {
      onChanged(await tax.addProof(item.id, file, setProgress))
    } catch (err) {
      setError(err.message)
    } finally {
      setProgress(null)
    }
  }

  return (
    <div className={`doc-row${item.proofStatus === 'rejected' ? ' doc-rejected' : ''}`} data-claim={item.label}>
      <div className="doc-head">
        <div>
          <strong>{item.label}</strong>
          <div className="hint">{detailWords(item)}</div>
          {item.head === 'rent' && (
            <div className="hint">
              {item.months} {item.months === 1 ? 'month' : 'months'}: {rupees(item.yearAmount)} in the year
            </div>
          )}
        </div>
        <div className="num claim-amount">
          <strong>{rupees(item.amount)}</strong>
          {item.head === 'rent' && <div className="hint">a month</div>}
        </div>
      </div>
      <p className="claim-proof">
        <Badge tone={tone}>{hr ? theirs : mine}</Badge>
        {accepted && Number(item.acceptedAmount) !== Number(item.amount) && (
          <span className="hint"> Accepted at {rupees(item.acceptedAmount)}{item.head === 'rent' ? ' a month' : ''}.</span>
        )}
        {item.reviewedBy && item.proofStatus !== 'submitted' && (
          <span className="hint">
            {' '}
            {item.proofStatus === 'accepted' ? 'Checked' : 'Returned'} by {item.reviewedBy}, {formatDate(item.reviewedAt)}
          </span>
        )}
      </p>
      {item.proofStatus === 'rejected' && item.reviewNote && <p className="notice">“{item.reviewNote}”</p>}
      {item.proofs.map((doc) => (
        <div key={doc.id} className="file-line">
          <span className="file-name">{doc.fileName}</span>
          <span className="hint">{sizeOf(doc.sizeBytes)}</span>
          <button type="button" className="text-link" onClick={() => open(doc)}>
            Open
          </button>
          {!hr && canSendProofs && !accepted && (
            <button type="button" className="text-link" disabled={busy} onClick={() => act(() => tax.removeProof(item.id, doc.id))}>
              Remove
            </button>
          )}
        </div>
      ))}
      {progress !== null && (
        <div className="progress-row">
          <div className="progress" role="progressbar" aria-valuenow={Math.round(progress * 100)} aria-valuemin={0} aria-valuemax={100}>
            <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
        </div>
      )}
      <div className="form-actions">
        {!hr && canSendProofs && !accepted && (
          <label htmlFor={inputId} className={`file-button ${item.proofs.length ? 'outline' : 'secondary'}`}>
            {item.proofs.length ? 'Add another file' : 'Upload proof'}
            <input id={inputId} type="file" accept="application/pdf,image/jpeg,image/png" className="sr-only" disabled={progress !== null} onChange={upload} />
          </label>
        )}
        {!hr && canEdit && (
          <>
            <button type="button" className="quiet" onClick={onEdit}>
              Change
            </button>
            {!accepted && (
              <button type="button" className="quiet" disabled={busy} onClick={onRemove}>
                Remove
              </button>
            )}
          </>
        )}
        {hr && canReview && item.proofStatus === 'submitted' && !mode && (
          <>
            <button type="button" className="primary" onClick={() => setMode('accept')}>
              Accept…
            </button>
            <button type="button" className="quiet" onClick={() => setMode('return')}>
              Return…
            </button>
          </>
        )}
      </div>
      {mode === 'accept' && (
        <form
          className="inline-form"
          onSubmit={(e) => {
            e.preventDefault()
            act(() => tax.accept(item.id, Number(amount)))
          }}
        >
          <div>
            <label htmlFor={`accept-${item.id}`}>{item.head === 'rent' ? 'Rent a month the proofs support (₹)' : 'Amount the proofs support (₹)'}</label>
            <input id={`accept-${item.id}`} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </div>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy || amount === '' || Number.isNaN(Number(amount))}>
              Accept
            </button>
            <button type="button" className="quiet" onClick={() => setMode(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
      {mode === 'return' && (
        <form
          className="inline-form"
          onSubmit={(e) => {
            e.preventDefault()
            act(() => tax.returnProof(item.id, note))
          }}
        >
          <div>
            <label htmlFor={`return-${item.id}`}>What is wrong with the proof</label>
            <input id={`return-${item.id}`} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="form-actions">
            <button type="submit" className="secondary" disabled={busy || note.trim().length < 3}>
              Return
            </button>
            <button type="button" className="quiet" onClick={() => setMode(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
      <ErrorNote>{error}</ErrorNote>
      {viewing && <FileViewer file={viewing} onClose={() => setViewing(null)} />}
    </div>
  )
}
