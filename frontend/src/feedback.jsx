import { useCallback, useEffect, useRef, useState } from 'react'
import { ConfirmContext, ToastContext } from './feedbackContext.js'

/* ── toasts ─────────────────────────────────────────────────────────
 * A short note that something worked ("Leave approved for Priya"), shown at the bottom of the
 * screen for a few seconds and read out by screen readers. Errors stay on the page instead,
 * next to what went wrong, so they are not missed. */

let nextId = 1

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const timers = useRef(new Map())

  const dismiss = useCallback((id) => {
    clearTimeout(timers.current.get(id))
    timers.current.delete(id)
    setToasts((all) => all.filter((t) => t.id !== id))
  }, [])

  const show = useCallback(
    (message) => {
      const id = nextId++
      setToasts((all) => [...all.slice(-2), { id, message }])
      timers.current.set(
        id,
        setTimeout(() => dismiss(id), 5000),
      )
    },
    [dismiss],
  )

  useEffect(() => {
    const all = timers.current
    return () => all.forEach((timer) => clearTimeout(timer))
  }, [])

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" aria-live="polite" aria-relevant="additions">
        {toasts.map((t) => (
          <div key={t.id} className="toast">
            <span>{t.message}</span>
            <button type="button" className="toast-close" aria-label="Dismiss" onClick={() => dismiss(t.id)}>
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

/* ── confirmations ─────────────────────────────────────────────────
 * For actions that cannot be taken back, or that tell someone else bad news. The dialog says what
 * will happen to whom; the button repeats the action's own word ("Reject", never "OK"); and the
 * safe choice has the focus, so Enter or Escape never does the harmful thing by accident. */

export function ConfirmProvider({ children }) {
  const [ask, setAsk] = useState(null)
  const dialog = useRef(null)
  const keep = useRef(null)

  const confirm = useCallback(
    (options) =>
      new Promise((resolve) => {
        setAsk({ ...options, resolve })
      }),
    [],
  )

  useEffect(() => {
    if (!ask || !dialog.current) return
    if (!dialog.current.open) dialog.current.showModal()
    keep.current?.focus()
  }, [ask])

  function answer(yes) {
    const current = ask
    dialog.current?.close()
    setAsk(null)
    current?.resolve(yes)
  }

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {ask && (
        <dialog
          ref={dialog}
          className="confirm-dialog"
          aria-labelledby="confirm-title"
          aria-describedby={ask.body ? 'confirm-body' : undefined}
          onCancel={(e) => {
            e.preventDefault()
            answer(false)
          }}
          onClick={(e) => {
            // A tap on the dimmed area outside the box is a "no".
            if (e.target === dialog.current) answer(false)
          }}
        >
          <h2 id="confirm-title">{ask.title}</h2>
          {ask.body && <p id="confirm-body">{ask.body}</p>}
          <div className="confirm-actions">
            <button type="button" ref={keep} className="outline" onClick={() => answer(false)}>
              {ask.cancelLabel || 'Keep it'}
            </button>
            <button type="button" className={ask.danger ? 'primary danger-fill' : 'primary'} onClick={() => answer(true)}>
              {ask.confirmLabel}
            </button>
          </div>
        </dialog>
      )}
    </ConfirmContext.Provider>
  )
}
