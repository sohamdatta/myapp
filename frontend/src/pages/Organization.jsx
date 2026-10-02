import { useState } from 'react'
import { invitationLink, org } from '../api.js'
import { formatDate, useAction, useLoad } from '../hooks.js'
import { Badge, CopyLink, Empty, ErrorNote, StatusBadge, Tabs } from '../ui.jsx'

/** Checkboxes for roles. A role the signed-in user may not grant is shown but cannot be changed. */
function RolePicker({ roles, selected, onChange }) {
  function toggle(code) {
    onChange(selected.includes(code) ? selected.filter((c) => c !== code) : [...selected, code])
  }
  return (
    <fieldset className="role-picker">
      <legend>Roles</legend>
      {roles
        .filter((r) => r.assignable)
        .map((r) => (
          <label key={r.code} className={r.grantable ? 'check' : 'check check-disabled'}>
            <input
              type="checkbox"
              checked={selected.includes(r.code)}
              disabled={!r.grantable}
              onChange={() => toggle(r.code)}
            />
            {r.name}
          </label>
        ))}
    </fieldset>
  )
}

function UserRow({ user, me, roles, onChanged }) {
  const [editing, setEditing] = useState(false)
  const [selected, setSelected] = useState(user.roleCodes)
  const { busy, error, run } = useAction()
  const isSelf = user.id === me.membershipId
  const canAssign = me.permissions.includes('role.assign') && !isSelf && user.status !== 'ended'
  const canManage = me.permissions.includes('user.manage') && !isSelf

  async function act(action, confirmText) {
    if (confirmText && !window.confirm(confirmText)) return
    const done = await run(async () => {
      await action()
      return true
    })
    if (done) {
      setEditing(false)
      onChanged()
    }
  }

  return (
    <>
      <tr>
        <td>
          {user.email}
          {isSelf && <span className="you"> (you)</span>}
        </td>
        <td>{user.roleNames.length ? user.roleNames.join(', ') : '—'}</td>
        <td>
          <StatusBadge status={user.status} />
        </td>
        <td>{formatDate(user.lastLoginAt)}</td>
        <td className="actions">
          {canAssign && (
            <button type="button" className="quiet" onClick={() => setEditing((v) => !v)}>
              {editing ? 'Cancel' : 'Edit roles'}
            </button>
          )}
          {canManage && user.status === 'active' && (
            <button type="button" className="quiet" disabled={busy} onClick={() => act(() => org.changeStatus(user.id, 'suspend'))}>
              Suspend
            </button>
          )}
          {canManage && user.status === 'suspended' && (
            <button type="button" className="quiet" disabled={busy} onClick={() => act(() => org.changeStatus(user.id, 'reactivate'))}>
              Reactivate
            </button>
          )}
          {canManage && user.status !== 'ended' && (
            <button
              type="button"
              className="quiet danger"
              disabled={busy}
              onClick={() =>
                act(
                  () => org.changeStatus(user.id, 'end'),
                  `End ${user.email}'s membership? Their roles are removed and they can no longer enter this Organization.`,
                )
              }
            >
              End
            </button>
          )}
        </td>
      </tr>
      {(editing || error) && (
        <tr className="detail-row">
          <td colSpan={5}>
            {editing && (
              <div className="inline-form">
                <RolePicker roles={roles} selected={selected} onChange={setSelected} />
                <button type="button" className="secondary" disabled={busy} onClick={() => act(() => org.changeRoles(user.id, selected))}>
                  {busy ? 'Saving…' : 'Save roles'}
                </button>
              </div>
            )}
            <ErrorNote>{error}</ErrorNote>
          </td>
        </tr>
      )}
    </>
  )
}

