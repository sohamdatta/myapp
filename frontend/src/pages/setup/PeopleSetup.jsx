import { useState } from 'react'
import { setup } from '../../api.js'
import { useLoad } from '../../hooks.js'
import { Tabs } from '../../ui.jsx'
import { FlowsSetup } from './SetupFlows.jsx'
import { FormSetup } from './SetupForm.jsx'
import { ChecklistsSetup, CodesSetup, DocumentsSetup, LettersSetup, MessagesSetup, PoliciesSetup, SettingsSetup } from './SetupLists.jsx'
import { LastChange } from './SetupShared.jsx'
import { useSetupLookups } from './setupText.js'

/**
 * People → Setup: everything about onboarding the Organization can change,
 * one tab per area, each saying who changed it last. Reading it needs
 * employee.read across the Organization; changing it needs onboarding.configure.
 */
const AREAS = [
  ['flows', 'Flows'],
  ['form', 'Form'],
  ['documents', 'Documents'],
  ['checklists', 'Checklists'],
  ['letters', 'Letters'],
  ['policies', 'Policies'],
  ['messages', 'Messages'],
  ['codes', 'Employee codes'],
  ['settings', 'Settings'],
]

export default function PeopleSetup({ me }) {
  const canConfigure = me.permissions.includes('onboarding.configure')
  const [area, setArea] = useState('flows')
  const lookups = useSetupLookups()
  const changes = useLoad(setup.changes)
  if (lookups.loading) return <p className="muted">Loading…</p>
  const props = { canConfigure, lookups, onChanged: changes.reload }
  return (
    <div className="setup">
      <Tabs tabs={AREAS.map(([id, label]) => ({ id, label }))} current={area} onChange={setArea} />
      {!canConfigure && <p className="notice">You can see how onboarding is set up; changing it needs the Org Admin or an HR Admin.</p>}
      {changes.data && <LastChange change={changes.data[area]} />}
      {area === 'flows' && <FlowsSetup {...props} />}
      {area === 'form' && <FormSetup {...props} />}
      {area === 'documents' && <DocumentsSetup {...props} />}
      {area === 'checklists' && <ChecklistsSetup {...props} />}
      {area === 'letters' && <LettersSetup {...props} />}
      {area === 'policies' && <PoliciesSetup {...props} />}
      {area === 'messages' && <MessagesSetup {...props} />}
      {area === 'codes' && <CodesSetup {...props} />}
      {area === 'settings' && <SettingsSetup {...props} />}
    </div>
  )
}
