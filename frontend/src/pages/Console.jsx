import { Fragment, useState } from 'react'
import { invitationLink, platform, startSupportSession } from '../api.js'
import { formatDate, formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, CopyLink, Empty, ErrorNote, StatusBadge } from '../ui.jsx'
import { useConfirm } from '../feedbackContext.js'


/** The add-ons an Organization can buy. Core HR is always included. */
const MODULES = [
  { id: 'attendance_leave', label: 'Attendance & Leave', about: 'punching, devices, the daily board, work from home, balances, leave requests and the leave year' },
  { id: 'payroll', label: 'Payroll', about: 'salaries, monthly runs, statutory deductions and payslips' },
]
const ALL_MODULES = MODULES.map((m) => m.id)

const BOTH = 'attendance_leave'

/**
 * Attendance and Leave are sold as one thing; each still works alone underneath. The editor always shows the pair as one
 * row. It reads as on only when both are on; if just one is on (an older arrangement) it reads as off, so saving turns on both.
 */
function paired(modules) {
  const att = modules.find((m) => m.module === 'attendance')
  const lv = modules.find((m) => m.module === 'leave')
  if (!att || !lv) return modules
  const both = att.status === 'on' && lv.status === 'on'
  const merged = {
    module: BOTH,
    name: 'Attendance & Leave',
    status: both ? 'on' : 'off',
    trialEndsOn: att.trialEndsOn || lv.trialEndsOn || null,
    active: att.active && lv.active,
    onlyOne: !both && (att.status === 'on' || lv.status === 'on') ? (att.status === 'on' ? 'Attendance' : 'Leave') : null,
  }
  return [merged, ...modules.filter((m) => m.module !== 'attendance' && m.module !== 'leave')]
}

/** What the list shows: the pair as one badge when both are working, otherwise each module as it is. */
function shown(modules) {
  const att = modules.find((m) => m.module === 'attendance')
  const lv = modules.find((m) => m.module === 'leave')
  return att?.active && lv?.active ? paired(modules) : modules
}
/** The employee licences an Organization has bought; empty means no limit. Cannot go below the employees already in use. */
function LicenceEditor({ org, onSaved }) {
  const { busy, error, run } = useAction()
  const [value, setValue] = useState(org.licensedEmployees == null ? '' : String(org.licensedEmployees))

  async function save(event) {
    event.preventDefault()
    const trimmed = value.trim()
    const done = await run(() => platform.setLicences(org.id, trimmed === '' ? null : Number(trimmed)))
    if (done) onSaved()
  }

  return (
    <form className="licence-editor" onSubmit={save} noValidate>
      <div className="module-row">
        <div>
          <strong>Employee licences</strong>
          <div className="hint">
            {org.employeesUsed} employee{org.employeesUsed === 1 ? '' : 's'} joining or working now. Past the limit, adding or importing
            employees is refused. Leave empty for no limit.
          </div>
        </div>
        <div>
          <label htmlFor={`licences-${org.id}`}>Licences</label>
          <input id={`licences-${org.id}`} type="number" min="1" step="1" inputMode="numeric" value={value} onChange={(e) => setValue(e.target.value)} />
        </div>
        <div className="form-actions">
          <button type="submit" className="outline" disabled={busy} aria-label="Save licences">
            Save
          </button>
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </form>
  )
}

