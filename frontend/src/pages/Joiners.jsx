import { useState } from 'react'
import { attendance, invitationLink, joiners, org, people } from '../api.js'
import { formatDate, formatDay, localToday, useAction, useLoad } from '../hooks.js'
import { Badge, CopyLink, Empty, ErrorNote, StatusBadge, Tabs } from '../ui.jsx'
import DocumentList from './DocumentList.jsx'
import FileViewer from './FileViewer.jsx'
import SectionForm from './SectionForm.jsx'
import { Checklist } from './TaskList.jsx'
import { SECTION_STATUS, letterState, pretty } from './onboardingText.js'

/** HR's onboarding: joiners still to start, adding one, and one joiner's sections, checks and activation. */

const WAITING = { joiner: 'The joiner', hr: 'HR', nobody: 'Ready' }
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const TYPES = ['permanent', 'probation', 'fixed_term', 'intern', 'consultant']

const nameOf = (j) => j.preferredName || j.legalName

/** What the Employment section and addresses choose from. Loaded once per screen. */
function useLookups() {
  const departments = useLoad(people.departments)
  const managers = useLoad(() => people.employees({ status: 'active' }))
  const locations = useLoad(attendance.locations)
  const shifts = useLoad(attendance.shifts)
  const states = useLoad(people.states)
  const loading = [departments, managers, locations, shifts, states].some((l) => l.loading)
  return {
    loading,
    departments: departments.data || [],
    managers: managers.data || [],
    locations: locations.data || [],
    shifts: shifts.data || [],
    states: states.data || {},
  }
}

function inviteNote(invitation) {
  return `It works once and stays valid until ${formatDate(invitation.expiresAt)}.`
}

// ── the list ────────────────────────────────────────────────────────

