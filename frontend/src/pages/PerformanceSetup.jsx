import { useState } from 'react'
import { performance } from '../api.js'
import { formatDate, useAction, useLoad, wholeNumber } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'
import { RuleEditor } from './setup/SetupShared.jsx'
import { describeRules, useSetupLookups } from './setup/setupText.js'

/** Performance setup, for HR: the goal year and one-to-ones, the rating scale, competencies, review forms, the merit table, and probation. */
export default function PerformanceSetup() {
  const setup = useLoad(performance.setup)
  const lookups = useSetupLookups()
  const [view, setView] = useState(null)
  const s = view || setup.data
  if ((setup.loading && !s) || lookups.loading) return <p className="muted">Loading…</p>
  if (setup.error) return <ErrorNote>{setup.error}</ErrorNote>
  return (
    <div className="perf-setup">
      <General s={s} onSaved={setView} />
      <Scale scale={s.scale} onSaved={setView} />
      <Competencies s={s} lookups={lookups} onSaved={setView} />
      <ReviewForms />
      <MeritSettings />
      <ProbationSettings />
    </div>
  )
}

function General({ s, onSaved }) {
  const [year, setYear] = useState(String(s.goalYearStart))
  const [days, setDays] = useState(String(s.oneToOneDays))
  const [nudge, setNudge] = useState(String(s.oneToOneNudgeDays))
  const [saved, setSaved] = useState(false)
  const { busy, error, run } = useAction()
  return (
    <form
      className="panel"
      onSubmit={async (e) => {
        e.preventDefault()
        setSaved(false)
        const next = await run(() =>
          performance.saveGeneral({
            goalYearStart: Number(year),
            oneToOneDays: wholeNumber(days, 'The one-to-one days'),
            oneToOneNudgeDays: wholeNumber(nudge, 'The reminder days'),
          }),
        )
        if (next) {
          setSaved(true)
          onSaved(next)
        }
      }}
    >
      <h3>Goals and one-to-ones</h3>
      <div className="grid">
        <div>
          <label htmlFor="perf-year">The goal year starts</label>
          <select id="perf-year" value={year} onChange={(e) => setYear(e.target.value)}>
            <option value="4">1 April (the financial year)</option>
            <option value="1">1 January</option>
          </select>
          <p className="hint">This cannot change once goals have been set.</p>
        </div>
        <div>
          <label htmlFor="perf-one-days">Suggest a one-to-one every (days)</label>
          <input id="perf-one-days" inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value)} />
        </div>
        <div>
          <label htmlFor="perf-nudge-days">Remind a manager after (days without one)</label>
          <input id="perf-nudge-days" inputMode="numeric" value={nudge} onChange={(e) => setNudge(e.target.value)} />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">Saved.</p>}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save
        </button>
        {s.updatedAt && <span className="hint">Last changed {formatDate(s.updatedAt)}</span>}
      </div>
    </form>
  )
}

