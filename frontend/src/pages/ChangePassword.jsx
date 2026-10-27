import { useEffect, useRef, useState } from 'react'
import { changePassword } from '../api.js'
import { useAction } from '../hooks.js'
import { ErrorNote } from '../ui.jsx'

/** Change your own password. Every other session of yours is signed out; this one stays. */
export default function ChangePassword({ onClose }) {
  const dialog = useRef(null)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState('')
  const [done, setDone] = useState(false)
  const { busy, error, run } = useAction()

  useEffect(() => {
    if (dialog.current && !dialog.current.open) dialog.current.showModal()
  }, [])

  async function submit(event) {
    event.preventDefault()
    setFormError('')
    if (next.length < 12) return setFormError('Choose a password of at least 12 characters')
    if (next !== confirm) return setFormError('The two passwords do not match')
    const ok = await run(async () => {
      await changePassword(current, next)
      return true
    })
    if (ok) setDone(true)
  }

  return (
    <dialog ref={dialog} className="confirm-dialog" aria-labelledby="cp-title" onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <h2 id="cp-title">Change your password</h2>
        {done ? (
          <>
            <p role="status">Your password is changed. You were signed out of every other place.</p>
            <button type="button" className="primary" onClick={onClose}>
              Close
            </button>
          </>
        ) : (
          <>
            <label htmlFor="cp-current">Current password</label>
            <input id="cp-current" type="password" autoComplete="current-password" autoFocus value={current} onChange={(e) => setCurrent(e.target.value)} />
            <label htmlFor="cp-new">New password</label>
            <input id="cp-new" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
            <p className="hint">At least 12 characters.</p>
            <label htmlFor="cp-confirm">Repeat the new password</label>
            <input id="cp-confirm" type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
            <ErrorNote>{formError || error}</ErrorNote>
            <button type="submit" className="primary" disabled={busy || !current}>
              {busy ? 'Saving…' : 'Change password'}
            </button>
            <button type="button" className="quiet" onClick={onClose}>
              Cancel
            </button>
          </>
        )}
      </form>
    </dialog>
  )
}
