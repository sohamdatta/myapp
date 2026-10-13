import { useState } from 'react'
import { exits, leave } from '../../api.js'
import { useAction, useLoad } from '../../hooks.js'
import { ErrorNote } from '../../ui.jsx'

/**
 * Setup → Exits: notice by employment type, whether the manager recommends first, how a notice
 * shortfall is settled by default, which leave can be taken during notice and which is encashed,
 * the retirement age, the absconding rule, and how long former employees keep their access.
 */

const TYPES = [
  ['permanent', 'Permanent'],
  ['probation', 'On probation'],
  ['fixed_term', 'Fixed term'],
  ['intern', 'Intern'],
  ['consultant', 'Consultant'],
]

export function ExitSetup({ canConfigure, onChanged }) {
  const data = useLoad(exits.settings)
  const types = useLoad(leave.types)
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState('')
  const { busy, error, run } = useAction()
  if (data.loading || types.loading) return <p className="muted">Loading…</p>
  if (data.error) return <ErrorNote>{data.error}</ErrorNote>
  const initial = data.data
  const s = form || { ...initial, noticeDays: JSON.parse(initial.noticeDays) }
  const set = (patch) => {
    setSaved('')
    setForm({ ...s, ...patch })
  }
  const leaveTypes = (types.data || []).filter((t) => t.active !== false)
  const toggle = (list, id) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id])
  const noticeLeave = s.noticeLeaveTypeIds // null: every type

  async function save(e) {
    e.preventDefault()
    const done = await run(() =>
      exits.saveSettings({
        noticeDays: s.noticeDays,
        managerStep: s.managerStep,
        noticeLeaveTypeIds: noticeLeave,
        shortfallDefault: s.shortfallDefault,
        retirementAge: Number(s.retirementAge),
        abscondingDays: Number(s.abscondingDays),
        responseDays: Number(s.responseDays),
        formerAccessMonths: Number(s.formerAccessMonths),
        encashableLeaveTypeIds: s.encashableLeaveTypeIds || [],
      }),
    )
    if (done) {
      setForm(null)
      setSaved('Saved.')
      data.reload()
      onChanged()
    }
  }

  return (
    <form className="panel setup-editor" onSubmit={save}>
      <fieldset disabled={!canConfigure}>
        <h3>Notice period, in days</h3>
        <div className="grid">
          {TYPES.map(([k, label]) => (
            <div key={k}>
              <label htmlFor={`ex-notice-${k}`}>{label}</label>
              <input
                id={`ex-notice-${k}`}
                type="number"
                min="0"
                max="180"
                value={s.noticeDays[k] ?? ''}
                onChange={(e) => set({ noticeDays: { ...s.noticeDays, [k]: e.target.value } })}
              />
            </div>
          ))}
        </div>
        <p className="hint">An employee still on probation owes the probation notice, whatever their type.</p>

        <h3>Resignations</h3>
        <label className="check">
          <input type="checkbox" checked={s.managerStep} onChange={(e) => set({ managerStep: e.target.checked })} />
          The manager recommends a last day before HR accepts
        </label>
        <div className="grid">
          <div>
            <label htmlFor="ex-shortfall">Notice not served is, by default</label>
            <select id="ex-shortfall" value={s.shortfallDefault} onChange={(e) => set({ shortfallDefault: e.target.value })}>
              <option value="leave_then_owed">Set against earned leave</option>
              <option value="owed">Owed by the employee</option>
              <option value="waived">Waived</option>
            </select>
          </div>
        </div>

        <h3>Leave</h3>
        <label className="check">
          <input
            type="checkbox"
            checked={noticeLeave === null || noticeLeave === undefined}
            onChange={(e) => set({ noticeLeaveTypeIds: e.target.checked ? null : [] })}
          />
          Any leave can be taken during the notice period
        </label>
        {noticeLeave && (
          <fieldset className="days">
            <legend>Leave allowed during notice</legend>
            {leaveTypes.map((t) => (
              <label key={t.id} className="check">
                <input type="checkbox" checked={noticeLeave.includes(t.id)} onChange={() => set({ noticeLeaveTypeIds: toggle(noticeLeave, t.id) })} />
                {t.name}
              </label>
            ))}
          </fieldset>
        )}
        <fieldset className="days">
          <legend>Leave paid out at exit</legend>
          {leaveTypes.length === 0 && <span className="hint">No leave types yet.</span>}
          {leaveTypes.map((t) => (
            <label key={t.id} className="check">
              <input
                type="checkbox"
                aria-label={`Encash ${t.name}`}
                checked={(s.encashableLeaveTypeIds || []).includes(t.id)}
                onChange={() => set({ encashableLeaveTypeIds: toggle(s.encashableLeaveTypeIds || [], t.id) })}
              />
              {t.name}
            </label>
          ))}
        </fieldset>

        <h3>Other exits and afterwards</h3>
        <div className="grid">
          <div>
            <label htmlFor="ex-retire">Retirement age</label>
            <input id="ex-retire" type="number" min="50" max="75" value={s.retirementAge} onChange={(e) => set({ retirementAge: e.target.value })} />
          </div>
          <div>
            <label htmlFor="ex-abscond">Days absent without word before absconding</label>
            <input id="ex-abscond" type="number" min="3" max="60" value={s.abscondingDays} onChange={(e) => set({ abscondingDays: e.target.value })} />
          </div>
          <div>
            <label htmlFor="ex-response">Days to answer a show-cause notice</label>
            <input id="ex-response" type="number" min="1" max="30" value={s.responseDays} onChange={(e) => set({ responseDays: e.target.value })} />
          </div>
          <div>
            <label htmlFor="ex-access">Former employees keep access for (months)</label>
            <input id="ex-access" type="number" min="0" max="36" value={s.formerAccessMonths} onChange={(e) => set({ formerAccessMonths: e.target.value })} />
          </div>
        </div>
      </fieldset>
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">{saved}</p>}
      {canConfigure && (
        <div className="form-actions">
          <button type="submit" className="secondary" disabled={busy}>
            Save exit settings
          </button>
        </div>
      )}
    </form>
  )
}
