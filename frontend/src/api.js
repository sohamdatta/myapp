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

let askForCode = null

/** The screen registers how to ask for a fresh code; a request refused with stepup.required is retried once after it. */
export function onStepUp(handler) {
  askForCode = handler
}

export const stepUp = (code) => post('/auth/step-up', { code })

async function send(method, path, body, retried = false) {
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
  if (response.status === 204) return { data: null, response }
  let data = {}
  try {
    data = await response.json()
  } catch {
    // An empty or non-JSON body: fall through with what the status tells us.
  }
  if (!response.ok) {
    if (data.code === 'stepup.required' && askForCode && !retried && (await askForCode())) {
      return send(method, path, body, true)
    }
    throw new ApiError(
      data.message || 'Something went wrong. Try again.',
      response.status,
      data.code || 'error',
    )
  }
  return { data, response }
}

async function request(method, path, body) {
  return (await send(method, path, body)).data
}

/** A list and how many matched in all (the X-Total-Count header), for lists the server may cut off. */
async function getWithTotal(path) {
  const { data, response } = await send('GET', path)
  const total = Number(response.headers.get('X-Total-Count'))
  return { rows: data, total: Number.isFinite(total) && total >= data.length ? total : data.length }
}

/**
 * Sends a file as the request body, reporting progress (0 to 1) as it goes. The server reads the
 * file's type from its bytes; the name and document type travel in the query string.
 */
export function uploadFile(path, file, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `/api${path}`)
    const token = getToken()
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    if (onProgress) xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total)
    xhr.onload = () => {
      let data = {}
      try {
        data = JSON.parse(xhr.responseText || '{}')
      } catch {
        // not JSON: fall through with the status
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data)
      else reject(new ApiError(data.message || (xhr.status === 413 ? 'The file is larger than 10 MB.' : 'The upload failed. Try again.'), xhr.status, data.code || 'error'))
    }
    xhr.onerror = () => reject(new ApiError('Cannot reach the server. Check your connection and try again.', 0, 'network'))
    xhr.send(file)
  })
}

/** Fetches a file the server sends as an attachment, as a Blob to show or save. */
export async function fetchFile(path, retried = false) {
  const headers = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`/api${path}`, { headers })
  if (!response.ok) {
    let data = {}
    try {
      data = await response.json()
    } catch {
      // not JSON
    }
    if (data.code === 'stepup.required' && askForCode && !retried && (await askForCode())) {
      return fetchFile(path, true)
    }
    throw new ApiError(data.message || 'The file could not be opened.', response.status, data.code || 'error')
  }
  return response.blob()
}

/** Saves a document the server holds, such as a letter, to the person's device. */
export async function downloadDocument(documentId, name) {
  const blob = await fetchFile(`/org/people/documents/${documentId}/content`)
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
  return true
}

const get = (path) => request('GET', path)
const post = (path, body = {}) => request('POST', path, body)
const put = (path, body) => request('PUT', path, body)
const del = (path) => request('DELETE', path)

// ── sign-in and sessions ────────────────────────────────────────────

export async function login(email, password) {
  const me = await post('/auth/login', { email, password })
  if (me.mfaRequired) return me   // { mfaRequired, challenge }: the code comes next
  setToken(me.token)
  return enterDefault(me)
}

/** Answers the challenge a right password earned, with the app's code or a recovery code. */
export async function completeMfa(challenge, code) {
  const me = await post('/auth/mfa', { challenge, code })
  setToken(me.token)
  return enterDefault(me)
}

export const mfa = {
  status: () => get('/auth/mfa'),
  start: () => post('/auth/mfa/start'),
  confirm: (password, code) => post('/auth/mfa/confirm', { password, code }),
  recovery: (code) => post('/auth/mfa/recovery', { code }),
  disable: (password, code) => post('/auth/mfa/disable', { password, code }),
}

/** Asks for a reset link. The answer is the same whether or not the address has an account. */
export const forgotPassword = (email) => post('/auth/forgot', { email })

export const resetPassword = (token, password) => post('/auth/reset', { token, password })

