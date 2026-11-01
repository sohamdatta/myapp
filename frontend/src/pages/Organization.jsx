import { useEffect, useRef, useState } from 'react'
import { invitationLink, org } from '../api.js'
import { formatDate, formatDay, formatWhen, useAction, useLoad } from '../hooks.js'
import { Badge, CopyLink, Empty, ErrorNote, StatusBadge, Tabs } from '../ui.jsx'
import { useConfirm, useToast } from '../feedbackContext.js'
import { USERS_TEXT, WIDE_ROLES } from './usersText.js'

/** What each role is for, in plain words. The exact permissions are under User management → Roles. */
const ROLE_SUMMARIES = {
  owner: 'Everything in the Organization, including users, roles, setup and billing',
  hr_admin: 'Runs HR: people, joiners, exits, attendance and leave for everyone; invites users',
  manager: 'Sees their own team, and approves their leave, attendance and work-from-home requests',
  employee: 'Punches, applies for leave and sees their own record. Give it to anyone who works here',
  it_admin: 'Gets the IT tasks of joining and leaving checklists (laptops, accounts)',
  payroll_operator: 'Prepares payroll. Cannot approve it',
  payroll_approver: 'Approves payroll and settlements. Cannot prepare them',
  auditor: 'Reads everything, including the audit log; changes nothing',
  ex_employee: 'Given by the app after the last working day: their exit letters and settlement only',
  joiner: 'Given by the app to someone joining: their own onboarding only',
  support_read: 'Platform support, for a limited time, reading only',
  support_write: 'Platform support, for a limited time',
}

/** Scope words as people say them. */
const SCOPES = { tenant: 'everyone', team: 'their team', self: 'themselves', assigned: 'those assigned to them' }

/** Checkboxes for roles. A role the signed-in user may not grant is shown but cannot be changed. */
const PAYROLL_ROLES = ['payroll_operator', 'payroll_approver']

function RolePicker({ me, roles, selected, onChange }) {
  // Payroll's roles are offered only when the Organization has Payroll.
  const payroll = (me?.modules || []).includes('payroll')
  function toggle(code) {
    onChange(selected.includes(code) ? selected.filter((c) => c !== code) : [...selected, code])
  }
  return (
    <fieldset className="role-picker">
      <legend>Roles</legend>
      <div className="role-options">
        {roles
          .filter((r) => r.assignable && (payroll || !PAYROLL_ROLES.includes(r.code)))
          .map((r) => (
            <div key={r.code} className="role-option">
              <label className={r.grantable ? 'check' : 'check check-disabled'}>
                <input
                  type="checkbox"
                  checked={selected.includes(r.code)}
                  disabled={!r.grantable}
                  aria-describedby={`role-about-${r.code}`}
                  onChange={() => toggle(r.code)}
                />
                {r.name}
              </label>
              <p id={`role-about-${r.code}`} className="hint">
                {ROLE_SUMMARIES[r.code]}
                {!r.grantable && <span className="role-locked"> {USERS_TEXT.notGrantable}</span>}
              </p>
            </div>
          ))}
      </div>
    </fieldset>
  )
}

/** A user's roles as tags; roles that reach everyone's data stand out. */
function RoleTags({ user }) {
  if (!user.roleNames.length) return <span className="hint">{USERS_TEXT.noRoles}</span>
  return user.roleNames.map((name, i) => {
    const code = user.roleCodes[i]
    const kind = code === 'owner' ? ' tag-owner' : WIDE_ROLES.includes(code) ? ' tag-wide' : ''
    return (
      <span key={name} className={`tag${kind}`}>
        {name}
      </span>
    )
  })
}

/** What the signed-in user may do to another user: change their roles, their access, both or neither. */
function powers(me, user) {
  const isSelf = user.id === me.membershipId
  return {
    isSelf,
    canAssign: me.permissions.includes('role.assign') && !isSelf && user.status !== 'ended',
    canManage: me.permissions.includes('user.manage') && !isSelf && user.status !== 'ended',
  }
}

