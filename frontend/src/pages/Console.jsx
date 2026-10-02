import { useState } from 'react'
import { invitationLink, platform, startSupportSession } from '../api.js'
import { formatDate, useAction, useLoad } from '../hooks.js'
import { Badge, CopyLink, Empty, ErrorNote, StatusBadge, Tabs } from '../ui.jsx'

function slugFrom(name) {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
}

function OrganizationsTab({ me }) {
  const orgs = useLoad(platform.organizations)
  const [form, setForm] = useState({ name: '', slug: '', plan: 'essential', adminEmail: '' })
  const [slugEdited, setSlugEdited] = useState(false)
  const [issued, setIssued] = useState(null)
  const { busy, error, run } = useAction()
  const isSuperAdmin = me.platformRole === 'super_admin'

  function setName(name) {
    setForm((f) => ({ ...f, name, slug: slugEdited ? f.slug : slugFrom(name) }))
  }

  async function create(event) {
    event.preventDefault()
    const created = await run(() => platform.createOrganization(form))
    if (created) {
      setIssued({ email: created.invitationEmail, link: invitationLink(created.invitationToken) })
      setForm({ name: '', slug: '', plan: 'essential', adminEmail: '' })
      setSlugEdited(false)
      orgs.reload()
    }
  }

  async function change(o, action) {
    if (action === 'close' && !window.confirm(`Close ${o.name}? This is final: nobody will be able to enter it again.`)) return
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
          <div className="grid">
            <div>
              <label htmlFor="org-name">Name</label>
              <input id="org-name" value={form.name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div>
              <label htmlFor="org-slug">Slug</label>
              <input
                id="org-slug"
                value={form.slug}
                onChange={(e) => {
                  setSlugEdited(true)
                  setForm((f) => ({ ...f, slug: e.target.value }))
                }}
              />
            </div>
            <div>
              <label htmlFor="org-plan">Plan</label>
              <select id="org-plan" value={form.plan} onChange={(e) => setForm((f) => ({ ...f, plan: e.target.value }))}>
                <option value="essential">Essential</option>
                <option value="growth">Growth</option>
                <option value="professional">Professional</option>
                <option value="enterprise">Enterprise</option>
              </select>
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
                <th>Plan</th>
                <th>Status</th>
                <th>Active users</th>
                <th>Pending invitations</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {orgs.data.map((o) => (
                <tr key={o.id}>
                  <td>
                    {o.name}
                    <div className="hint">{o.slug}</div>
                  </td>
                  <td>{o.planCode}</td>
                  <td>
                    <StatusBadge status={o.status} />
                    {o.status === 'provisioning' && <div className="hint">Awaiting Org Admin</div>}
                  </td>
                  <td>{o.activeMembers}</td>
                  <td>{o.pendingInvitations}</td>
                  <td className="actions">
                    {isSuperAdmin && o.status === 'provisioning' && (
                      <button type="button" className="quiet" disabled={busy} onClick={() => reinvite(o)}>
                        New invitation
                      </button>
                    )}
                    {isSuperAdmin && o.status === 'active' && (
                      <button type="button" className="quiet" disabled={busy} onClick={() => change(o, 'suspend')}>
                        Suspend
                      </button>
                    )}
                    {isSuperAdmin && o.status === 'suspended' && (
                      <button type="button" className="quiet" disabled={busy} onClick={() => change(o, 'reactivate')}>
                        Reactivate
                      </button>
                    )}
                    {isSuperAdmin && o.status !== 'closed' && (
                      <button type="button" className="quiet danger" disabled={busy} onClick={() => change(o, 'close')}>
                        Close
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

export default function Console({ me, onEnteredSupport }) {
  const isSuperAdmin = me.platformRole === 'super_admin'
  const canSupport = isSuperAdmin || me.platformRole === 'support'
  const tabs = [
    { id: 'organizations', label: 'Organizations' },
    isSuperAdmin && { id: 'users', label: 'Platform users' },
    canSupport && { id: 'support', label: 'Support access' },
  ].filter(Boolean)
  const [tab, setTab] = useState('organizations')

  return (
    <>
      <Tabs tabs={tabs} current={tab} onChange={setTab} />
      {tab === 'organizations' && <OrganizationsTab me={me} />}
      {tab === 'users' && <PlatformUsersTab me={me} />}
      {tab === 'support' && <SupportTab me={me} onEnteredSupport={onEnteredSupport} />}
    </>
  )
}