export const changeEmail = (newEmail, password) => post('/auth/email-change', { newEmail, password })
export const confirmEmailChange = (token) => post('/auth/email-change/confirm', { token })
export const undoEmailChange = (token) => post('/auth/email-change/undo', { token })
export const changePassword = (currentPassword, newPassword) => post('/auth/password', { currentPassword, newPassword })

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
  } catch (err) {
    // Fall through to the screen that lists where this person can go; a two-step requirement is explained there.
    if (err.code && err.code.startsWith('mfa.')) return { ...me, entryRefused: { code: err.code, message: err.message } }
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
  homeWorking: () => get('/org/attendance/me/wfh'),

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
  settings: () => get('/org/attendance/settings'),
  saveSettings: (body) => put('/org/attendance/settings', body),
  setHomeWorking: (id, allowed) => put(`/org/attendance/users/${id}/wfh`, { allowed }),

  setMode: (id, body) => post(`/org/attendance/locations/${id}/mode`, body),
  mark: (id, body) => post(`/org/attendance/users/${id}/marks`, body),
  devices: () => get('/org/attendance/devices'),
  saveDevice: (id, body) => (id ? put(`/org/attendance/devices/${id}`, body) : post('/org/attendance/devices', body)),
  deviceLog: (id) => get(`/org/attendance/devices/${id}/log`),

  importHealth: () => get('/org/attendance/imports'),
  previewImport: (body) => post('/org/attendance/imports/preview', body),
  importFile: (body) => post('/org/attendance/imports', body),
}

// ── leave, inside an Organization ───────────────────────────────────

export const leave = {
  types: () => get('/org/leave/types'),
  myBalances: () => get('/org/leave/me/balances'),
  myLedger: (type) => get(`/org/leave/me/ledger${query({ type })}`),
  myRequests: () => get('/org/leave/me/requests'),
  preview: (body) => post('/org/leave/me/requests/preview', body),
  apply: (body) => post('/org/leave/me/requests', body),
  cancel: (id) => post(`/org/leave/me/requests/${id}/cancel`),

  requests: (status) => get(`/org/leave/requests${query({ status })}`),
  decide: (id, action, note) => post(`/org/leave/requests/${id}/${action}`, { note }),
  calendar: (from, to) => get(`/org/leave/calendar${query({ from, to })}`),
  balances: () => get('/org/leave/balances'),
  userLedger: (id, type) => get(`/org/leave/users/${id}/ledger${query({ type })}`),

  applyFor: (id, body) => post(`/org/leave/users/${id}/requests`, body),
  adjust: (id, body) => post(`/org/leave/users/${id}/adjustments`, body),
  compOffCandidates: () => get('/org/leave/comp-off/candidates'),
  grantCompOff: (id, body) => post(`/org/leave/users/${id}/comp-off`, body),
  saveType: (id, body) => (id ? put(`/org/leave/types/${id}`, body) : post('/org/leave/types', body)),
  yearEndPreview: (year) => get(`/org/leave/year-end/preview${query({ year })}`),
  yearEndRun: (year) => post('/org/leave/year-end', { year }),
  saveYear: (startMonth) => put('/org/leave/year', { startMonth }),
}

// ── people: employee records and departments ────────────────────────

export const people = {
  employees: (params) => get(`/org/people/employees${query(params || {})}`),
  employeesWithTotal: (params) => getWithTotal(`/org/people/employees${query(params || {})}`),
  employee: (id) => get(`/org/people/employees/${id}`),
  orgChart: () => get('/org/people/org-chart'),
  addEmployee: (body) => post('/org/people/employees', body),
  changeJob: (id, body) => post(`/org/people/employees/${id}/job`, body),
  exit: (id, body) => post(`/org/people/employees/${id}/exit`, body),
  link: (id, membershipId) => post(`/org/people/employees/${id}/link`, { membershipId }),
  reveal: (id, field, reason) => post(`/org/people/employees/${id}/reveal`, { field, reason }),
  setIdentifier: (id, body) => put(`/org/people/employees/${id}/identifiers`, body),
  setBank: (id, body) => put(`/org/people/employees/${id}/bank`, body),
  unlinkedUsers: () => get('/org/people/unlinked-users'),
  departments: () => get('/org/people/departments'),
  saveDepartment: (id, body) => (id ? put(`/org/people/departments/${id}`, body) : post('/org/people/departments', body)),
  states: () => get('/org/people/states'),
}

