import { useState } from 'react'
import { Tabs } from '../ui.jsx'
import ProbationReviews from './Probation.jsx'
import ReviewCycles from './ReviewCycles.jsx'
import PerformanceSetup from './PerformanceSetup.jsx'
import PerformanceTeam, { DepartmentGoals } from './PerformanceTeam.jsx'

/**
 * Performance, for managers (their team) and HR (everyone): one tab per part of the module that
 * the person's permissions reach.
 */
export default function Performance({ me }) {
  const scopes = me.scopes || {}
  const canManage = scopes['performance.manage'] === 'tenant' && me.kind === 'tenant'
  const tabs = [
    { id: 'team', label: 'Team' },
    { id: 'reviews', label: 'Reviews' },
    { id: 'probation', label: 'Probation' },
    { id: 'department-goals', label: 'Department goals' },
    canManage && { id: 'setup', label: 'Setup' },
  ].filter(Boolean)
  const [tab, setTab] = useState(tabs[0].id)
  return (
    <>
      <Tabs tabs={tabs} current={tab} onChange={setTab} />
      {tab === 'team' && <PerformanceTeam />}
      {tab === 'reviews' && <ReviewCycles canManage={canManage} />}
      {tab === 'probation' && <ProbationReviews />}
      {tab === 'department-goals' && <DepartmentGoals canManage={canManage} />}
      {tab === 'setup' && <PerformanceSetup />}
    </>
  )
}
