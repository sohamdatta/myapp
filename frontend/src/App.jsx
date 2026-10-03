import { useEffect, useState } from 'react'
import { currentUser, endSupportSession, logout } from './api.js'
import Console from './pages/Console.jsx'
import Organization from './pages/Organization.jsx'
import { AcceptInvitation, Chooser, LoginForm } from './pages/SignIn.jsx'
import { formatDate } from './hooks.js'

function inviteTokenFromUrl() {
  return new URLSearchParams(window.location.search).get('invite')
}

function clearInviteFromUrl() {
  window.history.replaceState(null, '', window.location.pathname)
}

function Shell({ me, onSwitch, onSignOut, onEndSupport, children }) {
  const where = me.kind === 'platform' ? 'Platform console' : me.organization.name
  return (
    <div className="shell">
      {me.kind === 'support' && (
        <div className="support-banner" role="status">
          <span>
            Support session in {me.organization.name} · {me.support.mode === 'write' ? 'write' : 'read only'} · ends{' '}
            {formatDate(me.support.expiresAt)}. Everything you do is recorded in this Organization's audit log.
          </span>
          <button type="button" className="secondary" onClick={onEndSupport}>
            End session
          </button>
        </div>
      )}
      <header className="topbar">
        <div>
          <div className="where">{where}</div>
          <div className="who">{me.email}</div>
        </div>
        {me.kind !== 'support' && (
          <div className="topbar-actions">
            {me.organizations.length + (me.console ? 1 : 0) > 1 && (
              <button type="button" className="quiet" onClick={onSwitch}>
                Switch
              </button>
            )}
            <button type="button" className="quiet" onClick={onSignOut}>
              Sign out
            </button>
          </div>
        )}
      </header>
      <div className="content">{children}</div>
    </div>
  )
}

export default function App() {
  const [me, setMe] = useState(null)
  const [loading, setLoading] = useState(true)
  const [inviteToken, setInviteToken] = useState(inviteTokenFromUrl)
  const [choosing, setChoosing] = useState(false)

  async function refresh() {
    setMe(await currentUser())
    setLoading(false)
  }

  useEffect(() => {
    let cancelled = false
    currentUser().then((user) => {
      if (cancelled) return
      setMe(user)
      setLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [])

  function entered(next) {
    setChoosing(false)
    setMe(next)
  }

  async function handleSignOut() {
    await logout()
    setChoosing(false)
    setMe(null)
  }

  async function handleEndSupport() {
    await endSupportSession()
    await refresh()
  }

  function closeInvitation() {
    clearInviteFromUrl()
    setInviteToken(null)
  }

  if (loading) {
    return (
      <main>
        <p className="muted">Loading…</p>
      </main>
    )
  }

  if (inviteToken) {
    return (
      <main>
        <AcceptInvitation
          token={inviteToken}
          me={me}
          onAccepted={(next) => {
            closeInvitation()
            entered(next)
          }}
          onCancel={closeInvitation}
        />
      </main>
    )
  }

  if (!me) {
    return (
      <main>
        <LoginForm onSignedIn={setMe} />
      </main>
    )
  }

  if (me.kind === 'identity' || choosing) {
    return (
      <main>
        <Chooser
          me={me}
          onEntered={entered}
          onSignOut={handleSignOut}
          onCancel={me.kind === 'identity' ? undefined : () => setChoosing(false)}
        />
      </main>
    )
  }

  return (
    <Shell me={me} onSwitch={() => setChoosing(true)} onSignOut={handleSignOut} onEndSupport={handleEndSupport}>
      {me.kind === 'platform' ? <Console me={me} onEnteredSupport={entered} /> : <Organization key={me.organization.id} me={me} />}
    </Shell>
  )
}