// ── onboarding: HR's joiners, and the joiner's own onboarding ───────

export const joiners = {
  list: (status) => get(`/org/people/joiners${query({ status })}`),
  add: (body) => post('/org/people/joiners', body),
  get: (id) => get(`/org/people/joiners/${id}`),
  section: (id, section) => get(`/org/people/joiners/${id}/sections/${section}`),
  saveSection: (id, section, values) => put(`/org/people/joiners/${id}/sections/${section}`, values),
  submit: (id, section) => post(`/org/people/joiners/${id}/sections/${section}/submit`),
  verify: (id, section, note) => post(`/org/people/joiners/${id}/sections/${section}/verify`, { note }),
  returnSection: (id, section, note) => post(`/org/people/joiners/${id}/sections/${section}/return`, { note }),
  invite: (id) => post(`/org/people/joiners/${id}/invite`),
  activate: (id) => post(`/org/people/joiners/${id}/activate`),
  withdraw: (id, reason) => post(`/org/people/joiners/${id}/withdraw`, { reason }),
  history: (id) => get(`/org/people/joiners/${id}/history`),
  documents: (id) => get(`/org/people/joiners/${id}/documents`),
  uploadDocument: (id, typeId, file, onProgress) =>
    uploadFile(`/org/people/joiners/${id}/documents${query({ type: typeId, name: file.name })}`, file, onProgress),
  verifyDocument: (id, documentId) => post(`/org/people/joiners/${id}/documents/${documentId}/verify`),
  rejectDocument: (id, documentId, note) => post(`/org/people/joiners/${id}/documents/${documentId}/reject`, { note }),
  documentFile: (documentId) => fetchFile(`/org/people/documents/${documentId}/content`),
  tasks: (id) => get(`/org/people/joiners/${id}/tasks`),
  letters: (id) => get(`/org/people/joiners/${id}/letters`),
  previewLetter: (id, templateId) => get(`/org/people/joiners/${id}/letters/preview${query({ template: templateId })}`),
  issueLetter: (id, templateId, expiresInDays) => post(`/org/people/joiners/${id}/letters`, { templateId, expiresInDays }),
  uploadLetter: (id, file, { title, kind, response }) =>
    uploadFile(`/org/people/joiners/${id}/letters/upload${query({ title, kind, response })}`, file),
  policies: (id) => get(`/org/people/joiners/${id}/policies`),
  addTask: (id, body) => post(`/org/people/joiners/${id}/tasks`, body),
}

// ── getting an Organization started ─────────────────────────────────

export const gettingStarted = {
  status: () => get('/org/setup'),
  apply: (choice) => post('/org/setup/starter', choice),
}

// ── onboarding setup and the employee import ────────────────────────

const S = '/org/people/setup'
export const setup = {
  changes: () => get(`${S}/changes`),
  settings: () => get(`${S}/settings`),
  saveSettings: (body) => put(`${S}/settings`, body),
  publishNotice: (body) => post(`${S}/notice`, { body }),
  flows: () => get(`${S}/flows`),
  saveFlow: (id, body) => (id ? put(`${S}/flows/${id}`, body) : post(`${S}/flows`, body)),
  orderFlows: (ids) => put(`${S}/flows/order`, { ids }),
  fields: (flowId) => get(flowId ? `${S}/flows/${flowId}/fields` : `${S}/fields`),
  saveFields: (flowId, fields) => put(flowId ? `${S}/flows/${flowId}/fields` : `${S}/fields`, { fields }),
  customFields: () => get(`${S}/custom-fields`),
  saveCustomField: (id, body) => (id ? put(`${S}/custom-fields/${id}`, body) : post(`${S}/custom-fields`, body)),
  preview: (params) => get(`${S}/preview${query(params || {})}`),
  documentTypes: () => get(`${S}/document-types`),
  saveDocumentType: (id, body) => (id ? put(`${S}/document-types/${id}`, body) : post(`${S}/document-types`, body)),
  checklists: () => get(`${S}/checklists`),
  saveChecklist: (id, body) => (id ? put(`${S}/checklists/${id}`, body) : post(`${S}/checklists`, body)),
  letters: () => get(`${S}/letters`),
  saveLetter: (id, body) => (id ? put(`${S}/letters/${id}`, body) : post(`${S}/letters`, body)),
  policies: () => get(`${S}/policies`),
  savePolicy: (id, body) => (id ? put(`${S}/policies/${id}`, body) : post(`${S}/policies`, body)),
  messages: () => get(`${S}/messages`),
  saveMessage: (id, body) => put(`${S}/messages/${id}`, body),
  series: () => get(`${S}/number-series`),
  saveSeries: (id, body) => (id ? put(`${S}/number-series/${id}`, body) : post(`${S}/number-series`, body)),
  seriesExample: (id, body) => post(`${S}/number-series/${id}/example`, body || {}),
}

