import { exitTasks, tasks } from '../api.js'
import { formatDay, useLoad } from '../hooks.js'
import { Empty, ErrorNote } from '../ui.jsx'
import { TaskRow } from './TaskList.jsx'

/**
 * Tasks waiting for the signed-in person, whoever they are: IT preparing a laptop for a joiner
 * or collecting it from a leaver, a manager planning a first week or a handover, an approver.
 * Grouped by person, joiners first.
 */
export default function MyTasks() {
  const list = useLoad(() => Promise.all([tasks.mine(), exitTasks.mine()]))
  if (list.loading) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  const [joining, leaving] = list.data
  if (joining.length === 0 && leaving.length === 0) return <Empty>Nothing waiting for you. Joiners' and leavers' tasks will appear here.</Empty>

  const groups = []
  for (const t of joining) {
    let group = groups.find((g) => g.id === t.onboardingId)
    if (!group) groups.push((group = { id: t.onboardingId, tasks: [] }))
    group.tasks.push(t)
  }
  for (const t of leaving) {
    let group = groups.find((g) => g.id === t.exitCaseId)
    if (!group) groups.push((group = { id: t.exitCaseId, exit: true, tasks: [] }))
    group.tasks.push(t)
  }
  return (
    <div className="task-groups">
      {groups.map((g) => (
        <section key={g.id} className="panel task-group">
          {g.tasks.map((t) =>
            g.exit ? (
              <TaskRow
                key={t.id}
                task={t}
                taskApi={exitTasks}
                context={`${t.leaverPreferredName || t.leaverName}, leaving ${formatDay(t.lastWorkingDay)}`}
                onChanged={list.reload}
              />
            ) : (
              <TaskRow key={t.id} task={t} showJoiner onChanged={list.reload} />
            ),
          )}
        </section>
      ))}
    </div>
  )
}
