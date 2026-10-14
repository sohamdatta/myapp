import { useState } from 'react'
import { setup } from '../../api.js'
import { useAction, useLoad } from '../../hooks.js'
import { Badge, ErrorNote } from '../../ui.jsx'
import { pretty } from '../onboardingText.js'
import { RuleEditor } from './SetupShared.jsx'

/**
 * Flows: which onboarding a joiner follows. The list is in the order flows are
 * tried; the first whose rules match a joiner is theirs, so a flow for
 * everyone belongs last. Each flow has its welcome, its sections and who
 * verifies them.
 */

const ALWAYS_ON = ['employment', 'personal', 'statutory']
const NOT_SECTIONS = ['policies', 'letters'] // shown on their own, when there are any

export function FlowsSetup({ canConfigure, lookups, onChanged }) {
  const flows = useLoad(setup.flows)
  const [editing, setEditing] = useState(null)
  const { busy, error, run } = useAction()
  if (flows.loading) return <p className="muted">Loading…</p>
  if (flows.error) return <ErrorNote>{flows.error}</ErrorNote>
  const list = flows.data
  const done = () => {
    setEditing(null)
    flows.reload()
    onChanged()
  }

  async function move(index, by) {
    const ids = list.map((f) => f.id)
    const [id] = ids.splice(index, 1)
    ids.splice(index + by, 0, id)
    if (await run(() => setup.orderFlows(ids))) done()
  }

  return (
    <>
      <div className="toolbar">
        <h3>Flows</h3>
        <span className="spacer" />
        {canConfigure && editing !== 'new' && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            Add a flow
          </button>
        )}
      </div>
      <p className="hint">Flows are tried from the top; a joiner follows the first whose rules match them.</p>
      <ErrorNote>{error}</ErrorNote>
      {editing === 'new' && (
        <FlowEditor
          flow={{ name: '', appliesTo: {}, verificationLevels: 1, active: true, sections: null, steps: [] }}
          isNew
          lookups={lookups}
          onDone={done}
          onCancel={() => setEditing(null)}
        />
      )}
      <div className="setup-list">
        {list.map((f, i) =>
          editing && editing !== 'new' && editing.id === f.id ? (
            <FlowEditor key={f.id} flow={f} lookups={lookups} onDone={done} onCancel={() => setEditing(null)} />
          ) : (
            <div key={f.id} className="setup-item" data-item={f.name}>
              <div>
                <strong>
                  {i + 1}. {f.name}
                </strong>{' '}
                {!f.active && <Badge>Not in use</Badge>}
                <div className="hint">
                  {f.rules} · {f.verificationLevels === 0 ? 'not verified' : `${f.verificationLevels} level${f.verificationLevels > 1 ? 's' : ''} of checking`} ·{' '}
                  {f.sections.filter((s) => s.enabled && !NOT_SECTIONS.includes(s.section)).length} sections
                </div>
              </div>
              {canConfigure && (
                <div className="form-actions">
                  <button type="button" className="quiet" disabled={busy || i === 0} aria-label={`Move ${f.name} up`} onClick={() => move(i, -1)}>
                    ↑
                  </button>
                  <button type="button" className="quiet" disabled={busy || i === list.length - 1} aria-label={`Move ${f.name} down`} onClick={() => move(i, 1)}>
                    ↓
                  </button>
                  <button type="button" className="quiet plain" onClick={() => setEditing(f)}>
                    Edit
                  </button>
                </div>
              )}
            </div>
          ),
        )}
      </div>
    </>
  )
}