export const importing = {
  read: (content) => post('/org/people/import/read', { content }),
  preview: (content, mapping) => post('/org/people/import/preview', { content, mapping }),
  run: (content, mapping) => post('/org/people/import/run', { content, mapping }),
  uninvited: () => get('/org/people/import/uninvited'),
  invite: (employmentIds) => post('/org/people/import/invite', { employmentIds }),
}

export const tasks = {
  mine: () => get('/org/people/tasks/mine'),
  done: (id) => post(`/org/people/tasks/${id}/done`),
  skip: (id, reason) => post(`/org/people/tasks/${id}/skip`, { reason }),
  reassign: (id, membershipId) => post(`/org/people/tasks/${id}/reassign`, { membershipId }),
  approve: (id) => post(`/org/people/tasks/${id}/approve`),
  attach: (id, file, onProgress) => uploadFile(`/org/people/tasks/${id}/attachment${query({ name: file.name })}`, file, onProgress),
}

export const onboarding = {
  mine: () => get('/org/onboarding/me'),
  acceptNotice: (noticeId) => post('/org/onboarding/me/notice', { noticeId }),
  section: (section) => get(`/org/onboarding/me/sections/${section}`),
  save: (section, values) => put(`/org/onboarding/me/sections/${section}`, values),
  submit: (section) => post(`/org/onboarding/me/sections/${section}/submit`),
  documents: () => get('/org/onboarding/me/documents'),
  upload: (typeId, file, onProgress) => uploadFile(`/org/onboarding/me/documents${query({ type: typeId, name: file.name })}`, file, onProgress),
  documentFile: (documentId) => fetchFile(`/org/onboarding/me/documents/${documentId}/content`),
  letters: () => get('/org/onboarding/me/letters'),
  answerLetter: (id, accept) => post(`/org/onboarding/me/letters/${id}/${accept ? 'accept' : 'decline'}`),
  policies: () => get('/org/onboarding/me/policies'),
  acknowledge: (id, version) => post(`/org/onboarding/me/policies/${id}/acknowledge`, { version }),
}

// ── exits: the employee's own, and everyone's for HR and managers ───

export const exits = {
  mine: () => get('/org/me/exit'),
  resign: (body) => post('/org/me/exit', body),
  withdraw: (note) => post('/org/me/exit/withdraw', { note }),
  list: (status) => get(`/org/people/exits${query({ status })}`),
  get: (id) => get(`/org/people/exits/${id}`),
  start: (body) => post('/org/people/exits', body),
  recommend: (id, body) => post(`/org/people/exits/${id}/recommend`, body),
  accept: (id, body) => post(`/org/people/exits/${id}/accept`, body),
  decideWithdrawal: (id, approve, note) => post(`/org/people/exits/${id}/withdrawal`, { approve, note }),
  cancel: (id, note) => post(`/org/people/exits/${id}/cancel`, { note }),
  close: (id, body) => post(`/org/people/exits/${id}/close`, body),
  addTask: (id, body) => post(`/org/people/exits/${id}/tasks`, body),
  prepareSettlement: (id) => post(`/org/people/exits/${id}/settlement/prepare`),
  addSettlementLine: (id, body) => post(`/org/people/exits/${id}/settlement/lines`, body),
  changeSettlementLine: (id, lineId, body) => put(`/org/people/exits/${id}/settlement/lines/${lineId}`, body),
  removeSettlementLine: (id, lineId) => del(`/org/people/exits/${id}/settlement/lines/${lineId}`),
  approveSettlement: (id) => post(`/org/people/exits/${id}/settlement/approve`),
  paySettlementLine: (id, lineId, body) => post(`/org/people/exits/${id}/settlement/lines/${lineId}/paid`, body),
  legalHold: (id, body) => put(`/org/people/exits/${id}/legal-hold`, body),
  previewLetter: (id, templateId) => get(`/org/people/exits/${id}/letters/preview${query({ template: templateId })}`),
  issueLetter: (id, templateId) => post(`/org/people/exits/${id}/letters`, { templateId }),
  interview: (id) => get(`/org/people/exits/${id}/interview`),
  sendInterview: (id) => post(`/org/people/exits/${id}/interview`),
  interviewNotes: (id, notes) => put(`/org/people/exits/${id}/interview/notes`, { notes }),
  answerInterview: (answers) => post('/org/me/exit/interview', { answers }),
  declineInterview: () => post('/org/me/exit/interview/decline'),
  settings: () => get('/org/people/setup/exit'),
  saveSettings: (body) => put('/org/people/setup/exit', body),
}