function UserRow({ user, me, current, onOpen }) {
  const { isSelf, canAssign, canManage } = powers(me, user)
  const t = USERS_TEXT
  return (
    <tr className={current ? 'row-current' : undefined}>
      <td>
        <div className="user-cell">
          <span className="avatar" aria-hidden="true">
            {user.email[0]}
          </span>
          <span>
            {user.email}
            {isSelf && <span className="you">{t.you}</span>}
          </span>
        </div>
      </td>
      <td>
        <RoleTags user={user} />
      </td>
      <td>
        <StatusBadge status={user.status} />
      </td>
      <td className={user.lastLoginAt ? 'when' : 'when never'}>{user.lastLoginAt ? formatWhen(user.lastLoginAt) : t.never}</td>
      <td className="actions">
        {isSelf && <span className="hint">{t.self}</span>}
        {(canAssign || canManage) && (
          <button
            type="button"
            className="outline"
            aria-label={canAssign ? t.editLabel(user.email) : t.manageLabel(user.email)}
            aria-expanded={current}
            aria-controls="user-panel"
            onClick={() => onOpen(user.id)}
          >
            {canAssign ? t.editRoles : t.manage}
          </button>
        )}
      </td>
    </tr>
  )
}

/** Beside the list: one user's roles and access, changed in one place. */
function UserPanel({ user, me, roles, onClose, onChanged }) {
  const t = USERS_TEXT.panel
  const { canAssign, canManage } = powers(me, user)
  const [selected, setSelected] = useState(user.roleCodes)
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()
  const heading = useRef(null)

  // Opening the panel moves the focus to it, so keyboard and screen reader users land where the change is.
  useEffect(() => {
    heading.current?.focus()
  }, [user.id])

  const changed = [...selected].sort().join() !== [...user.roleCodes].sort().join()

  async function act(action, done, ask) {
    if (ask && !(await confirm(ask))) return
    const ok = await run(async () => {
      await action()
      return true
    })
    if (ok) {
      toast(done)
      onChanged()
    }
  }

  return (
    <aside id="user-panel" className="side-panel user-panel" aria-labelledby="user-panel-title">
      <div className="side-panel-head">
        <span className="avatar avatar-lg" aria-hidden="true">
          {user.email[0]}
        </span>
        <div className="side-panel-who">
          <h2 id="user-panel-title" ref={heading} tabIndex={-1}>
            {user.email}
          </h2>
          <StatusBadge status={user.status} />
          <p className="hint">
            {user.lastLoginAt ? t.lastSignIn(formatDate(user.lastLoginAt)) : t.neverSignedIn}
            <br />
            {user.mfaEnrolled ? t.twoStepOn : t.twoStepOff}
            {user.joinedAt && (
              <>
                <br />
                {t.joined(formatDate(user.joinedAt))}
              </>
            )}
          </p>
        </div>
        <button type="button" className="quiet icon-button" aria-label={t.close} onClick={onClose}>
          <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      </div>

      {canAssign && (
        <div className="side-panel-section">
          <RolePicker me={me} roles={roles} selected={selected} onChange={setSelected} />
          <div className="form-actions">
            <button
              type="button"
              className="primary"
              disabled={busy || !changed}
              onClick={() => act(() => org.changeRoles(user.id, selected), t.saved(user.email))}
            >
              {busy ? t.saving : t.save}
            </button>
            <button type="button" className="quiet" onClick={onClose}>
              {t.cancel}
            </button>
          </div>
        </div>
      )}

      <ErrorNote>{error}</ErrorNote>

      {canManage && (
        <div className="side-panel-section">
          <h3>{t.access}</h3>
          <div className="access-row">
            <p className="hint">{user.status === 'suspended' ? t.reactivateHint : t.suspendHint}</p>
            {user.status === 'active' && (
              <button type="button" className="outline" disabled={busy} onClick={() => act(() => org.changeStatus(user.id, 'suspend'), t.suspended(user.email))}>
                {t.suspend}
              </button>
            )}
            {user.status === 'suspended' && (
              <button type="button" className="outline" disabled={busy} onClick={() => act(() => org.changeStatus(user.id, 'reactivate'), t.reactivated(user.email))}>
                {t.reactivate}
              </button>
            )}
          </div>
          <div className="access-row">
            <p className="hint">{t.endHint}</p>
            <button
              type="button"
              className="danger-outline"
              disabled={busy}
              onClick={() => act(() => org.changeStatus(user.id, 'end'), t.ended(user.email), t.endAsk(user.email))}
            >
              {t.end}
            </button>
          </div>
        </div>
      )}
    </aside>
  )
}

