const TOKEN_KEY = 'hrms-token'
const CONSOLE_TOKEN_KEY = 'hrms-console-token'

function read(key) {
  try {
    return sessionStorage.getItem(key)
  } catch {
    return null
  }
}

function write(key, value) {
  try {
    if (value) sessionStorage.setItem(key, value)
    else sessionStorage.removeItem(key)
  } catch {
    // Storage is unavailable: the user simply signs in again after a reload.
  }
}

export function getToken() {
  return read(TOKEN_KEY)
}

export function setToken(token) {
  write(TOKEN_KEY, token)
}

/** An error from the API, with the server's code so screens can react to it. */
export class ApiError extends Error {
  constructor(message, status, code) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function request(method, path, body) {
  const headers = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let response
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError('Cannot reach the server. Check that the backend is running.', 0, 'network')
  }
  if (response.status === 204) return null
  let data = {}
  try {
    data = await response.json()
  } catch {
    // An empty or non-JSON body: fall through with what the status tells us.
  }
  if (!response.ok) {
    throw new ApiError(
      data.message || 'Something went wrong. Try again.',
      response.status,
      data.code || 'error',
    )
  }
  return data
}

const get = (path) => request('GET', path)
const post = (path, body = {}) => request('POST', path, body)
const put = (path, body) => request('PUT', path, body)

// ── sign-in and sessions ────────────────────────────────────────────

export async function login(email, password) {
  const me = await post('/auth/login', { email, password })
  setToken(me.token)
  return me
}

/** The signed-in person, or null when there is no valid session. */
export async function currentUser() {
  if (!getToken()) return null
  try {
    return await get('/auth/me')
  } catch (err) {
    if (err.status === 401 || err.status === 403) setToken(null)
    return null
  }
}

export async function enterOrganization(organizationId) {
  const me = await post('/auth/enter', { organizationId })
  setToken(me.token)
  return me
}

export async function enterConsole() {
  const me = await post('/auth/enter', { console: true })
  setToken(me.token)
  return me
}

export async function logout() {
  try {
    await post('/auth/logout')
  } catch {
    // The local session is cleared either way.
  }
  setToken(null)
  write(CONSOLE_TOKEN_KEY, null)
}

// ── invitations ─────────────────────────────────────────────────────

export const previewInvitation = (token) =>
  get(`/invitations/preview?token=${encodeURIComponent(token)}`)

export async function acceptInvitation(token, password) {
  const me = await post('/invitations/accept', { token, password })
  setToken(me.token)
  return me
}

/** The link an invited person opens. No email is sent yet, so screens show it to copy. */
export const invitationLink = (token) =>
  `${window.location.origin}/?invite=${encodeURIComponent(token)}`

// ── inside an Organization ──────────────────────────────────────────

export const org = {
  users: () => get('/org/users'),
  roles: () => get('/org/roles'),
  invitations: () => get('/org/invitations'),
  audit: () => get('/org/audit'),
  invite: (email, roles) => post('/org/invitations', { email, roles }),
  resendInvitation: (id) => post(`/org/invitations/${id}/resend`),
  revokeInvitation: (id) => post(`/org/invitations/${id}/revoke`),
  changeRoles: (id, roles) => put(`/org/users/${id}/roles`, { roles }),
  changeStatus: (id, action) => post(`/org/users/${id}/${action}`),
}

// ── the platform console ────────────────────────────────────────────

export const platform = {
  organizations: () => get('/platform/organizations'),
  createOrganization: (body) => post('/platform/organizations', body),
  inviteAdmin: (id, adminEmail) => post(`/platform/organizations/${id}/invite-admin`, { adminEmail }),
  changeOrganization: (id, action) => post(`/platform/organizations/${id}/${action}`),
  users: () => get('/platform/users'),
  addUser: (body) => post('/platform/users', body),
  changeUser: (id, action) => post(`/platform/users/${id}/${action}`),
  grants: () => get('/platform/grants'),
  requestGrant: (body) => post('/platform/grants', body),
  decideGrant: (id, action) => post(`/platform/grants/${id}/${action}`),
}

/** Starts a support session, keeping the console session to return to. */
export async function startSupportSession(grantId) {
  const me = await post(`/platform/grants/${grantId}/start`)
  write(CONSOLE_TOKEN_KEY, getToken())
  setToken(me.token)
  return me
}

/** Ends a support session and returns to the console if that session is still valid. */
export async function endSupportSession() {
  try {
    await post('/auth/logout')
  } catch {
    // The support session may already have expired.
  }
  setToken(read(CONSOLE_TOKEN_KEY))
  write(CONSOLE_TOKEN_KEY, null)
}
