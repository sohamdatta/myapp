import { useState } from 'react'
import { formatDay } from '../hooks.js'
import { ErrorNote } from '../ui.jsx'
import { pretty } from './onboardingText.js'

/**
 * One onboarding section as a form, for the joiner and for HR alike. The
 * server sends the fields to show (with type, label, help and who may edit
 * them), the saved values, and masked identifiers. Only fields that changed
 * are sent back, so an untouched secret stays as it was.
 */

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

const RELATIONSHIPS = ['spouse', 'son', 'daughter', 'father', 'mother', 'father_in_law', 'mother_in_law', 'brother', 'sister', 'other']

const SCHEMES = { pf: 'Provident fund', gratuity: 'Gratuity', insurance: 'Insurance' }

const MONO = new Set(['pan', 'aadhaar', 'uan', 'pf_member', 'esi_ip', 'ifsc', 'bank_account'])

const PLACEHOLDERS = {
  pan: 'ABCPE1234F',
  ifsc: 'SBIN0001234',
  aadhaar: '12 digits',
  uan: '12 digits',
  phone: '98765 43210',
}

const emptyAddress = { line1: '', line2: '', city: '', stateCode: '', pin: '', country: 'IN' }

/**
 * @param view     the section from the server: fields, values, masked, status, note
 * @param canEdit  whether the caller may change this section now
 * @param editableField  field => whether this caller may change that field
 * @param lookups  { departments, managers, locations, shifts, states } for the Employment section and addresses
 * @param onSave   values => Promise; onSubmit: () => Promise (save first if needed)
 */
