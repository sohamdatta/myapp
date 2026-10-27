import { Suspense, lazy, useEffect, useRef, useState } from 'react'
import { currentUser, endSupportSession, logout } from './api.js'
import PageView from './PageView.jsx'
import { preloadAll, preloadPage, rememberLanding } from './pageRegistry.js'
import { isPlainClick, pathFor, resetLocation, usePage } from './router.js'
import { AcceptInvitation, Chooser, LoginForm, ResetPassword } from './pages/SignIn.jsx'
import StepUpPrompt from './pages/StepUpPrompt.jsx'
// loaded when opened: it carries the QR code library, which most visits never need
const Security = lazy(() => import('./pages/Security.jsx'))
import { formatDate } from './hooks.js'
import Bell from './Bell.jsx'
import RegionBoundary from './RegionBoundary.jsx'
import { PRODUCT, logoUrl } from './brand/brand.js'
import NexvanceLogo from './brand/NexvanceLogo.jsx'
import { NavIcon } from './icons.jsx'
import { initials } from './initials.js'
import { firstPage, navFor } from './nav.js'

function inviteTokenFromUrl() {
  return new URLSearchParams(window.location.search).get('invite')
}

function resetTokenFromUrl() {
  return new URLSearchParams(window.location.search).get('reset')
}

function clearInviteFromUrl() {
  window.history.replaceState(null, '', window.location.pathname)
}

