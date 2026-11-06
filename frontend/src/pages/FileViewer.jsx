import { useEffect, useRef } from 'react'

/** A file shown over the page from memory: an image, or a PDF in a frame, with a download. */
export default function FileViewer({ file, onClose }) {
  const closeButton = useRef(null)
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  useEffect(() => () => URL.revokeObjectURL(file.url), [file])
  // Keyboard and screen-reader users start inside the dialog, and go back to where they were when it closes.
  useEffect(() => {
    const before = document.activeElement
    closeButton.current?.focus()
    return () => before?.focus?.()
  }, [])

  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label={file.name}>
      <div className="viewer-bar">
        <span className="file-name">{file.name}</span>
        <a className="outline file-button" href={file.url} download={file.name}>
          Download
        </a>
        <button type="button" className="secondary" onClick={onClose} ref={closeButton}>
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
