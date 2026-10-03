import { useEffect, useState } from 'react'
import { currentUser, endSupportSession, logout } from './api.js'
import Console from './pages/Console.jsx'
import Organization from './pages/Organization.jsx'
import { AcceptInvitation, Chooser, LoginForm } from './pages/SignIn.jsx'
import { formatDate } from './hooks.js'
import { firstPage, navFor } from './nav.js'

function inviteTokenFromUrl() {
  return new URLSearchParams(window.location.search).get('invite')
}

function clearInviteFromUrl() {
  window.history.replaceState(null, '', window.location.pathname)
}

function Shell({ me, nav, page, onNavigate, onSwitch, onSignOut, onEndSupport, children }) {
  const where = me.kind === 'platform' ? 'Platform console' : me.organization.name
  const title = nav.flatMap((section) => section.items).find((item) => item.id === page)?.label || where
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
      <div className="shell-body">
        <nav className="sidebar" aria-label="Main">
          <div className="brand">
            <div className="brand-mark" aria-hidden="true">
              {where[0].toUpperCase()}
            </div>
            <div>
              <div className="where">{where}</div>
              <div className="brand-sub">HRMS</div>
            </div>
          </div>
          {nav.map((section) => (
            <div key={section.label} className="nav-section">
              <div className="nav-label">{section.label}</div>
              {section.items.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className={item.id === page ? 'nav-item nav-current' : 'nav-item'}
                  aria-current={item.id === page ? 'page' : undefined}
                  onClick={() => onNavigate(item.id)}
                >
                  {item.label}
                </button>
              ))}
            </div>
          ))}
        </nav>
        <main className="main">
          <header className="topbar">
            <h1 className="page-title">{title}</h1>
            <div className="topbar-actions">
              <span className="who">{me.email}</span>
              {me.kind !== 'support' && me.organizations.length + (me.console ? 1 : 0) > 1 && (
                <button type="button" className="quiet plain" onClick={onSwitch}>
                  Switch
                </button>
              )}
              {me.kind !== 'support' && (
                <button type="button" className="quiet plain" onClick={onSignOut}>
                  Sign out
                </button>
              )}
            </div>
          </header>
          <div className="content">{children}</div>
        </main>
      </div>
    </div>
  )
}

/** The signed-in app: the sidebar decides which page shows. Remounted when the person moves to another place. */
function Workspace({ me, onSwitch, onSignOut, onEndSupport, onEnteredSupport }) {
  const nav = navFor(me)
  const [page, setPage] = useState(() => firstPage(nav))
  return (
    <Shell me={me} nav={nav} page={page} onNavigate={setPage} onSwitch={onSwitch} onSignOut={onSignOut} onEndSupport={onEndSupport}>
      {me.kind === 'platform' ? (
        <Console me={me} page={page} onEnteredSupport={onEnteredSupport} />
      ) : (
        <Organization me={me} page={page} onNavigate={setPage} />
      )}
    </Shell>
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
      <main className="centered">
        <p className="muted">Loading…</p>
      </main>
    )
  }

  if (inviteToken) {
    return (
      <main className="centered">
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
      <main className="centered">
        <LoginForm onSignedIn={setMe} />
      </main>
    )
  }

  if (me.kind === 'identity' || choosing) {
    return (
      <main className="centered">
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
    <Workspace
      key={me.kind === 'platform' ? 'console' : `${me.kind}-${me.organization.id}`}
      me={me}
      onSwitch={() => setChoosing(true)}
      onSignOut={handleSignOut}
      onEndSupport={handleEndSupport}
      onEnteredSupport={entered}
    />
  )
}
