import { useEffect, useState } from 'react'
import { acceptInvitation, enterConsole, enterOrganization, login, previewInvitation } from '../api.js'
import { useAction } from '../hooks.js'
import { ErrorNote } from '../ui.jsx'

export function LoginForm({ onSignedIn }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const { busy, error, run } = useAction()

  async function handleSubmit(event) {
    event.preventDefault()
    const me = await run(() => login(email, password))
    if (me) onSignedIn(me)
  }

  return (
    <form className="card" onSubmit={handleSubmit} noValidate>
      <h1>Sign in</h1>
      <p className="muted">Use your work email to continue.</p>

      <label htmlFor="email">Email</label>
      <input
        id="email"
        name="email"
        type="email"
        autoComplete="username"
        autoFocus
        value={email}
        onChange={(e) => setEmail(e.target.value)}
      />

      <label htmlFor="password">Password</label>
      <div className="password-row">
        <input
          id="password"
          name="password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <button type="button" className="link" onClick={() => setShowPassword((v) => !v)} aria-pressed={showPassword}>
          {showPassword ? 'Hide' : 'Show'}
        </button>
      </div>

      <ErrorNote>{error}</ErrorNote>

      <button type="submit" className="primary" disabled={busy}>
        {busy ? 'Signing in…' : 'Sign in'}
      </button>
    </form>
  )
}

/** Shown only when someone asks to switch, or has nowhere to go. Sign-in itself goes straight in. */
export function Chooser({ me, onEntered, onSignOut, onCancel }) {
  const { busy, error, run } = useAction()
  const nothing = me.organizations.length === 0 && !me.console

  async function enter(action) {
    const next = await run(action)
    if (next) onEntered(next)
  }

  return (
    <div className="card">
      <h1>{nothing ? 'No access yet' : 'Switch to'}</h1>
      <p className="muted">
        {nothing
          ? `${me.email} does not belong to an Organization. Ask your administrator for an invitation.`
          : `Signed in as ${me.email}.`}
      </p>

      <div className="choices">
        {me.organizations.map((o) => (
          <button
            key={o.tenantId}
            type="button"
            className="choice"
            disabled={busy}
            onClick={() => enter(() => enterOrganization(o.tenantId))}
          >
            <span className="choice-title">{o.tenantName}</span>
            <span className="choice-note">{o.status === 'ended' ? 'Former employee access' : 'Organization'}</span>
          </button>
        ))}
        {me.console && (
          <button type="button" className="choice" disabled={busy} onClick={() => enter(enterConsole)}>
            <span className="choice-title">Platform console</span>
            <span className="choice-note">{me.console.replace('_', ' ')}</span>
          </button>
        )}
      </div>

      <ErrorNote>{error}</ErrorNote>

      {onCancel && (
        <button type="button" className="quiet" onClick={onCancel}>
          Back
        </button>
      )}
      <button type="button" className="quiet" onClick={onSignOut}>
        Sign out
      </button>
    </div>
  )
}

/** The page behind an invitation link. */
export function AcceptInvitation({ token, me, onAccepted, onCancel }) {
  const [offer, setOffer] = useState(null)
  const [loadError, setLoadError] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState('')
  const { busy, error, run } = useAction()

  useEffect(() => {
    let cancelled = false
    previewInvitation(token)
      .then((data) => !cancelled && setOffer(data))
      .catch((err) => !cancelled && setLoadError(err.message))
    return () => {
      cancelled = true
    }
  }, [token])

  if (loadError) {
    return (
      <div className="card">
        <h1>Invitation not available</h1>
        <p className="muted">{loadError}</p>
        <button type="button" className="primary" onClick={onCancel}>
          Go to sign in
        </button>
      </div>
    )
  }
  if (!offer) return <p className="muted">Loading…</p>

  const signedInAsInvitee = me && me.email.toLowerCase() === offer.email.toLowerCase()
  const needsNewPassword = !offer.identityExists
  const needsPassword = offer.identityExists && !signedInAsInvitee

  async function handleSubmit(event) {
    event.preventDefault()
    setFormError('')
    if (needsNewPassword && password.length < 12) {
      setFormError('Choose a password of at least 12 characters')
      return
    }
    if (needsNewPassword && password !== confirm) {
      setFormError('The two passwords do not match')
      return
    }
    const next = await run(() => acceptInvitation(token, password || null))
    if (next) onAccepted(next)
  }

  return (
    <form className="card" onSubmit={handleSubmit} noValidate>
      <h1>Join {offer.tenantName}</h1>
      <p className="muted">
        {offer.email} is invited as {offer.roleNames.join(', ')}.
      </p>

      {needsNewPassword && (
        <>
          <label htmlFor="new-password">Choose a password</label>
          <input
            id="new-password"
            type="password"
            autoComplete="new-password"
            autoFocus
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <label htmlFor="confirm-password">Repeat the password</label>
          <input
            id="confirm-password"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
        </>
      )}

      {needsPassword && (
        <>
          <label htmlFor="existing-password">Your password</label>
          <input
            id="existing-password"
            type="password"
            autoComplete="current-password"
            autoFocus
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <p className="hint">This email already has a login. Enter its password to add this Organization.</p>
        </>
      )}

      <ErrorNote>{formError || error}</ErrorNote>

      <button type="submit" className="primary" disabled={busy}>
        {busy ? 'Joining…' : 'Accept invitation'}
      </button>
    </form>
  )
}
