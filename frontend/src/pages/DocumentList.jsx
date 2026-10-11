import { useEffect, useState } from 'react'
import { formatDate, formatDay } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'

/**
 * The documents a joiner is asked for, each with what has been uploaded, for
 * the joiner (upload, see status) and for HR (also open, verify, reject).
 * Files are fetched with the session and shown from memory; nothing is
 * linked by URL, since the server sends files only with the session's token.
 */

const DOC_STATUS = {
  uploaded: ['warn', 'Waiting to be checked'],
  verified: ['good', 'Verified'],
  rejected: ['warn', 'Rejected'],
}

function requirement(type) {
  if (type.required === 'before_activation') return 'Needed before joining'
  if (type.required === 'within_days') return `Within ${type.withinDays} days of joining, by ${formatDay(type.dueOn)}`
  return 'Optional'
}

const sizeOf = (bytes) => (bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`)

export default function DocumentList({ data, canUpload, canVerify, canOpen, onUpload, onOpen, onVerify, onReject, joiner }) {
  const [viewing, setViewing] = useState(null) // { name, url, type }
  const [error, setError] = useState('')

  async function open(doc) {
    setError('')
    try {
      const blob = await onOpen(doc.id)
      setViewing({ name: doc.fileName, type: doc.contentType, url: URL.createObjectURL(blob) })
    } catch (err) {
      setError(err.message)
    }
  }

  return (
    <div className="doc-list">
      <ErrorNote>{error}</ErrorNote>
      {data.types.length === 0 && <p className="hint">No documents are asked for.</p>}
      {data.types.map((type) => (
        <DocumentRow
          key={type.id}
          type={type}
          joiner={joiner}
          canUpload={canUpload}
          canVerify={canVerify}
          canOpen={canOpen}
          onUpload={onUpload}
          onOpen={open}
          onVerify={onVerify}
          onReject={onReject}
        />
      ))}
      {data.others.length > 0 && (
        <div className="doc-row">
          <div className="doc-head">
            <strong>Other documents</strong>
          </div>
          {data.others.map((doc) => (
            <FileLine key={doc.id} doc={doc} canOpen={canOpen} onOpen={open} />
          ))}
        </div>
      )}
      {viewing && <Viewer file={viewing} onClose={() => setViewing(null)} />}
    </div>
  )
}

function DocumentRow({ type, joiner, canUpload, canVerify, canOpen, onUpload, onOpen, onVerify, onReject }) {
  const current = type.documents.find((d) => d.status !== 'superseded')
  const earlier = type.documents.filter((d) => d !== current)
  const [progress, setProgress] = useState(null)
  const [rejecting, setRejecting] = useState(false)
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const inputId = `upload-${type.id}`
  const mayUpload = canUpload && !(joiner && current?.status === 'verified')

  async function upload(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setError('')
    if (file.size > 10 * 1024 * 1024) {
      setError('The file is larger than 10 MB. Take the photo again at a lower size, or send a PDF.')
      return
    }
    setProgress(0)
    try {
      await onUpload(type.id, file, setProgress)
    } catch (err) {
      setError(err.message)
    } finally {
      setProgress(null)
    }
  }

  async function decide(verify) {
    setBusy(true)
    setError('')
    try {
      if (verify) await onVerify(current.id)
      else await onReject(current.id, note)
      setRejecting(false)
      setNote('')
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`doc-row${current?.status === 'rejected' ? ' doc-rejected' : ''}`} data-type={type.name}>
      <div className="doc-head">
        <div>
          <strong>{type.name}</strong>
          <div className="hint">{requirement(type)}</div>
        </div>
        {current ? <Badge tone={DOC_STATUS[current.status][0]}>{DOC_STATUS[current.status][1]}</Badge> : <Badge>Not uploaded</Badge>}
      </div>
      {type.instructions && <p className="hint">{type.instructions}</p>}
      {current && <FileLine doc={current} canOpen={canOpen} onOpen={onOpen} />}
      {current?.status === 'rejected' && current.decisionNote && (
        <p className="notice">
          <strong>{joiner ? 'HR says:' : 'Rejected:'}</strong> {current.decisionNote}
        </p>
      )}
      {progress !== null && (
        <div className="progress" role="progressbar" aria-label={`Uploading ${type.name}`} aria-valuenow={Math.round(progress * 100)}>
          <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        {mayUpload && (
          <label htmlFor={inputId} className={`file-button ${current ? 'outline' : 'secondary'}`}>
            {current ? 'Replace' : joiner ? 'Upload or take a photo' : 'Upload'}
            <input
              id={inputId}
              type="file"
              accept="application/pdf,image/jpeg,image/png"
              className="sr-only"
              disabled={progress !== null}
              onChange={upload}
            />
          </label>
        )}
        {canVerify && current?.status === 'uploaded' && !rejecting && (
          <>
            <button type="button" className="primary" disabled={busy} onClick={() => decide(true)}>
              Verify
            </button>
            <button type="button" className="outline" onClick={() => setRejecting(true)}>
              Reject
            </button>
          </>
        )}
      </div>
      {rejecting && (
        <div className="verify-box">
          <label htmlFor={`reject-${type.id}`}>What is wrong with it</label>
          <input id={`reject-${type.id}`} value={note} placeholder="The photo is blurred" onChange={(e) => setNote(e.target.value)} />
          <div className="form-actions">
            <button type="button" className="secondary" disabled={busy} onClick={() => decide(false)}>
              Reject
            </button>
            <button type="button" className="quiet" onClick={() => setRejecting(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {!joiner && earlier.length > 0 && (
        <details className="hint">
          <summary>Earlier uploads</summary>
          {earlier.map((doc) => (
            <FileLine key={doc.id} doc={doc} canOpen={canOpen} onOpen={onOpen} />
          ))}
        </details>
      )}
    </div>
  )
}

function FileLine({ doc, canOpen, onOpen }) {
  return (
    <div className="file-line">
      <span className="file-name">{doc.fileName}</span>
      <span className="hint">
        {sizeOf(doc.sizeBytes)} · {formatDate(doc.createdAt)}
        {doc.status === 'superseded' && ' · replaced'}
      </span>
      {canOpen && (
        <button type="button" className="quiet plain" onClick={() => onOpen(doc)}>
          View
        </button>
      )}
    </div>
  )
}

function Viewer({ file, onClose }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  useEffect(() => () => URL.revokeObjectURL(file.url), [file])

  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label={file.name}>
      <div className="viewer-bar">
        <span className="file-name">{file.name}</span>
        <a className="outline file-button" href={file.url} download={file.name}>
          Download
        </a>
        <button type="button" className="secondary" onClick={onClose}>
          Close
        </button>
      </div>
      <div className="viewer-body">
        {file.type === 'application/pdf' ? (
          <iframe title={file.name} src={file.url} />
        ) : (
          <img src={file.url} alt={file.name} />
        )}
      </div>
    </div>
  )
}
