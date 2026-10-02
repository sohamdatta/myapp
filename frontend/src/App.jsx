import { useEffect, useState } from 'react'
import { currentUser, login, logout } from './api.js'

function LoginForm({ onSignedIn }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    if (!username.trim() || !password) {
      setError('Enter your username and password')
      return
    }
    setBusy(true)
    setError('')
    try {
      onSignedIn(await login(username, password))
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card" onSubmit={handleSubmit} noValidate>
      <h1>Sign in</h1>
      <p className="muted">Use your account to continue.</p>

      <label htmlFor="username">Username</label>
      <input
        id="username"
        name="username"
        autoComplete="username"
        autoFocus
        value={username}
        onChange={(e) => setUsername(e.target.value)}
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
        <button
          type="button"
          className="link"
          onClick={() => setShowPassword((v) => !v)}
          aria-pressed={showPassword}
        >
          {showPassword ? 'Hide' : 'Show'}
        </button>
      </div>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      <button type="submit" className="primary" disabled={busy}>
        {busy ? 'Signing in…' : 'Sign in'}
      </button>
    </form>
  )
}

function Home({ username, onSignOut }) {
  return (
    <div className="card">
      <h1>Welcome, {username}</h1>
      <p className="muted">You are signed in.</p>
      <button type="button" className="primary" onClick={onSignOut}>
        Sign out
      </button>
    </div>
  )
}

export default function App() {
  const [username, setUsername] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    currentUser().then((name) => {
      if (cancelled) return
      setUsername(name)
      setLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [])

  async function handleSignOut() {
    await logout()
    setUsername(null)
  }

  return (
    <main>
      {loading ? (
        <p className="muted">Loading…</p>
      ) : username ? (
        <Home username={username} onSignOut={handleSignOut} />
      ) : (
        <LoginForm onSignedIn={setUsername} />
      )}
    </main>
  )
}
