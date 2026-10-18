import { useState } from 'react'
import { performance } from '../api.js'
import { formatDate, useAction, useLoad, wholeNumber } from '../hooks.js'
import { ErrorNote, Tabs } from '../ui.jsx'
import ProbationReviews from './Probation.jsx'

/**
 * Performance, for managers (their team) and HR (everyone): one tab per part of the module that
 * the person's permissions reach.
 */
export default function Performance({ me }) {
  const scopes = me.scopes || {}
  const canManage = scopes['performance.manage'] === 'tenant' && me.kind === 'tenant'
  const tabs = [{ id: 'probation', label: 'Probation' }, canManage && { id: 'setup', label: 'Setup' }].filter(Boolean)
  const [tab, setTab] = useState(tabs[0].id)
  return (
    <>
      {tabs.length > 1 && <Tabs tabs={tabs} current={tab} onChange={setTab} />}
      {tab === 'probation' && <ProbationReviews />}
      {tab === 'setup' && <PerformanceSetup />}
    </>
  )
}

function PerformanceSetup() {
  const settings = useLoad(performance.probationSettings)
  if (settings.loading) return <p className="muted">Loading…</p>
  if (settings.error) return <ErrorNote>{settings.error}</ErrorNote>
  return <ProbationSettings settings={settings.data} onSaved={settings.reload} />
}

function ProbationSettings({ settings, onSaved }) {
  const [days, setDays] = useState(settings.checkinDays.join(', '))
  const [recommend, setRecommend] = useState(String(settings.recommendDays))
  const [saved, setSaved] = useState(false)
  const { busy, error, run } = useAction()

  async function save(event) {
    event.preventDefault()
    setSaved(false)
    const ok = await run(() => {
      const list = days
        .split(',')
        .map((d) => d.trim())
        .filter(Boolean)
        .map((d) => wholeNumber(d, 'Each check-in day'))
      return performance.saveProbationSettings({ checkinDays: list, recommendDays: wholeNumber(recommend, 'The recommendation days') })
    })
    if (ok) {
      setSaved(true)
      onSaved()
    }
  }

  return (
    <form className="panel" onSubmit={save} noValidate>
      <h3>Probation</h3>
      <p className="hint">
        Every employee on probation gets a review: check-ins by their manager, a recommendation before the probation ends, and HR's
        decision. Changes apply to reviews that start from now on.
      </p>
      <div className="grid">
        <div>
          <label htmlFor="perf-checkin-days">Check-ins, in days from joining</label>
          <input id="perf-checkin-days" value={days} onChange={(e) => setDays(e.target.value)} />
          <p className="hint">Separate them with commas, such as 30, 60, 90. Only those before the probation ends are asked for.</p>
        </div>
        <div>
          <label htmlFor="perf-recommend-days">Ask for the recommendation this many days before the end</label>
          <input id="perf-recommend-days" inputMode="numeric" value={recommend} onChange={(e) => setRecommend(e.target.value)} />
        </div>
      </div>
      <p className="hint">
        The confirmation and probation extension letters are under People → Setup → Letters, with the other letters.
      </p>
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">Saved.</p>}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save
        </button>
        {settings.updatedAt && <span className="hint">Last changed {formatDate(settings.updatedAt)}</span>}
      </div>
    </form>
  )
}
