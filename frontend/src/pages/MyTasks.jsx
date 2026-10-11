import { tasks } from '../api.js'
import { useLoad } from '../hooks.js'
import { Empty, ErrorNote } from '../ui.jsx'
import { TaskRow } from './TaskList.jsx'

/**
 * Onboarding tasks waiting for the signed-in person, whoever they are: IT
 * preparing a laptop, a manager planning a first week, an approver. Grouped
 * by joiner, soonest joining first.
 */
export default function MyTasks() {
  const list = useLoad(tasks.mine)
  if (list.loading) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  if (list.data.length === 0) return <Empty>Nothing waiting for you. New joiners' tasks will appear here.</Empty>

  const groups = []
  for (const t of list.data) {
    let group = groups.find((g) => g.id === t.onboardingId)
    if (!group) groups.push((group = { id: t.onboardingId, tasks: [] }))
    group.tasks.push(t)
  }
  return (
    <div className="task-groups">
      {groups.map((g) => (
        <section key={g.id} className="panel task-group">
          {g.tasks.map((t) => (
            <TaskRow key={t.id} task={t} showJoiner onChanged={list.reload} />
          ))}
        </section>
      ))}
    </div>
  )
}