export const exitTasks = {
  mine: () => get('/org/people/exit-tasks/mine'),
  done: (id, body) => post(`/org/people/exit-tasks/${id}/done`, body || {}),
  skip: (id, reason) => post(`/org/people/exit-tasks/${id}/skip`, { reason }),
  reassign: (id, membershipId) => post(`/org/people/exit-tasks/${id}/reassign`, { membershipId }),
}

// ── performance: the employee's own, and their team's or everyone's ──

export const performance = {
  mine: () => get('/org/me/performance'),
  replyToCheckin: (id, reply) => post(`/org/me/performance/probation/checkins/${id}/reply`, { reply }),

  probations: (status) => get(`/org/performance/probation${query({ status })}`),
  probation: (id) => get(`/org/performance/probation/${id}`),
  writeCheckin: (id, checkinId, body) => put(`/org/performance/probation/${id}/checkins/${checkinId}`, body),
  recommend: (id, body) => post(`/org/performance/probation/${id}/recommend`, body),
  decide: (id, body) => post(`/org/performance/probation/${id}/decide`, body),

  probationSettings: () => get('/org/performance/setup/probation'),
  saveProbationSettings: (body) => put('/org/performance/setup/probation', body),

  team: () => get('/org/performance/team'),

  goals: (employmentId, period) => get(`/org/performance/goals${query({ employmentId, period })}`),
  addGoal: (body) => post('/org/performance/goals', body),
  changeGoal: (id, body) => put(`/org/performance/goals/${id}`, body),
  removeGoal: (id) => del(`/org/performance/goals/${id}`),
  dropGoal: (id, reason) => post(`/org/performance/goals/${id}/drop`, { reason }),
  goalCheckin: (id, body) => post(`/org/performance/goals/${id}/checkins`, body),
  submitGoals: (periodStart) => post('/org/me/performance/goals/submit', { periodStart }),
  agreeGoals: (employmentId, periodStart) => post('/org/performance/goals/agree', { employmentId, periodStart }),
  returnGoals: (employmentId, periodStart, note) => post('/org/performance/goals/return', { employmentId, periodStart, note }),
  departmentGoals: (period) => get(`/org/performance/department-goals${query({ period })}`),

  oneToOnes: (withId) => get(`/org/performance/one-to-ones${query({ with: withId })}`),
  addOneToOne: (body) => post('/org/performance/one-to-ones', body),
  changeOneToOne: (id, body) => put(`/org/performance/one-to-ones/${id}`, body),
  addItem: (id, body) => post(`/org/performance/one-to-ones/${id}/items`, body),
  markItem: (id, done) => put(`/org/performance/one-to-one-items/${id}`, { done }),

  feedbackAbout: (employmentId) => get(`/org/performance/feedback${query({ employmentId })}`),
  giveFeedback: (body) => post('/org/performance/feedback', body),
  editFeedback: (id, text) => put(`/org/performance/feedback/${id}`, { text }),
  withdrawFeedback: (id) => post(`/org/performance/feedback/${id}/withdraw`),
  givenFeedback: () => get('/org/me/performance/feedback/given'),
  colleagues: () => get('/org/performance/colleagues'),

  cycles: () => get('/org/performance/cycles'),
  cycle: (id) => get(`/org/performance/cycles/${id}`),
  saveCycle: (id, body) => (id ? put(`/org/performance/cycles/${id}`, body) : post('/org/performance/cycles', body)),
  cycleAction: (id, action, body) => post(`/org/performance/cycles/${id}/${action}`, body || {}),
  reviewsToWrite: () => get('/org/performance/reviews/to-write'),
  myReviews: () => get('/org/me/performance/reviews'),
  review: (id) => get(`/org/performance/reviews/${id}`),
  saveEntry: (id, role, body) => put(`/org/performance/reviews/${id}/entries/${role}`, body),
  returnSelf: (id, reason) => post(`/org/performance/reviews/${id}/return-self`, { reason }),
  acknowledge: (id, comment) => post(`/org/performance/reviews/${id}/acknowledge`, { comment }),
  withdrawReview: (id, reason) => post(`/org/performance/reviews/${id}/withdraw`, { reason }),
  moveOn: (id, reason) => post(`/org/performance/reviews/${id}/move-on`, { reason }),
  forms: () => get('/org/performance/setup/forms'),
  saveForm: (id, body) => (id ? put(`/org/performance/setup/forms/${id}`, body) : post('/org/performance/setup/forms', body)),

  setup: () => get('/org/performance/setup'),
  saveGeneral: (body) => put('/org/performance/setup/general', body),
  saveScale: (levels) => put('/org/performance/setup/scale', { levels }),
  saveCompetency: (id, body) => (id ? put(`/org/performance/setup/competencies/${id}`, body) : post('/org/performance/setup/competencies', body)),
  saveCompetencySet: (id, body) =>
    id ? put(`/org/performance/setup/competency-sets/${id}`, body) : post('/org/performance/setup/competency-sets', body),

  requestPeers: (id, employmentIds) => post(`/org/performance/reviews/${id}/peers`, { employmentIds }),
  signOff: (id, action, note) => post(`/org/performance/reviews/${id}/sign-off`, { action, note }),
  peerReview: (id) => get(`/org/me/performance/peer-reviews/${id}`),
  savePeerReview: (id, answers, submit) => put(`/org/me/performance/peer-reviews/${id}`, { answers, submit }),
  declinePeerReview: (id, reason) => post(`/org/me/performance/peer-reviews/${id}/decline`, { reason }),
  feedbackAsked: () => get('/org/me/performance/feedback/requests'),
  askForFeedback: (body) => post('/org/me/performance/feedback/requests', body),
  declineFeedbackRequest: (id, text) => post(`/org/me/performance/feedback/requests/${id}/decline`, { text }),
  plans: (status) => get(`/org/performance/plans${status ? `?status=${status}` : ''}`),
  plan: (id) => get(`/org/performance/plans/${id}`),
  savePlan: (id, body) => (id ? put(`/org/performance/plans/${id}`, body) : post('/org/performance/plans', body)),
  startPlan: (id, startDate) => post(`/org/performance/plans/${id}/start`, { startDate }),
  decidePlan: (id, body) => post(`/org/performance/plans/${id}/decide`, body),
  planCheckin: (id, checkinId, body) => put(`/org/performance/plans/${id}/checkins/${checkinId}`, body),
  acknowledgePlan: (id, text) => post(`/org/me/performance/plan/${id}/acknowledge`, { text }),
  replyToPlanCheckin: (id, text) => post(`/org/me/performance/plan/checkins/${id}/reply`, { text }),

  calibration: (cycleId) => get(`/org/performance/cycles/${cycleId}/calibration`),
  openSession: (cycleId, body) => post(`/org/performance/cycles/${cycleId}/calibration/sessions`, body),
  session: (id) => get(`/org/performance/calibration/${id}`),
  sessionAction: (id, action, body) => post(`/org/performance/calibration/${id}/${action}`, body || {}),
  calibrate: (sessionId, reviewId, level, reason) => post(`/org/performance/calibration/${sessionId}/reviews/${reviewId}`, { level, reason }),
  merit: () => get('/org/performance/merit'),
  saveMerit: (body) => put('/org/performance/merit', body),
  outcomes: (cycleId) => get(`/org/performance/cycles/${cycleId}/outcomes`),
  outcomesAction: (cycleId, action, body) => post(`/org/performance/cycles/${cycleId}/outcomes/${action}`, body || {}),
  saveOutcome: (id, body) => put(`/org/performance/outcomes/${id}`, body),
}