export default function SectionForm({ view, canEdit, editableField, lookups = {}, onSave, onSubmit, submitLabel = 'Send for checking', actions = true }) {
  const [draft, setDraft] = useState({})
  const [again, setAgain] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const values = view.values || {}
  const masked = view.masked || {}
  const fields = view.fields || []
  const value = (key) => (key in draft ? draft[key] : values[key])
  const set = (key, v) => {
    setSaved(false)
    setError('')
    setDraft((d) => ({ ...d, [key]: v }))
  }
  const dirty = Object.keys(draft).length > 0
  const editable = (f) => canEdit && editableField(f)

  function check() {
    if (draft['bank.account_number'] && draft['bank.account_number'].replace(/\s/g, '') !== again.replace(/\s/g, '')) {
      return 'The two account numbers are different. Type it again carefully.'
    }
    return ''
  }

  async function act(andSubmit) {
    const problem = check()
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError('')
    try {
      if (dirty) {
        await onSave(clean(draft))
        setDraft({})
        setAgain('')
      }
      if (andSubmit) await onSubmit()
      setSaved(!andSubmit)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  if (fields.length === 0) return <p className="hint">Nothing to fill in here.</p>

  return (
    <form
      className="section-form"
      noValidate
      onSubmit={(e) => {
        e.preventDefault()
        act(false)
      }}
    >
      {view.intro && <p className="hint">{view.intro}</p>}
      <div className="grid">
        {fields.map((f) => (
          <Field
            key={f.key}
            field={f}
            value={value(f.key)}
            masked={masked[f.key]}
            editable={editable(f)}
            onChange={(v) => set(f.key, v)}
            lookups={lookups}
            extra={{ value, set, again, setAgain, values }}
          />
        ))}
      </div>
      <ErrorNote>{error}</ErrorNote>
      {saved && !dirty && (
        <p className="hint" role="status">
          Saved.
        </p>
      )}
      {canEdit && actions && (
        <div className="form-actions">
          <button type="button" className="primary" disabled={busy} onClick={() => act(true)}>
            {busy ? 'Saving…' : submitLabel}
          </button>
          <button type="submit" className="secondary" disabled={busy || !dirty}>
            Save
          </button>
        </div>
      )}
    </form>
  )
}

/** Empty strings become null, so clearing a box clears the value; addresses and lists go as they are. */
function clean(draft) {
  const out = {}
  for (const [key, v] of Object.entries(draft)) {
    out[key] = typeof v === 'string' && v.trim() === '' ? null : v
  }
  return out
}

function Field({ field, value, masked, editable, onChange, lookups, extra }) {
  const id = `f-${field.key.replaceAll('.', '-')}`
  const required = field.visibility === 'required'
  const label = (
    <label htmlFor={id}>
      {field.label}
      {!required && <span className="muted"> (optional)</span>}
    </label>
  )
  const help = field.help && <p className="hint">{field.help}</p>
  const wide = ['address', 'contacts', 'family', 'nominations', 'long_text', 'weekdays', 'multi_select'].includes(field.type)

  if (!editable) {
    return (
      <div className={wide ? 'field-wide' : undefined}>
        <span className="field-label">{field.label}</span>
        <div className="field-value" id={id}>
          {show(field, value, masked, lookups)}
        </div>
      </div>
    )
  }

  let input
  switch (field.type) {
    case 'select':
      input = (
        <select id={id} value={value ?? ''} onChange={(e) => onChange(e.target.value)}>
          <option value="">Choose…</option>
          {(field.options || []).map((o) => (
            <option key={o} value={o}>
              {pretty(o)}
            </option>
          ))}
        </select>
      )
      break
    case 'yes_no':
      input = (
        <select id={id} value={value === true ? 'yes' : value === false ? 'no' : ''} onChange={(e) => onChange(e.target.value === '' ? null : e.target.value === 'yes')}>
          <option value="">Choose…</option>
          <option value="yes">Yes</option>
          <option value="no">No</option>
        </select>
      )
      break
    case 'multi_select':
      input = (
        <div className="days" id={id}>
          {(field.options || []).map((o) => {
            const list = value || []
            return (
              <label key={o} className="check">
                <input
                  type="checkbox"
                  checked={list.includes(o)}
                  onChange={(e) => onChange(e.target.checked ? [...list, o] : list.filter((x) => x !== o))}
                />
                {o}
              </label>
            )
          })}
        </div>
      )
      break
    case 'long_text':
      input = <textarea id={id} rows={3} value={value ?? ''} onChange={(e) => onChange(e.target.value)} />
      break
    case 'department':
    case 'employee':
    case 'work_location':
    case 'shift': {
      const list = choicesFor(field.type, lookups)
      input = (
        <select id={id} value={value ?? ''} onChange={(e) => onChange(e.target.value)}>
          <option value="">{field.type === 'employee' ? 'Nobody' : 'Choose…'}</option>
          {list.map((o) => (
            <option key={o.id} value={o.id}>
              {o.label}
            </option>
          ))}
        </select>
      )
      break
    }
    case 'weekdays': {
      const list = (value || []).map(Number)
      input = (
        <div className="days" id={id}>
          {WEEKDAYS.map((d, i) => (
            <label key={d} className="check">
              <input
                type="checkbox"
                checked={list.includes(i)}
                onChange={(e) => onChange(e.target.checked ? [...list, i].sort() : list.filter((x) => x !== i))}
              />
              {d}
            </label>
          ))}
        </div>
      )
      break
    }
    case 'address':
      return (
        <fieldset className="field-wide">
          <legend>
            {field.label}
            {!required && <span className="muted"> (optional)</span>}
          </legend>
          {help}
          {field.key === 'address.permanent' && (
            <label className="check">
              <input
                type="checkbox"
                onChange={(e) => e.target.checked && onChange({ ...(extra.value('address.current') || emptyAddress) })}
              />
              Same as my current address
            </label>
          )}
          <AddressInput id={id} value={value} onChange={onChange} states={lookups.states || {}} />
        </fieldset>
      )
    case 'contacts':
      return (
        <fieldset className="field-wide">
          <legend>{field.label}</legend>
          {help}
          <Rows
            id={id}
            rows={value || []}
            onChange={onChange}
            max={3}
            blank={{ name: '', relationship: '', mobile: '' }}
            addLabel="Add a contact"
            columns={[
              { key: 'name', label: 'Name' },
              { key: 'relationship', label: 'Relationship', placeholder: 'Mother' },
              { key: 'mobile', label: 'Mobile', type: 'tel' },
            ]}
          />
        </fieldset>
      )
    case 'family':
      return (
        <fieldset className="field-wide">
          <legend>
            {field.label}
            {!required && <span className="muted"> (optional)</span>}
          </legend>
          {help}
          <Rows
            id={id}
            rows={value || []}
            onChange={onChange}
            max={20}
            blank={{ name: '', relationship: '', dateOfBirth: '', dependent: false }}
            addLabel="Add a family member"
            columns={[
              { key: 'name', label: 'Name' },
              { key: 'relationship', label: 'Relationship', options: RELATIONSHIPS },
              { key: 'dateOfBirth', label: 'Date of birth', type: 'date' },
              { key: 'dependent', label: 'Dependent', type: 'checkbox' },
            ]}
          />
        </fieldset>
      )
    case 'nominations': {
      const members = (extra.value('family.members') || []).map((m) => m.name).filter(Boolean)
      return (
        <fieldset className="field-wide">
          <legend>
            {field.label}
            {!required && <span className="muted"> (optional)</span>}
          </legend>
          {help}
          {members.length === 0 ? (
            <p className="hint">List your family members first; nominees are chosen from them.</p>
          ) : (
            <Rows
              id={id}
              rows={(value || []).map((n) => ({ ...n, sharePct: n.sharePct ?? '' }))}
              onChange={onChange}
              max={12}
              blank={{ scheme: 'pf', member: members[0], sharePct: '100' }}
              addLabel="Add a nominee"
              columns={[
                { key: 'scheme', label: 'For', options: Object.keys(SCHEMES), labels: SCHEMES },
                { key: 'member', label: 'Nominee', options: members, raw: true },
                { key: 'sharePct', label: 'Share %', type: 'number' },
              ]}
            />
          )}
        </fieldset>
      )
    }
    case 'pan': {
      const pending = extra.value('statutory.pan_pending_reason')
      const applied = pending !== undefined && pending !== null
      return (
        <div>
          {label}
          <input
            id={id}
            className="mono"
            autoComplete="off"
            value={value ?? ''}
            disabled={applied}
            placeholder={masked && masked !== 'NONE' ? `Saved •••• ${masked} — type to replace` : PLACEHOLDERS.pan}
            onChange={(e) => onChange(e.target.value.toUpperCase())}
          />
          {help}
          {!(masked && masked !== 'NONE') && (
            <>
              <label className="check">
                <input
                  type="checkbox"
                  checked={applied}
                  onChange={(e) => extra.set('statutory.pan_pending_reason', e.target.checked ? '' : null)}
                />
                I have applied for a PAN and do not have it yet
              </label>
              {applied && (
                <input
                  aria-label="When you applied"
                  value={pending}
                  placeholder="Applied on 2 October, acknowledgement 1234…"
                  onChange={(e) => extra.set('statutory.pan_pending_reason', e.target.value)}
                />
              )}
            </>
          )}
        </div>
      )
    }
    case 'bank_account':
      return (
        <div>
          {label}
          <input
            id={id}
            className="mono"
            inputMode="numeric"
            autoComplete="off"
            value={value ?? ''}
            placeholder={masked ? `Saved •••• ${masked} — type to replace` : ''}
            onChange={(e) => onChange(e.target.value)}
          />
          {value && (
            <>
              <label htmlFor={`${id}-again`}>Account number again</label>
              <input
                id={`${id}-again`}
                className="mono"
                inputMode="numeric"
                autoComplete="off"
                value={extra.again}
                onChange={(e) => extra.setAgain(e.target.value)}
              />
            </>
          )}
          {help}
        </div>
      )
    default: {
      const type = { date: 'date', email: 'email', phone: 'tel', number: 'text' }[field.type] || 'text'
      input = (
        <input
          id={id}
          type={type}
          className={MONO.has(field.type) ? 'mono' : undefined}
          inputMode={field.type === 'number' ? 'decimal' : ['aadhaar', 'uan'].includes(field.type) ? 'numeric' : undefined}
          autoComplete="off"
          maxLength={field.type === 'country' ? 2 : undefined}
          value={value ?? ''}
          placeholder={masked ? (masked === 'set' ? 'Saved — type to replace' : `Saved •••• ${masked} — type to replace`) : PLACEHOLDERS[field.type]}
          onChange={(e) => onChange(['pan', 'ifsc', 'country'].includes(field.type) ? e.target.value.toUpperCase() : e.target.value)}
        />
      )
    }
  }
  return (
    <div className={wide ? 'field-wide' : undefined}>
      {label}
      {input}
      {help}
    </div>
  )
}

function choicesFor(type, lookups) {
  switch (type) {
    case 'department':
      return (lookups.departments || []).filter((d) => d.active).map((d) => ({ id: d.id, label: d.parentName ? `${d.parentName} › ${d.name}` : d.name }))
    case 'employee':
      return (lookups.managers || []).map((m) => ({ id: m.id, label: [m.preferredName || m.legalName, m.designation].filter(Boolean).join(', ') }))
    case 'work_location':
      return (lookups.locations || []).map((l) => ({ id: l.id, label: l.name }))
    default:
      return (lookups.shifts || []).filter((s) => s.active !== false).map((s) => ({ id: s.id, label: `${s.name} (${s.startTime}–${s.endTime})` }))
  }
}

function show(field, value, masked, lookups) {
  if (masked) return masked === 'set' ? 'Saved' : masked === 'NONE' ? '—' : `•••• ${masked}`
  if (value === null || value === undefined || value === '' || (Array.isArray(value) && value.length === 0)) return '—'
  switch (field.type) {
    case 'date':
      return formatDay(value)
    case 'department':
    case 'employee':
    case 'work_location':
    case 'shift':
      return choicesFor(field.type, lookups).find((o) => o.id === value)?.label || 'Set'
    case 'weekdays':
      return value.map((d) => WEEKDAYS[Number(d)]).join(', ')
    case 'yes_no':
      return value ? 'Yes' : 'No'
    case 'multi_select':
      return value.join(', ')
    case 'address':
      return [value.line1, value.line2, value.city, value.stateCode, value.pin].filter(Boolean).join(', ')
    case 'contacts':
      return value.map((c) => `${c.name} (${c.relationship}) ${c.mobile}`).join('; ')
    case 'family':
      return value.map((m) => `${m.name} (${pretty(m.relationship).toLowerCase()})`).join(', ')
    case 'nominations':
      return value.map((n) => `${SCHEMES[n.scheme] || n.scheme}: ${n.member} ${Number(n.sharePct)}%`).join('; ')
    case 'select':
      return pretty(value)
    default:
      return String(value)
  }
}

function AddressInput({ id, value, onChange, states }) {
  const a = { ...emptyAddress, ...(value || {}) }
  const set = (patch) => onChange({ ...a, ...patch })
  return (
    <div className="grid">
      <div>
        <label htmlFor={`${id}-line1`}>House, street</label>
        <input id={`${id}-line1`} value={a.line1 || ''} onChange={(e) => set({ line1: e.target.value })} />
      </div>
      <div>
        <label htmlFor={`${id}-line2`}>Area, landmark (optional)</label>
        <input id={`${id}-line2`} value={a.line2 || ''} onChange={(e) => set({ line2: e.target.value })} />
      </div>
      <div>
        <label htmlFor={`${id}-city`}>City</label>
        <input id={`${id}-city`} value={a.city || ''} onChange={(e) => set({ city: e.target.value })} />
      </div>
      <div>
        <label htmlFor={`${id}-state`}>State</label>
        <select id={`${id}-state`} value={a.stateCode || ''} onChange={(e) => set({ stateCode: e.target.value })}>
          <option value="">Choose…</option>
          {Object.entries(states).map(([code, name]) => (
            <option key={code} value={code}>
              {name}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor={`${id}-pin`}>PIN code</label>
        <input id={`${id}-pin`} inputMode="numeric" value={a.pin || ''} onChange={(e) => set({ pin: e.target.value })} />
      </div>
    </div>
  )
}

/** A small editable list: one row of inputs per item, with add and remove. */
function Rows({ id, rows, onChange, columns, blank, max, addLabel }) {
  const update = (index, patch) => onChange(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)))
  return (
    <div className="rows" id={id}>
      {rows.map((row, index) => (
        <div key={index} className="row-item">
          {columns.map((c) => {
            const cid = `${id}-${index}-${c.key}`
            if (c.type === 'checkbox') {
              return (
                <label key={c.key} className="check">
                  <input type="checkbox" checked={Boolean(row[c.key])} onChange={(e) => update(index, { [c.key]: e.target.checked })} />
                  {c.label}
                </label>
              )
            }
            return (
              <div key={c.key}>
                <label htmlFor={cid}>{c.label}</label>
                {c.options ? (
                  <select id={cid} value={row[c.key] ?? ''} onChange={(e) => update(index, { [c.key]: e.target.value })}>
                    <option value="">Choose…</option>
                    {c.options.map((o) => (
                      <option key={o} value={o}>
                        {c.labels ? c.labels[o] : c.raw ? o : pretty(o)}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    id={cid}
                    type={c.type === 'tel' ? 'tel' : c.type === 'date' ? 'date' : 'text'}
                    inputMode={c.type === 'number' ? 'decimal' : undefined}
                    placeholder={c.placeholder}
                    value={row[c.key] ?? ''}
                    onChange={(e) => update(index, { [c.key]: e.target.value })}
                  />
                )}
              </div>
            )
          })}
          <button type="button" className="quiet" onClick={() => onChange(rows.filter((_, i) => i !== index))}>
            Remove
          </button>
        </div>
      ))}
      {rows.length < max && (
        <button type="button" className="outline" onClick={() => onChange([...rows, { ...blank }])}>
          {addLabel}
        </button>
      )}
    </div>
  )
}