/** Switches an Organization's modules on (optionally for a trial) or off. Off hides; it deletes nothing. */
function ModuleEditor({ org, onSaved }) {
  const { busy, error, run } = useAction()
  const [trials, setTrials] = useState(() => Object.fromEntries(paired(org.modules).map((m) => [m.module, m.trialEndsOn || ''])))

  async function save(module, on) {
    const done = await run(() => platform.setModule(org.id, { module, on, trialEndsOn: on ? trials[module] || null : null }))
    if (done) onSaved()
  }

  return (
    <div className="module-editor">
      {paired(org.modules).map((m) => {
        const about = MODULES.find((x) => x.id === m.module)
        return (
          <div key={m.module} className="module-row">
            <div>
              <strong>{m.name}</strong> {m.active ? <Badge tone="good">on</Badge> : <Badge>off</Badge>}
              {m.status === 'on' && !m.active && <Badge tone="warn">trial ended</Badge>}
              {m.onlyOne && <Badge tone="warn">only {m.onlyOne} is on</Badge>}
              <div className="hint">{about?.about}</div>
            </div>
            <div>
              <label htmlFor={`trial-${org.id}-${m.module}`}>Trial until</label>
              <input
                id={`trial-${org.id}-${m.module}`}
                type="date"
                value={trials[m.module]}
                onChange={(e) => setTrials({ ...trials, [m.module]: e.target.value })}
              />
            </div>
            <div className="form-actions">
              <button
                type="button"
                className="outline"
                disabled={busy}
                aria-label={m.active ? `Save ${m.name}` : `Switch on ${m.name}`}
                onClick={() => save(m.module, true)}
              >
                {m.active ? 'Save' : 'Switch on'}
              </button>
              {m.status === 'on' && (
                <button type="button" className="quiet danger" disabled={busy} onClick={() => save(m.module, false)}>
                  Switch off
                </button>
              )}
            </div>
          </div>
        )
      })}
      <p className="hint">
        Switching a module off hides its screens and refuses its requests at once; its records stay and come back when it is switched on.
        A trial date is optional: without one a module stays on until switched off.
      </p>
      <ErrorNote>{error}</ErrorNote>
    </div>
  )
}

/** Closes the row-actions menu once an action is chosen. */
const shut = (e) => {
  const menu = e.currentTarget.closest('details')
  if (menu) menu.open = false
}

function slugFrom(name) {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
}

