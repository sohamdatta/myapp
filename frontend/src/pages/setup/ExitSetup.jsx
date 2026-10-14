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
  const s = form || { ...initial, noticeDays: JSON.parse(initial.noticeDays), questions: JSON.parse(initial.interviewQuestions || '[]') }
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
        interviewQuestions: s.questions,
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
          {leaveTypes.filter((t) => t.paid !== false).map((t) => (
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

        <p className="hint">How long a former employee's records are kept is set under Setup → Settings.</p>

        <div className="subpanel">
        <h3>
          Exit interview <span className="hint">version {initial.interviewVersion}</span>
        </h3>
        <p className="hint">
          Sent by HR to the leaving employee, and read only by HR. Changing the questions makes a new version; interviews already sent keep
          theirs.
        </p>
        <InterviewQuestions value={s.questions} onChange={(questions) => set({ questions })} />
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

const QUESTION_TYPES = { rating: 'A rating, 1 to 5', choice: 'One of some choices', text: 'Their own words' }

function InterviewQuestions({ value, onChange }) {
  const setAt = (i, patch) => onChange(value.map((q, j) => (j === i ? { ...q, ...patch } : q)))
  const move = (i, d) => {
    const next = [...value]
    ;[next[i], next[i + d]] = [next[i + d], next[i]]
    onChange(next)
  }
  return (
    <div className="rows interview-questions">
      {value.map((q, i) => (
        <div key={q.id || i} className="question-item" data-question={i + 1}>
          <div className="grid">
            <div>
              <label htmlFor={`iq-text-${i}`}>Question {i + 1}</label>
              <input id={`iq-text-${i}`} value={q.text} maxLength={200} onChange={(e) => setAt(i, { text: e.target.value })} />
            </div>
            <div>
              <label htmlFor={`iq-type-${i}`}>Answered as</label>
              <select id={`iq-type-${i}`} value={q.type} onChange={(e) => setAt(i, { type: e.target.value, choices: q.choices || ['', ''] })}>
                {Object.entries(QUESTION_TYPES).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {q.type === 'choice' && (
            <>
              <label htmlFor={`iq-choices-${i}`}>Choices, one per line</label>
              <textarea
                id={`iq-choices-${i}`}
                rows={Math.min(8, (q.choices || []).length + 1)}
                value={(q.choices || []).join('\n')}
                onChange={(e) => setAt(i, { choices: e.target.value.split('\n') })}
              />
            </>
          )}
          <div className="form-actions">
            <button type="button" className="quiet" disabled={i === 0} onClick={() => move(i, -1)} aria-label={`Move question ${i + 1} up`}>
              ↑
            </button>
            <button
              type="button"
              className="quiet"
              disabled={i === value.length - 1}
              onClick={() => move(i, 1)}
              aria-label={`Move question ${i + 1} down`}
            >
              ↓
            </button>
            <button type="button" className="quiet" onClick={() => onChange(value.filter((_, j) => j !== i))}>
              Remove
            </button>
          </div>
        </div>
      ))}
      <button type="button" className="outline" onClick={() => onChange([...value, { type: 'rating', text: '' }])}>
        Add a question
      </button>
    </div>
  )
}