function FlowEditor({ flow, isNew, lookups, onDone, onCancel }) {
  const [f, setF] = useState(flow)
  const { busy, error, run } = useAction()
  const set = (patch) => setF({ ...f, ...patch })
  const sections = (f.sections || []).filter((s) => !NOT_SECTIONS.includes(s.section))
  const setSection = (name, patch) => set({ sections: f.sections.map((s) => (s.section === name ? { ...s, ...patch } : s)) })
  const moveSection = (index, by) => {
    const order = [...sections]
    const [x] = order.splice(index, 1)
    order.splice(index + by, 0, x)
    const others = f.sections.filter((s) => NOT_SECTIONS.includes(s.section))
    set({ sections: [...order, ...others].map((s, i) => ({ ...s, position: i + 1 })) })
  }
  const levels = Number(f.verificationLevels) || 0
  const stepFor = (level) => (f.steps || []).find((s) => s.level === level && !s.section) || { level, verifier: 'role', roleCode: 'hr_admin' }
  const setStep = (level, patch) => {
    const others = (f.steps || []).filter((s) => !(s.level === level && !s.section))
    set({ steps: [...others, { ...stepFor(level), ...patch, level }] })
  }

  async function save() {
    const body = {
      name: f.name,
      appliesTo: f.appliesTo || {},
      welcomeMessage: f.welcomeMessage || '',
      welcomeVideoUrl: f.welcomeVideoUrl || '',
      dayOneNote: f.dayOneNote || '',
      hrContactId: f.hrContactId || '',
      verificationLevels: levels,
      active: f.active !== false,
    }
    if (f.sections) {
      body.sections = f.sections.map((s, i) => ({ section: s.section, enabled: s.enabled, position: s.position ?? i + 1, title: s.title || null,
        intro: s.intro || null, verify: s.verify }))
    }
    if (!isNew || levels > 0) {
      body.steps = Array.from({ length: levels }, (_, i) => stepFor(i + 1)).map((s) => ({
        level: s.level,
        section: null,
        verifier: s.verifier,
        roleCode: s.verifier === 'role' ? s.roleCode : null,
        membershipId: s.verifier === 'user' ? s.membershipId : null,
      }))
    }
    if (await run(() => setup.saveFlow(isNew ? null : f.id, body))) onDone()
  }

  return (
    <form
      className="panel setup-editor"
      onSubmit={(e) => {
        e.preventDefault()
        save()
      }}
    >
      <h4>{isNew ? 'New flow' : f.name}</h4>
      <div className="grid">
        <div>
          <label htmlFor="fl-name">Name</label>
          <input id="fl-name" value={f.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="fl-contact">HR contact for joiners</label>
          <select id="fl-contact" value={f.hrContactId || ''} onChange={(e) => set({ hrContactId: e.target.value })}>
            <option value="">Whoever adds the joiner</option>
            {lookups.users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.email}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="fl-levels">Levels of checking</label>
          <select id="fl-levels" value={levels} onChange={(e) => set({ verificationLevels: Number(e.target.value) })}>
            {[0, 1, 2, 3].map((n) => (
              <option key={n} value={n}>
                {n === 0 ? 'None: sent is accepted' : n}
              </option>
            ))}
          </select>
          <p className="hint">How many people check each section the joiner sends, one after the other.</p>
        </div>
      </div>
      <RuleEditor idPrefix="fl" value={f.appliesTo} onChange={(appliesTo) => set({ appliesTo })} lookups={lookups} />

      {levels > 0 && (
        <fieldset className="rule-editor">
          <legend>Who checks</legend>
          {Array.from({ length: levels }, (_, i) => i + 1).map((level) => {
            const s = stepFor(level)
            return (
              <div key={level} className="inline-form step-row">
                <span className="hint">Level {level}</span>
                <select aria-label={`Level ${level} checked by`} value={s.verifier} onChange={(e) => setStep(level, { verifier: e.target.value })}>
                  <option value="role">Everyone with a role</option>
                  <option value="user">A named user</option>
                  <option value="manager">The joiner's manager</option>
                </select>
                {s.verifier === 'role' && (
                  <select aria-label={`Level ${level} role`} value={s.roleCode || ''} onChange={(e) => setStep(level, { roleCode: e.target.value })}>
                    {lookups.roles.map((r) => (
                      <option key={r.code} value={r.code}>
                        {r.name}
                      </option>
                    ))}
                  </select>
                )}
                {s.verifier === 'user' && (
                  <select aria-label={`Level ${level} user`} value={s.membershipId || ''} onChange={(e) => setStep(level, { membershipId: e.target.value })}>
                    <option value="">Choose…</option>
                    {lookups.users.map((u) => (
                      <option key={u.id} value={u.id}>
                        {u.email}
                      </option>
                    ))}
                  </select>
                )}
              </div>
            )
          })}
          <p className="hint">The Org Admin can always check, at any level.</p>
        </fieldset>
      )}

      <label htmlFor="fl-welcome">Welcome message</label>
      <textarea id="fl-welcome" rows={3} value={f.welcomeMessage || ''} onChange={(e) => set({ welcomeMessage: e.target.value })} />
      <div className="grid">
        <div>
          <label htmlFor="fl-dayone">First day: where and when to come</label>
          <input id="fl-dayone" value={f.dayOneNote || ''} placeholder="Reception, 3rd floor, at 9:30" onChange={(e) => set({ dayOneNote: e.target.value })} />
        </div>
        <div>
          <label htmlFor="fl-video">Welcome video link</label>
          <input id="fl-video" value={f.welcomeVideoUrl || ''} placeholder="https://" onChange={(e) => set({ welcomeVideoUrl: e.target.value })} />
        </div>
      </div>

      {f.sections && (
        <>
          <h4>Sections</h4>
          <div className="table-wrap">
            <table className="sections-table">
              <thead>
                <tr>
                  <th>Shown</th>
                  <th>Section</th>
                  <th>Title the joiner sees</th>
                  <th>Checked</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {sections.map((s, i) => (
                  <tr key={s.section} data-section={s.section}>
                    <td>
                      <input
                        type="checkbox"
                        aria-label={`Show ${s.section}`}
                        checked={s.enabled}
                        disabled={ALWAYS_ON.includes(s.section)}
                        title={ALWAYS_ON.includes(s.section) ? 'Every joiner needs this section' : undefined}
                        onChange={(e) => setSection(s.section, { enabled: e.target.checked })}
                      />
                    </td>
                    <td>{pretty(s.section)}</td>
                    <td>
                      <input aria-label={`Title of ${s.section}`} placeholder={pretty(s.section)} value={s.title || ''} onChange={(e) => setSection(s.section, { title: e.target.value })} />
                    </td>
                    <td>
                      <input type="checkbox" aria-label={`Check ${s.section}`} checked={s.verify} onChange={(e) => setSection(s.section, { verify: e.target.checked })} />
                    </td>
                    <td className="actions">
                      <button type="button" className="quiet" disabled={i === 0} aria-label={`Move ${s.section} up`} onClick={() => moveSection(i, -1)}>
                        ↑
                      </button>
                      <button type="button" className="quiet" disabled={i === sections.length - 1} aria-label={`Move ${s.section} down`} onClick={() => moveSection(i, 1)}>
                        ↓
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">Changes apply to joiners added from now on; joiners already in progress keep what they started with.</p>
        </>
      )}
      <label className="check">
        <input type="checkbox" checked={f.active !== false} onChange={(e) => set({ active: e.target.checked })} />
        In use
      </label>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          Save flow
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
