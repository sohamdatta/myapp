import { useRef, useState } from 'react'
import { joiners, setup } from '../../api.js'
import { formatDate, useAction, useLoad } from '../../hooks.js'
import { Badge, Empty, ErrorNote } from '../../ui.jsx'
import { EXIT_LETTER_PLACEHOLDERS, LETTER_PLACEHOLDERS, MESSAGE_PLACEHOLDERS, SAMPLE_VALUES, expandCode, fill, pretty } from '../onboardingText.js'
import { Placeholders, RuleEditor } from './SetupShared.jsx'
import { describeRules, insertAt } from './setupText.js'

/** The setup screens that are lists with an editor each, and the settings. */

// ── settings and the privacy notice ─────────────────────────────────

export function SettingsSetup({ canConfigure, onChanged }) {
  const data = useLoad(setup.settings)
  const [form, setForm] = useState(null)
  const [notice, setNotice] = useState(null)
  const { busy, error, run } = useAction()
  const [saved, setSaved] = useState('')
  if (data.loading) return <p className="muted">Loading…</p>
  if (data.error) return <ErrorNote>{data.error}</ErrorNote>
  const s = form || data.data
  const set = (patch) => {
    setSaved('')
    setForm({ ...s, ...patch })
  }
  const body = notice ?? data.data.notice?.body ?? ''

  async function save(e) {
    e.preventDefault()
    const done = await run(() =>
      setup.saveSettings({
        offerStage: s.offerStage,
        inviteWhen: s.inviteWhen,
        inviteDaysBefore: s.inviteWhen === 'days_before_joining' ? Number(s.inviteDaysBefore) || null : null,
        linkValidDays: Number(s.linkValidDays),
        activation: s.activation,
        allowActivationWithoutPan: s.allowActivationWithoutPan,
        rehireMatch: s.rehireMatch,
        withdrawnRetentionDays: Number(s.withdrawnRetentionDays),
        exitedRetentionYears: Number(s.exitedRetentionYears),
        offerExpiryDays: Number(s.offerExpiryDays),
      }),
    )
    if (done) {
      setForm(null)
      setSaved('Settings saved.')
      data.reload()
      onChanged()
    }
  }

  return (
    <>
      <form className="panel" onSubmit={save}>
        <h3>Joining</h3>
        <fieldset className="grid" disabled={!canConfigure}>
          <div>
            <label htmlFor="set-invite">Send the invitation</label>
            <select id="set-invite" value={s.inviteWhen} onChange={(e) => set({ inviteWhen: e.target.value })}>
              <option value="manual">When HR presses Invite</option>
              <option value="on_create">As soon as the joiner is added</option>
              <option value="days_before_joining">Some days before joining</option>
              {s.offerStage && <option value="on_offer_accepted">When the offer is accepted</option>}
            </select>
          </div>
          {s.inviteWhen === 'days_before_joining' && (
            <div>
              <label htmlFor="set-days-before">Days before joining</label>
              <input id="set-days-before" inputMode="numeric" value={s.inviteDaysBefore ?? ''} onChange={(e) => set({ inviteDaysBefore: e.target.value })} />
            </div>
          )}
          <div>
            <label htmlFor="set-link">Invitation link valid for (days)</label>
            <input id="set-link" inputMode="numeric" value={s.linkValidDays} onChange={(e) => set({ linkValidDays: e.target.value })} />
          </div>
          <div>
            <label htmlFor="set-activation">Activation on the joining date</label>
            <select id="set-activation" value={s.activation} onChange={(e) => set({ activation: e.target.value })}>
              <option value="automatic">Automatic, when ready</option>
              <option value="manual">When HR presses Activate</option>
            </select>
          </div>
          <div>
            <label htmlFor="set-offer-days">Days to answer an offer</label>
            <input id="set-offer-days" inputMode="numeric" value={s.offerExpiryDays} onChange={(e) => set({ offerExpiryDays: e.target.value })} />
          </div>
          <div>
            <label htmlFor="set-withdrawn">Keep a withdrawn joiner's data for (days)</label>
            <input id="set-withdrawn" inputMode="numeric" value={s.withdrawnRetentionDays} onChange={(e) => set({ withdrawnRetentionDays: e.target.value })} />
          </div>
          <div>
            <label htmlFor="set-exited">Keep a former employee's records for (years)</label>
            <input id="set-exited" inputMode="numeric" value={s.exitedRetentionYears} onChange={(e) => set({ exitedRetentionYears: e.target.value })} />
          </div>
        </fieldset>
        <fieldset className="days" disabled={!canConfigure}>
          <label className="check">
            <input type="checkbox" checked={s.offerStage} onChange={(e) => set({ offerStage: e.target.checked })} />
            Joiners get an offer letter to accept first
          </label>
          <label className="check">
            <input type="checkbox" checked={s.allowActivationWithoutPan} onChange={(e) => set({ allowActivationWithoutPan: e.target.checked })} />
            A joiner can be activated while their PAN is pending
          </label>
        </fieldset>
        <fieldset className="days" disabled={!canConfigure}>
          <legend>A new joiner may be someone who was here before when these match</legend>
          {['personal_email', 'mobile', 'pan', 'aadhaar_last4'].map((m) => (
            <label key={m} className="check">
              <input
                type="checkbox"
                checked={(s.rehireMatch || []).includes(m)}
                onChange={(e) => set({ rehireMatch: e.target.checked ? [...(s.rehireMatch || []), m] : s.rehireMatch.filter((x) => x !== m) })}
              />
              {{ personal_email: 'Personal email', mobile: 'Mobile', pan: 'PAN', aadhaar_last4: 'Aadhaar last four' }[m]}
            </label>
          ))}
        </fieldset>
        <ErrorNote>{error}</ErrorNote>
        {saved && (
          <p className="hint" role="status">
            {saved}
          </p>
        )}
        {canConfigure && (
          <div className="form-actions">
            <button type="submit" className="secondary" disabled={busy || !form}>
              Save settings
            </button>
          </div>
        )}
      </form>
      <section className="panel">
        <h3>Privacy notice</h3>
        <p className="hint">
          Version {data.data.notice?.version}, published {formatDate(data.data.notice?.publishedAt)}. Every joiner accepts it before
          they start; publishing a new version asks joiners who have not started to accept it again.
        </p>
        <label htmlFor="set-notice" className="sr-only">
          Notice
        </label>
        <textarea id="set-notice" rows={12} value={body} disabled={!canConfigure} onChange={(e) => setNotice(e.target.value)} />
        {canConfigure && (
          <div className="form-actions">
            <button
              type="button"
              className="secondary"
              disabled={busy || notice === null || notice === data.data.notice?.body}
              onClick={async () => {
                if (await run(() => setup.publishNotice(notice))) {
                  setNotice(null)
                  data.reload()
                  onChanged()
                }
              }}
            >
              Publish as a new version
            </button>
          </div>
        )}
      </section>
    </>
  )
}