export function JoinerList({ canWrite, onOpen, onAdd }) {
  const [status, setStatus] = useState('open')
  const list = useLoad(() => joiners.list(status))
  const tiles = list.data?.tiles

  return (
    <>
      {tiles && status === 'open' && (
        <div className="tiles">
          {[
            ['joiningThisWeek', 'Joining in 7 days'],
            ['waitingOnJoiner', 'Waiting on the joiner'],
            ['waitingOnHr', 'Waiting on HR'],
            ['ready', 'Ready to activate'],
            ['overdueTasks', 'Overdue tasks'],
          ].map(([key, label]) => (
            <div key={key} className="tile">
              <div className="tile-label">{label}</div>
              <div className="tile-value">{tiles[key]}</div>
            </div>
          ))}
        </div>
      )}
      <div className="filters">
        {[
          ['open', 'Joining'],
          ['active', 'Joined'],
          ['withdrawn', 'Withdrawn'],
          ['all', 'All'],
        ].map(([value, label]) => (
          <button
            key={value}
            type="button"
            className={status === value ? 'chip chip-current' : 'chip'}
            onClick={() => {
              setStatus(value)
              list.reload()
            }}
          >
            {label}
          </button>
        ))}
        <span className="spacer" />
        {canWrite && (
          <button type="button" className="outline" onClick={onAdd}>
            Add joiner
          </button>
        )}
      </div>
      {list.loading ? (
        <p className="muted">Loading…</p>
      ) : list.error ? (
        <ErrorNote>{list.error}</ErrorNote>
      ) : list.data.joiners.length === 0 ? (
        <Empty>{status === 'open' ? 'Nobody is joining. Add a joiner to start their onboarding.' : 'Nobody here.'}</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Joining</th>
                <th>Designation</th>
                <th>Reports to</th>
                <th>Checked</th>
                <th>Tasks</th>
                <th>Waiting on</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {list.data.joiners.map((j) => (
                <tr key={j.id}>
                  <td>
                    <button type="button" className="name-link" onClick={() => onOpen(j.id)}>
                      {nameOf(j)}
                    </button>
                    {j.personalEmail && <div className="hint">{j.personalEmail}</div>}
                  </td>
                  <td>{formatDay(j.joiningDate)}</td>
                  <td>{j.designation || '—'}</td>
                  <td>{j.managerName || '—'}</td>
                  <td>
                    {j.verified} of {j.sectionCount}
                  </td>
                  <td>
                    {j.tasksDone} of {j.taskCount}
                    {j.overdueTasks > 0 && (
                      <>
                        {' '}
                        <Badge tone="warn">{j.overdueTasks} overdue</Badge>
                      </>
                    )}
                  </td>
                  <td>{j.waitingOn ? j.waitingOn === 'nobody' ? <Badge tone="good">Ready</Badge> : WAITING[j.waitingOn] : '—'}</td>
                  <td>
                    <StatusBadge status={j.status} />
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

// ── adding a joiner ─────────────────────────────────────────────────

const NEW_JOINER = {
  legalName: '',
  personalEmail: '',
  mobile: '',
  joiningDate: '',
  employmentType: 'permanent',
  contractEnd: '',
  designation: '',
  departmentId: '',
  managerId: '',
  workLocationId: '',
  shiftId: '',
  weeklyOffs: [0],
  employeeCode: '',
}

export function AddJoiner({ onSaved, onCancel }) {
  const [form, setForm] = useState(NEW_JOINER)
  const [rehire, setRehire] = useState(null) // candidates the server found
  const [invite, setInvite] = useState(false)
  const lookups = useLookups()
  const { busy, error, run, clearError } = useAction()
  const set = (patch) => {
    clearError()
    setForm((f) => ({ ...f, ...patch }))
  }

  async function save(withInvite, rehireOf) {
    setInvite(withInvite)
    const body = { ...form, invite: withInvite, rehireOf: rehireOf || null }
    for (const key of Object.keys(body)) if (body[key] === '') body[key] = null
    const saved = await run(() => joiners.add(body))
    if (!saved) return
    if (saved.possibleRehire) {
      setRehire(saved.possibleRehire)
      return
    }
    onSaved(saved.id, saved.invitation)
  }

  if (lookups.loading) return <p className="muted">Loading…</p>

  if (rehire) {
    return (
      <section className="panel">
        <h2>Is this someone who was here before?</h2>
        <p>
          {rehire.length === 1 ? 'An earlier person has' : 'Earlier people have'} the same email or mobile as {form.legalName}. If it is
          the same person, their earlier details are kept and this becomes a rehire.
        </p>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Email, mobile</th>
                <th>Last stint</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rehire.map((r) => (
                <tr key={r.personId}>
                  <td>{r.legalName}</td>
                  <td>{[r.personalEmail, r.mobile].filter(Boolean).join(', ')}</td>
                  <td>
                    {r.lastStatus ? pretty(r.lastStatus) : '—'}
                    {r.lastJoined && <div className="hint">joined {formatDay(r.lastJoined)}</div>}
                    {r.lastExit && (
                      <div className="hint">
                        left {formatDay(r.lastExit)}
                        {r.lastExitKind ? ` (${pretty(r.lastExitKind).toLowerCase()})` : ''}
                      </div>
                    )}
                    {r.rehire && (
                      <div className={r.rehire === 'no' ? 'error-text' : 'hint'} data-testid="rehire-eligibility">
                        Could be hired again: {pretty(r.rehire)}
                        {r.rehireNote ? `. ${r.rehireNote}` : ''}
                      </div>
                    )}
                  </td>
                  <td>
                    <button type="button" className="secondary" disabled={busy} onClick={() => save(invite, r.personId)}>
                      Same person
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <ErrorNote>{error}</ErrorNote>
        <div className="form-actions">
          <button type="button" className="outline" disabled={busy} onClick={() => save(invite, 'new')}>
            Someone new
          </button>
          <button type="button" className="quiet" onClick={() => setRehire(null)}>
            Back
          </button>
        </div>
      </section>
    )
  }

  return (
    <form
      className="panel"
      noValidate
      onSubmit={(e) => {
        e.preventDefault()
        save(false)
      }}
    >
      <h2>Add a joiner</h2>
      <p className="hint">
        What you know now. The joiner fills in their own personal, bank and tax details once you invite them; you can also
        fill them in for the joiner.
      </p>
      <h3>Who</h3>
      <div className="grid">
        <div>
          <label htmlFor="nj-name">Full name</label>
          <input id="nj-name" value={form.legalName} autoComplete="off" onChange={(e) => set({ legalName: e.target.value })} />
        </div>
        <div>
          <label htmlFor="nj-email">Personal email</label>
          <input id="nj-email" type="email" value={form.personalEmail} onChange={(e) => set({ personalEmail: e.target.value })} />
          <p className="hint">The invitation goes here.</p>
        </div>
        <div>
          <label htmlFor="nj-mobile">Mobile</label>
          <input id="nj-mobile" type="tel" inputMode="tel" value={form.mobile} onChange={(e) => set({ mobile: e.target.value })} />
        </div>
      </div>
      <h3>Job</h3>
      <div className="grid">
        <div>
          <label htmlFor="nj-joining">Joining date</label>
          <input id="nj-joining" type="date" min={localToday()} value={form.joiningDate} onChange={(e) => set({ joiningDate: e.target.value })} />
        </div>
        <div>
          <label htmlFor="nj-type">Employment type</label>
          <select id="nj-type" value={form.employmentType} onChange={(e) => set({ employmentType: e.target.value })}>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {pretty(t)}
              </option>
            ))}
          </select>
        </div>
        {form.employmentType === 'fixed_term' && (
          <div>
            <label htmlFor="nj-contract">Contract ends</label>
            <input id="nj-contract" type="date" value={form.contractEnd} onChange={(e) => set({ contractEnd: e.target.value })} />
          </div>
        )}
        <div>
          <label htmlFor="nj-designation">Designation</label>
          <input id="nj-designation" value={form.designation} placeholder="Software engineer" onChange={(e) => set({ designation: e.target.value })} />
        </div>
        <div>
          <label htmlFor="nj-department">Department</label>
          <select id="nj-department" value={form.departmentId} onChange={(e) => set({ departmentId: e.target.value })}>
            <option value="">None</option>
            {lookups.departments
              .filter((d) => d.active)
              .map((d) => (
                <option key={d.id} value={d.id}>
                  {d.parentName ? `${d.parentName} › ${d.name}` : d.name}
                </option>
              ))}
          </select>
        </div>
        <div>
          <label htmlFor="nj-manager">Reports to</label>
          <select id="nj-manager" value={form.managerId} onChange={(e) => set({ managerId: e.target.value })}>
            <option value="">Nobody</option>
            {lookups.managers.map((m) => (
              <option key={m.id} value={m.id}>
                {m.preferredName || m.legalName}
                {m.designation ? `, ${m.designation}` : ''}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="nj-code">Employee code</label>
          <input id="nj-code" value={form.employeeCode} placeholder="E042" onChange={(e) => set({ employeeCode: e.target.value })} />
        </div>
      </div>
      <h3>Attendance from day one</h3>
      <div className="grid">
        <div>
          <label htmlFor="nj-location">Work location</label>
          <select id="nj-location" value={form.workLocationId} onChange={(e) => set({ workLocationId: e.target.value })}>
            <option value="">Choose…</option>
            {lookups.locations.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="nj-shift">Shift</label>
          <select id="nj-shift" value={form.shiftId} onChange={(e) => set({ shiftId: e.target.value })}>
            <option value="">Choose…</option>
            {lookups.shifts
              .filter((s) => s.active !== false)
              .map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name} ({s.startTime}–{s.endTime})
                </option>
              ))}
          </select>
        </div>
        <fieldset className="days">
          <legend>Weekly offs</legend>
          {WEEKDAYS.map((d, i) => (
            <label key={d} className="check">
              <input
                type="checkbox"
                checked={form.weeklyOffs.includes(i)}
                onChange={(e) => set({ weeklyOffs: e.target.checked ? [...form.weeklyOffs, i].sort() : form.weeklyOffs.filter((x) => x !== i) })}
              />
              {d}
            </label>
          ))}
        </fieldset>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="primary" disabled={busy || !form.personalEmail} onClick={() => save(true)}>
          {busy && invite ? 'Saving…' : 'Save and invite'}
        </button>
        <button type="submit" className="secondary" disabled={busy}>
          {busy && !invite ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

// ── one joiner ──────────────────────────────────────────────────────

export function JoinerPage({ id, onBack, initialInvitation }) {
  const joiner = useLoad(() => joiners.get(id))
  const lookups = useLookups()
  const [view, setView] = useState('sections')
  const [open, setOpen] = useState(null)
  const [invitation, setInvitation] = useState(initialInvitation || null)
  const [withdrawing, setWithdrawing] = useState(false)
  const [message, setMessage] = useState('')
  const { busy, error, run } = useAction()

  if (joiner.loading || lookups.loading) return <p className="muted">Loading…</p>
  if (joiner.error) return <ErrorNote>{joiner.error}</ErrorNote>
  const j = joiner.data
  const isOpen = ['draft', 'offered', 'invited', 'in_progress'].includes(j.status)
  const canManage = j.canManage && isOpen

  async function invite() {
    const sent = await run(() => joiners.invite(id))
    if (sent) {
      setInvitation(sent)
      joiner.reload()
    }
  }

  async function activate() {
    const done = await run(() => joiners.activate(id))
    if (done) {
      setMessage(done.activated ? `${nameOf(j)} is now an employee.` : `${nameOf(j)} becomes an employee on ${formatDay(done.on)}.`)
      joiner.reload()
    }
  }

  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← All joiners
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>
            {nameOf(j)} <StatusBadge status={j.status} />
          </h2>
          <p className="muted">
            {[j.designation, j.departmentName, `joining ${formatDay(j.joiningDate)}`].filter(Boolean).join(' · ')}
          </p>
        </div>
        {canManage && (
          <div className="form-actions">
            {!j.hasLogin && j.personalEmail && (
              <button type="button" className="outline" disabled={busy} onClick={invite}>
                {j.invitation ? 'New invitation link' : 'Invite'}
              </button>
            )}
            <button type="button" className="secondary" disabled={busy} onClick={activate}>
              {j.activationRequestedAt ? 'Activation scheduled' : 'Activate'}
            </button>
            <button type="button" className="quiet danger" onClick={() => setWithdrawing(true)}>
              Withdraw
            </button>
          </div>
        )}
      </div>
      {invitation && (
        <CopyLink
          label={`Invitation for ${invitation.email}.`}
          link={invitationLink(invitation.token)}
          note={inviteNote(invitation)}
          onDismiss={() => setInvitation(null)}
        />
      )}
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      {withdrawing && (
        <Withdraw
          joiner={j}
          onDone={() => {
            setWithdrawing(false)
            joiner.reload()
          }}
          onCancel={() => setWithdrawing(false)}
        />
      )}

      <div className="panels">
        <section className="panel">
          <h3>Joining</h3>
          <dl className="facts">
            <dt>Personal email</dt>
            <dd>{j.personalEmail || '—'}</dd>
            <dt>Mobile</dt>
            <dd>{j.mobile || '—'}</dd>
            <dt>Reports to</dt>
            <dd>{j.managerName || '—'}</dd>
            <dt>Work location</dt>
            <dd>{j.workLocation || '—'}</dd>
            <dt>Flow</dt>
            <dd>{j.flow?.name}</dd>
            <dt>Login</dt>
            <dd>
              {j.hasLogin
                ? 'Accepted the invitation'
                : j.invitation
                  ? `Invited; link ${j.invitation.expired ? 'expired' : `valid until ${formatDate(j.invitation.expiresAt)}`}`
                  : 'Not invited yet'}
            </dd>
            <dt>Notice</dt>
            <dd>{j.noticeAcceptedAt ? `Accepted ${formatDate(j.noticeAcceptedAt)}` : 'Not yet accepted'}</dd>
            {j.status === 'withdrawn' && (
              <>
                <dt>Withdrawn</dt>
                <dd>
                  {formatDate(j.withdrawnAt)}: {j.withdrawnReason}
                </dd>
              </>
            )}
          </dl>
        </section>
        {isOpen && (
          <section className="panel">
            <h3>Before activation</h3>
            {j.blockers.length === 0 ? (
              <p>
                <Badge tone="good">Ready</Badge>{' '}
                {j.activation === 'automatic'
                  ? `Becomes an employee on ${formatDay(j.joiningDate)} without anything more from you.`
                  : 'Press Activate to make them an employee on the joining date.'}
              </p>
            ) : (
              <ul className="blockers">
                {j.blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            )}
          </section>
        )}
      </div>

      <Tabs
        tabs={[
          { id: 'sections', label: 'Sections' },
          j.sections.some((s) => s.section === 'documents') && { id: 'documents', label: 'Documents' },
          { id: 'checklist', label: 'Checklist' },
          { id: 'letters', label: 'Letters and policies' },
          { id: 'history', label: 'History' },
        ].filter(Boolean)}
        current={view}
        onChange={setView}
      />
      {view === 'sections' && (
        <div className="section-list">
          {j.sections.filter((s) => s.section !== 'documents').map((s) => (
            <SectionCard
              key={s.section}
              joinerId={id}
              summary={s}
              open={open === s.section}
              onToggle={() => setOpen(open === s.section ? null : s.section)}
              lookups={lookups}
              onChanged={joiner.reload}
            />
          ))}
        </div>
      )}
      {view === 'documents' && <JoinerDocuments id={id} onChanged={joiner.reload} />}
      {view === 'checklist' && <JoinerChecklist id={id} onChanged={joiner.reload} />}
      {view === 'letters' && <JoinerLetters id={id} onChanged={joiner.reload} />}
      {view === 'history' && <History id={id} />}
    </>
  )
}

function SectionCard({ joinerId, summary, open, onToggle, lookups, onChanged }) {
  return (
    <section className={`panel section-card section-${summary.status}`}>
      <button type="button" className="section-toggle" aria-expanded={open} onClick={onToggle}>
        <span className="section-title">{summary.title}</span>
        <span>
          {summary.levels > 1 && summary.status === 'submitted' && (
            <span className="hint">
              level {summary.level + 1} of {summary.levels}{' '}
            </span>
          )}
          <StatusBadge status={summary.status} />
        </span>
      </button>
      {summary.status === 'returned' && summary.note && !open && <p className="hint">Returned: {summary.note}</p>}
      {open && <SectionDetail joinerId={joinerId} section={summary.section} lookups={lookups} onChanged={onChanged} />}
    </section>
  )
}

function SectionDetail({ joinerId, section, lookups, onChanged }) {
  const detail = useLoad(() => joiners.section(joinerId, section))
  const [note, setNote] = useState('')
  const [returning, setReturning] = useState(false)
  // HR fills the Employment section; the joiner's sections show as read-only until HR chooses to edit them.
  const [editChoice, setEditing] = useState(null)
  const { busy, error, run } = useAction()

  if (detail.loading) return <p className="muted">Loading…</p>
  if (detail.error) return <ErrorNote>{detail.error}</ErrorNote>
  const d = detail.data
  const editing = editChoice ?? (section === 'employment' && ['not_started', 'draft', 'returned'].includes(d.status))
  const refresh = () => {
    detail.reload()
    onChanged()
  }

  async function decide(verify) {
    const done = await run(() => (verify ? joiners.verify(joinerId, section, note) : joiners.returnSection(joinerId, section, note)))
    if (done) {
      setNote('')
      setReturning(false)
      refresh()
    }
  }

  return (
    <div className="section-detail">
      {d.status === 'returned' && d.note && (
        <p className="notice">
          <strong>Returned:</strong> {d.note}
        </p>
      )}
      {d.canEdit && !editing && (
        <p>
          <button type="button" className="quiet plain" onClick={() => setEditing(true)}>
            Edit for the joiner
          </button>
        </p>
      )}
      <SectionForm
        key={`${d.status}-${editing}-${JSON.stringify(d.values)}`}
        view={d}
        canEdit={d.canEdit && editing}
        editableField={() => true}
        lookups={lookups}
        submitLabel={d.status === 'submitted' ? 'Save and keep for checking' : 'Send for checking'}
        onSave={async (values) => {
          await joiners.saveSection(joinerId, section, values)
          refresh()
        }}
        onSubmit={async () => {
          if (d.status !== 'submitted') await joiners.submit(joinerId, section)
          refresh()
        }}
      />
      {d.canVerify && (
        <div className="verify-box">
          <h4>
            Check {d.levels > 1 ? `(level ${d.nextLevel} of ${d.levels})` : ''}
          </h4>
          {returning && (
            <>
              <label htmlFor={`note-${section}`}>What needs fixing</label>
              <textarea id={`note-${section}`} rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
            </>
          )}
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            {!returning && (
              <button type="button" className="primary" disabled={busy} onClick={() => decide(true)}>
                Verify
              </button>
            )}
            {returning ? (
              <>
                <button type="button" className="secondary" disabled={busy} onClick={() => decide(false)}>
                  Return to joiner
                </button>
                <button type="button" className="quiet" onClick={() => setReturning(false)}>
                  Cancel
                </button>
              </>
            ) : (
              <button type="button" className="outline" onClick={() => setReturning(true)}>
                Return with a note
              </button>
            )}
          </div>
        </div>
      )}
      {d.reviews.length > 0 && (
        <ul className="reviews">
          {d.reviews.map((r, i) => (
            <li key={i}>
              {r.decision === 'verified' ? 'Verified' : 'Returned'}
              {r.level > 1 ? ` at level ${r.level}` : ''} by {r.decidedBy || 'someone'}, {formatDate(r.decidedAt)}
              {r.note ? `: ${r.note}` : ''}
            </li>
          ))}
        </ul>
      )}
      <p className="hint">{SECTION_STATUS[d.status]}</p>
    </div>
  )
}

function JoinerDocuments({ id, onChanged }) {
  const docs = useLoad(() => joiners.documents(id))
  if (docs.loading) return <p className="muted">Loading…</p>
  if (docs.error) return <ErrorNote>{docs.error}</ErrorNote>
  const d = docs.data
  const changed = () => {
    docs.reload()
    onChanged()
  }
  return (
    <DocumentList
      data={d}
      canUpload={d.canUpload}
      canVerify={d.canVerify}
      canOpen={d.canOpen}
      onUpload={async (typeId, file, onProgress) => {
        await joiners.uploadDocument(id, typeId, file, onProgress)
        changed()
      }}
      onOpen={joiners.documentFile}
      onVerify={async (documentId) => {
        await joiners.verifyDocument(id, documentId)
        changed()
      }}
      onReject={async (documentId, note) => {
        await joiners.rejectDocument(id, documentId, note)
        changed()
      }}
    />
  )
}

function JoinerChecklist({ id, onChanged }) {
  const list = useLoad(() => joiners.tasks(id))
  const users = useLoad(() => org.users().catch(() => []))
  const [adding, setAdding] = useState(false)
  if (list.loading || users.loading) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  const active = (users.data || []).filter((u) => u.status === 'active')
  const changed = () => {
    list.reload()
    onChanged()
  }
  return (
    <>
      {list.data.canManage && (
        <div className="toolbar">
          <span className="spacer" />
          {!adding && (
            <button type="button" className="outline" onClick={() => setAdding(true)}>
              Add a task
            </button>
          )}
        </div>
      )}
      {adding && (
        <AddTask
          id={id}
          users={active}
          onDone={() => {
            setAdding(false)
            changed()
          }}
          onCancel={() => setAdding(false)}
        />
      )}
      {list.data.tasks.length === 0 ? (
        <Empty>No checklist tasks for this joiner.</Empty>
      ) : (
        <Checklist tasks={list.data.tasks} hr={list.data.canManage} users={active} onChanged={changed} />
      )}
    </>
  )
}

const LETTER_STATE = {
  waiting: ['warn', 'Waiting for an answer'],
  accepted: ['good', 'Accepted'],
  declined: ['warn', 'Declined'],
  expired: ['neutral', 'Expired'],
  none: ['neutral', 'No answer needed'],
}

function JoinerLetters({ id, onChanged }) {
  const data = useLoad(() => joiners.letters(id))
  const policies = useLoad(() => joiners.policies(id))
  const [templateId, setTemplateId] = useState('')
  const [days, setDays] = useState('')
  const [preview, setPreview] = useState(null)
  const [viewing, setViewing] = useState(null)
  const [uploading, setUploading] = useState(false)
  const { busy, error, run } = useAction()
  if (data.loading || policies.loading) return <p className="muted">Loading…</p>
  if (data.error) return <ErrorNote>{data.error}</ErrorNote>
  const d = data.data
  const chosen = d.templates.find((t) => t.id === templateId)

  async function view(letter) {
    const blob = await run(() => joiners.documentFile(letter.documentId))
    if (blob) setViewing({ name: `${letter.title}.pdf`, type: 'application/pdf', url: URL.createObjectURL(blob) })
  }

  async function issue() {
    if (await run(() => joiners.issueLetter(id, templateId, chosen?.kind === 'offer' && days ? Number(days) : null))) {
      setTemplateId('')
      setPreview(null)
      data.reload()
      onChanged()
    }
  }

  return (
    <>
      <section className="panel">
        <div className="section-head">
          <h3>Letters</h3>
          {d.canIssue && !uploading && (
            <button type="button" className="quiet plain" onClick={() => setUploading(true)}>
              Upload a letter instead
            </button>
          )}
        </div>
        {d.letters.length === 0 ? (
          <p className="hint">No letters issued yet.</p>
        ) : (
          <div className="letter-list">
            {d.letters.map((l) => {
              const [tone, label] = LETTER_STATE[letterState(l)]
              return (
                <div key={l.id} className="file-line letter-row" data-letter={l.title}>
                  <span className="file-name">{l.title}</span>
                  <span className="hint">
                    {pretty(l.kind)} · issued {formatDate(l.issuedAt)}
                    {l.expiresOn && !l.response && ` · answer by ${formatDay(l.expiresOn)}`}
                    {l.respondedAt && ` · ${formatDate(l.respondedAt)}`}
                  </span>
                  <Badge tone={tone}>{label}</Badge>
                  <button type="button" className="quiet plain" onClick={() => view(l)}>
                    View
                  </button>
                </div>
              )
            })}
          </div>
        )}
        {d.canIssue && (
          <div className="verify-box">
            <div className="grid">
              <div>
                <label htmlFor="letter-template">Issue a letter</label>
                <select
                  id="letter-template"
                  value={templateId}
                  onChange={(e) => {
                    setTemplateId(e.target.value)
                    setPreview(null)
                  }}
                >
                  <option value="">Choose…</option>
                  {d.templates.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </div>
              {chosen?.kind === 'offer' && (
                <div>
                  <label htmlFor="letter-days">Days to answer</label>
                  <input id="letter-days" inputMode="numeric" placeholder={String(d.offerExpiryDays)} value={days} onChange={(e) => setDays(e.target.value)} />
                </div>
              )}
            </div>
            {chosen?.kind === 'offer' && (
              <p className="hint">The joiner will see only this offer until they accept it; declining it ends their onboarding.</p>
            )}
            {preview && <pre className="letter-preview">{preview}</pre>}
            <ErrorNote>{error}</ErrorNote>
            <div className="form-actions">
              <button type="button" className="outline" disabled={!templateId || busy} onClick={async () => {
                const p = await run(() => joiners.previewLetter(id, templateId))
                if (p) setPreview(p.text)
              }}>
                Preview
              </button>
              <button type="button" className="primary" disabled={!templateId || busy} onClick={issue}>
                Issue letter
              </button>
            </div>
          </div>
        )}
        {uploading && (
          <UploadLetter
            id={id}
            onDone={() => {
              setUploading(false)
              data.reload()
              onChanged()
            }}
            onCancel={() => setUploading(false)}
          />
        )}
      </section>
      <section className="panel">
        <h3>Policies</h3>
        {policies.error ? (
          <ErrorNote>{policies.error}</ErrorNote>
        ) : policies.data.length === 0 ? (
          <p className="hint">No policies apply to this joiner.</p>
        ) : (
          policies.data.map((p) => (
            <div key={p.id} className="file-line" data-policy={p.name}>
              <span className="file-name">{p.name}</span>
              <span className="hint">
                version {p.version} · {p.due === 'before_activation' ? 'before joining' : `by ${formatDay(p.dueOn)}`}
              </span>
              {p.current ? (
                <Badge tone="good">Acknowledged {formatDate(p.acknowledgedAt)}</Badge>
              ) : (
                <Badge tone="warn">{p.acknowledgedVersion ? `Acknowledged version ${p.acknowledgedVersion}` : 'Not acknowledged'}</Badge>
              )}
            </div>
          ))
        )}
      </section>
      {viewing && <FileViewer file={viewing} onClose={() => setViewing(null)} />}
    </>
  )
}

function UploadLetter({ id, onDone, onCancel }) {
  const [form, setForm] = useState({ title: '', kind: 'other', response: 'accept' })
  const [file, setFile] = useState(null)
  const { busy, error, run } = useAction()
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  return (
    <form
      className="verify-box"
      onSubmit={async (e) => {
        e.preventDefault()
        if (file && (await run(() => joiners.uploadLetter(id, file, form)))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="up-title">Title</label>
          <input id="up-title" value={form.title} placeholder="Relocation letter" onChange={(e) => set({ title: e.target.value })} />
        </div>
        <div>
          <label htmlFor="up-kind">Kind</label>
          <select id="up-kind" value={form.kind} onChange={(e) => set({ kind: e.target.value })}>
            {['offer', 'appointment', 'confirmation', 'other'].map((k) => (
              <option key={k} value={k}>
                {pretty(k)}
              </option>
            ))}
          </select>
        </div>
        {form.kind !== 'offer' && (
          <div>
            <label htmlFor="up-response">The joiner</label>
            <select id="up-response" value={form.response} onChange={(e) => set({ response: e.target.value })}>
              <option value="accept">Accepts it</option>
              <option value="accept_or_decline">Accepts or declines it</option>
              <option value="none">Only reads it</option>
            </select>
          </div>
        )}
        <div>
          <label htmlFor="up-file">The letter, as a PDF</label>
          <input id="up-file" type="file" accept="application/pdf" onChange={(e) => setFile(e.target.files?.[0] || null)} />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy || !file}>
          Issue letter
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function AddTask({ id, users, onDone, onCancel }) {
  const [form, setForm] = useState({ title: '', assigneeId: '', dueDate: '', required: true })
  const { busy, error, run } = useAction()
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => joiners.addTask(id, form))) onDone()
      }}
    >
      <h3>Add a task for this joiner</h3>
      <div className="grid">
        <div>
          <label htmlFor="task-title">Task</label>
          <input id="task-title" value={form.title} placeholder="Order a desk chair" onChange={(e) => set({ title: e.target.value })} />
        </div>
        <div>
          <label htmlFor="task-assignee">Who does it</label>
          <select id="task-assignee" value={form.assigneeId} onChange={(e) => set({ assigneeId: e.target.value })}>
            <option value="">Me</option>
            {users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.email}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="task-due">Due</label>
          <input id="task-due" type="date" value={form.dueDate} onChange={(e) => set({ dueDate: e.target.value })} />
        </div>
      </div>
      <label className="check">
        <input type="checkbox" checked={form.required} onChange={(e) => set({ required: e.target.checked })} />
        Needed before the joiner can be activated, when due before the joining date
      </label>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          Add task
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function Withdraw({ joiner, onDone, onCancel }) {
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => joiners.withdraw(joiner.id, reason))) onDone()
      }}
    >
      <h3>Withdraw {nameOf(joiner)}</h3>
      <p className="hint">
        Their invitation and login stop working. What they entered is deleted after the retention period set for the
        Organization.
      </p>
      <label htmlFor="withdraw-reason">Reason</label>
      <input id="withdraw-reason" value={reason} placeholder="Accepted another offer" onChange={(e) => setReason(e.target.value)} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary danger" disabled={busy}>
          Withdraw
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function detailOf(h) {
  if (h.detail && typeof h.detail === 'object') return h.detail
  try {
    return JSON.parse(h.detail || '{}') || {}
  } catch {
    return {}
  }
}

function History({ id }) {
  const history = useLoad(() => joiners.history(id))
  if (history.loading) return <p className="muted">Loading…</p>
  if (history.error) return <ErrorNote>{history.error}</ErrorNote>
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Who</th>
            <th>What</th>
          </tr>
        </thead>
        <tbody>
          {history.data.map((h, i) => (
            <tr key={i}>
              <td>{formatDate(h.at)}</td>
              <td>{h.actorLabel}</td>
              <td>
                {pretty(h.action.replace('joiner.', ''))}
                {detailOf(h).section && <span className="hint"> · {pretty(detailOf(h).section)}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
