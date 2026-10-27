import { useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { changePassword, mfa } from '../api.js'
import { useAction, useLoad } from '../hooks.js'
import { ErrorNote } from '../ui.jsx'

/** Your own sign-in: change the password, and set up the second step. Every other session of yours ends when either changes. */
export default function Security({ onClose }) {
  const dialog = useRef(null)

  useEffect(() => {
    if (dialog.current && !dialog.current.open) dialog.current.showModal()
  }, [])

  return (
    <dialog ref={dialog} className="confirm-dialog" aria-labelledby="sec-title" onClose={onClose}>
      <h2 id="sec-title">Security</h2>
      <PasswordSection />
      <hr />
      <TwoStepSection />
      <button type="button" className="quiet" onClick={onClose}>
        Close
      </button>
    </dialog>
  )
}

function PasswordSection() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState('')
  const [done, setDone] = useState(false)
  const { busy, error, run } = useAction()

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

  if (done) return <p role="status">Your password is changed. You were signed out of every other place.</p>
  return (
    <form onSubmit={submit} noValidate aria-label="Change your password">
      <h3>Change your password</h3>
      <label htmlFor="cp-current">Current password</label>
      <input id="cp-current" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
      <label htmlFor="cp-new">New password</label>
      <input id="cp-new" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
      <p className="hint">At least 12 characters.</p>
      <label htmlFor="cp-confirm">Repeat the new password</label>
      <input id="cp-confirm" type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
      <ErrorNote>{formError || error}</ErrorNote>
      <button type="submit" className="primary" disabled={busy || !current}>
        {busy ? 'Saving…' : 'Change password'}
      </button>
    </form>
  )
}

function TwoStepSection() {
  const status = useLoad(mfa.status)
  const { busy, error, run } = useAction()
  const [setup, setSetup] = useState(null) // { secret, uri, qr }
  const [code, setCode] = useState('')
  const [codes, setCodes] = useState(null) // recovery codes, shown once
  const [password, setPassword] = useState('')
  const [asking, setAsking] = useState(null) // 'recovery' | 'disable'

  async function begin() {
    const started = await run(() => mfa.start())
    if (started) setSetup({ ...started, qr: await QRCode.toDataURL(started.uri, { margin: 1, width: 192 }) })
  }

  async function confirm(event) {
    event.preventDefault()
    const result = await run(() => mfa.confirm(code))
    if (result) {
      setCodes(result.recoveryCodes)
      setSetup(null)
      setCode('')
      status.reload()
    }
  }

  async function submitAsk(event) {
    event.preventDefault()
    if (asking === 'recovery') {
      const result = await run(() => mfa.recovery(code))
      if (result) setCodes(result.recoveryCodes)
    } else {
      const ok = await run(async () => {
        await mfa.disable(password, code)
        return true
      })
      if (ok) setCodes(null)
    }
    setAsking(null)
    setCode('')
    setPassword('')
    status.reload()
  }

  if (status.loading) return <p className="muted">Loading…</p>
  if (status.error) return <ErrorNote>{status.error}</ErrorNote>

  return (
    <section aria-label="Two-step sign-in">
      <h3>Two-step sign-in</h3>
      {codes && (
        <div role="status">
          <p>
            <strong>Save these recovery codes now.</strong> Each works once if you lose your phone. They are not shown again.
          </p>
          <ul id="recovery-codes" className="recovery-codes">
            {codes.map((c) => (
              <li key={c}>
                <code>{c}</code>
              </li>
            ))}
          </ul>
          <button type="button" className="secondary" onClick={() => setCodes(null)}>
            I have saved them
          </button>
        </div>
      )}

      {!codes && !status.data.enrolled && !setup && (
        <>
          <p className="muted">Ask for a code from an authenticator app each time you sign in, as well as the password.</p>
          <button type="button" className="secondary" disabled={busy} onClick={begin}>
            Set up two-step sign-in
          </button>
        </>
      )}

      {setup && (
        <form onSubmit={confirm} noValidate>
          <p>Scan this with an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password…), then enter the 6-digit code it shows.</p>
          <img src={setup.qr} alt="QR code for the authenticator app" width="192" height="192" />
          <p className="hint">
            Cannot scan? Enter this key by hand: <code id="mfa-secret">{setup.secret}</code>
          </p>
          <label htmlFor="mfa-confirm-code">Code from the app</label>
          <input id="mfa-confirm-code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />
          <button type="submit" className="primary" disabled={busy || !code.trim()}>
            Turn on
          </button>
        </form>
      )}

      {!codes && status.data.enrolled && !asking && (
        <>
          <p role="status">
            Two-step sign-in is <strong>on</strong>. {status.data.recoveryCodesLeft} recovery codes left.
          </p>
          <button type="button" className="quiet" onClick={() => setAsking('recovery')}>
            New recovery codes
          </button>
          <button type="button" className="quiet danger" onClick={() => setAsking('disable')}>
            Turn off
          </button>
        </>
      )}

      {asking && (
        <form onSubmit={submitAsk} noValidate>
          {asking === 'disable' && (
            <>
              <label htmlFor="mfa-off-password">Your password</label>
              <input id="mfa-off-password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </>
          )}
          <label htmlFor="mfa-ask-code">Code from the app</label>
          <input id="mfa-ask-code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />
          <button type="submit" className="primary" disabled={busy || !code.trim()}>
            {asking === 'disable' ? 'Turn off two-step sign-in' : 'Make new recovery codes'}
          </button>
          <button type="button" className="quiet" onClick={() => setAsking(null)}>
            Cancel
          </button>
        </form>
      )}
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}
