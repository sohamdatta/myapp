import { useState } from 'react'
import { setup } from '../../api.js'
import { useAction, useLoad } from '../../hooks.js'
import { Badge, ErrorNote } from '../../ui.jsx'
import SectionForm from '../SectionForm.jsx'
import { pretty } from '../onboardingText.js'
import { RuleEditor } from './SetupShared.jsx'
import { TYPES } from './setupText.js'

const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`

/**
 * The joiner's form: each built-in field hidden, optional or required, with
 * its own label and help, for the whole Organization or overridden for one
 * flow; the Organization's own fields; and a preview of the form as a joiner
 * sees it on a phone.
 */

const SECTION_NAMES = ['employment', 'personal', 'contact', 'address', 'emergency', 'family', 'bank', 'statutory', 'documents']
const DATA_TYPES = {
  text: 'Short text',
  long_text: 'Long text',
  number: 'Number',
  date: 'Date',
  select: 'One choice',
  multi_select: 'Several choices',
  yes_no: 'Yes or no',
  email: 'Email',
  phone: 'Phone',
}

export function FormSetup({ canConfigure, lookups, onChanged }) {
  const flows = useLoad(setup.flows)
  const [scope, setScope] = useState('') // '' is the Organization, otherwise a flow id
  const [view, setView] = useState('fields')
  if (flows.loading) return <p className="muted">Loading…</p>
  return (
    <>
      <div className="filters">
        {[
          ['fields', 'Built-in fields'],
          ['custom', 'Your own fields'],
          ['preview', 'Preview as joiner'],
        ].map(([id, label]) => (
          <button key={id} type="button" className={view === id ? 'chip chip-current' : 'chip'} onClick={() => setView(id)}>
            {label}
          </button>
        ))}
      </div>
      {view === 'fields' && (
        <>
          <div className="inline-form">
            <div>
              <label htmlFor="form-scope">Settings for</label>
              <select id="form-scope" value={scope} onChange={(e) => setScope(e.target.value)}>
                <option value="">The whole Organization</option>
                {(flows.data || []).map((f) => (
                  <option key={f.id} value={f.id}>
                    Only the flow {f.name}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <FieldSettings key={scope} flowId={scope || null} canConfigure={canConfigure} onChanged={onChanged} />
        </>
      )}
      {view === 'custom' && <CustomFields canConfigure={canConfigure} lookups={lookups} onChanged={onChanged} />}
      {view === 'preview' && <Preview flows={flows.data || []} lookups={lookups} />}
    </>
  )
}

function FieldSettings({ flowId, canConfigure, onChanged }) {
  const fields = useLoad(() => setup.fields(flowId))
  const [changes, setChanges] = useState({})
  const [saved, setSaved] = useState('')
  const { busy, error, run } = useAction()
  if (fields.loading) return <p className="muted">Loading…</p>
  if (fields.error) return <ErrorNote>{fields.error}</ErrorNote>
  const layer = flowId ? 'flow' : 'organization'
  const current = (f) => ({ visibility: null, label: null, help: null, editableBy: null, ...(f[layer] || {}), ...(changes[f.key] || {}) })
  const change = (key, patch) => {
    setSaved('')
    setChanges((c) => ({ ...c, [key]: { ...(c[key] || {}), ...patch } }))
  }

  async function save() {
    const list = Object.keys(changes).map((key) => {
      const f = fields.data.find((x) => x.key === key)
      const c = current(f)
      return { key, visibility: c.visibility || null, label: c.label || null, help: c.help || null, editableBy: c.editableBy || null }
    })
    if (await run(() => setup.saveFields(flowId, list))) {
      setChanges({})
      setSaved('Saved. Joiners added from now on get the new form.')
      fields.reload()
      onChanged()
    }
  }

  return (
    <>
      {SECTION_NAMES.map((section) => {
        const list = fields.data.filter((f) => f.section === section && f.builtIn.type !== 'file')
        if (list.length === 0) return null
        return (
          <section key={section} className="panel field-section" data-section={section}>
            <h4>{pretty(section)}</h4>
            <div className="field-row field-head" aria-hidden="true">
              <span>Field</span>
              <span>Asked</span>
              <span>Label the joiner sees</span>
              <span>Help under it</span>
            </div>
            {list.map((f) => {
              const c = current(f)
              const visibility = c.visibility || (flowId ? '' : f.builtIn.visibility)
              return (
                <div key={f.key} className="field-row" data-field={f.key}>
                  <div className="field-name">
                    <strong>{c.label || f.effective.label}</strong>
                    {f.fixed && (
                      <div className="hint" title="The record cannot be kept without it">
                        Always required
                      </div>
                    )}
                    {section === 'employment' && <div className="hint">Filled in by HR</div>}
                  </div>
                  <div className="segmented" role="radiogroup" aria-label={`${f.effective.label}: shown as`}>
                    {(flowId ? ['', 'hidden', 'optional', 'required'] : ['hidden', 'optional', 'required']).map((v) => (
                      <label key={v} className={visibility === v ? 'segment segment-on' : 'segment'}>
                        <input
                          type="radio"
                          name={`vis-${f.key}`}
                          value={v}
                          checked={visibility === v}
                          disabled={!canConfigure || (f.fixed && v !== 'required' && v !== '')}
                          onChange={() => change(f.key, { visibility: v || null })}
                        />
                        {v === '' ? `As the Organization (${f.organization?.visibility || f.builtIn.visibility})` : pretty(v)}
                      </label>
                    ))}
                  </div>
                  <input
                    aria-label={`Label for ${f.builtIn.label}`}
                    placeholder={f.builtIn.label}
                    value={c.label || ''}
                    disabled={!canConfigure}
                    onChange={(e) => change(f.key, { label: e.target.value })}
                  />
                  <input
                    aria-label={`Help for ${f.builtIn.label}`}
                    placeholder="Help text (optional)"
                    value={c.help || ''}
                    disabled={!canConfigure}
                    onChange={(e) => change(f.key, { help: e.target.value })}
                  />
                </div>
              )
            })}
          </section>
        )
      })}
      <ErrorNote>{error}</ErrorNote>
      {saved && (
        <p className="hint" role="status">
          {saved}
        </p>
      )}
      {canConfigure && (
        <div className="form-actions sticky-actions">
          <button type="button" className="primary" disabled={busy || Object.keys(changes).length === 0} onClick={save}>
            Save {Object.keys(changes).length || ''} change{Object.keys(changes).length === 1 ? '' : 's'}
          </button>
          {Object.keys(changes).length > 0 && (
            <button type="button" className="quiet" onClick={() => setChanges({})}>
              Undo
            </button>
          )}
        </div>
      )}
    </>
  )
}

function CustomFields({ canConfigure, lookups, onChanged }) {
  const list = useLoad(setup.customFields)
  const [editing, setEditing] = useState(null)
  if (list.loading) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  const done = () => {
    setEditing(null)
    list.reload()
    onChanged()
  }
  const blank = { key: '', label: '', help: '', section: 'personal', dataType: 'text', options: [], visibility: 'optional', editableBy: 'joiner',
    sensitive: false, managerCanSee: false, appliesTo: {}, active: true }
  return (
    <>
      <div className="toolbar">
        <p className="hint">Fields of your own, in any section, for whom their rules say.</p>
        <span className="spacer" />
        {canConfigure && editing !== 'new' && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            Add a field
          </button>
        )}
      </div>
      {editing === 'new' && <CustomFieldEditor field={blank} isNew lookups={lookups} onDone={done} onCancel={() => setEditing(null)} />}
      <div className="setup-list">
        {list.data.map((f) =>
          editing?.id === f.id ? (
            <CustomFieldEditor key={f.id} field={f} lookups={lookups} onDone={done} onCancel={() => setEditing(null)} />
          ) : (
            <div key={f.id} className="setup-item" data-item={f.label}>
              <div>
                <strong>{f.label}</strong> {f.sensitive && <Badge>Sensitive</Badge>} {!f.active && <Badge>Not in use</Badge>}
                <div className="hint">
                  {DATA_TYPES[f.dataType]} in {pretty(f.section)} · {f.visibility} · {f.rules}
                </div>
              </div>
              {canConfigure && (
                <button type="button" className="quiet plain" onClick={() => setEditing(f)}>
                  Edit
                </button>
              )}
            </div>
          ),
        )}
      </div>
    </>
  )
}

function CustomFieldEditor({ field, isNew, lookups, onDone, onCancel }) {
  const [f, setF] = useState({ ...field, optionsText: (field.options || []).join(', ') })
  const { busy, error, run } = useAction()
  const set = (patch) => setF({ ...f, ...patch })
  const choices = ['select', 'multi_select'].includes(f.dataType)
  return (
    <form
      className="panel setup-editor"
      onSubmit={async (e) => {
        e.preventDefault()
        const body = {
          key: isNew ? f.key : undefined,
          label: f.label,
          help: f.help || '',
          section: f.section,
          dataType: isNew ? f.dataType : undefined,
          options: choices ? f.optionsText.split(',').map((x) => x.trim()).filter(Boolean) : [],
          visibility: f.visibility,
          editableBy: f.editableBy,
          sensitive: isNew ? f.sensitive : undefined,
          managerCanSee: f.managerCanSee,
          appliesTo: f.appliesTo || {},
          active: f.active !== false,
        }
        if (await run(() => setup.saveCustomField(isNew ? null : f.id, body))) onDone()
      }}
    >
      <h4>{isNew ? 'New field' : f.label}</h4>
      <div className="grid">
        <div>
          <label htmlFor="cf-label">Label</label>
          <input
            id="cf-label"
            value={f.label}
            onChange={(e) => set({ label: e.target.value, key: isNew && (!f.key || f.key === slug(f.label)) ? slug(e.target.value) : f.key })}
          />
        </div>
        <div>
          <label htmlFor="cf-key">Key</label>
          <input
            id="cf-key"
            className="mono"
            value={f.key}
            disabled={!isNew}
            aria-describedby="cf-key-hint"
            onChange={(e) => set({ key: e.target.value })}
          />
          <p id="cf-key-hint" className="hint">
            Made from the label. Matches a column of the same name when importing; fixed once saved.
          </p>
        </div>
        <div>
          <label htmlFor="cf-section">Section</label>
          <select id="cf-section" value={f.section} onChange={(e) => set({ section: e.target.value })}>
            {SECTION_NAMES.map((s) => (
              <option key={s} value={s}>
                {pretty(s)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="cf-type">Kind of answer</label>
          <select id="cf-type" value={f.dataType} disabled={!isNew} onChange={(e) => set({ dataType: e.target.value })}>
            {Object.entries(DATA_TYPES).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </div>
        {choices && (
          <div>
            <label htmlFor="cf-options">Choices, separated by commas</label>
            <input id="cf-options" value={f.optionsText} onChange={(e) => set({ optionsText: e.target.value })} />
          </div>
        )}
        <div>
          <label htmlFor="cf-visibility">Answer</label>
          <select id="cf-visibility" value={f.visibility} onChange={(e) => set({ visibility: e.target.value })}>
            <option value="optional">Optional</option>
            <option value="required">Required</option>
          </select>
        </div>
        <div>
          <label htmlFor="cf-editor">Filled in by</label>
          <select id="cf-editor" value={f.editableBy} onChange={(e) => set({ editableBy: e.target.value })}>
            <option value="joiner">The joiner</option>
            <option value="hr">HR</option>
          </select>
        </div>
      </div>
      <label htmlFor="cf-help">Help text</label>
      <input id="cf-help" value={f.help || ''} onChange={(e) => set({ help: e.target.value })} />
      <div className="days">
        <label className="check">
          <input type="checkbox" checked={!!f.sensitive} disabled={!isNew} onChange={(e) => set({ sensitive: e.target.checked, managerCanSee: false })} />
          Sensitive: stored encrypted, shown only on request
        </label>
        <label className="check">
          <input type="checkbox" checked={!!f.managerCanSee} disabled={f.sensitive} onChange={(e) => set({ managerCanSee: e.target.checked })} />
          Managers can see it
        </label>
        <label className="check">
          <input type="checkbox" checked={f.active !== false} onChange={(e) => set({ active: e.target.checked })} />
          In use
        </label>
      </div>
      <RuleEditor idPrefix="cf" value={f.appliesTo} onChange={(appliesTo) => set({ appliesTo })} lookups={lookups} />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          Save field
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

const slug = (label) =>
  (label || '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .replace(/^[^a-z]+/, '')
    .slice(0, 40)

function Preview({ flows, lookups }) {
  const [params, setParams] = useState({ flow: '', employmentType: 'permanent', department: '', workLocation: '' })
  const preview = useLoad(() => setup.preview(params))
  const [open, setOpen] = useState('personal')
  const set = (patch) => {
    setParams({ ...params, ...patch })
    preview.reload()
  }
  return (
    <>
      <div className="inline-form">
        <div>
          <label htmlFor="pv-flow">Flow</label>
          <select id="pv-flow" value={params.flow} onChange={(e) => set({ flow: e.target.value })}>
            <option value="">The one the rules pick</option>
            {flows.map((f) => (
              <option key={f.id} value={f.id}>
                {f.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="pv-type">Employment type</label>
          <select id="pv-type" value={params.employmentType} onChange={(e) => set({ employmentType: e.target.value })}>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {pretty(t)}
              </option>
            ))}
          </select>
        </div>
        {lookups.departments.length > 0 && (
          <div>
            <label htmlFor="pv-dept">Department</label>
            <select id="pv-dept" value={params.department} onChange={(e) => set({ department: e.target.value })}>
              <option value="">Any</option>
              {lookups.departments.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>
      {preview.loading ? (
        <p className="muted">Loading…</p>
      ) : preview.error ? (
        <ErrorNote>{preview.error}</ErrorNote>
      ) : (
        <div className="phone-frame" aria-label="The form on a phone">
          <p className="hint">
            Flow: <strong>{preview.data.flow.name}</strong>, as the joiner sees it
          </p>
          {preview.data.sections
            .filter((s) => s.section !== 'employment' && !['policies', 'letters'].includes(s.section))
            .map((s) => (
              <section key={s.section} className="panel section-card" data-preview={s.section}>
                <button type="button" className="section-toggle" aria-expanded={open === s.section} onClick={() => setOpen(open === s.section ? null : s.section)}>
                  <span className="section-title">{s.title}</span>
                  <span className="hint">{plural(s.fields.filter((f) => f.type !== 'file').length, 'field')}</span>
                </button>
                {open === s.section && (
                  <SectionForm
                    view={{ ...s, fields: s.fields.filter((f) => f.type !== 'file'), values: {}, masked: {} }}
                    canEdit
                    actions={false}
                    editableField={(f) => f.editableBy === 'joiner'}
                    lookups={{ states: {} }}
                    onSave={async () => {}}
                    onSubmit={async () => {}}
                  />
                )}
              </section>
            ))}
        </div>
      )}
    </>
  )
}