function UsersTab({ me }) {
  const users = useLoad(org.users)
  const roles = useLoad(org.roles)
  const [filter, setFilter] = useState('active')

  if (users.loading || roles.loading) return <p className="muted">Loading…</p>
  if (users.error || roles.error) return <ErrorNote>{users.error || roles.error}</ErrorNote>

  const counts = { active: 0, suspended: 0, ended: 0 }
  users.data.forEach((u) => (counts[u.status] += 1))
  const shown = users.data.filter((u) => u.status === filter)

  return (
    <>
      <div className="filters">
        {[
          ['active', 'Active'],
          ['suspended', 'Suspended'],
          ['ended', 'Former'],
        ].map(([id, label]) => (
          <button key={id} type="button" className={filter === id ? 'chip chip-current' : 'chip'} onClick={() => setFilter(id)}>
            {label} <span className="count">{counts[id]}</span>
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <Empty>No {filter === 'ended' ? 'former' : filter} users.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Email</th>
                <th>Roles</th>
                <th>Status</th>
                <th>Last sign-in</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {shown.map((u) => (
                <UserRow key={u.id} user={u} me={me} roles={roles.data} onChanged={users.reload} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function InvitationsTab({ me }) {
  const invitations = useLoad(org.invitations)
  const roles = useLoad(org.roles)
  const [email, setEmail] = useState('')
  const [selected, setSelected] = useState(['employee'])
  const [issued, setIssued] = useState(null)
  const { busy, error, run } = useAction()
  const canInvite = me.permissions.includes('user.invite') && me.kind === 'tenant'

  async function invite(event) {
    event.preventDefault()
    const created = await run(() => org.invite(email, selected))
    if (created) {
      setIssued({ email: created.email, link: invitationLink(created.token) })
      setEmail('')
      invitations.reload()
    }
  }

  async function resend(id) {
    const renewed = await run(() => org.resendInvitation(id))
    if (renewed) {
      setIssued({ email: renewed.email, link: invitationLink(renewed.token) })
      invitations.reload()
    }
  }

  async function revoke(id) {
    const done = await run(async () => {
      await org.revokeInvitation(id)
      return true
    })
    if (done) invitations.reload()
  }

  if (invitations.loading || roles.loading) return <p className="muted">Loading…</p>
  if (invitations.error || roles.error) return <ErrorNote>{invitations.error || roles.error}</ErrorNote>

  return (
    <>
      {canInvite && (
        <form className="panel" onSubmit={invite} noValidate>
          <h2>Invite a user</h2>
          <label htmlFor="invite-email">Email</label>
          <input id="invite-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          <RolePicker roles={roles.data} selected={selected} onChange={setSelected} />
          <p className="hint">Roles you are not allowed to grant are greyed out.</p>
          <button type="submit" className="secondary" disabled={busy}>
            {busy ? 'Inviting…' : 'Create invitation'}
          </button>
        </form>
      )}

      <ErrorNote>{error}</ErrorNote>
      {issued && <CopyLink label={`Invitation for ${issued.email}.`} link={issued.link} onDismiss={() => setIssued(null)} />}

      <h2>Pending invitations</h2>
      {invitations.data.length === 0 ? (
        <Empty>No pending invitations.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Email</th>
                <th>Roles</th>
                <th>Expires</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {invitations.data.map((i) => (
                <tr key={i.id}>
                  <td>{i.email}</td>
                  <td>{i.roleNames.join(', ')}</td>
                  <td>{i.expired ? <Badge tone="warn">expired</Badge> : formatDate(i.expiresAt)}</td>
                  <td className="actions">
                    {canInvite && (
                      <>
                        <button type="button" className="quiet" disabled={busy} onClick={() => resend(i.id)}>
                          New link
                        </button>
                        <button type="button" className="quiet danger" disabled={busy} onClick={() => revoke(i.id)}>
                          Revoke
                        </button>
                      </>
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

function RolesTab() {
  const roles = useLoad(org.roles)
  if (roles.loading) return <p className="muted">Loading…</p>
  if (roles.error) return <ErrorNote>{roles.error}</ErrorNote>
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Role</th>
            <th>Permissions and their scope</th>
          </tr>
        </thead>
        <tbody>
          {roles.data.map((r) => (
            <tr key={r.code}>
              <td>
                {r.name}
                {!r.assignable && <div className="hint">Set by the system</div>}
              </td>
              <td className="permissions">{r.permissions.join(', ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function describe(detail) {
  try {
    const entries = Object.entries(JSON.parse(detail || '{}'))
    return entries.map(([key, value]) => `${key}: ${value}`).join(' · ')
  } catch {
    return ''
  }
}

function AuditTab() {
  const audit = useLoad(org.audit)
  if (audit.loading) return <p className="muted">Loading…</p>
  if (audit.error) return <ErrorNote>{audit.error}</ErrorNote>
  if (audit.data.length === 0) return <Empty>Nothing has been recorded yet.</Empty>
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Who</th>
            <th>Action</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          {audit.data.map((e) => (
            <tr key={e.id}>
              <td>{formatDate(e.at)}</td>
              <td>
                {e.actorLabel}
                {e.actorKind === 'support' && (
                  <>
                    {' '}
                    <Badge tone="warn">platform support</Badge>
                  </>
                )}
              </td>
              <td>
                <code>{e.action}</code>
              </td>
              <td>{describe(e.detail)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function Organization({ me }) {
  const canSeeUsers = me.permissions.includes('user.read')
  const canSeeAudit = me.permissions.includes('audit.read')
  const tabs = [
    canSeeUsers && { id: 'users', label: 'Users' },
    canSeeUsers && { id: 'invitations', label: 'Invitations' },
    canSeeUsers && { id: 'roles', label: 'Roles' },
    canSeeAudit && { id: 'audit', label: 'Audit log' },
  ].filter(Boolean)
  const [tab, setTab] = useState(tabs.length ? tabs[0].id : null)

  if (tabs.length === 0) {
    return (
      <section className="panel">
        <h2>You are signed in</h2>
        <p>
          {me.email} is a member of {me.organization.name}. User management is limited to administrators, and
          there is nothing else to show for your roles yet.
        </p>
      </section>
    )
  }

  return (
    <>
      <Tabs tabs={tabs} current={tab} onChange={setTab} />
      {tab === 'users' && <UsersTab me={me} />}
      {tab === 'invitations' && <InvitationsTab me={me} />}
      {tab === 'roles' && <RolesTab />}
      {tab === 'audit' && <AuditTab />}
    </>
  )
}
