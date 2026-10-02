const TOKEN_KEY = 'login-app-token'

export function getToken() {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

function setToken(token) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    // Storage is unavailable: the user simply signs in again after a reload.
  }
}

async function readJson(response) {
  try {
    return await response.json()
  } catch {
    return {}
  }
}

export async function login(username, password) {
  let response
  try {
    response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })
  } catch {
    throw new Error('Cannot reach the server. Check that the backend is running.')
  }
  const data = await readJson(response)
  if (response.status === 401) {
    throw new Error(data.message || 'Incorrect username or password')
  }
  if (!response.ok) {
    throw new Error('Cannot reach the server. Check that the backend is running.')
  }
  setToken(data.token)
  return data.username
}

/** Returns the signed-in username, or null when there is no valid session. */
export async function currentUser() {
  const token = getToken()
  if (!token) return null
  try {
    const response = await fetch('/api/auth/me', {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!response.ok) {
      if (response.status === 401) setToken(null)
      return null
    }
    const data = await readJson(response)
    return data.username || null
  } catch {
    return null
  }
}

export async function logout() {
  const token = getToken()
  setToken(null)
  if (!token) return
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch {
    // The local session is already cleared.
  }
}
