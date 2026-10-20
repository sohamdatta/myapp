import { useState } from 'react'
import { Tabs } from '../ui.jsx'
import { People as WhoWorksWhere, Setup as CalendarSetup } from './AttendanceSetup.jsx'

/**
 * Without the Attendance module: where people work, on which shift and weekly offs. Payroll takes the
 * state for professional tax from the location, and working days from the shift and holidays.
 */
export default function WorkCalendar({ me }) {
  const [tab, setTab] = useState('people')
  const canManage = (me.scopes || {})['attendance.manage'] === 'tenant' && me.kind === 'tenant'
  return (
    <>
      <Tabs
        tabs={[
          { id: 'people', label: 'People' },
          { id: 'setup', label: 'Locations and shifts' },
        ]}
        current={tab}
        onChange={setTab}
      />
      <p className="hint">
        Payroll takes each person's state from their work location, and working days from their shift, weekly offs and holidays.
      </p>
      {tab === 'people' && <WhoWorksWhere canManage={canManage} attendanceOn={false} />}
      {tab === 'setup' && <CalendarSetup canManage={canManage} attendanceOn={false} />}
    </>
  )
}