function Shell({ me, nav, page, onNavigate, onSwitch, onSignOut, onEndSupport, children }) {
  const [changingPassword, setChangingPassword] = useState(false)
  const where = me.kind === 'platform' ? 'Platform console' : me.organization.name
  const items = nav.flatMap((section) => section.items)
  const title = items.find((item) => item.id === page)?.label || where
  const logo = me.kind === 'platform' ? null : logoUrl(me.organization)
  const [menuOpen, setMenuOpen] = useState(false)
  const heading = useRef(null)
  const firstRender = useRef(true)

  useEffect(() => {
    document.title = title === where ? `${where} · ${PRODUCT.name} HRMS` : `${title} · ${where}`
  }, [title, where])

  // After moving to another page, start at its top with the focus on its title, as a page load would.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    window.scrollTo(0, 0)
    heading.current?.focus({ preventScroll: true })
  }, [page])

  // The menu on phones closes with Escape, like any overlay.
  useEffect(() => {
    if (!menuOpen) return
    const onKey = (e) => e.key === 'Escape' && setMenuOpen(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [menuOpen])

  // A few seconds in, once the first page has had its data, fetch the person's other pages quietly so
  // later clicks are instant. Earlier, the downloads would compete with the first page's own requests.
  useEffect(() => {
    const timer = setTimeout(() => preloadAll(me, items.map((item) => item.id)), 3000)
    return () => clearTimeout(timer)
    // Once per Workspace: the items do not change while it is mounted.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function go(id) {
    setMenuOpen(false)
    onNavigate(id)
  }

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
        {menuOpen && <div className="drawer-backdrop" aria-hidden="true" onClick={() => setMenuOpen(false)} />}
        <nav id="main-nav" className={menuOpen ? 'sidebar sidebar-open' : 'sidebar'} aria-label="Main">
          {logo ? (
            <div className="brand brand-org">
              <img className="org-logo" src={logo} alt={`${where} logo`} />
              <div className="where">{where}</div>
            </div>
          ) : (
            <div className="brand">
              <NexvanceLogo />
            </div>
          )}
          {!logo && <div className="where where-under">{where}</div>}
          <RegionBoundary region="nav" page={page} fallback={<p className="hint">The menu could not be drawn. Reload the page.</p>}>
            {nav.map((section) => (
              <div key={section.label} className="nav-section">
                <div className="nav-label">{section.label}</div>
                {section.items.map((item) => (
                  <a
                    key={item.id}
                    href={pathFor(item.id)}
                    className={item.id === page ? 'nav-item nav-current' : 'nav-item'}
                    aria-current={item.id === page ? 'page' : undefined}
                    onPointerEnter={() => preloadPage(me, item.id)}
                    onFocus={() => preloadPage(me, item.id)}
                    onClick={(e) => {
                      if (!isPlainClick(e)) return
                      e.preventDefault()
                      go(item.id)
                    }}
                  >
                    <NavIcon id={item.id} />
                    <span>{item.label}</span>
                  </a>
                ))}
              </div>
            ))}
          </RegionBoundary>
          <a className="powered-by" href={PRODUCT.site} target="_blank" rel="noreferrer">
            {logo ? `Powered by ${PRODUCT.name}` : PRODUCT.tagline}
          </a>
        </nav>
        <main className="main">
          <header className="topbar">
            <div className="topbar-title">
              <button
                type="button"
                className="menu-button"
                aria-label="Menu"
                aria-expanded={menuOpen}
                aria-controls="main-nav"
                onClick={() => setMenuOpen((open) => !open)}
              >
                <span aria-hidden="true">☰</span>
              </button>
              <h1 className="page-title" ref={heading} tabIndex={-1}>
                {title}
              </h1>
            </div>
            <div className="topbar-actions">
              {me.kind === 'tenant' && me.membershipId && (
                <RegionBoundary region="bell" page={page} fallback={null}>
                  <Bell pages={items.map((item) => item.id)} onNavigate={go} />
                </RegionBoundary>
              )}
              <span className="who">
                <span className="who-avatar" aria-hidden="true">
                  {initials(null, me.email)}
                </span>
                <span className="who-email">{me.email}</span>
              </span>
              {me.kind !== 'support' && me.organizations.length + (me.console ? 1 : 0) > 1 && (
                <button type="button" className="quiet plain" onClick={onSwitch}>
                  Switch
                </button>
              )}
              {me.kind !== 'support' && (
                <button type="button" className="quiet plain" onClick={() => setChangingPassword(true)}>
                  Security
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
          <StepUpPrompt />
          {changingPassword && (
            <Suspense fallback={null}>
              <Security onClose={() => setChangingPassword(false)} />
            </Suspense>
          )}
        </main>
      </div>
    </div>
  )
}

/** The signed-in app: the address decides which page shows. Remounted when the person moves to another place. */
function Workspace({ me, onSwitch, onSignOut, onEndSupport, onEnteredSupport, onRefresh }) {
  const nav = navFor(me)
  const allowed = nav.flatMap((section) => section.items.map((item) => item.id))
  const landing = firstPage(nav)
  const [page, navigate] = usePage(allowed, landing)
  useEffect(() => {
    if (landing) rememberLanding(me, landing)
  }, [me, landing])
  return (
    <Shell me={me} nav={nav} page={page} onNavigate={navigate} onSwitch={onSwitch} onSignOut={onSignOut} onEndSupport={onEndSupport}>
      <PageView me={me} page={page} onNavigate={navigate} onEnteredSupport={onEnteredSupport} onRefresh={onRefresh} />
    </Shell>
  )
}

export default function App() {
  const [me, setMe] = useState(null)
  const [loading, setLoading] = useState(true)
  const [inviteToken, setInviteToken] = useState(inviteTokenFromUrl)
  // A reset token is taken out of the address at once: it should not sit in the history while the page is open.
  const [resetToken, setResetToken] = useState(() => {
    const token = resetTokenFromUrl()
    if (token) clearInviteFromUrl()
    return token
  })
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

  // Moving from one place to another (another Organization, into or out of a support session)
  // starts on the new place's first page; only a fresh visit or sign-in keeps the page in the address.
  function entered(next) {
    if (me && me.kind !== 'identity') resetLocation()
    setChoosing(false)
    setMe(next)
  }

  async function handleSignOut() {
    await logout()
    resetLocation()
    setChoosing(false)
    setMe(null)
  }

  async function handleEndSupport() {
    await endSupportSession()
    resetLocation()
    await refresh()
  }

  function closeInvitation() {
    clearInviteFromUrl()
    setInviteToken(null)
  }

  if (loading) {
    return (
      <main className="centered" aria-busy="true">
        <p className="muted">Loading…</p>
      </main>
    )
  }

  if (resetToken) {
    return (
      <main className="centered">
        <ResetPassword
          token={resetToken}
          onDone={() => {
            setResetToken(null)
            setMe(null)   // a reset ends every session, this one too
          }}
          onCancel={() => setResetToken(null)}
        />
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
      onRefresh={refresh}
    />
  )
}
