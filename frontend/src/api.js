const TOKEN_KEY = 'hrms-token'
const CONSOLE_TOKEN_KEY = 'hrms-console-token'
const LAST_DESTINATION_KEY = 'hrms-last-destination'

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

/** Remembers an Organization id, or 'console', so the next sign-in on this browser goes straight there. */
function rememberDestination(destination) {
  try {
    localStorage.setItem(LAST_DESTINATION_KEY, destination)
  } catch {
    // Without storage, the next sign-in simply uses the default destination.
  }
}

function lastDestination() {
  try {
    return localStorage.getItem(LAST_DESTINATION_KEY)
  } catch {
    return null
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
  return enterDefault(me)
}

/** The signed-in person, or null when there is no valid session. */
export async function currentUser() {
  if (!getToken()) return null
  try {
    const me = await get('/auth/me')
    return me.kind === 'identity' ? await enterDefault(me) : me
  } catch (err) {
    if (err.status === 401 || err.status === 403) setToken(null)
    return null
  }
}

export async function enterOrganization(organizationId) {
  const me = await post('/auth/enter', { organizationId })
  setToken(me.token)
  rememberDestination(organizationId)
  return me
}

export async function enterConsole() {
  const me = await post('/auth/enter', { console: true })
  setToken(me.token)
  rememberDestination('console')
  return me
}

/**
 * Takes a newly signed-in person straight to where they work, with no screen
 * to choose from: the place they last used on this browser if they still have
 * access to it, otherwise their Organization, otherwise the console. Someone
 * with nowhere to go, or whose entry fails, is returned unchanged.
 */
async function enterDefault(me) {
  const organizationIds = me.organizations.map((o) => o.tenantId)
  const last = lastDestination()
  try {
    if (last === 'console' && me.console) return await enterConsole()
    if (last && organizationIds.includes(last)) return await enterOrganization(last)
    if (organizationIds.length > 0) return await enterOrganization(organizationIds[0])
    if (me.console) return await enterConsole()
  } catch {
    // Fall through to the screen that lists where this person can go.
  }
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
  rememberDestination(me.organization.id)
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

// ── attendance, inside an Organization ──────────────────────────────

const query = (params) => {
  const filled = Object.entries(params).filter(([, value]) => value)
  return filled.length ? `?${new URLSearchParams(filled)}` : ''
}

export const attendance = {
  today: () => get('/org/attendance/me/today'),
  punch: (position) => post('/org/attendance/me/punch', position || {}),
  myDays: (month) => get(`/org/attendance/me/days${query({ month })}`),
  myRequests: () => get('/org/attendance/me/requests'),
  createRequest: (body) => post('/org/attendance/me/requests', body),
  cancelRequest: (id) => post(`/org/attendance/me/requests/${id}/cancel`),

  board: (date) => get(`/org/attendance/days${query({ date })}`),
  userDays: (id, month) => get(`/org/attendance/users/${id}/days${query({ month })}`),
  manualPunch: (id, body) => post(`/org/attendance/users/${id}/punches`, body),
  requests: (status) => get(`/org/attendance/requests${query({ status })}`),
  decideRequest: (id, action, note) => post(`/org/attendance/requests/${id}/${action}`, { note }),
  register: (month) => get(`/org/attendance/register${query({ month })}`),

  locations: () => get('/org/attendance/locations'),
  saveLocation: (id, body) =>
    id ? put(`/org/attendance/locations/${id}`, body) : post('/org/attendance/locations', body),
  shifts: () => get('/org/attendance/shifts'),
  saveShift: (id, body) => (id ? put(`/org/attendance/shifts/${id}`, body) : post('/org/attendance/shifts', body)),
  holidays: (year) => get(`/org/attendance/holidays${query({ year })}`),
  addHoliday: (body) => post('/org/attendance/holidays', body),
  removeHoliday: (id) => post(`/org/attendance/holidays/${id}/delete`),
  people: () => get('/org/attendance/assignments'),
  assign: (body) => post('/org/attendance/assignments', body),
  setEmployeeCode: (id, code) => put(`/org/attendance/users/${id}/employee-code`, { code }),

  importHealth: () => get('/org/attendance/imports'),
  previewImport: (body) => post('/org/attendance/imports/preview', body),
  importFile: (body) => post('/org/attendance/imports', body),
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