function Scale({ scale, onSaved }) {
  const [editing, setEditing] = useState(false)
  const [levels, setLevels] = useState(scale.levels.map((l) => ({ ...l, description: l.description || '' })))
  const { busy, error, run } = useAction()
  const resize = (n) => {
    const next = Array.from({ length: n }, (_, i) => {
      const value = n - i
      return levels.find((l) => l.value === value) || { value, label: '', description: '' }
    })
    setLevels(next)
  }
  return (
    <section className="panel" data-testid="rating-scale">
      <div className="section-head">
        <h3>
          Rating scale <Badge>version {scale.version}</Badge>
        </h3>
        {!editing && (
          <button type="button" className="outline" onClick={() => setEditing(true)}>
            Change
          </button>
        )}
      </div>
      {!editing ? (
        <dl className="facts">
          {scale.levels.map((l) => (
            <div key={l.value} style={{ display: 'contents' }}>
              <dt>
                {l.value} · {l.label}
              </dt>
              <dd>{l.description || '—'}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            const next = await run(() => performance.saveScale(levels))
            if (next) {
              setEditing(false)
              onSaved(next)
            }
          }}
        >
          <p className="hint">Saving makes a new version. Reviews keep the version they were rated on.</p>
          <label htmlFor="scale-size">Levels</label>
          <select id="scale-size" value={levels.length} onChange={(e) => resize(Number(e.target.value))}>
            {[3, 4, 5, 6, 7].map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
          {levels.map((l, i) => (
            <div className="grid" key={l.value}>
              <div>
                <label htmlFor={`level-${l.value}`}>Level {l.value}</label>
                <input
                  id={`level-${l.value}`}
                  value={l.label}
                  onChange={(e) => setLevels(levels.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))}
                />
              </div>
              <div>
                <label htmlFor={`level-${l.value}-description`}>What it means</label>
                <input
                  id={`level-${l.value}-description`}
                  value={l.description}
                  onChange={(e) => setLevels(levels.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))}
                />
              </div>
            </div>
          ))}
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              Save as a new version
            </button>
            <button type="button" className="quiet" onClick={() => setEditing(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </section>
  )
}

function Competencies({ s, lookups, onSaved }) {
  const [editing, setEditing] = useState(null) // a competency, {} for new
  const [editingSet, setEditingSet] = useState(null)
  const { busy, error, run } = useAction()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const startCompetency = (k) => {
    setEditing(k)
    setName(k.name || '')
    setDescription(k.description || '')
  }
  const names = Object.fromEntries(s.competencies.map((k) => [k.id, k.name]))
  return (
    <section className="panel" data-testid="competencies">
      <div className="section-head">
        <h3>Competencies</h3>
        {!editing && (
          <button type="button" className="outline" onClick={() => startCompetency({})}>
            Add a competency
          </button>
        )}
      </div>
      <p className="hint">Optional. People are rated on the competencies of the first set below whose rules match them.</p>
      {editing && (
        <form
          className="goal-form"
          onSubmit={async (e) => {
            e.preventDefault()
            const next = await run(() => performance.saveCompetency(editing.id, { name, description, active: editing.active !== false }))
            if (next) {
              setEditing(null)
              onSaved(next)
            }
          }}
        >
          <label htmlFor="comp-name">Name</label>
          <input id="comp-name" value={name} onChange={(e) => setName(e.target.value)} />
          <label htmlFor="comp-description">What it looks like</label>
          <textarea id="comp-description" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              Save
            </button>
            <button type="button" className="quiet" onClick={() => setEditing(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
      <ul className="bullet-list">
        {s.competencies.map((k) => (
          <li key={k.id} data-competency={k.name}>
            <button type="button" className="text-link" onClick={() => startCompetency(k)}>
              {k.name}
            </button>{' '}
            {!k.active && <Badge>off</Badge>} <span className="hint">{k.description}</span>
          </li>
        ))}
      </ul>
      <h4>Who is rated on which</h4>
      {s.competencySets.length === 0 && <p className="hint">No sets yet: nobody is rated on competencies.</p>}
      <ul className="bullet-list">
        {s.competencySets.map((set) => (
          <li key={set.id}>
            <button type="button" className="text-link" onClick={() => setEditingSet(set)}>
              {set.name}
            </button>{' '}
            <span className="hint">
              {describeRules(set.appliesTo, lookups)} · {set.competencyIds.map((id) => names[id]).filter(Boolean).join(', ')}
            </span>{' '}
            {!set.active && <Badge>off</Badge>}
          </li>
        ))}
      </ul>
      {!editingSet && s.competencies.length > 0 && (
        <button type="button" className="outline" onClick={() => setEditingSet({ name: '', appliesTo: {}, competencyIds: [], active: true })}>
          Add a set
        </button>
      )}
      {editingSet && (
        <SetForm
          set={editingSet}
          competencies={s.competencies.filter((k) => k.active)}
          lookups={lookups}
          onDone={(next) => {
            setEditingSet(null)
            if (next) onSaved(next)
          }}
        />
      )}
    </section>
  )
}

function SetForm({ set, competencies, lookups, onDone }) {
  const [v, setV] = useState(set)
  const { busy, error, run } = useAction()
  const toggle = (id) =>
    setV({ ...v, competencyIds: v.competencyIds.includes(id) ? v.competencyIds.filter((x) => x !== id) : [...v.competencyIds, id] })
  return (
    <form
      className="goal-form"
      onSubmit={async (e) => {
        e.preventDefault()
        const next = await run(() =>
          performance.saveCompetencySet(set.id, { name: v.name, appliesTo: v.appliesTo, competencyIds: v.competencyIds, active: v.active }),
        )
        if (next) onDone(next)
      }}
    >
      <label htmlFor="set-name">Name</label>
      <input id="set-name" value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />
      <fieldset className="days">
        <legend>Competencies</legend>
        {competencies.map((k) => (
          <label key={k.id} className="check">
            <input type="checkbox" checked={v.competencyIds.includes(k.id)} onChange={() => toggle(k.id)} />
            {k.name}
          </label>
        ))}
      </fieldset>
      <RuleEditor idPrefix="set" value={v.appliesTo} onChange={(appliesTo) => setV({ ...v, appliesTo })} lookups={lookups} />
      <label className="check">
        <input type="checkbox" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} /> In use
      </label>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save
        </button>
        <button type="button" className="quiet" onClick={() => onDone(null)}>
          Cancel
        </button>
      </div>
    </form>
  )
}

const ANSWERED_BY = { self: 'The employee', manager: 'The manager', peer: 'Peers' }

function ReviewForms() {
  const forms = useLoad(performance.forms)
  const [editing, setEditing] = useState(null)
  if (forms.loading) return <p className="muted">Loading…</p>
  if (forms.error) return <ErrorNote>{forms.error}</ErrorNote>
  return (
    <section className="panel" data-testid="review-forms">
      <div className="section-head">
        <h3>Review forms</h3>
        {!editing && (
          <button type="button" className="outline" onClick={() => setEditing({ name: '', questions: [{ for: 'self', type: 'text', text: '', required: true }] })}>
            Add a form
          </button>
        )}
      </div>
      <p className="hint">Saving makes a new version. A cycle keeps the version it started with.</p>
      {editing ? (
        <FormEditor
          form={editing}
          onDone={(saved) => {
            setEditing(null)
            if (saved) forms.reload()
          }}
        />
      ) : (
        <ul className="bullet-list">
          {forms.data.map((f) => (
            <li key={f.id} data-form={f.name}>
              <button type="button" className="text-link" onClick={() => setEditing(f)}>
                {f.name}
              </button>{' '}
              <span className="hint">
                version {f.version} · {f.questions.length} questions
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function FormEditor({ form, onDone }) {
  const [name, setName] = useState(form.name)
  const [questions, setQuestions] = useState(form.questions.map((q) => ({ ...q })))
  const { busy, error, run } = useAction()
  const change = (i, patch) => setQuestions(questions.map((q, j) => (j === i ? { ...q, ...patch } : q)))
  return (
    <form
      className="goal-form"
      onSubmit={async (e) => {
        e.preventDefault()
        if (await run(() => performance.saveForm(form.id, { name, questions }))) onDone(true)
      }}
    >
      <label htmlFor="form-name">Name</label>
      <input id="form-name" value={name} onChange={(e) => setName(e.target.value)} />
      {questions.map((q, i) => (
        <div className="question-row" key={q.id || `new-${i}`}>
          <div>
            <label htmlFor={`q-text-${i}`}>Question {i + 1}</label>
            <input id={`q-text-${i}`} value={q.text} onChange={(e) => change(i, { text: e.target.value })} />
          </div>
          <div>
            <label htmlFor={`q-for-${i}`}>Answered by</label>
            <select id={`q-for-${i}`} value={q.for} onChange={(e) => change(i, { for: e.target.value })}>
              {Object.entries(ANSWERED_BY).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor={`q-type-${i}`}>Answer</label>
            <select id={`q-type-${i}`} value={q.type} onChange={(e) => change(i, { type: e.target.value })}>
              <option value="text">In words</option>
              <option value="rating">A rating</option>
            </select>
          </div>
          <label className="check">
            <input type="checkbox" checked={Boolean(q.required)} onChange={(e) => change(i, { required: e.target.checked })} /> Required
          </label>
          <button type="button" className="quiet plain danger" onClick={() => setQuestions(questions.filter((_, j) => j !== i))}>
            Remove
          </button>
        </div>
      ))}
      <button type="button" className="outline" onClick={() => setQuestions([...questions, { for: 'self', type: 'text', text: '', required: false }])}>
        Add a question
      </button>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy}>
          Save as a new version
        </button>
        <button type="button" className="quiet" onClick={() => onDone(false)}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function MeritSettings() {
  const settings = useLoad(performance.merit)
  if (settings.loading) return <p className="muted">Loading…</p>
  if (settings.error) return <ErrorNote>{settings.error}</ErrorNote>
  return <MeritForm key={JSON.stringify(settings.data)} settings={settings.data} onSaved={settings.reload} />
}

/** The guide distribution and the merit table, one row per rating. */
function MeritForm({ settings, onSaved }) {
  const start = (table) => Object.fromEntries(settings.scale.map((l) => [l.value, String(table[l.value] ?? 0)]))
  const [guide, setGuide] = useState(() => start(settings.distributionGuide))
  const [merit, setMerit] = useState(() => start(settings.meritTable))
  const [saved, setSaved] = useState(false)
  const { busy, error, run } = useAction()
  const total = Object.values(guide).reduce((n, v) => n + (Number(v) || 0), 0)
  return (
    <form
      className="panel"
      noValidate
      onSubmit={async (e) => {
        e.preventDefault()
        setSaved(false)
        if (await run(() => performance.saveMerit({ distributionGuide: guide, meritTable: merit }))) {
          setSaved(true)
          onSaved()
        }
      }}
    >
      <h3>Calibration guide and merit table</h3>
      <p className="hint">
        The guide is the share of people you expect at each rating, shown beside the actual spread in calibration; it is never enforced.
        The merit table is the increment each final rating suggests when outcomes are prepared.
      </p>
      <div className="table-wrap">
        <table data-testid="merit-table">
          <thead>
            <tr>
              <th>Rating</th>
              <th>Guide (% of people)</th>
              <th>Increment (%)</th>
            </tr>
          </thead>
          <tbody>
            {settings.scale.map((l) => (
              <tr key={l.value}>
                <td>
                  {l.value} · {l.label}
                </td>
                <td>
                  <input
                    aria-label={`Guide for ${l.label}`}
                    inputMode="decimal"
                    value={guide[l.value]}
                    onChange={(e) => setGuide({ ...guide, [l.value]: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    aria-label={`Increment for ${l.label}`}
                    inputMode="decimal"
                    value={merit[l.value]}
                    onChange={(e) => setMerit({ ...merit, [l.value]: e.target.value })}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className={total === 100 ? 'hint' : 'hint warn'}>The guide adds up to {total}%{total === 100 ? '' : '; it needs to be 100%'}.</p>
      <p className="hint">The increment and promotion letters are under People → Setup → Letters.</p>
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">Saved.</p>}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy} data-testid="save-merit">
          Save
        </button>
      </div>
    </form>
  )
}

function ProbationSettings() {
  const settings = useLoad(performance.probationSettings)
  if (settings.loading) return <p className="muted">Loading…</p>
  if (settings.error) return <ErrorNote>{settings.error}</ErrorNote>
  return <ProbationForm settings={settings.data} onSaved={settings.reload} />
}

function ProbationForm({ settings, onSaved }) {
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
      <p className="hint">The confirmation and probation extension letters are under People → Setup → Letters, with the other letters.</p>
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">Saved.</p>}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={busy} data-testid="save-probation">
          Save
        </button>
        {settings.updatedAt && <span className="hint">Last changed {formatDate(settings.updatedAt)}</span>}
      </div>
    </form>
  )
}