function OrganizationsTab({ me }) {
  const orgs = useLoad(platform.organizations)
  const [form, setForm] = useState({ name: '', slug: '', adminEmail: '', modules: ALL_MODULES, licensedEmployees: '' })
  const [editing, setEditing] = useState(null)
  const [slugEdited, setSlugEdited] = useState(false)
  const [issued, setIssued] = useState(null)
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const isSuperAdmin = me.platformRole === 'super_admin'
  const canChangeModules = isSuperAdmin || me.platformRole === 'billing'

  function setName(name) {
    setForm((f) => ({ ...f, name, slug: slugEdited ? f.slug : slugFrom(name) }))
  }

  async function create(event) {
    event.preventDefault()
    const created = await run(() =>
      platform.createOrganization({ ...form, licensedEmployees: form.licensedEmployees.trim() === '' ? null : Number(form.licensedEmployees) }),
    )
    if (created) {
      setIssued({ email: created.invitationEmail, link: invitationLink(created.invitationToken) })
      setForm({ name: '', slug: '', adminEmail: '', modules: ALL_MODULES, licensedEmployees: '' })
      setSlugEdited(false)
      orgs.reload()
    }
  }

  async function change(o, action) {
    if (
      action === 'close' &&
      !(await confirm({
        title: `Close ${o.name}?`,
        body: 'This is final: nobody will be able to enter it again.',
        confirmLabel: 'Close Organization',
        danger: true,
      }))
    )
      return
    const done = await run(async () => {
      await platform.changeOrganization(o.id, action)
      return true
    })
    if (done) orgs.reload()
  }

  async function reinvite(o) {
    const email = window.prompt(`Email of the first Org Admin for ${o.name}`)
    if (!email) return
    const sent = await run(() => platform.inviteAdmin(o.id, email))
    if (sent) {
      setIssued({ email: sent.invitationEmail, link: invitationLink(sent.invitationToken) })
      orgs.reload()
    }
  }

  return (
    <>
      {isSuperAdmin && (
        <form className="panel" onSubmit={create} noValidate>
          <h2>Create an Organization</h2>
          <div className="grid org-grid">
            <div>
              <label htmlFor="org-name">Name</label>
              <input id="org-name" value={form.name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div>
              <label htmlFor="org-slug">Short name (slug)</label>
              <input
                id="org-slug"
                aria-describedby="org-slug-hint"
                value={form.slug}
                onChange={(e) => {
                  setSlugEdited(true)
                  setForm((f) => ({ ...f, slug: e.target.value }))
                }}
              />
              <p id="org-slug-hint" className="hint">
                3 to 40 lowercase letters, numbers and dashes, such as globex-ltd.
              </p>
            </div>
            <div>
              <label htmlFor="org-licences">Employee licences</label>
              <input
                id="org-licences"
                type="number"
                min="1"
                step="1"
                inputMode="numeric"
                aria-describedby="org-licences-hint"
                value={form.licensedEmployees}
                onChange={(e) => setForm((f) => ({ ...f, licensedEmployees: e.target.value }))}
              />
              <p id="org-licences-hint" className="hint">
                How many employees they bought. Empty: no limit.
              </p>
            </div>
            <div>
              <label htmlFor="org-admin">First Org Admin's email</label>
              <input
                id="org-admin"
                type="email"
                value={form.adminEmail}
                onChange={(e) => setForm((f) => ({ ...f, adminEmail: e.target.value }))}
              />
            </div>
          </div>
          <fieldset className="days">
            <legend>Modules (Core HR is always included)</legend>
            {MODULES.map((m) => (
              <label key={m.id} className="check">
                <input
                  type="checkbox"
                  id={`org-mod-${m.id}`}
                  checked={form.modules.includes(m.id)}
                  onChange={() =>
                    setForm((f) => ({
                      ...f,
                      modules: f.modules.includes(m.id) ? f.modules.filter((x) => x !== m.id) : [...f.modules, m.id],
                    }))
                  }
                />
                {m.label}
              </label>
            ))}
          </fieldset>
          <button type="submit" className="secondary" disabled={busy}>
            {busy ? 'Creating…' : 'Create and invite'}
          </button>
        </form>
      )}

      <ErrorNote>{error}</ErrorNote>
      {issued && <CopyLink label={`Invitation for ${issued.email}.`} link={issued.link} onDismiss={() => setIssued(null)} />}

      <h2>Organizations</h2>
      <p className="hint">The console shows counts only. It has no access to the users or data inside an Organization.</p>
      {orgs.loading ? (
        <p className="muted">Loading…</p>
      ) : orgs.error ? (
        <ErrorNote>{orgs.error}</ErrorNote>
      ) : orgs.data.length === 0 ? (
        <Empty>No Organizations yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Modules</th>
                <th>Licences</th>
                <th>Status</th>
                <th className="num">Users</th>
                <th className="num">Invitations</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {orgs.data.map((o) => (
                <Fragment key={o.id}>
                <tr className={editing === o.id ? 'row-current' : undefined}>
                  <td className="org-cell">
                    {o.name}
                    <div className="hint">{o.slug}</div>
                  </td>
                  <td>
                    <div className="module-badges">
                      <Badge>Core HR</Badge>
                      {shown(o.modules)
                        .filter((m) => m.active)
                        .map((m) => (
                          <Badge key={m.module} tone="good">
                            {m.name}
                          </Badge>
                        ))}
                    </div>
                    {shown(o.modules)
                      .filter((m) => m.active && m.trialEndsOn)
                      .map((m) => (
                        <div key={m.module} className="hint">
                          {m.name} trial until {formatDay(m.trialEndsOn)}
                        </div>
                      ))}
                    {shown(o.modules)
                      .filter((m) => m.status === 'on' && !m.active)
                      .map((m) => (
                        <div key={m.module}>
                          <Badge tone="warn">{m.name} trial ended</Badge>
                        </div>
                      ))}
                  </td>
                  <td className="licence-cell">
                    {o.licensedEmployees == null ? (
                      <>
                        No limit
                        <div className="hint">{o.employeesUsed} in use</div>
                      </>
                    ) : (
                      <>
                        {o.employeesUsed} of {o.licensedEmployees}
                        {o.employeesUsed >= o.licensedEmployees && <div className="hint warn">Full</div>}
                      </>
                    )}
                  </td>
                  <td>
                    <StatusBadge status={o.status} />
                    {o.status === 'provisioning' && <div className="hint">Awaiting Org Admin</div>}
                  </td>
                  <td className="num">{o.activeMembers}</td>
                  <td className="num">{o.pendingInvitations}</td>
                  <td className="actions">
                    {canChangeModules && o.status !== 'closed' && (
                      <button
                        type="button"
                        className="quiet"
                        aria-expanded={editing === o.id}
                        onClick={() => setEditing(editing === o.id ? null : o.id)}
                      >
                        {editing === o.id ? 'Hide' : 'Manage'}
                      </button>
                    )}
                    {isSuperAdmin && o.status !== 'closed' && (
                      <details className="menu">
                        <summary aria-label={`More actions for ${o.name}`}>⋯</summary>
                        <div className="menu-items">
                          {o.status === 'provisioning' && (
                            <button type="button" className="quiet" disabled={busy} onClick={(e) => { shut(e); reinvite(o) }}>
                              New invitation
                            </button>
                          )}
                          {o.status === 'active' && (
                            <button type="button" className="quiet" disabled={busy} onClick={(e) => { shut(e); change(o, 'suspend') }}>
                              Suspend
                            </button>
                          )}
                          {o.status === 'suspended' && (
                            <button type="button" className="quiet" disabled={busy} onClick={(e) => { shut(e); change(o, 'reactivate') }}>
                              Reactivate
                            </button>
                          )}
                          <button type="button" className="quiet danger" disabled={busy} onClick={(e) => { shut(e); change(o, 'close') }}>
                            Close
                          </button>
                        </div>
                      </details>
                    )}
                  </td>
                </tr>
                {editing === o.id && (
                  <tr className="detail-row">
                    <td colSpan={7}>
                      <LicenceEditor org={o} onSaved={orgs.reload} />
                      <ModuleEditor org={o} onSaved={orgs.reload} />
                    </td>
                  </tr>
                )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function PlatformUsersTab({ me }) {
  const users = useLoad(platform.users)
  const [form, setForm] = useState({ email: '', role: 'support', password: '' })
  const { busy, error, run } = useAction()

  async function add(event) {
    event.preventDefault()
    const done = await run(async () => {
      await platform.addUser(form)
      return true
    })
    if (done) {
      setForm({ email: '', role: 'support', password: '' })
      users.reload()
    }
  }

  async function change(u, action) {
    const done = await run(async () => {
      await platform.changeUser(u.id, action)
      return true
    })
    if (done) users.reload()
  }

  return (
    <>
      <form className="panel" onSubmit={add} noValidate>
        <h2>Add a platform user</h2>
        <div className="grid">
          <div>
            <label htmlFor="pu-email">Email</label>
            <input id="pu-email" type="email" value={form.email} onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))} />
          </div>
          <div>
            <label htmlFor="pu-role">Role</label>
            <select id="pu-role" value={form.role} onChange={(e) => setForm((f) => ({ ...f, role: e.target.value }))}>
              <option value="super_admin">Super Admin</option>
              <option value="support">Support</option>
              <option value="billing">Billing</option>
            </select>
          </div>
          <div>
            <label htmlFor="pu-password">Initial password</label>
            <input
              id="pu-password"
              type="password"
              autoComplete="new-password"
              value={form.password}
              onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))}
            />
          </div>
        </div>
        <p className="hint">At least 12 characters. Ignored when the email already has a login.</p>
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Adding…' : 'Add platform user'}
        </button>
      </form>

      <ErrorNote>{error}</ErrorNote>

      {users.loading ? (
        <p className="muted">Loading…</p>
      ) : users.error ? (
        <ErrorNote>{users.error}</ErrorNote>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Email</th>
                <th>Role</th>
                <th>Status</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {users.data.map((u) => (
                <tr key={u.id}>
                  <td>
                    {u.email}
                    {u.email === me.email && <span className="you"> (you)</span>}
                  </td>
                  <td>{u.role.replace('_', ' ')}</td>
                  <td>
                    <StatusBadge status={u.status} />
                  </td>
                  <td className="actions">
                    {u.email !== me.email && u.status === 'active' && (
                      <button type="button" className="quiet" disabled={busy} onClick={() => change(u, 'suspend')}>
                        Suspend
                      </button>
                    )}
                    {u.status === 'suspended' && (
                      <button type="button" className="quiet" disabled={busy} onClick={() => change(u, 'reactivate')}>
                        Reactivate
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function SupportTab({ me, onEnteredSupport }) {
  const grants = useLoad(platform.grants)
  const orgs = useLoad(platform.organizations)
  const [form, setForm] = useState({ organizationId: '', mode: 'read_only', reason: '' })
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const isSuperAdmin = me.platformRole === 'super_admin'

  async function request(event) {
    event.preventDefault()
    const created = await run(() => platform.requestGrant(form))
    if (created) {
      setForm({ organizationId: '', mode: 'read_only', reason: '' })
      grants.reload()
    }
  }

  async function decide(g, action) {
    if (action === 'revoke') {
      const sure = await confirm({
        title: 'Revoke this support access?',
        body: 'Any session using it ends at once.',
        confirmLabel: 'Revoke access',
        danger: true,
      })
      if (!sure) return
    }
    const done = await run(async () => {
      await platform.decideGrant(g.id, action)
      return true
    })
    if (done) grants.reload()
  }

  async function start(g) {
    const next = await run(() => startSupportSession(g.id))
    if (next) onEnteredSupport(next)
  }

  const openOrgs = (orgs.data || []).filter((o) => o.status !== 'closed')

  return (
    <>
      <form className="panel" onSubmit={request} noValidate>
        <h2>Request support access</h2>
        <p className="hint">
          Access is to one Organization, for 60 minutes, and is recorded in that Organization's own audit log.
          Another Super Admin must approve it; if there is no other, it is approved automatically and flagged.
        </p>
        <div className="grid">
          <div>
            <label htmlFor="g-org">Organization</label>
            <select id="g-org" value={form.organizationId} onChange={(e) => setForm((f) => ({ ...f, organizationId: e.target.value }))}>
              <option value="">Choose…</option>
              {openOrgs.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="g-mode">Mode</label>
            <select id="g-mode" value={form.mode} onChange={(e) => setForm((f) => ({ ...f, mode: e.target.value }))}>
              <option value="read_only">Read only</option>
              <option value="write">Write</option>
            </select>
          </div>
        </div>
        <label htmlFor="g-reason">Reason</label>
        <input id="g-reason" value={form.reason} onChange={(e) => setForm((f) => ({ ...f, reason: e.target.value }))} />
        <button type="submit" className="secondary" disabled={busy || !form.organizationId}>
          {busy ? 'Requesting…' : 'Request access'}
        </button>
      </form>

      <ErrorNote>{error}</ErrorNote>

      <h2>Grants</h2>
      {grants.loading ? (
        <p className="muted">Loading…</p>
      ) : grants.error ? (
        <ErrorNote>{grants.error}</ErrorNote>
      ) : grants.data.length === 0 ? (
        <Empty>No support access has been requested.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Organization</th>
                <th>Requested by</th>
                <th>Mode</th>
                <th>Status</th>
                <th>Reason</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {grants.data.map((g) => (
                <tr key={g.id}>
                  <td>{g.tenantName}</td>
                  <td>
                    {g.requestedBy}
                    <div className="hint">{formatDate(g.requestedAt)}</div>
                  </td>
                  <td>{g.mode === 'write' ? 'Write' : 'Read only'}</td>
                  <td>
                    {g.status === 'approved' && !g.live ? <Badge>expired</Badge> : <StatusBadge status={g.status} />}
                    {g.autoApproved && <div className="hint">Auto-approved</div>}
                    {g.live && <div className="hint">Until {formatDate(g.expiresAt)}</div>}
                  </td>
                  <td>{g.reason}</td>
                  <td className="actions">
                    {g.live && g.mine && (
                      <button type="button" className="quiet" disabled={busy} onClick={() => start(g)}>
                        Start session
                      </button>
                    )}
                    {isSuperAdmin && g.status === 'requested' && !g.mine && (
                      <>
                        <button type="button" className="quiet" disabled={busy} onClick={() => decide(g, 'approve')}>
                          Approve
                        </button>
                        <button type="button" className="quiet danger" disabled={busy} onClick={() => decide(g, 'reject')}>
                          Reject
                        </button>
                      </>
                    )}
                    {isSuperAdmin && g.live && (
                      <button type="button" className="quiet danger" disabled={busy} onClick={() => decide(g, 'revoke')}>
                        Revoke
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

/** How mail is doing: what is waiting, what failed, and who is not mailed. Sending is set up through the environment, not here. */
function EmailTab({ me }) {
  const email = useLoad(platform.email)
  const { busy, error, run } = useAction()
  const [to, setTo] = useState('')
  const [suppressTo, setSuppressTo] = useState('')
  const isSuperAdmin = me.platformRole === 'super_admin'

  async function act(work, after) {
    const done = await run(async () => {
      await work()
      return true
    })
    if (done) {
      after?.()
      email.reload()
    }
  }

  if (email.loading) return <p className="muted">Loading…</p>
  if (email.error) return <ErrorNote>{email.error}</ErrorNote>
  const o = email.data
  return (
    <>
      <div className="panel">
        <h2>Sending</h2>
        {o.configured ? (
          <p>
            Mail goes out through <strong>{o.server}</strong>, from <strong>{o.from}</strong>.
          </p>
        ) : (
          <p>
            <Badge>not set up</Badge> No mail server is set. Messages wait in the queue. Set <code>APP_SMTP_HOST</code> and{' '}
            <code>APP_SMTP_FROM</code> on the server and restart it.
          </p>
        )}
        <p className="hint">
          Waiting {o.counts.queued + o.counts.sending} · Sent {o.counts.sent} · Failed {o.counts.failed} · Not mailed (on the list below){' '}
          {o.counts.suppressed}
        </p>
        {isSuperAdmin && (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              act(() => platform.sendTestEmail(to), () => setTo(''))
            }}
            noValidate
          >
            <label htmlFor="email-test">Send a test message to</label>
            <input id="email-test" type="email" value={to} onChange={(e) => setTo(e.target.value)} />
            <button type="submit" className="secondary" disabled={busy || !to}>
              Send test
            </button>
          </form>
        )}
      </div>

      <ErrorNote>{error}</ErrorNote>

      <h2>Failed, or waiting to try again</h2>
      {o.failures.length === 0 ? (
        <Empty>Nothing has failed.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>To</th>
                <th>Message</th>
                <th>Tries</th>
                <th>Last error</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {o.failures.map((m) => (
                <tr key={m.id}>
                  <td>{m.to}</td>
                  <td>
                    {m.subject}
                    <div className="hint">{formatDate(m.createdAt)}</div>
                  </td>
                  <td>
                    {m.attempts} {m.status === 'queued' && <div className="hint">next {formatDate(m.nextAttemptAt)}</div>}
                  </td>
                  <td>{m.lastError}</td>
                  <td className="actions">
                    {isSuperAdmin && m.status === 'failed' && (
                      <button type="button" className="quiet" disabled={busy} onClick={() => act(() => platform.retryEmail(m.id))}>
                        Try again
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h2>Not mailed</h2>
      <p className="hint">Addresses that bounced, complained, or were added by hand. Nothing is sent to them.</p>
      {isSuperAdmin && (
        <form
          onSubmit={(e) => {
            e.preventDefault()
            act(() => platform.suppressEmail(suppressTo, 'manual'), () => setSuppressTo(''))
          }}
          noValidate
        >
          <label htmlFor="email-suppress">Stop mailing</label>
          <input id="email-suppress" type="email" value={suppressTo} onChange={(e) => setSuppressTo(e.target.value)} />
          <button type="submit" className="secondary" disabled={busy || !suppressTo}>
            Add to the list
          </button>
        </form>
      )}
      {o.suppressions.length === 0 ? (
        <Empty>No addresses are blocked.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Address</th>
                <th>Why</th>
                <th>Added</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {o.suppressions.map((s) => (
                <tr key={s.email}>
                  <td>{s.email}</td>
                  <td>{s.reason}</td>
                  <td>
                    {formatDate(s.at)}
                    {s.by && <div className="hint">{s.by}</div>}
                  </td>
                  <td className="actions">
                    {isSuperAdmin && (
                      <button type="button" className="quiet" disabled={busy} onClick={() => act(() => platform.unsuppressEmail(s.email))}>
                        Mail again
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

export default function Console({ me, page, onEnteredSupport }) {
  return (
    <>
      {page === 'organizations' && <OrganizationsTab me={me} />}
      {page === 'users' && <PlatformUsersTab me={me} />}
      {page === 'support' && <SupportTab me={me} onEnteredSupport={onEnteredSupport} />}
      {page === 'email' && <EmailTab me={me} />}
    </>
  )
}
