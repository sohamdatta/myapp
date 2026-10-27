import { lazy, Suspense, useEffect, useState } from 'react'
import { acceptInvitation, completeMfa, enterConsole, enterOrganization, forgotPassword, login, previewInvitation, resetPassword } from '../api.js'
import { useAction } from '../hooks.js'
import { ErrorNote } from '../ui.jsx'
import NexvanceLogo from '../brand/NexvanceLogo.jsx'

const Security = lazy(() => import('./Security.jsx'))

export function LoginForm({ onSignedIn }) {
  const [forgot, setForgot] = useState(false)
  if (forgot) return <ForgotPassword onBack={() => setForgot(false)} />
  return <SignInForm onSignedIn={onSignedIn} onForgot={() => setForgot(true)} />
}

function SignInForm({ onSignedIn, onForgot }) {
  const [challenge, setChallenge] = useState(null)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const { busy, error, run } = useAction()

  async function handleSubmit(event) {
    event.preventDefault()
    const me = await run(() => login(email, password))
    if (me?.mfaRequired) setChallenge(me.challenge)
    else if (me) onSignedIn(me)
  }

  if (challenge) return <CodeForm challenge={challenge} onSignedIn={onSignedIn} onBack={() => setChallenge(null)} />

  return (
    <>
    <div className="brand brand-sign-in">
      <NexvanceLogo size={40} tagline />
    </div>
    <form className="card" onSubmit={handleSubmit} noValidate>
      <h1>Sign in</h1>
      <p className="muted">Use your work email. You go straight to your Organization.</p>

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
      <button type="button" className="link" onClick={onForgot}>
        Forgot your password?
      </button>
    </form>
    <p className="muted">New here? Open the invitation link your administrator sent you.</p>
    </>
  )
}

/** Shown only when someone asks to switch, or has nowhere to go. Sign-in itself goes straight in. */
export function Chooser({ me, onEntered, onSignOut, onCancel }) {
  const { busy, error, run } = useAction()
  const nothing = me.organizations.length === 0 && !me.console
  const suspended = me.suspendedIn || []
  const [settingUp, setSettingUp] = useState(false)
  const refused = me.entryRefused

  async function enter(action) {
    const next = await run(action)
    if (next) onEntered(next)
  }

  return (
    <div className="card">
      <h1>{nothing ? (suspended.length ? 'Access suspended' : 'No access yet') : 'Switch to'}</h1>
      <p className="muted">
        {nothing
          ? suspended.length
            ? `Your access to ${suspended.join(', ')} is suspended. Ask its administrator to reactivate it.`
            : `${me.email} does not belong to an Organization. Ask your administrator for an invitation.`
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
      {refused && !error && <ErrorNote>{refused.message}</ErrorNote>}
      {(refused?.code === 'mfa.enrolment_required' || error?.includes?.('two-step')) && (
        <button type="button" className="primary" onClick={() => setSettingUp(true)}>
          Set up two-step sign-in
        </button>
      )}
      {settingUp && (
        <Suspense fallback={null}>
          <Security onClose={() => setSettingUp(false)} />
        </Suspense>
      )}

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
            aria-describedby="new-password-hint"
            type="password"
            autoComplete="new-password"
            autoFocus
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <p id="new-password-hint" className="hint">
            At least 12 characters. A few unrelated words make a strong one you can remember.
          </p>
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

/** Asks for a reset link. Says the same thing for every address, so it cannot be used to find who has an account. */
function ForgotPassword({ onBack }) {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const { busy, error, run } = useAction()

  async function handleSubmit(event) {
    event.preventDefault()
    const done = await run(async () => {
      await forgotPassword(email)
      return true
    })
    if (done) setSent(true)
  }

  return (
    <form className="card" onSubmit={handleSubmit} noValidate>
      <h1>Reset your password</h1>
      {sent ? (
        <>
          <p role="status">If {email.trim() || 'that address'} has an account, a reset link is on its way. It works once and expires in 30 minutes.</p>
          <button type="button" className="primary" onClick={onBack}>
            Back to sign in
          </button>
        </>
      ) : (
        <>
          <p className="muted">Enter your work email and we will send you a link to choose a new password.</p>
          <label htmlFor="forgot-email">Email</label>
          <input id="forgot-email" type="email" autoComplete="username" autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
          <ErrorNote>{error}</ErrorNote>
          <button type="submit" className="primary" disabled={busy || !email.trim()}>
            {busy ? 'Sending…' : 'Send the link'}
          </button>
          <button type="button" className="link" onClick={onBack}>
            Back to sign in
          </button>
        </>
      )}
    </form>
  )
}

/** The page behind a reset link: choose a new password. Nobody is signed in by it. */
export function ResetPassword({ token, onDone, onCancel }) {
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState('')
  const [changed, setChanged] = useState(false)
  const { busy, error, run } = useAction()

  async function handleSubmit(event) {
    event.preventDefault()
    setFormError('')
    if (password.length < 12) {
      setFormError('Choose a password of at least 12 characters')
      return
    }
    if (password !== confirm) {
      setFormError('The two passwords do not match')
      return
    }
    const done = await run(async () => {
      await resetPassword(token, password)
      return true
    })
    if (done) setChanged(true)
  }

  if (changed) {
    return (
      <div className="card">
        <h1>Password changed</h1>
        <p role="status">Your password is changed and you were signed out everywhere. Sign in with the new one.</p>
        <button type="button" className="primary" onClick={onDone}>
          Go to sign in
        </button>
      </div>
    )
  }
  return (
    <form className="card" onSubmit={handleSubmit} noValidate>
      <h1>Choose a new password</h1>
      <label htmlFor="new-password">New password</label>
      <input id="new-password" aria-describedby="new-password-hint" type="password" autoComplete="new-password" autoFocus value={password} onChange={(e) => setPassword(e.target.value)} />
      <p id="new-password-hint" className="hint">
        At least 12 characters. A few unrelated words make a strong one you can remember.
      </p>
      <label htmlFor="confirm-password">Repeat the password</label>
      <input id="confirm-password" type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
      <ErrorNote>{formError || error}</ErrorNote>
      <button type="submit" className="primary" disabled={busy}>
        {busy ? 'Saving…' : 'Change password'}
      </button>
      <button type="button" className="link" onClick={onCancel}>
        Back to sign in
      </button>
    </form>
  )
}

/** The second step: the code from the authenticator app, or one of the ten recovery codes. */
function CodeForm({ challenge, onSignedIn, onBack }) {
  const [code, setCode] = useState('')
  const { busy, error, run } = useAction()

  async function handleSubmit(event) {
    event.preventDefault()
    const me = await run(() => completeMfa(challenge, code))
    if (me) onSignedIn(me)
  }

  return (
    <form className="card" onSubmit={handleSubmit} noValidate>
      <h1>Enter your code</h1>
      <p className="muted">Open your authenticator app and enter the 6-digit code for this account. Lost your phone? Use a recovery code instead.</p>
      <label htmlFor="mfa-code">Code</label>
      <input id="mfa-code" name="code" inputMode="text" autoComplete="one-time-code" autoFocus value={code} onChange={(e) => setCode(e.target.value)} />
      <ErrorNote>{error}</ErrorNote>
      <button type="submit" className="primary" disabled={busy || !code.trim()}>
        {busy ? 'Checking…' : 'Sign in'}
      </button>
      <button type="button" className="link" onClick={onBack}>
        Start again
      </button>
    </form>
  )
}