export const notifications = {
  list: () => get('/org/notifications'),
  read: (ids) => post('/org/notifications/read', { ids }),
}

// ── the platform console ────────────────────────────────────────────

export const platform = {
  organizations: () => get('/platform/organizations'),
  createOrganization: (body) => post('/platform/organizations', body),
  inviteAdmin: (id, adminEmail) => post(`/platform/organizations/${id}/invite-admin`, { adminEmail }),
  changeOrganization: (id, action) => post(`/platform/organizations/${id}/${action}`),
  setModule: (id, body) => post(`/platform/organizations/${id}/modules`, body),
  users: () => get('/platform/users'),
  addUser: (body) => post('/platform/users', body),
  changeUser: (id, action) => post(`/platform/users/${id}/${action}`),
  grants: () => get('/platform/grants'),
  requestGrant: (body) => post('/platform/grants', body),
  decideGrant: (id, action) => post(`/platform/grants/${id}/${action}`),
  email: () => get('/platform/email'),
  retryEmail: (id) => post(`/platform/email/${id}/retry`),
  suppressEmail: (email, reason) => post('/platform/email/suppressions', { email, reason }),
  unsuppressEmail: (email) => del(`/platform/email/suppressions?email=${encodeURIComponent(email)}`),
  sendTestEmail: (to) => post('/platform/email/test', { to }),
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

// ── payroll ─────────────────────────────────────────────────────────

/** Saves a file the server makes on request (a register, a bank file, a payslip). */
export async function downloadFile(path, name) {
  const blob = await fetchFile(path)
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
  return true
}

const payrollCalls = {
  setup: () => get('/org/payroll/setup'),
  saveSettings: (body) => put('/org/payroll/settings', body),
  addRegistration: (body) => post('/org/payroll/registrations', body),
  removeRegistration: (id) => del(`/org/payroll/registrations/${id}`),
  saveComponent: (id, body) => (id ? put(`/org/payroll/components/${id}`, body) : post('/org/payroll/components', body)),
  retireComponent: (id) => del(`/org/payroll/components/${id}`),
  componentHistory: (id) => get(`/org/payroll/components/${id}/history`),
  checkFormula: (formula) => post('/org/payroll/formula/check', { formula }),
  saveTable: (id, body) => (id ? put(`/org/payroll/tables/${id}`, body) : post('/org/payroll/tables', body)),
  saveStructure: (id, body) => (id ? put(`/org/payroll/structures/${id}`, body) : post('/org/payroll/structures', body)),
  saveGroup: (id, body) => (id ? put(`/org/payroll/groups/${id}`, body) : post('/org/payroll/groups', body)),
  members: (id) => get(`/org/payroll/groups/${id}/members`),
  addMembers: (id, employmentIds, validFrom) => post(`/org/payroll/groups/${id}/members`, { employmentIds, validFrom }),
  runs: () => get('/org/payroll/runs'),
  open: (groupId, period) => post('/org/payroll/runs', { groupId, period }),
  run: (id) => get(`/org/payroll/runs/${id}`),
  addInput: (id, body) => post(`/org/payroll/runs/${id}/inputs`, body),
  removeInput: (id, inputId) => del(`/org/payroll/runs/${id}/inputs/${inputId}`),
  hold: (id, employmentId, reason) => post(`/org/payroll/runs/${id}/hold`, { employmentId, reason }),
  unhold: (id, employmentId) => post(`/org/payroll/runs/${id}/unhold`, { employmentId }),
  freeze: (id) => post(`/org/payroll/runs/${id}/freeze`),
  calculate: (id) => post(`/org/payroll/runs/${id}/calculate`),
  reopen: (id, reason) => post(`/org/payroll/runs/${id}/reopen`, { reason }),
  accept: (id, exceptionId, reason) => post(`/org/payroll/runs/${id}/exceptions/${exceptionId}/accept`, { reason }),
  approve: (id) => post(`/org/payroll/runs/${id}/approve`),
  sendBack: (id, reason) => post(`/org/payroll/runs/${id}/send-back`, { reason }),
  paid: (id, paidOn, reference) => post(`/org/payroll/runs/${id}/paid`, { paidOn, reference }),
  release: (id) => post(`/org/payroll/runs/${id}/release`),
  employee: (id, employmentId) => get(`/org/payroll/runs/${id}/employees/${employmentId}`),
  payslipPdf: (id, employmentId, name) => downloadFile(`/org/payroll/runs/${id}/employees/${employmentId}/payslip`, name),
  output: (id, kind, name) => downloadFile(`/org/payroll/runs/${id}/outputs/${kind}`, name),
  pay: (employmentId) => get(`/org/people/employees/${employmentId}/pay`),
  previewPay: (employmentId, body) => post(`/org/people/employees/${employmentId}/pay/preview`, body),
  revisePay: (employmentId, body) => post(`/org/people/employees/${employmentId}/pay`, body),
  addOverride: (employmentId, body) => post(`/org/people/employees/${employmentId}/pay/overrides`, body),
  removeOverride: (employmentId, id) => del(`/org/people/employees/${employmentId}/pay/overrides/${id}`),
  saveProfile: (employmentId, body) => put(`/org/people/employees/${employmentId}/pay/profile`, body),
  myPay: () => get('/org/me/pay'),
  myPayslip: (id) => get(`/org/me/pay/${id}`),
  myPayslipPdf: (id, name) => downloadFile(`/org/me/pay/${id}/pdf`, name),
}

/** Income tax declarations (Form 124) and their proofs: the employee's own, and HR's checks. */
export const tax = {
  mine: (year) => get(`/org/me/tax${query({ year })}`),
  addItem: (body) => post('/org/me/tax/items', body),
  updateItem: (id, body) => put(`/org/me/tax/items/${id}`, body),
  removeItem: (id) => del(`/org/me/tax/items/${id}`),
  submit: () => post('/org/me/tax/submit'),
  addProof: (id, file, onProgress) => uploadFile(`/org/me/tax/items/${id}/proofs${query({ name: file.name })}`, file, onProgress),
  removeProof: (id, documentId) => del(`/org/me/tax/items/${id}/proofs/${documentId}`),
  proof: (documentId) => fetchFile(`/org/tax/proofs/${documentId}`),
  overview: (year) => get(`/org/payroll/tax${query({ year })}`),
  saveWindow: (body) => put('/org/payroll/tax/window', body),
  employee: (id, year) => get(`/org/payroll/tax/employees/${id}${query({ year })}`),
  accept: (id, amount) => post(`/org/payroll/tax/items/${id}/accept`, { amount }),
  returnProof: (id, note) => post(`/org/payroll/tax/items/${id}/return`, { note }),
}

/** The payroll calls; one that answers with no content resolves to true, so callers can test for success. */
export const payroll = Object.fromEntries(
  Object.entries(payrollCalls).map(([name, call]) => [name, (...args) => Promise.resolve(call(...args)).then((r) => r ?? true)]),
)

/** The Organization's settings: values, where each comes from, changes waiting for a second person, and the change log. */
export const orgSettings = {
  list: (group) => get(`/org/settings${query({ group })}`),
  explain: (key, params) => get(`/org/settings/${key}${query(params)}`),
  changes: (key) => get(`/org/settings/changes${query({ key })}`),
  change: (key, body) => put(`/org/settings/${key}`, body),
  approve: (id, body) => post(`/org/settings/changes/${id}/approve`, body || {}),
  reject: (id, body) => post(`/org/settings/changes/${id}/reject`, body),
  withdraw: (id) => post(`/org/settings/changes/${id}/withdraw`),
  undo: (id, body) => post(`/org/settings/changes/${id}/undo`, body || {}),
}

// ── the Organization's own brand ────────────────────────────────────

export const branding = {
  get: () => get('/org/setup/branding'),
  uploadLogo: (file) => uploadFile('/org/setup/branding/logo', file),
  removeLogo: () => del('/org/setup/branding/logo'),
}
