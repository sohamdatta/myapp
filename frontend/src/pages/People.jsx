import { useState } from 'react'
import { people } from '../api.js'
import { formatDay, localToday, useAction, useLoad, handoff } from '../hooks.js'
import { Badge, Empty, ErrorNote, StatusBadge, Tabs } from '../ui.jsx'
import { AddJoiner, JoinerList, JoinerPage } from './Joiners.jsx'
import PeopleImport from './PeopleImport.jsx'
import PeopleSetup from './setup/PeopleSetup.jsx'

const TYPES = {
  permanent: 'Permanent',
  probation: 'On probation',
  fixed_term: 'Fixed term',
  intern: 'Intern',
  consultant: 'Consultant',
}

const GENDERS = { female: 'Female', male: 'Male', transgender: 'Transgender', undisclosed: 'Prefer not to say' }

const IDENTIFIERS = {
  pan: 'PAN',
  aadhaar: 'Aadhaar',
  uan: 'UAN',
  pf_member: 'Previous PF member id',
  esi_ip: 'ESI insurance number',
}

const masked = (last4) => (last4 && last4 !== 'NONE' ? `•••• ${last4}` : '—')
const displayName = (e) => e.preferredName || e.legalName

// ── the employee list ───────────────────────────────────────────────

function EmployeeList({ canWrite, onOpen, onAdd }) {
  const [status, setStatus] = useState('active')
  const [search, setSearch] = useState('')
  const [department, setDepartment] = useState('')
  const departments = useLoad(people.departments)
  const list = useLoad(() => people.employees({ status }))

  const term = search.trim().toLowerCase()
  const shown = (list.data || []).filter(
    (e) =>
      (!department || e.departmentId === department) &&
      (!term ||
        [e.legalName, e.preferredName, e.employeeCode, e.email, e.designation].some((v) => v && v.toLowerCase().includes(term))),
  )

  return (
    <>
      <div className="filters">
        {[
          ['active', 'Active'],
          ['exited', 'Left'],
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
        <label htmlFor="emp-search" className="sr-only">
          Search employees
        </label>
        <input id="emp-search" type="search" placeholder="Search name, code, email" value={search} onChange={(e) => setSearch(e.target.value)} />
        {departments.data?.length > 0 && (
          <>
            <label htmlFor="emp-dept" className="sr-only">
              Department
            </label>
            <select id="emp-dept" value={department} onChange={(e) => setDepartment(e.target.value)}>
              <option value="">All departments</option>
              {departments.data.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.parentName ? `${d.parentName} › ${d.name}` : d.name}
                </option>
              ))}
            </select>
          </>
        )}
        <span className="spacer" />
        {canWrite && (
          <button type="button" className="outline" onClick={onAdd}>
            Add employee
          </button>
        )}
      </div>
      {list.loading ? (
        <p className="muted">Loading…</p>
      ) : list.error ? (
        <ErrorNote>{list.error}</ErrorNote>
      ) : shown.length === 0 ? (
        <Empty>
          {list.data.length === 0
            ? status === 'active'
              ? 'No employee records yet. Add your existing staff here, starting with yourself.'
              : 'Nobody here.'
            : 'Nobody matches that search.'}
        </Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Code</th>
                <th>Designation</th>
                <th>Department</th>
                <th>Reports to</th>
                <th>Joined</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((e) => (
                <tr key={e.id}>
                  <td>
                    <button type="button" className="name-link" onClick={() => onOpen(e.id)}>
                      {displayName(e)}
                    </button>
                    {e.email && <div className="hint">{e.email}</div>}
                  </td>
                  <td>{e.employeeCode || '—'}</td>
                  <td>{e.designation || '—'}</td>
                  <td>{e.departmentName || '—'}</td>
                  <td>{e.managerName || '—'}</td>
                  <td>{formatDay(e.joiningDate)}</td>
                  <td>
                    <StatusBadge status={e.status === 'exited' ? 'ended' : e.status} />
                    {e.status === 'exited' && <div className="hint">Last day {formatDay(e.exitDate)}</div>}
                    {e.leavingOn && <div className="hint">Leaving {formatDay(e.leavingOn)}</div>}
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

// ── adding an employee ──────────────────────────────────────────────

const NEW_EMPLOYEE = {
  legalName: '',
  preferredName: '',
  dateOfBirth: '',
  gender: '',
  mobile: '',
  personalEmail: '',
  joiningDate: '',
  employmentType: 'permanent',
  probationEnd: '',
  contractEnd: '',
  designation: '',
  departmentId: '',
  managerId: '',
  employeeCode: '',
  membershipId: '',
  pan: '',
  panPendingReason: '',
  aadhaar: '',
  uan: '',
  bankHolder: '',
  bankAccount: '',
  bankAccountAgain: '',
  ifsc: '',
}

/** Managers: active employees. Departments: those in use. Shared by the add and change-job forms. */
function JobFields({ form, set, departments, managers, idPrefix, exclude }) {
  return (
    <>
      <div>
        <label htmlFor={`${idPrefix}-designation`}>Designation</label>
        <input
          id={`${idPrefix}-designation`}
          value={form.designation}
          placeholder="Software engineer"
          onChange={(e) => set({ designation: e.target.value })}
        />
      </div>
      <div>
        <label htmlFor={`${idPrefix}-department`}>Department</label>
        <select id={`${idPrefix}-department`} value={form.departmentId} onChange={(e) => set({ departmentId: e.target.value })}>
          <option value="">None</option>
          {departments
            .filter((d) => d.active)
            .map((d) => (
              <option key={d.id} value={d.id}>
                {d.parentName ? `${d.parentName} › ${d.name}` : d.name}
              </option>
            ))}
        </select>
      </div>
      <div>
        <label htmlFor={`${idPrefix}-manager`}>Reports to</label>
        <select id={`${idPrefix}-manager`} value={form.managerId} onChange={(e) => set({ managerId: e.target.value })}>
          <option value="">Nobody</option>
          {managers
            .filter((m) => m.id !== exclude)
            .map((m) => (
              <option key={m.id} value={m.id}>
                {displayName(m)}
                {m.designation ? `, ${m.designation}` : ''}
              </option>
            ))}
        </select>
      </div>
    </>
  )
}

function AddEmployee({ onSaved, onCancel }) {
  const [form, setForm] = useState(NEW_EMPLOYEE)
  const departments = useLoad(people.departments)
  const managers = useLoad(() => people.employees({ status: 'active' }))
  const users = useLoad(people.unlinkedUsers)
  const { busy, error, run, clearError } = useAction()
  const [problem, setProblem] = useState('')
  const set = (patch) => {
    setProblem('')
    clearError()
    setForm((f) => ({ ...f, ...patch }))
  }

  async function submit(event) {
    event.preventDefault()
    if (form.bankAccount && form.bankAccount.replace(/\s/g, '') !== form.bankAccountAgain.replace(/\s/g, '')) {
      setProblem('The two account numbers are different. Type it again carefully.')
      return
    }
    const body = { ...form }
    delete body.bankAccountAgain
    const saved = await run(() => people.addEmployee(body))
    if (saved) onSaved(saved.id)
  }

  if (departments.loading || managers.loading || users.loading) return <p className="muted">Loading…</p>

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>Add an employee</h2>
      <p className="hint">
        For someone already working here. Add people who are still to join under Joiners, so they fill in their own
        details.
      </p>

      <h3>Person</h3>
      <div className="grid">
        <div>
          <label htmlFor="ne-legal">Full name as on PAN</label>
          <input id="ne-legal" value={form.legalName} autoComplete="off" onChange={(e) => set({ legalName: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ne-preferred">Preferred name (optional)</label>
          <input id="ne-preferred" value={form.preferredName} onChange={(e) => set({ preferredName: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ne-dob">Date of birth</label>
          <input id="ne-dob" type="date" value={form.dateOfBirth} onChange={(e) => set({ dateOfBirth: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ne-gender">Gender</label>
          <select id="ne-gender" value={form.gender} onChange={(e) => set({ gender: e.target.value })}>
            <option value="">Not given</option>
            {Object.entries(GENDERS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="ne-mobile">Mobile</label>
          <input id="ne-mobile" type="tel" inputMode="tel" value={form.mobile} onChange={(e) => set({ mobile: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ne-email">Personal email</label>
          <input id="ne-email" type="email" value={form.personalEmail} onChange={(e) => set({ personalEmail: e.target.value })} />
        </div>
      </div>

      <h3>Employment</h3>
      <div className="grid">
        <div>
          <label htmlFor="ne-joining">Joining date</label>
          <input id="ne-joining" type="date" value={form.joiningDate} onChange={(e) => set({ joiningDate: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ne-type">Employment type</label>
          <select id="ne-type" value={form.employmentType} onChange={(e) => set({ employmentType: e.target.value })}>
            {Object.entries(TYPES).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        {form.employmentType === 'probation' && (
          <div>
            <label htmlFor="ne-probation">Probation ends</label>
            <input id="ne-probation" type="date" value={form.probationEnd} onChange={(e) => set({ probationEnd: e.target.value })} />
          </div>
        )}
        {form.employmentType === 'fixed_term' && (
          <div>
            <label htmlFor="ne-contract">Contract ends</label>
            <input id="ne-contract" type="date" value={form.contractEnd} onChange={(e) => set({ contractEnd: e.target.value })} />
          </div>
        )}
        <JobFields form={form} set={set} departments={departments.data || []} managers={managers.data || []} idPrefix="ne" />
        <div>
          <label htmlFor="ne-code">Employee code (optional)</label>
          <input id="ne-code" value={form.employeeCode} placeholder="E001" onChange={(e) => set({ employeeCode: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ne-user">Their user account</label>
          <select id="ne-user" value={form.membershipId} onChange={(e) => set({ membershipId: e.target.value })}>
            <option value="">None yet</option>
            {(users.data || []).map((u) => (
              <option key={u.membershipId} value={u.membershipId}>
                {u.email}
              </option>
            ))}
          </select>
          <p className="hint">Linking it lets their manager see their attendance and leave.</p>
        </div>
      </div>

      <h3>Identity and bank</h3>
      <p className="hint">
        Stored encrypted. Screens show only the last four characters; the full number is shown on request to HR and to
        the employee, and every such view is logged.
      </p>
      <div className="grid">
        <div>
          <label htmlFor="ne-pan">PAN</label>
          <input id="ne-pan" value={form.pan} autoComplete="off" placeholder="ABCPE1234F" onChange={(e) => set({ pan: e.target.value })} />
        </div>
        {!form.pan && (
          <div>
            <label htmlFor="ne-pan-pending">Or why it is pending</label>
            <input
              id="ne-pan-pending"
              value={form.panPendingReason}
              placeholder="Applied for on 2 Oct"
              onChange={(e) => set({ panPendingReason: e.target.value })}
            />
          </div>
        )}
        <div>
          <label htmlFor="ne-aadhaar">Aadhaar (optional)</label>
          <input id="ne-aadhaar" inputMode="numeric" autoComplete="off" value={form.aadhaar} onChange={(e) => set({ aadhaar: e.target.value })} />
          <p className="hint">Checked, then only the last four digits are kept.</p>
        </div>
        <div>
          <label htmlFor="ne-uan">UAN (optional)</label>
          <input id="ne-uan" inputMode="numeric" autoComplete="off" value={form.uan} onChange={(e) => set({ uan: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ne-account">Bank account number</label>
          <input id="ne-account" inputMode="numeric" autoComplete="off" value={form.bankAccount} onChange={(e) => set({ bankAccount: e.target.value })} />
        </div>
        {form.bankAccount && (
          <div>
            <label htmlFor="ne-account-again">Account number again</label>
            <input
              id="ne-account-again"
              inputMode="numeric"
              autoComplete="off"
              value={form.bankAccountAgain}
              onChange={(e) => set({ bankAccountAgain: e.target.value })}
            />
          </div>
        )}
        <div>
          <label htmlFor="ne-ifsc">IFSC</label>
          <input id="ne-ifsc" value={form.ifsc} autoComplete="off" placeholder="SBIN0001234" onChange={(e) => set({ ifsc: e.target.value })} />
        </div>
        {form.bankAccount && (
          <div>
            <label htmlFor="ne-holder">Account holder (if not the full name)</label>
            <input id="ne-holder" value={form.bankHolder} onChange={(e) => set({ bankHolder: e.target.value })} />
          </div>
        )}
      </div>

      <ErrorNote>{problem || error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Add employee'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

// ── one employee ────────────────────────────────────────────────────

/** Last four by default; the full value needs a reason, and the server logs it. */
function Revealable({ employeeId, field, label, last4, canReveal }) {
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [value, setValue] = useState(null)
  const { busy, error, run } = useAction()

  async function show(event) {
    event.preventDefault()
    const result = await run(() => people.reveal(employeeId, field, reason))
    if (result) {
      setValue(result.value)
      setAsking(false)
      setReason('')
    }
  }

  return (
    <>
      <dt>{label}</dt>
      <dd>
        {value ? <span className="mono">{value}</span> : <span className="mono">{masked(last4)}</span>}{' '}
        {canReveal && last4 && last4 !== 'NONE' && !asking && (
          <button type="button" className="quiet" onClick={() => (value ? setValue(null) : setAsking(true))}>
            {value ? 'Hide' : 'Show'}
          </button>
        )}
        {asking && (
          <form className="toolbar" onSubmit={show} noValidate>
            <div>
              <label htmlFor={`why-${field}`}>Why do you need the full {label}?</label>
              <input id={`why-${field}`} value={reason} placeholder="Filing the TDS return" onChange={(e) => setReason(e.target.value)} />
            </div>
            <button type="submit" className="secondary" disabled={busy}>
              Show
            </button>
            <button type="button" className="quiet" onClick={() => setAsking(false)}>
              Cancel
            </button>
          </form>
        )}
        <ErrorNote>{error}</ErrorNote>
      </dd>
    </>
  )
}

function IdentifierForm({ employee, onSaved, onCancel }) {
  const [kind, setKind] = useState('pan')
  const [value, setValue] = useState('')
  const [pendingReason, setPendingReason] = useState('')
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    const done = await run(async () => {
      await people.setIdentifier(employee.id, { kind, value, pendingReason })
      return true
    })
    if (done) onSaved()
  }

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>Set or correct an identifier</h2>
      <div className="toolbar">
        <div>
          <label htmlFor="idf-kind">Which</label>
          <select id="idf-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            {Object.entries(IDENTIFIERS).map(([k, label]) => (
              <option key={k} value={k}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="idf-value">Number</label>
          <input id="idf-value" autoComplete="off" value={value} onChange={(e) => setValue(e.target.value)} />
        </div>
        {kind === 'pan' && !value && (
          <div>
            <label htmlFor="idf-pending">Or why it is pending</label>
            <input id="idf-pending" value={pendingReason} onChange={(e) => setPendingReason(e.target.value)} />
          </div>
        )}
      </div>
      <p className="hint">The earlier value is kept as history.</p>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function BankForm({ employee, onSaved, onCancel }) {
  const [form, setForm] = useState({ holderName: '', accountNumber: '', again: '', ifsc: '', bankName: '', branch: '', effectiveFrom: localToday() })
  const [problem, setProblem] = useState('')
  const { busy, error, run } = useAction()
  const set = (patch) => {
    setProblem('')
    setForm((f) => ({ ...f, ...patch }))
  }

  async function submit(event) {
    event.preventDefault()
    if (form.accountNumber.replace(/\s/g, '') !== form.again.replace(/\s/g, '')) {
      setProblem('The two account numbers are different. Type it again carefully.')
      return
    }
    const done = await run(async () => {
      await people.setBank(employee.id, form)
      return true
    })
    if (done) onSaved()
  }

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>New bank account</h2>
      <div className="grid">
        <div>
          <label htmlFor="bk-account">Account number</label>
          <input id="bk-account" inputMode="numeric" autoComplete="off" value={form.accountNumber} onChange={(e) => set({ accountNumber: e.target.value })} />
        </div>
        <div>
          <label htmlFor="bk-again">Account number again</label>
          <input id="bk-again" inputMode="numeric" autoComplete="off" value={form.again} onChange={(e) => set({ again: e.target.value })} />
        </div>
        <div>
          <label htmlFor="bk-ifsc">IFSC</label>
          <input id="bk-ifsc" value={form.ifsc} onChange={(e) => set({ ifsc: e.target.value })} />
        </div>
        <div>
          <label htmlFor="bk-holder">Account holder</label>
          <input id="bk-holder" value={form.holderName} placeholder={employee.legalName} onChange={(e) => set({ holderName: e.target.value })} />
        </div>
        <div>
          <label htmlFor="bk-from">Pay into it from</label>
          <input id="bk-from" type="date" value={form.effectiveFrom} onChange={(e) => set({ effectiveFrom: e.target.value })} />
        </div>
      </div>
      <p className="hint">The old account stays on record, so it is clear which account each month was paid to.</p>
      <ErrorNote>{problem || error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Save account'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function JobForm({ employee, onSaved, onCancel }) {
  const [form, setForm] = useState({
    effectiveFrom: localToday(),
    designation: employee.designation || '',
    departmentId: employee.departmentId || '',
    managerId: employee.managerId || '',
    reason: '',
  })
  const departments = useLoad(people.departments)
  const managers = useLoad(() => people.employees({ status: 'active' }))
  const { busy, error, run } = useAction()
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function submit(event) {
    event.preventDefault()
    const done = await run(async () => {
      await people.changeJob(employee.id, form)
      return true
    })
    if (done) onSaved()
  }

  if (departments.loading || managers.loading) return <p className="muted">Loading…</p>
  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>Change job</h2>
      <div className="grid">
        <div>
          <label htmlFor="job-from">From</label>
          <input id="job-from" type="date" value={form.effectiveFrom} onChange={(e) => set({ effectiveFrom: e.target.value })} />
        </div>
        <JobFields form={form} set={set} departments={departments.data || []} managers={managers.data || []} idPrefix="job" exclude={employee.id} />
        <div>
          <label htmlFor="job-reason">Reason (optional)</label>
          <input id="job-reason" value={form.reason} placeholder="Promotion" onChange={(e) => set({ reason: e.target.value })} />
        </div>
      </div>
      <p className="hint">The current job stays in the history. A date in the future takes effect on that day.</p>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          {busy ? 'Saving…' : 'Save change'}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function LinkForm({ employee, onSaved, onCancel }) {
  const users = useLoad(people.unlinkedUsers)
  const [membershipId, setMembershipId] = useState('')
  const { busy, error, run } = useAction()

  async function submit(event) {
    event.preventDefault()
    const done = await run(async () => {
      await people.link(employee.id, membershipId)
      return true
    })
    if (done) onSaved()
  }

  if (users.loading) return <p className="muted">Loading…</p>
  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>Link to a user account</h2>
      {users.data.length === 0 ? (
        <Empty>Every user already has an employee record. Invite them under User management first.</Empty>
      ) : (
        <div className="toolbar">
          <div>
            <label htmlFor="link-user">User</label>
            <select id="link-user" value={membershipId} onChange={(e) => setMembershipId(e.target.value)}>
              <option value="">Choose…</option>
              {users.data.map((u) => (
                <option key={u.membershipId} value={u.membershipId}>
                  {u.email}
                </option>
              ))}
            </select>
          </div>
          <button type="submit" className="secondary" disabled={busy || !membershipId}>
            Link
          </button>
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/** The signed-in employee's own record: the same page HR sees, without what only HR may do. */
export function MyProfile({ id }) {
  if (!id) return <Empty>There is no employee record linked to your login yet. HR links it under People.</Empty>
  return <EmployeePage id={id} own />
}

function EmployeePage({ id, onBack, canExit, onExit, own }) {
  const employee = useLoad(() => people.employee(id))
  const [view, setView] = useState('details')
  const [form, setForm] = useState(null)

  if (employee.loading) return <p className="muted">Loading…</p>
  if (employee.error) return <ErrorNote>{employee.error}</ErrorNote>
  const e = employee.data
  const active = e.status === 'active'
  const canWrite = e.canWrite && !e.own
  const identifiers = Object.fromEntries((e.identifiers || []).map((i) => [i.kind, i]))
  const saved = () => {
    setForm(null)
    employee.reload()
  }

  return (
    <>
      {!own && (
        <p>
          <button type="button" className="quiet plain" onClick={onBack}>
            ← All employees
          </button>
        </p>
      )}
      {own && <p className="hint">Your record as HR keeps it. To correct something, ask HR.</p>}
      <div className="section-head">
        <div>
          <h2>
            {displayName(e)} <StatusBadge status={e.status === 'exited' ? 'ended' : e.status} />
          </h2>
          <p className="muted">
            {[e.designation, e.departmentName, e.employeeCode].filter(Boolean).join(' · ')}
            {e.status === 'exited' && ` · last working day ${formatDay(e.exitDate)}`}
            {e.leavingOn && ` · leaving ${formatDay(e.leavingOn)}`}
          </p>
          {e.exitId && canExit && (
            <p>
              <button type="button" className="text-link" onClick={() => onExit({ open: e.exitId })}>
                {e.status === 'exited' ? 'Open their exit →' : 'They are leaving: open the exit →'}
              </button>
            </p>
          )}
        </div>
        {canWrite && !form && (
          <div className="form-actions">
            {active && (
              <button type="button" className="outline" onClick={() => setForm('job')}>
                Change job
              </button>
            )}
            {!e.membershipId && active && (
              <button type="button" className="outline" onClick={() => setForm('link')}>
                Link user account
              </button>
            )}
            {active && !e.exitId && canExit && (
              <button type="button" className="quiet danger" onClick={() => onExit({ startFor: e.id })}>
                Start an exit…
              </button>
            )}
          </div>
        )}
      </div>

      {form === 'job' && <JobForm employee={e} onSaved={saved} onCancel={() => setForm(null)} />}
      {form === 'link' && <LinkForm employee={e} onSaved={saved} onCancel={() => setForm(null)} />}
      {form === 'identifier' && <IdentifierForm employee={e} onSaved={saved} onCancel={() => setForm(null)} />}
      {form === 'bank' && <BankForm employee={e} onSaved={saved} onCancel={() => setForm(null)} />}

      <Tabs
        tabs={[
          { id: 'details', label: 'Details' },
          { id: 'job', label: 'Job history' },
        ]}
        current={view}
        onChange={setView}
      />

      {view === 'details' && (
        <div className="panels">
          <section className="panel">
            <h3>Personal</h3>
            <dl className="facts">
              <dt>Full name as on PAN</dt>
              <dd>{e.legalName}</dd>
              {e.preferredName && (
                <>
                  <dt>Preferred name</dt>
                  <dd>{e.preferredName}</dd>
                </>
              )}
              <dt>Date of birth</dt>
              <dd>{e.dateOfBirth ? formatDay(e.dateOfBirth) : '—'}</dd>
              <dt>Gender</dt>
              <dd>{GENDERS[e.gender] || '—'}</dd>
              <dt>Mobile</dt>
              <dd>{e.mobile || '—'}</dd>
              <dt>Personal email</dt>
              <dd>{e.personalEmail || '—'}</dd>
              <dt>User account</dt>
              <dd>{e.email || 'Not linked'}</dd>
            </dl>
          </section>
          <section className="panel">
            <h3>Employment</h3>
            <dl className="facts">
              <dt>Joined</dt>
              <dd>{formatDay(e.joiningDate)}</dd>
              <dt>Type</dt>
              <dd>{TYPES[e.employmentType]}</dd>
              {e.probationEnd && (
                <>
                  <dt>Probation ends</dt>
                  <dd>{formatDay(e.probationEnd)}</dd>
                </>
              )}
              {e.contractEnd && (
                <>
                  <dt>Contract ends</dt>
                  <dd>{formatDay(e.contractEnd)}</dd>
                </>
              )}
              <dt>Designation</dt>
              <dd>{e.designation || '—'}</dd>
              <dt>Department</dt>
              <dd>{e.departmentName || '—'}</dd>
              <dt>Reports to</dt>
              <dd>{e.managerName || '—'}</dd>
              <dt>Employee code</dt>
              <dd>{e.employeeCode || '—'}</dd>
              {e.exitReason && (
                <>
                  <dt>Reason for leaving</dt>
                  <dd>{e.exitReason}</dd>
                </>
              )}
            </dl>
          </section>
          <section className="panel">
            <div className="section-head">
              <h3>Identity</h3>
              {canWrite && !form && (
                <button type="button" className="quiet" onClick={() => setForm('identifier')}>
                  Set or correct
                </button>
              )}
            </div>
            <dl className="facts">
              {['pan', 'aadhaar', 'uan', 'pf_member', 'esi_ip']
                .filter((k) => k === 'pan' || identifiers[k])
                .map((k) =>
                  k === 'aadhaar' ? (
                    <div key={k} className="fact">
                      <dt>Aadhaar</dt>
                      <dd>
                        <span className="mono">{masked(identifiers.aadhaar.last4)}</span>
                        <div className="hint">Only the last four digits are kept.</div>
                      </dd>
                    </div>
                  ) : (
                    <div key={k} className="fact">
                      <Revealable
                        employeeId={e.id}
                        field={k}
                        label={IDENTIFIERS[k]}
                        last4={identifiers[k]?.last4}
                        canReveal={e.canReveal}
                      />
                      {k === 'pan' && identifiers.pan?.pendingReason && (
                        <dd>
                          <Badge tone="warn">pending</Badge> {identifiers.pan.pendingReason}
                        </dd>
                      )}
                    </div>
                  ),
                )}
            </dl>
          </section>
          <section className="panel">
            <div className="section-head">
              <h3>Bank account</h3>
              {canWrite && !form && (
                <button type="button" className="quiet" onClick={() => setForm('bank')}>
                  {e.bankAccount ? 'New account' : 'Add'}
                </button>
              )}
            </div>
            {e.bankAccount ? (
              <dl className="facts">
                <div className="fact">
                  <Revealable employeeId={e.id} field="bank_account" label="Account" last4={e.bankAccount.accountLast4} canReveal={e.canReveal} />
                </div>
                <dt>Holder</dt>
                <dd>{e.bankAccount.holderName}</dd>
                <dt>IFSC</dt>
                <dd className="mono">{e.bankAccount.ifsc}</dd>
                <dt>Paid into since</dt>
                <dd>{formatDay(e.bankAccount.effectiveFrom)}</dd>
              </dl>
            ) : (
              <p className="muted">No bank account yet.</p>
            )}
          </section>
        </div>
      )}

      {view === 'job' && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>From</th>
                <th>Designation</th>
                <th>Department</th>
                <th>Reports to</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              {e.jobHistory.map((j) => (
                <tr key={j.id}>
                  <td>
                    {formatDay(j.effectiveFrom)}
                    {j.effectiveFrom > localToday() && (
                      <>
                        {' '}
                        <Badge tone="warn">upcoming</Badge>
                      </>
                    )}
                  </td>
                  <td>{j.designation}</td>
                  <td>{j.departmentName || '—'}</td>
                  <td>{j.managerName || '—'}</td>
                  <td>
                    {j.reason || '—'}
                    {j.createdByEmail && <div className="hint">by {j.createdByEmail}</div>}
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

// ── departments ─────────────────────────────────────────────────────

function DepartmentRow({ department, all, canWrite, onSaved }) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(department.name)
  const [parentId, setParentId] = useState(department.parentId || '')
  const { busy, error, run } = useAction()

  async function save(active) {
    const done = await run(async () => {
      await people.saveDepartment(department.id, { name, parentId, active })
      return true
    })
    if (done) {
      setEditing(false)
      onSaved()
    }
  }

  return (
    <>
      <tr>
        <td>
          {department.name} {!department.active && <Badge>not in use</Badge>}
        </td>
        <td>{department.parentName || '—'}</td>
        <td>{department.headcount}</td>
        <td className="actions">
          {canWrite && (
            <button type="button" className="quiet" onClick={() => setEditing(!editing)}>
              Change
            </button>
          )}
        </td>
      </tr>
      {editing && (
        <tr className="detail-row">
          <td colSpan={4}>
            <div className="toolbar">
              <div>
                <label htmlFor={`dep-name-${department.id}`}>Name</label>
                <input id={`dep-name-${department.id}`} value={name} onChange={(e) => setName(e.target.value)} />
              </div>
              <div>
                <label htmlFor={`dep-parent-${department.id}`}>Part of</label>
                <select id={`dep-parent-${department.id}`} value={parentId} onChange={(e) => setParentId(e.target.value)}>
                  <option value="">Nothing (top level)</option>
                  {all
                    .filter((d) => d.id !== department.id)
                    .map((d) => (
                      <option key={d.id} value={d.id}>
                        {d.name}
                      </option>
                    ))}
                </select>
              </div>
              <button type="button" className="secondary" disabled={busy} onClick={() => save(department.active)}>
                Save
              </button>
              <button type="button" className="quiet" disabled={busy} onClick={() => save(!department.active)}>
                {department.active ? 'Stop using' : 'Use again'}
              </button>
            </div>
            <ErrorNote>{error}</ErrorNote>
          </td>
        </tr>
      )}
    </>
  )
}

function Departments({ canWrite }) {
  const departments = useLoad(people.departments)
  const [name, setName] = useState('')
  const [parentId, setParentId] = useState('')
  const { busy, error, run } = useAction()

  async function add(event) {
    event.preventDefault()
    const done = await run(async () => {
      await people.saveDepartment(null, { name, parentId })
      return true
    })
    if (done) {
      setName('')
      setParentId('')
      departments.reload()
    }
  }

  if (departments.loading) return <p className="muted">Loading…</p>
  if (departments.error) return <ErrorNote>{departments.error}</ErrorNote>
  return (
    <>
      {canWrite && (
        <form className="panel" onSubmit={add} noValidate>
          <h2>Add a department</h2>
          <div className="toolbar">
            <div>
              <label htmlFor="dep-name">Name</label>
              <input id="dep-name" value={name} placeholder="Engineering" onChange={(e) => setName(e.target.value)} />
            </div>
            <div>
              <label htmlFor="dep-parent">Part of</label>
              <select id="dep-parent" value={parentId} onChange={(e) => setParentId(e.target.value)}>
                <option value="">Nothing (top level)</option>
                {departments.data
                  .filter((d) => d.active)
                  .map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name}
                    </option>
                  ))}
              </select>
            </div>
            <button type="submit" className="secondary" disabled={busy}>
              Add department
            </button>
          </div>
          <ErrorNote>{error}</ErrorNote>
        </form>
      )}
      {departments.data.length === 0 ? (
        <Empty>No departments yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Department</th>
                <th>Part of</th>
                <th>People</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {departments.data.map((d) => (
                <DepartmentRow key={d.id} department={d} all={departments.data} canWrite={canWrite} onSaved={departments.reload} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

// ── the page ────────────────────────────────────────────────────────

export default function People({ me, onNavigate }) {
  const scopes = me.scopes || {}
  const everyone = ['tenant', 'assigned'].includes(scopes['employee.read'])
  const canWrite = scopes['employee.write'] === 'tenant'
  const views = [
    { id: 'employees', label: everyone ? 'Employees' : 'My team' },
    everyone && { id: 'joiners', label: 'Joiners' },
    everyone && { id: 'departments', label: 'Departments' },
    everyone && canWrite && { id: 'import', label: 'Import' },
    everyone && { id: 'setup', label: 'Setup' },
  ].filter(Boolean)
  const [view, setView] = useState('employees')
  const [open, setOpen] = useState(null) // an employment id, or 'new'
  const [joiner, setJoiner] = useState(null) // { id, invitation } of the joiner open, or 'new'

  if (joiner === 'new') {
    return <AddJoiner onSaved={(id, invitation) => setJoiner({ id, invitation })} onCancel={() => setJoiner(null)} />
  }
  if (joiner) {
    return <JoinerPage key={joiner.id} id={joiner.id} initialInvitation={joiner.invitation} onBack={() => setJoiner(null)} />
  }
  if (open === 'new') {
    return <AddEmployee onSaved={(id) => setOpen(id)} onCancel={() => setOpen(null)} />
  }
  if (open) {
    return (
      <EmployeePage
        key={open}
        id={open}
        onBack={() => setOpen(null)}
        canExit={(me.scopes || {})['exit.manage'] === 'tenant' && me.kind === 'tenant'}
        onExit={(what) => {
          handoff.set(what)
          onNavigate('exits')
        }}
      />
    )
  }
  return (
    <>
      <Tabs tabs={views} current={view} onChange={setView} />
      {view === 'employees' && <EmployeeList canWrite={canWrite} onOpen={setOpen} onAdd={() => setOpen('new')} />}
      {view === 'joiners' && <JoinerList canWrite={canWrite} onOpen={(id) => setJoiner({ id })} onAdd={() => setJoiner('new')} />}
      {view === 'departments' && <Departments canWrite={canWrite} />}
      {view === 'import' && <PeopleImport />}
      {view === 'setup' && <PeopleSetup me={me} />}
    </>
  )
}
