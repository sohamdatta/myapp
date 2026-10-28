import { useState } from 'react'
import { gettingStarted } from '../api.js'
import { useAction } from '../hooks.js'
import { ErrorNote } from '../ui.jsx'
import { useConfirm, useToast } from '../feedbackContext.js'
import { CARRY_OPTIONS, GS } from './gettingStartedText.js'

const clock = (time) => time.slice(0, 5)

/**
 * Choosing a starting setup for an empty Organization: the kind of business, the main location, and
 * the leave numbers, each shown in words before anything is added. Only sections that are still empty
 * are offered, so it never overwrites what someone has already set up.
 */
export default function StarterPicker({ status, onApplied }) {
  const t = GS.picker
  const { open } = status
  const [id, setId] = useState(status.starters[0].id)
  const [place, setPlace] = useState('')
  const [state, setState] = useState('')
  const [edits, setEdits] = useState({})
  const [problem, setProblem] = useState('')
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()
  const starter = status.starters.find((s) => s.id === id)

  const leave = starter.leave.map((l) => ({
    ...l,
    days: edits[`${id}.${l.code}`]?.days ?? String(l.days),
    carry: edits[`${id}.${l.code}`]?.carry ?? l.carry,
  }))
  const change = (code, patch) => setEdits({ ...edits, [`${id}.${code}`]: { ...edits[`${id}.${code}`], ...patch } })

  async function submit() {
    if (open.calendar && (!place.trim() || !state)) {
      setProblem(t.needPlace)
      return
    }
    setProblem('')
    const sure = await confirm({
      title: t.askTitle(starter.title),
      body: t.ask(t.what(open, starter, open.leave)),
      confirmLabel: t.askLabel,
      cancelLabel: t.cancel,
    })
    if (!sure) return
    const changes = leave
      .filter((l) => edits[`${id}.${l.code}`])
      .map((l) => ({ code: l.code, days: Number(l.days), carry: l.carry }))
    const done = await run(() =>
      gettingStarted.apply({ starter: id, locationName: open.calendar ? place : null, stateCode: open.calendar ? state : null, leave: changes }),
    )
    if (done) {
      toast(t.toast(starter.title))
      onApplied()
    }
  }

  return (
    <div className="starter">
      {!open.calendar && <p className="hint">{t.partial.leaveOnly}</p>}
      {!open.leave && status.leaveOn && <p className="hint">{t.partial.calendarOnly}</p>}
      {!status.leaveOn && <p className="hint">{t.leaveOffNote}</p>}

      <fieldset className="starter-choices">
        <legend>{t.legend}</legend>
        {status.starters.map((s) => (
          <label key={s.id} className={s.id === id ? 'starter-choice starter-choice-current' : 'starter-choice'}>
            <input type="radio" name="starter" value={s.id} checked={s.id === id} onChange={() => setId(s.id)} />
            <span>
              <strong>{s.title}</strong>
              <span className="hint">{s.who}</span>
              <span className="hint">{t.summary(s)}</span>
            </span>
          </label>
        ))}
      </fieldset>

      {open.calendar && (
        <fieldset className="starter-place">
          <legend>{t.placeLegend}</legend>
          <div className="grid">
            <div>
              <label htmlFor="starter-place-name">{t.placeName}</label>
              <input id="starter-place-name" value={place} maxLength={80} onChange={(e) => setPlace(e.target.value)} aria-describedby="starter-place-hint" />
              <p id="starter-place-hint" className="hint">
                {t.placeNameHint}
              </p>
            </div>
            <div>
              <label htmlFor="starter-place-state">{t.placeState}</label>
              <select id="starter-place-state" value={state} onChange={(e) => setState(e.target.value)} aria-describedby="starter-state-hint">
                <option value="">{t.placeStateChoose}</option>
                {status.states.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.name}
                  </option>
                ))}
              </select>
              <p id="starter-state-hint" className="hint">
                {t.placeStateHint}
              </p>
            </div>
          </div>
        </fieldset>
      )}

      <section className="starter-what" aria-label={t.whatTitle(starter.title)}>
        <h4>{t.whatTitle(starter.title)}</h4>
        {open.calendar && (
          <div>
            <h5>{t.shifts}</h5>
            <ul className="plain-list">
              {starter.shifts.map((s) => (
                <li key={s.name}>{t.shiftLine({ ...s, start: clock(s.start), end: clock(s.end) })}</li>
              ))}
            </ul>
            {status.attendanceOn && <p className="hint">{t.punchNote}</p>}
            <h5>{t.holidaysTitle}</h5>
            <p>{t.holidays}</p>
            <h5>{t.weekTitle}</h5>
            <p>{t.week}</p>
          </div>
        )}
        {open.leave && (
          <div>
            <h5>{t.leaveTitle}</h5>
            <p className="hint">{t.leaveHint}</p>
            {leave.map((l) => (
              <div key={l.code} className="leave-line">
                <label htmlFor={`leave-days-${l.code}`}>{t.leaveName(l.name)}</label>
                <input
                  id={`leave-days-${l.code}`}
                  type="number"
                  inputMode="decimal"
                  min="0.5"
                  max="60"
                  step="0.5"
                  value={l.days}
                  aria-label={t.leaveDays(l.name)}
                  onChange={(e) => change(l.code, { days: e.target.value })}
                />
                <span>{t.leaveDaysUnit}</span>
                <span>{t.leaveUnused}</span>
                <select value={l.carry} aria-label={t.leaveCarry(l.name)} onChange={(e) => change(l.code, { carry: e.target.value })}>
                  {CARRY_OPTIONS.map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
        )}
      </section>

      <ErrorNote>{problem || error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="primary" disabled={busy} onClick={submit}>
          {busy ? t.applying : t.apply}
        </button>
      </div>
    </div>
  )
}
