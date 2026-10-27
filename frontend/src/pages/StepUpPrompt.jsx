import { useEffect, useRef, useState } from 'react'
import { onStepUp, stepUp } from '../api.js'
import { useAction } from '../hooks.js'
import { ErrorNote } from '../ui.jsx'

/** Asks for a fresh authenticator code when an action needs one, then lets the action go on. Mounted once, for the signed-in app. */
export default function StepUpPrompt() {
  const [waiting, setWaiting] = useState(null)
  const [code, setCode] = useState('')
  const { busy, error, run } = useAction()
  const dialog = useRef(null)

  useEffect(() => {
    // requests refused together share one question and all go on after one code
    let pending = null
    onStepUp(() => {
      if (!pending) {
        pending = new Promise((resolve) => setWaiting({ resolve: (ok) => { pending = null; resolve(ok) } }))
      }
      return pending
    })
    return () => onStepUp(null)
  }, [])

  useEffect(() => {
    if (waiting && dialog.current && !dialog.current.open) dialog.current.showModal()
  }, [waiting])

  function finish(ok) {
    waiting.resolve(ok)
    setWaiting(null)
    setCode('')
  }

  async function submit(event) {
    event.preventDefault()
    const done = await run(async () => {
      await stepUp(code)
      return true
    })
    if (done) finish(true)
  }

  if (!waiting) return null
  return (
    <dialog ref={dialog} className="confirm-dialog" aria-labelledby="stepup-title" onClose={() => waiting && finish(false)}>
      <form onSubmit={submit} noValidate>
        <h2 id="stepup-title">Confirm it is you</h2>
        <p className="muted">This action needs a fresh code from your authenticator app.</p>
        <label htmlFor="stepup-code">Code</label>
        <input id="stepup-code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />
        <ErrorNote>{error}</ErrorNote>
        <button type="submit" className="primary" disabled={busy || !code.trim()}>
          Continue
        </button>
        <button type="button" className="quiet" onClick={() => dialog.current.close()}>
          Cancel
        </button>
      </form>
    </dialog>
  )
}