function UsersTab({ me, onInvite }) {
  const users = useLoad(org.users)
  const roles = useLoad(org.roles)
  const [filter, setFilter] = useState('active')
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(null)
  const t = USERS_TEXT
  const canInvite = me.permissions.includes('user.invite') && me.kind === 'tenant'
  if (users.loading || roles.loading) return <p className="muted">Loading…</p>
  if (users.error || roles.error) return <ErrorNote>{users.error || roles.error}</ErrorNote>
  const counts = { active: 0, suspended: 0, ended: 0 }
  users.data.forEach((u) => (counts[u.status] += 1))
  const wanted = search.trim().toLowerCase()
  const shown = users.data.filter((u) => u.status === filter && u.email.toLowerCase().includes(wanted))
  // The panel follows the user even when a change moves them out of the list being shown.
  const openUser = users.data.find((u) => u.id === open && u.status !== 'ended')
  return (
    <>
      <div className="filters">
        <label htmlFor="user-search" className="sr-only">
          {t.search}
        </label>
        <input id="user-search" type="search" placeholder={t.search} value={search} onChange={(e) => setSearch(e.target.value)} />
        <div className="switcher" role="group" aria-label={t.statusGroup}>
          {t.statuses.map(([id, label]) => (
            <button key={id} type="button" aria-pressed={filter === id} className={filter === id ? 'switch-option switch-current' : 'switch-option'} onClick={() => setFilter(id)}>
              {label} <span className="count">{counts[id]}</span>
            </button>
          ))}
        </div>
        <span className="spacer" />
        {canInvite && (
          <button type="button" className="primary" onClick={onInvite}>
            {t.invite}
          </button>
        )}
      </div>
      <div className="with-panel">
        <div className="with-panel-main">
          {shown.length === 0 ? (
            <Empty>{wanted ? t.noMatch : t.none(filter)}</Empty>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t.columns.user}</th>
                    <th>{t.columns.roles}</th>
                    <th>{t.columns.status}</th>
                    <th>{t.columns.lastSignIn}</th>
                    <th aria-label={t.columns.actions} />
                  </tr>
                </thead>
                <tbody>
                  {shown.map((u) => (
                    <UserRow key={u.id} user={u} me={me} current={u.id === open} onOpen={setOpen} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        {openUser && (
          <UserPanel
            key={`${openUser.id}-${openUser.roleCodes.join()}`}
            user={openUser}
            me={me}
            roles={roles.data}
            onClose={() => setOpen(null)}
            onChanged={users.reload}
          />
        )}
      </div>
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
  const confirm = useConfirm()
  const toast = useToast()
  const canInvite = me.permissions.includes('user.invite') && me.kind === 'tenant'

  async function invite(event) {
    event.preventDefault()
    const created = await run(() => org.invite(email, selected))
    if (created) {
      setIssued({ email: created.email, link: invitationLink(created.token) })
      setEmail('')
      // Roles go back to the default, so the next invitation never carries the last one's roles by accident.
      setSelected(['employee'])
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

  async function revoke(invitation) {
    const sure = await confirm({
      title: `Revoke the invitation for ${invitation.email}?`,
      body: 'The link stops working at once. You can invite them again later.',
      confirmLabel: 'Revoke invitation',
      cancelLabel: 'Keep it',
      danger: true,
    })
    if (!sure) return
    const done = await run(async () => {
      await org.revokeInvitation(invitation.id)
      return true
    })
    if (done) {
      toast(`Invitation for ${invitation.email} revoked`)
      invitations.reload()
    }
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
          <RolePicker me={me} roles={roles.data} selected={selected} onChange={setSelected} />
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
                        <button type="button" className="quiet danger" disabled={busy} onClick={() => revoke(i)}>
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
            <th>What it is for</th>
          </tr>
        </thead>
        <tbody>
          {roles.data.map((r) => (
            <tr key={r.code}>
              <td>
                {r.name}
                {!r.assignable && <div className="hint">Set by the system</div>}
              </td>
              <td>
                {ROLE_SUMMARIES[r.code] || '—'}
                <details className="permissions">
                  <summary>Exact permissions</summary>
                  {r.permissions
                    .map((p) => {
                      const m = /^(.*) \((\w+)\)$/.exec(p)
                      return m ? `${m[1]} (${SCOPES[m[2]] || m[2]})` : p
                    })
                    .join(', ')}
                </details>
              </td>
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

/** The area an audit action belongs to, in words. */
const AREAS = {
  membership: 'User',
  role: 'Role',
  user: 'User',
  invitation: 'Invitation',
  tenant: 'Organization',
  support: 'Support',
  grant: 'Support access',
  platform_user: 'Platform user',
  attendance: 'Attendance',
  leave: 'Leave',
  department: 'Department',
  employee: 'Employee',
  joiner: 'Joiner',
  onboarding: 'Onboarding setup',
  exit: 'Exit',
}

/** "exit.settlement_approved" as "Exit: settlement approved". */
function actionWords(action) {
  const [area, ...rest] = action.split('.')
  const what = rest.join(' ').replaceAll('_', ' ')
  return `${AREAS[area] || area.charAt(0).toUpperCase() + area.slice(1).replaceAll('_', ' ')}${what ? `: ${what}` : ''}`
}

export function AuditTab() {
  const audit = useLoad(org.audit)
  const [filter, setFilter] = useState('')
  if (audit.loading) return <p className="muted">Loading…</p>
  if (audit.error) return <ErrorNote>{audit.error}</ErrorNote>
  if (audit.data.length === 0) return <Empty>Nothing has been recorded yet.</Empty>
  const words = filter.trim().toLowerCase()
  const rows = audit.data.filter(
    (e) => !words || [e.actorLabel, e.action, actionWords(e.action), e.targetLabel, describe(e.detail)].join(' ').toLowerCase().includes(words),
  )
  return (
    <>
      <div className="filters">
        <label htmlFor="audit-filter" className="sr-only">
          Filter the audit log
        </label>
        <input id="audit-filter" type="search" placeholder="Filter" value={filter} onChange={(e) => setFilter(e.target.value)} />
        <span className="you">The latest 200 events, newest first</span>
      </div>
      <div className="table-wrap" tabIndex={0} role="region" aria-label="Audit log">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Who</th>
              <th>Did</th>
              <th>About</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((e) => (
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
                  {actionWords(e.action)}
                  <div>
                    <code>{e.action}</code>
                  </div>
                </td>
                <td>{e.targetLabel || '—'}</td>
                <td>{describe(e.detail)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

const MODULE_ABOUT = {
  attendance: 'Punching, devices, the daily board, attendance requests and work from home',
  leave: 'Balances, leave requests and approvals, who is away, leave types and the year end',
  payroll: 'Salary setup, monthly runs, statutory deductions, payslips and the bank file',
}

/** What the Organization has bought. Changes are made by the HRMS team, not here. */
function SubscriptionTab({ me }) {
  return (
    <>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Module</th>
              <th>What it covers</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Core HR</td>
              <td>People, the org chart, users and roles, joining and leaving, work locations</td>
              <td>
                <Badge tone="good">included</Badge>
              </td>
            </tr>
            {(me.subscription || []).map((m) => (
              <tr key={m.module}>
                <td>{m.name}</td>
                <td>{MODULE_ABOUT[m.module]}</td>
                <td>
                  {m.active ? <Badge tone="good">on</Badge> : <Badge>not included</Badge>}
                  {m.active && m.trialEndsOn && <div className="hint">Trial until {formatDay(m.trialEndsOn)}</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint" style={{ marginTop: 12 }}>
        To add a module or end a trial early, contact the HRMS team. Switching a module off hides it and keeps its records; they come
        back when it is switched on again.
      </p>
    </>
  )
}

/** Users, invitations and roles: one page with a tab for each, and what the Organization has bought. */
export function UserManagement({ me }) {
  const [tab, setTab] = useState('users')
  const tabs = [
    { id: 'users', label: 'Users' },
    { id: 'invitations', label: 'Invitations' },
    { id: 'roles', label: 'Roles' },
    me.subscription && { id: 'subscription', label: 'Subscription' },
  ].filter(Boolean)
  return (
    <>
      <Tabs tabs={tabs} current={tab} onChange={setTab} />
      {tab === 'users' && <UsersTab me={me} onInvite={() => setTab('invitations')} />}
      {tab === 'invitations' && <InvitationsTab me={me} />}
      {tab === 'roles' && <RolesTab />}
      {tab === 'subscription' && <SubscriptionTab me={me} />}
    </>
  )
}
