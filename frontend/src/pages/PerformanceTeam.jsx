import { useState } from 'react'
import { people, performance } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote, Tabs } from '../ui.jsx'
import { FeedbackList, GiveFeedback } from './FeedbackPanel.jsx'
import GoalsPanel from './GoalsPanel.jsx'
import OneToOnePanel from './OneToOnePanel.jsx'
import { GOAL_STATUS, MEASURES } from './performanceText.js'

/** The Team tab: a manager's reports (or everyone, for HR), and one person at a time. */
export default function PerformanceTeam() {
  const [open, setOpen] = useState(null)
  const team = useLoad(performance.team)
  if (open) return <Person person={open} onBack={() => (setOpen(null), team.reload())} />
  if (team.loading) return <p className="muted">Loading…</p>
  if (team.error) return <ErrorNote>{team.error}</ErrorNote>
  const t = team.data
  return (
    <>
      <p className="hint">Goals for {t.period}.</p>
      {t.people.length === 0 && <Empty>Nobody reports to you.</Empty>}
      {t.people.length > 0 && (
        <div className="table-wrap">
          <table className="stack">
            <thead>
              <tr>
                <th>Name</th>
                <th>Goals</th>
                <th>Progress</th>
                <th>Probation</th>
                <th>Last one-to-one</th>
              </tr>
            </thead>
            <tbody>
              {t.people.map((p) => {
                const [tone, words] = GOAL_STATUS[p.goalStatus] || ['neutral', p.goalStatus]
                return (
                  <tr key={p.id} data-person={p.name}>
                    <td>
                      <button type="button" className="text-link" onClick={() => setOpen(p)}>
                        {p.name}
                      </button>
                      <div className="hint">{[p.designation, p.department, !p.isReport && p.manager && `reports to ${p.manager}`].filter(Boolean).join(' · ')}</div>
                    </td>
                    <td>
                      <Badge tone={tone}>{words}</Badge>
                      {p.goals > 0 && <div className="hint">{p.goals} goals</div>}
                    </td>
                    <td>{p.progress != null ? `${p.progress}%` : '—'}</td>
                    <td>{p.probationEnd ? `until ${formatDay(p.probationEnd)}` : '—'}</td>
                    <td>{p.isReport ? (p.lastOneToOne ? formatDay(p.lastOneToOne) : 'Never') : '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function Person({ person, onBack }) {
  const tabs = [
    { id: 'goals', label: 'Goals' },
    person.isReport && { id: 'one-to-ones', label: 'One-to-ones' },
    { id: 'feedback', label: 'Feedback' },
  ].filter(Boolean)
  const [tab, setTab] = useState('goals')
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← Team
        </button>
      </p>
      <div className="section-head">
        <div>
          <h2>{person.name}</h2>
          <p className="muted">{[person.designation, person.department, person.employeeCode].filter(Boolean).join(' · ')}</p>
        </div>
      </div>
      <Tabs tabs={tabs} current={tab} onChange={setTab} />
      {tab === 'goals' && <GoalsPanel employmentId={person.id} />}
      {tab === 'one-to-ones' && <OneToOnePanel withId={person.id} />}
      {tab === 'feedback' && <PersonFeedback person={person} />}
    </>
  )
}

function PersonFeedback({ person }) {
  const list = useLoad(() => performance.feedbackAbout(person.id))
  return (
    <div className="panels">
      <section className="panel">
        <h3>Feedback about {person.name}</h3>
        {list.loading && <p className="muted">Loading…</p>}
        <ErrorNote>{list.error}</ErrorNote>
        {list.data && <FeedbackList items={list.data} onChanged={list.reload} empty="No feedback you can read yet." />}
      </section>
      <GiveFeedback to={person.id} toName={person.name} onGiven={list.reload} />
    </div>
  )
}

/** Department goals, which people's goals can support: HR adds and changes them; everyone else reads them. */
export function DepartmentGoals({ canManage }) {
  const goals = useLoad(() => performance.departmentGoals())
  const departments = useLoad(() => (canManage ? people.departments() : Promise.resolve([])))
  const [adding, setAdding] = useState(false)
  if (goals.loading) return <p className="muted">Loading…</p>
  if (goals.error) return <ErrorNote>{goals.error}</ErrorNote>
  return (
    <section className="panel">
      <div className="section-head">
        <h3>Department goals</h3>
        {canManage && !adding && (
          <button type="button" className="outline" onClick={() => setAdding(true)}>
            Add a department goal
          </button>
        )}
      </div>
      <p className="hint">People in a department can link their own goals to these, as can a manager's team to the manager's goals.</p>
      {adding && (
        <DepartmentGoalForm
          departments={(departments.data || []).filter((d) => d.active)}
          onDone={(saved) => {
            setAdding(false)
            if (saved) goals.reload()
          }}
        />
      )}
      {goals.data.length === 0 && !adding && <Empty>No department goals for this period.</Empty>}
      {goals.data.length > 0 && (
        <ul className="plain-list department-goals">
          {goals.data.map((g) => (
            <li key={g.id} data-department-goal={g.title}>
              <strong>{g.department}</strong>: {g.title}{' '}
              <span className="hint">
                {MEASURES[g.measure]}
                {g.measure === 'number' && `, target ${Number(g.target)} ${g.unit || ''}`} · {g.supporting} supporting
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function DepartmentGoalForm({ departments, onDone }) {
  const [v, setV] = useState({ departmentId: '', title: '', measure: 'rating', target: '', unit: '' })
  const { busy, error, run } = useAction()
  const set = (patch) => setV({ ...v, ...patch })
  return (
    <form
      className="goal-form"
      onSubmit={async (e) => {
        e.preventDefault()
        const ok = await run(() =>
          performance.addGoal({
            departmentId: v.departmentId,
            title: v.title,
            measure: v.measure,
            target: v.measure === 'number' && v.target !== '' ? Number(v.target) : null,
            unit: v.measure === 'number' ? v.unit : null,
          }),
        )
        if (ok) onDone(true)
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="dg-department">Department</label>
          <select id="dg-department" value={v.departmentId} onChange={(e) => set({ departmentId: e.target.value })}>
            <option value="">Choose…</option>
            {departments.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="dg-measure">Measured by</label>
          <select id="dg-measure" value={v.measure} onChange={(e) => set({ measure: e.target.value })}>
            {Object.entries(MEASURES).map(([k, label]) => (
              <option key={k} value={k}>
                {label}
              </option>
            ))}
          </select>
        </div>
        {v.measure === 'number' && (
          <>
            <div>
              <label htmlFor="dg-target">Target</label>
              <input id="dg-target" inputMode="decimal" value={v.target} onChange={(e) => set({ target: e.target.value })} />
            </div>
            <div>
              <label htmlFor="dg-unit">Unit</label>
              <input id="dg-unit" value={v.unit} onChange={(e) => set({ unit: e.target.value })} />
            </div>
          </>
        )}
      </div>
      <label htmlFor="dg-title">Goal</label>
      <input id="dg-title" value={v.title} onChange={(e) => set({ title: e.target.value })} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Add
        </button>
        <button type="button" className="quiet" onClick={() => onDone(false)}>
          Cancel
        </button>
      </div>
    </form>
  )
}