// ── a list with an editor ───────────────────────────────────────────

/**
 * The shape every list screen shares: the items with a summary line each, Edit on one, Add to start
 * a new one. The editor is given the item (or a blank one) and saves through `save`.
 */
function ListScreen({ title, load, blank, addLabel, canConfigure, summary, Editor, onChanged, lookups, extra, groups }) {
  const list = useLoad(load)
  const [editing, setEditing] = useState(null) // an item, or 'new'
  if (list.loading) return <p className="muted">Loading…</p>
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  const done = () => {
    setEditing(null)
    list.reload()
    onChanged()
  }
  return (
    <>
      <div className="toolbar">
        <h3>{title}</h3>
        <span className="spacer" />
        {canConfigure && addLabel && editing !== 'new' && (
          <button type="button" className="outline" onClick={() => setEditing('new')}>
            {addLabel}
          </button>
        )}
      </div>
      {editing === 'new' && <Editor item={blank()} isNew lookups={lookups} extra={extra} onDone={done} onCancel={() => setEditing(null)} />}
      {list.data.length === 0 && editing !== 'new' && <Empty>None yet.</Empty>}
      {(groups ? groups.map(([label, test]) => [label, list.data.filter(test)]) : [[null, list.data]])
        .filter(([, items]) => items.length > 0)
        .map(([label, items]) => (
      <div key={label || 'all'} className="setup-list">
        {label && <h4 className="setup-group">{label}</h4>}
        {items.map((item) =>
          editing && editing !== 'new' && editing.id === item.id ? (
            <Editor key={item.id} item={item} lookups={lookups} extra={extra} onDone={done} onCancel={() => setEditing(null)} />
          ) : (
            <div key={item.id} className="setup-item" data-item={item.name || item.event}>
              <div>
                <strong>{item.name || pretty(item.event)}</strong>
                {item.active === false && (
                  <>
                    {' '}
                    <Badge>Not in use</Badge>
                  </>
                )}
                <div className="hint">{summary(item, lookups)}</div>
              </div>
              {canConfigure && (
                <button type="button" className="quiet plain" onClick={() => setEditing(item)}>
                  Edit
                </button>
              )}
            </div>
          ),
        )}
      </div>
        ))}
    </>
  )
}

