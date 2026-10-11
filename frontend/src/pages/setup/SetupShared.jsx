import { formatDate } from '../../hooks.js'
import { pretty } from '../onboardingText.js'
import { TYPES, describeRules } from './setupText.js'

/** "Last changed by …, when", under a setup screen's title. */
export function LastChange({ change }) {
  if (!change) return <p className="hint">Not changed yet: these are the defaults.</p>
  return (
    <p className="hint">
      Last changed by {change.actorLabel} on {formatDate(change.at)}
    </p>
  )
}

/**
 * Who something applies to: any of the chosen employment types, departments, work locations and
 * designations. Nothing chosen in a row means any; nothing chosen at all means everyone.
 */
export function RuleEditor({ value, onChange, lookups, idPrefix }) {
  const rules = value || {}
  const toggle = (attr, item) => {
    const list = rules[attr] || []
    const next = list.includes(item) ? list.filter((x) => x !== item) : [...list, item]
    const out = { ...rules }
    if (next.length) out[attr] = next
    else delete out[attr]
    onChange(out)
  }
  const chips = (attr, items) =>
    items.map((item) => (
      <label key={item.id} className="check">
        <input type="checkbox" checked={(rules[attr] || []).includes(item.id)} onChange={() => toggle(attr, item.id)} />
        {item.name}
      </label>
    ))
  return (
    <fieldset className="rule-editor">
      <legend>Applies to: {describeRules(rules, lookups)}</legend>
      <div className="rule-row">
        <span className="hint">Employment type</span>
        <div className="days">{chips('employment_type', TYPES.map((t) => ({ id: t, name: pretty(t) })))}</div>
      </div>
      {lookups.departments.length > 0 && (
        <div className="rule-row">
          <span className="hint">Department</span>
          <div className="days">{chips('department', lookups.departments)}</div>
        </div>
      )}
      {lookups.locations.length > 0 && (
        <div className="rule-row">
          <span className="hint">Work location</span>
          <div className="days">{chips('work_location', lookups.locations)}</div>
        </div>
      )}
      <div className="rule-row">
        <label htmlFor={`${idPrefix}-designations`} className="hint">
          Designations, separated by commas
        </label>
        <input
          id={`${idPrefix}-designations`}
          value={(rules.designation || []).join(', ')}
          placeholder="Any designation"
          onChange={(e) => {
            const list = e.target.value.split(',').map((x) => x.trim()).filter(Boolean)
            const out = { ...rules }
            if (list.length) out.designation = list
            else delete out.designation
            onChange(out)
          }}
        />
      </div>
    </fieldset>
  )
}

/** The placeholders a template may use, each clickable to add it where the cursor is. */
export function Placeholders({ list, onPick }) {
  return (
    <details className="placeholders">
      <summary>Placeholders you can use</summary>
      <ul>
        {Object.entries(list).map(([key, help]) => (
          <li key={key}>
            <button type="button" className="quiet plain mono" onClick={() => onPick(`{{${key}}}`)}>
              {`{{${key}}}`}
            </button>{' '}
            <span className="hint">{help}</span>
          </li>
        ))}
      </ul>
    </details>
  )
}