function EditorFrame({ title, children, onSave, onCancel, busy, error }) {
  return (
    <form
      className="panel setup-editor"
      onSubmit={(e) => {
        e.preventDefault()
        onSave()
      }}
    >
      <h4>{title}</h4>
      {children}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="secondary" disabled={busy}>
          Save
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function Active({ value, onChange, id }) {
  return (
    <label className="check" htmlFor={id}>
      <input id={id} type="checkbox" checked={value !== false} onChange={(e) => onChange(e.target.checked)} />
      In use
    </label>
  )
}

// ── documents ───────────────────────────────────────────────────────

const REQUIRED = { before_activation: 'Before joining', within_days: 'Within some days of joining', optional: 'Optional' }

export function DocumentsSetup(props) {
  return (
    <ListScreen
      {...props}
      title="Documents joiners give"
      load={setup.documentTypes}
      addLabel="Add a document"
      blank={() => ({ name: '', required: 'optional', withinDays: 30, instructions: '', appliesTo: {}, active: true })}
      summary={(d, l) => `${d.required === 'within_days' ? `Within ${d.withinDays} days of joining` : REQUIRED[d.required]} · ${describeRules(d.appliesTo, l)}`}
      Editor={DocumentEditor}
    />
  )
}

function DocumentEditor({ item, isNew, lookups, onDone, onCancel }) {
  const [d, setD] = useState(item)
  const { busy, error, run } = useAction()
  const set = (patch) => setD({ ...d, ...patch })
  return (
    <EditorFrame
      title={isNew ? 'New document' : d.name}
      busy={busy}
      error={error}
      onCancel={onCancel}
      onSave={async () => {
        const body = { name: d.name, required: d.required, withinDays: d.required === 'within_days' ? Number(d.withinDays) : null,
          instructions: d.instructions || '', appliesTo: d.appliesTo || {}, active: d.active !== false }
        if (await run(() => setup.saveDocumentType(isNew ? null : d.id, body))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="doc-name">Name</label>
          <input id="doc-name" value={d.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="doc-required">Needed</label>
          <select id="doc-required" value={d.required} onChange={(e) => set({ required: e.target.value })}>
            {Object.entries(REQUIRED).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </div>
        {d.required === 'within_days' && (
          <div>
            <label htmlFor="doc-days">Days after joining</label>
            <input id="doc-days" inputMode="numeric" value={d.withinDays ?? ''} onChange={(e) => set({ withinDays: e.target.value })} />
          </div>
        )}
      </div>
      <label htmlFor="doc-instructions">Instructions for the joiner</label>
      <textarea id="doc-instructions" rows={2} value={d.instructions || ''} onChange={(e) => set({ instructions: e.target.value })} />
      <RuleEditor idPrefix="doc" value={d.appliesTo} onChange={(appliesTo) => set({ appliesTo })} lookups={lookups} />
      <Active id="doc-active" value={d.active} onChange={(active) => set({ active })} />
    </EditorFrame>
  )
}

// ── checklists ──────────────────────────────────────────────────────

const OWNERS = { hr: 'HR', it: 'IT', manager: 'Manager', buddy: 'Buddy', joiner: 'Joiner', role: 'A role', user: 'A named user' }
const EXIT_OWNERS = { hr: 'HR', it: 'IT', finance: 'Finance', manager: 'Manager', joiner: 'The leaver', role: 'A role', user: 'A named user' }
const TRIGGERS = { added: 'Added', offer_accepted: 'Offer accepted', invited: 'Invited', joining_date: 'Joining date', activated: 'Activated' }
const EXIT_TRIGGERS = { exit_accepted: 'The exit is accepted', last_working_day: 'The last working day' }

export function ChecklistsSetup(props) {
  return (
    <ListScreen
      {...props}
      title="Checklists"
      load={setup.checklists}
      addLabel="Add a checklist"
      blank={() => ({ name: '', kind: 'joining', appliesTo: {}, active: true, tasks: [] })}
      summary={(t, l) => `${t.tasks.length} tasks · ${describeRules(t.appliesTo, l)}`}
      groups={[
        ['People joining', (t) => t.kind !== 'exit'],
        ['People leaving', (t) => t.kind === 'exit'],
      ]}
      Editor={ChecklistEditor}
    />
  )
}

const blankTask = (kind) => ({
  title: '',
  owner: 'hr',
  triggerEvent: kind === 'exit' ? 'last_working_day' : 'joining_date',
  offsetDays: 0,
  required: true,
  needsAttachment: false,
  approver: '',
})

function ChecklistEditor({ item, isNew, lookups, onDone, onCancel }) {
  const [t, setT] = useState(item)
  const { busy, error, run } = useAction()
  const set = (patch) => setT({ ...t, ...patch })
  const exit = t.kind === 'exit'
  const owners = exit ? EXIT_OWNERS : OWNERS
  const triggers = exit ? EXIT_TRIGGERS : TRIGGERS
  const setTask = (i, patch) => set({ tasks: t.tasks.map((x, j) => (j === i ? { ...x, ...patch } : x)) })
  const move = (i, by) => {
    const tasks = [...t.tasks]
    const [x] = tasks.splice(i, 1)
    tasks.splice(i + by, 0, x)
    set({ tasks })
  }
  return (
    <EditorFrame
      title={isNew ? 'New checklist' : t.name}
      busy={busy}
      error={error}
      onCancel={onCancel}
      onSave={async () => {
        const body = {
          name: t.name,
          kind: t.kind || 'joining',
          appliesTo: t.appliesTo || {},
          active: t.active !== false,
          tasks: t.tasks.map((x) => ({
            id: x.id || null,
            title: x.title,
            instructions: x.instructions || null,
            owner: x.owner,
            ownerRoleCode: x.owner === 'role' ? x.ownerRoleCode : null,
            ownerMembershipId: x.owner === 'user' ? x.ownerMembershipId : null,
            triggerEvent: x.triggerEvent,
            offsetDays: Number(x.offsetDays) || 0,
            required: x.required !== false,
            needsAttachment: !!x.needsAttachment,
            approver: x.approver || null,
            approverRoleCode: x.approver === 'role' ? x.approverRoleCode : null,
            approverMembershipId: x.approver === 'user' ? x.approverMembershipId : null,
          })),
        }
        if (await run(() => setup.saveChecklist(isNew ? null : t.id, body))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="cl-name">Name</label>
          <input id="cl-name" value={t.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        {isNew && (
          <div>
            <label htmlFor="cl-kind">For</label>
            <select id="cl-kind" value={t.kind} onChange={(e) => set({ kind: e.target.value, tasks: [] })}>
              <option value="joining">People joining</option>
              <option value="exit">People leaving</option>
            </select>
          </div>
        )}
      </div>
      <RuleEditor idPrefix="cl" value={t.appliesTo} onChange={(appliesTo) => set({ appliesTo })} lookups={lookups} />
      <h4>Tasks</h4>
      <div className="rows">
        {t.tasks.map((x, i) => (
          <div key={x.id || `new-${i}`} className="row-item" data-task={x.title}>
            <div>
              <label htmlFor={`cl-t-${i}-title`}>Task</label>
              <input id={`cl-t-${i}-title`} value={x.title} onChange={(e) => setTask(i, { title: e.target.value })} />
            </div>
            <div>
              <label htmlFor={`cl-t-${i}-owner`}>Who does it</label>
              <select id={`cl-t-${i}-owner`} value={x.owner} onChange={(e) => setTask(i, { owner: e.target.value })}>
                {Object.entries(owners).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </select>
            </div>
            {x.owner === 'role' && (
              <div>
                <label htmlFor={`cl-t-${i}-role`}>Role</label>
                <select id={`cl-t-${i}-role`} value={x.ownerRoleCode || ''} onChange={(e) => setTask(i, { ownerRoleCode: e.target.value })}>
                  <option value="">Choose…</option>
                  {lookups.roles.map((r) => (
                    <option key={r.code} value={r.code}>
                      {r.name}
                    </option>
                  ))}
                </select>
              </div>
            )}
            {x.owner === 'user' && (
              <div>
                <label htmlFor={`cl-t-${i}-user`}>User</label>
                <select id={`cl-t-${i}-user`} value={x.ownerMembershipId || ''} onChange={(e) => setTask(i, { ownerMembershipId: e.target.value })}>
                  <option value="">Choose…</option>
                  {lookups.users.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.email}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div>
              <label htmlFor={`cl-t-${i}-offset`}>Due, days from</label>
              <input id={`cl-t-${i}-offset`} inputMode="numeric" value={x.offsetDays} onChange={(e) => setTask(i, { offsetDays: e.target.value })} />
            </div>
            <div>
              <label htmlFor={`cl-t-${i}-trigger`}>{exit ? 'the day' : 'the day the joiner is'}</label>
              <select id={`cl-t-${i}-trigger`} value={x.triggerEvent} onChange={(e) => setTask(i, { triggerEvent: e.target.value })}>
                {Object.entries(triggers).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v.toLowerCase()}
                  </option>
                ))}
              </select>
            </div>
            {!exit && (
              <div>
                <label htmlFor={`cl-t-${i}-approver`}>Approved by</label>
                <select id={`cl-t-${i}-approver`} value={x.approver || ''} onChange={(e) => setTask(i, { approver: e.target.value })}>
                  <option value="">Nobody</option>
                  <option value="hr">HR</option>
                  <option value="manager">Manager</option>
                </select>
              </div>
            )}
            <label className="check">
              <input type="checkbox" checked={x.required !== false} onChange={(e) => setTask(i, { required: e.target.checked })} />
              Required
            </label>
            {!exit && (
              <label className="check">
                <input type="checkbox" checked={!!x.needsAttachment} onChange={(e) => setTask(i, { needsAttachment: e.target.checked })} />
                Needs a file
              </label>
            )}
            <div className="form-actions">
              <button type="button" className="quiet" disabled={i === 0} onClick={() => move(i, -1)} aria-label={`Move ${x.title} up`}>
                ↑
              </button>
              <button type="button" className="quiet" disabled={i === t.tasks.length - 1} onClick={() => move(i, 1)} aria-label={`Move ${x.title} down`}>
                ↓
              </button>
              <button type="button" className="quiet" onClick={() => set({ tasks: t.tasks.filter((_, j) => j !== i) })}>
                Remove
              </button>
            </div>
          </div>
        ))}
        <button type="button" className="outline" onClick={() => set({ tasks: [...t.tasks, blankTask(t.kind)] })}>
          Add a task
        </button>
      </div>
      <p className="hint">
        {exit
          ? 'A negative number of days means before: −2 from the last working day is two days before it. Nobody holding the role means HR does it.'
          : 'A negative number of days means before: −2 from the joining date is two days before joining.'}
      </p>
      <Active id="cl-active" value={t.active} onChange={(active) => set({ active })} />
    </EditorFrame>
  )
}

// ── letters ─────────────────────────────────────────────────────────

const RESPONSES = { none: 'Only reads it', accept: 'Accepts it', accept_or_decline: 'Accepts or declines it' }
const JOINING_KINDS = ['offer', 'appointment', 'confirmation', 'other']
const EXIT_KINDS = {
  exit_acceptance: 'Resignation accepted',
  relieving: 'Relieving',
  experience: 'Experience',
  termination: 'Termination',
  retirement: 'Retirement',
  contract_end: 'End of contract',
  show_cause: 'Show-cause notice',
  final_notice: 'Final notice',
}
const isExit = (kind) => kind in EXIT_KINDS

export function LettersSetup(props) {
  return (
    <ListScreen
      {...props}
      title="Letters"
      load={setup.letters}
      addLabel="Add a letter"
      blank={() => ({ kind: 'other', name: '', body: '{{organization.name}}\n\n\n\nDear {{person.legal_name}},\n\n', response: 'accept', appliesTo: {}, active: true })}
      summary={(t, l) =>
        isExit(t.kind)
          ? `${EXIT_KINDS[t.kind]} · version ${t.version}`
          : `${pretty(t.kind)} · version ${t.version} · the joiner ${RESPONSES[t.response].toLowerCase()} · ${describeRules(t.appliesTo, l)}`
      }
      Editor={LetterEditor}
      groups={[
        ['Joining', (t) => !isExit(t.kind)],
        ['Leaving', (t) => isExit(t.kind)],
      ]}
    />
  )
}

function LetterEditor({ item, isNew, lookups, onDone, onCancel }) {
  const [t, setT] = useState(item)
  const [sample, setSample] = useState('')
  const [sampleFor, setSampleFor] = useState('')
  const open = useLoad(() => joiners.list('open').catch(() => ({ joiners: [] })))
  const area = useRef(null)
  const { busy, error, run } = useAction()
  const set = (patch) => setT({ ...t, ...patch })
  return (
    <EditorFrame
      title={isNew ? 'New letter' : `${t.name} (version ${t.version})`}
      busy={busy}
      error={error}
      onCancel={onCancel}
      onSave={async () => {
        const body = { kind: t.kind, name: t.name, body: t.body, response: t.response, appliesTo: t.appliesTo || {}, active: t.active !== false }
        if (await run(() => setup.saveLetter(isNew ? null : t.id, body))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="lt-name">Name</label>
          <input id="lt-name" value={t.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="lt-kind">Kind</label>
          <select id="lt-kind" value={t.kind} onChange={(e) => set({ kind: e.target.value })}>
            {(isNew || !isExit(item.kind)) && (
              <optgroup label="Joining">
                {JOINING_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {pretty(k)}
                  </option>
                ))}
              </optgroup>
            )}
            {(isNew || isExit(item.kind)) && (
              <optgroup label="Leaving">
                {Object.entries(EXIT_KINDS).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </optgroup>
            )}
          </select>
        </div>
        {t.kind !== 'offer' && !isExit(t.kind) && (
          <div>
            <label htmlFor="lt-response">The joiner</label>
            <select id="lt-response" value={t.response} onChange={(e) => set({ response: e.target.value })}>
              {Object.entries(RESPONSES).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>
      <label htmlFor="lt-body">Wording</label>
      <textarea id="lt-body" ref={area} rows={14} value={t.body} onChange={(e) => set({ body: e.target.value })} />
      <Placeholders
        list={isExit(t.kind) ? EXIT_LETTER_PLACEHOLDERS : LETTER_PLACEHOLDERS}
        onPick={(p) => insertAt(area, t.body, p, (body) => set({ body }))}
      />
      <p className="hint">Changing the wording makes a new version; letters already issued keep the version they were issued with.</p>
      <div className="inline-form">
        <div hidden={isExit(t.kind)}>
          <label htmlFor="lt-sample">See it filled for</label>
          <select id="lt-sample" value={sampleFor} onChange={(e) => setSampleFor(e.target.value)}>
            <option value="">Sample values</option>
            {(open.data?.joiners || []).map((j) => (
              <option key={j.id} value={j.id}>
                {j.legalName}
              </option>
            ))}
          </select>
        </div>
        <button
          type="button"
          className="outline"
          onClick={async () => {
            if (sampleFor && !isExit(t.kind) && !isNew && t.body === item.body) {
              const p = await run(() => joiners.previewLetter(sampleFor, t.id))
              if (p) setSample(p.text)
            } else {
              setSample(fill(t.body, SAMPLE_VALUES))
            }
          }}
        >
          Show sample
        </button>
      </div>
      {sampleFor && (isNew || t.body !== item.body) && <p className="hint">Save first to fill it for a real joiner; this shows sample values.</p>}
      {sample && <pre className="letter-preview">{sample}</pre>}
      {!isExit(t.kind) && <RuleEditor idPrefix="lt" value={t.appliesTo} onChange={(appliesTo) => set({ appliesTo })} lookups={lookups} />}
      <Active id="lt-active" value={t.active} onChange={(active) => set({ active })} />
    </EditorFrame>
  )
}

// ── policies ────────────────────────────────────────────────────────

export function PoliciesSetup(props) {
  return (
    <ListScreen
      {...props}
      title="Policies to acknowledge"
      load={setup.policies}
      addLabel="Add a policy"
      blank={() => ({ name: '', body: '', due: 'before_activation', withinDays: 30, appliesTo: {}, active: true })}
      summary={(p, l) => `Version ${p.version} · ${p.due === 'before_activation' ? 'before joining' : `within ${p.withinDays} days`} · ${describeRules(p.appliesTo, l)}`}
      Editor={PolicyEditor}
    />
  )
}

function PolicyEditor({ item, isNew, lookups, onDone, onCancel }) {
  const [p, setP] = useState(item)
  const { busy, error, run } = useAction()
  const set = (patch) => setP({ ...p, ...patch })
  return (
    <EditorFrame
      title={isNew ? 'New policy' : `${p.name} (version ${p.version})`}
      busy={busy}
      error={error}
      onCancel={onCancel}
      onSave={async () => {
        const body = { name: p.name, body: p.body, due: p.due, withinDays: p.due === 'within_days' ? Number(p.withinDays) : null,
          appliesTo: p.appliesTo || {}, active: p.active !== false }
        if (await run(() => setup.savePolicy(isNew ? null : p.id, body))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="pol-name">Name</label>
          <input id="pol-name" value={p.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="pol-due">Acknowledged</label>
          <select id="pol-due" value={p.due} onChange={(e) => set({ due: e.target.value })}>
            <option value="before_activation">Before joining</option>
            <option value="within_days">Within days of joining</option>
          </select>
        </div>
        {p.due === 'within_days' && (
          <div>
            <label htmlFor="pol-days">Days</label>
            <input id="pol-days" inputMode="numeric" value={p.withinDays ?? ''} onChange={(e) => set({ withinDays: e.target.value })} />
          </div>
        )}
      </div>
      <label htmlFor="pol-body">The policy</label>
      <textarea id="pol-body" rows={10} value={p.body || ''} onChange={(e) => set({ body: e.target.value })} />
      <p className="hint">Changing the wording makes a new version, which everyone it applies to acknowledges again.</p>
      <RuleEditor idPrefix="pol" value={p.appliesTo} onChange={(appliesTo) => set({ appliesTo })} lookups={lookups} />
      <Active id="pol-active" value={p.active} onChange={(active) => set({ active })} />
    </EditorFrame>
  )
}

// ── messages ────────────────────────────────────────────────────────

const JOINING_EVENTS = ['joiner_invited', 'offer_issued', 'section_returned', 'reminder_joiner', 'joiner_ready', 'activated', 'welcome_team']

export function MessagesSetup(props) {
  return (
    <ListScreen
      {...props}
      title="Messages"
      load={setup.messages}
      blank={() => ({})}
      summary={(m) => fill(m.subject ? `${m.subject}: ${m.body}` : m.body, SAMPLE_VALUES)}
      Editor={MessageEditor}
      groups={[
        ['Joining', (m) => JOINING_EVENTS.includes(m.event)],
        ['Tasks', (m) => m.event.startsWith('task_')],
        ['Leaving', (m) => !JOINING_EVENTS.includes(m.event) && !m.event.startsWith('task_')],
      ]}
    />
  )
}

function MessageEditor({ item, onDone, onCancel }) {
  const [m, setM] = useState(item)
  const area = useRef(null)
  const { busy, error, run } = useAction()
  const set = (patch) => setM({ ...m, ...patch })
  return (
    <EditorFrame
      title={pretty(m.event)}
      busy={busy}
      error={error}
      onCancel={onCancel}
      onSave={async () => {
        if (await run(() => setup.saveMessage(m.id, { subject: m.subject || '', body: m.body, active: m.active !== false }))) onDone()
      }}
    >
      <label htmlFor="msg-subject">Title</label>
      <input id="msg-subject" value={m.subject || ''} onChange={(e) => set({ subject: e.target.value })} />
      <label htmlFor="msg-body">Message</label>
      <textarea id="msg-body" ref={area} rows={3} value={m.body} onChange={(e) => set({ body: e.target.value })} />
      <Placeholders list={MESSAGE_PLACEHOLDERS} onPick={(p) => insertAt(area, m.body, p, (body) => set({ body }))} />
      <p className="hint">
        Reads as: <strong>{fill(m.subject, SAMPLE_VALUES)}</strong> {fill(m.body, SAMPLE_VALUES)}
      </p>
      <Active id="msg-active" value={m.active} onChange={(active) => set({ active })} />
    </EditorFrame>
  )
}

// ── employee codes ──────────────────────────────────────────────────

export function CodesSetup(props) {
  return (
    <>
      <ListScreen
        {...props}
        title="Employee codes"
        load={setup.series}
        addLabel="Add a series"
        blank={() => ({ name: '', prefix: 'EMP-{YY}-', suffix: '', digits: 4, startAt: 1, reset: 'yearly', appliesTo: {}, active: true })}
        summary={(s, l) => `Next code: ${s.example} · ${s.reset === 'yearly' ? 'starts again each year' : 'never restarts'} · ${describeRules(s.appliesTo, l)}`}
        Editor={SeriesEditor}
      />
      <p className="hint">
        A joiner with no code typed by HR gets the next code of the first series that applies to them, when they become an employee.
        With no series, HR types each code.
      </p>
    </>
  )
}

function SeriesEditor({ item, isNew, lookups, onDone, onCancel }) {
  const [s, setS] = useState(item)
  const { busy, error, run } = useAction()
  const set = (patch) => setS({ ...s, ...patch })
  const example = `${expandCode(s.prefix)}${String(Number(s.startAt) || 0).padStart(Number(s.digits) || 1, '0')}${expandCode(s.suffix)}`
  return (
    <EditorFrame
      title={isNew ? 'New series' : s.name}
      busy={busy}
      error={error}
      onCancel={onCancel}
      onSave={async () => {
        const body = { name: s.name, prefix: s.prefix, suffix: s.suffix, digits: Number(s.digits), startAt: Number(s.startAt), reset: s.reset,
          appliesTo: s.appliesTo || {}, active: s.active !== false }
        if (await run(() => setup.saveSeries(isNew ? null : s.id, body))) onDone()
      }}
    >
      <div className="grid">
        <div>
          <label htmlFor="ser-name">Name</label>
          <input id="ser-name" value={s.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ser-prefix">Before the number</label>
          <input id="ser-prefix" className="mono" value={s.prefix} onChange={(e) => set({ prefix: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ser-digits">Digits</label>
          <input id="ser-digits" inputMode="numeric" value={s.digits} onChange={(e) => set({ digits: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ser-suffix">After the number</label>
          <input id="ser-suffix" className="mono" value={s.suffix} onChange={(e) => set({ suffix: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ser-start">First number</label>
          <input id="ser-start" inputMode="numeric" value={s.startAt} onChange={(e) => set({ startAt: e.target.value })} />
        </div>
        <div>
          <label htmlFor="ser-reset">Numbering</label>
          <select id="ser-reset" value={s.reset} onChange={(e) => set({ reset: e.target.value })}>
            <option value="yearly">Starts again each year</option>
            <option value="never">Never restarts</option>
          </select>
        </div>
      </div>
      <p className="hint">
        Tokens: <span className="mono">{'{YYYY} {YY} {MM}'}</span> from the joining date, <span className="mono">{'{LOC} {DEPT}'}</span> the
        first letters of the work location and department, <span className="mono">{'{TYPE}'}</span> the kind of employment.
      </p>
      <p>
        First code, for someone joining today in Engineering at an office: <strong className="mono" data-testid="series-example">{example}</strong>
      </p>
      <RuleEditor idPrefix="ser" value={s.appliesTo} onChange={(appliesTo) => set({ appliesTo })} lookups={lookups} />
      <Active id="ser-active" value={s.active} onChange={(active) => set({ active })} />
    </EditorFrame>
  )
}
