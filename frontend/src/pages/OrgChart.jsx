import { useEffect, useMemo, useState } from 'react'
import { people as peopleApi } from '../api.js'
import { formatDay, handoff, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'

const initials = (name) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('')

const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`

/** Who reports to whom, built once from the flat list the server sends. */
function shape(list) {
  const byId = new Map(list.map((p) => [p.id, p]))
  const reports = new Map()
  for (const p of list) {
    if (p.managerId && byId.has(p.managerId)) {
      if (!reports.has(p.managerId)) reports.set(p.managerId, [])
      reports.get(p.managerId).push(p)
    }
  }
  const tops = list.filter((p) => !p.managerId || !byId.has(p.managerId))
  // Count everyone under a person, for "12 in their team".
  const under = new Map()
  const count = (id, seen = new Set()) => {
    if (under.has(id)) return under.get(id)
    if (seen.has(id)) return 0
    seen.add(id)
    const n = (reports.get(id) || []).reduce((sum, r) => sum + 1 + count(r.id, seen), 0)
    under.set(id, n)
    return n
  }
  list.forEach((p) => count(p.id))
  const chain = (id) => {
    const out = []
    let at = byId.get(id)
    const seen = new Set()
    while (at && at.managerId && byId.has(at.managerId) && !seen.has(at.id)) {
      seen.add(at.id)
      out.push(at.managerId)
      at = byId.get(at.managerId)
    }
    return out
  }
  // Leaders (with people under them) form the chart; people who report to nobody and lead nobody are listed apart.
  const leaders = tops.filter((p) => reports.has(p.id))
  const alone = tops.filter((p) => !reports.has(p.id))
  return { byId, reports, under, chain, leaders, alone }
}

/**
 * The org chart: everyone working here, in their reporting lines. Anyone can find a person, see
 * their manager and team, and open the record when their access allows it.
 */
export default function OrgChart({ onNavigate }) {
  const chart = useLoad(() => peopleApi.orgChart())
  if (chart.loading) return <p className="muted">Loading…</p>
  if (chart.error) return <ErrorNote>{chart.error}</ErrorNote>
  if (!chart.data.people.length) return <Empty>Nobody is working here yet. People appear once they are employees.</Empty>
  return <Chart data={chart.data} onNavigate={onNavigate} />
}

function Chart({ data, onNavigate }) {
  const tree = useMemo(() => shape(data.people), [data.people])
  const [open, setOpen] = useState(() => {
    // Start with the leaders and their direct reports showing, and the way down to yourself.
    const ids = new Set(tree.leaders.map((p) => p.id))
    if (data.me) tree.chain(data.me).forEach((id) => ids.add(id))
    return ids
  })
  const [selected, setSelected] = useState(data.me || tree.leaders[0]?.id || null)
  const [search, setSearch] = useState('')
  const [focus, setFocus] = useState(null)

  useEffect(() => {
    if (!focus) return
    document.getElementById(`org-${focus}`)?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' })
  }, [focus, open])

  const show = (id) => {
    setOpen((was) => new Set([...was, ...tree.chain(id)]))
    setSelected(id)
    setFocus(id)
    setSearch('')
  }
  const toggle = (id) =>
    setOpen((was) => {
      const next = new Set(was)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const words = search.trim().toLowerCase()
  const matches = words
    ? data.people
        .filter((p) => [p.name, p.designation, p.departmentName, p.email].some((v) => v && v.toLowerCase().includes(words)))
        .slice(0, 8)
    : []
  const person = selected ? tree.byId.get(selected) : null

  return (
    <div className="org-chart">
      <div className="filters org-tools">
        <div className="org-search">
          <label htmlFor="org-search" className="sr-only">
            Find someone
          </label>
          <input
            id="org-search"
            type="search"
            placeholder="Find someone by name, role or department"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && matches[0]) show(matches[0].id)
            }}
          />
          {words && (
            <ul className="org-matches" aria-label="People found">
              {matches.length === 0 && <li className="hint">Nobody matches.</li>}
              {matches.map((p) => (
                <li key={p.id}>
                  <button type="button" className="quiet plain" onClick={() => show(p.id)}>
                    <strong>{p.name}</strong> <span className="hint">{[p.designation, p.departmentName].filter(Boolean).join(' · ')}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        {data.me && (
          <button type="button" className="outline" onClick={() => show(data.me)}>
            Show me
          </button>
        )}
        <button type="button" className="quiet" onClick={() => setOpen(new Set(tree.reports.keys()))}>
          Expand all
        </button>
        <button type="button" className="quiet" onClick={() => setOpen(new Set())}>
          Collapse all
        </button>
        <span className="spacer" />
        <span className="hint">
          {plural(data.people.length, 'person', 'people')} · as of {formatDay(data.asOf)}
        </span>
      </div>

      {person && (
        <PersonPanel
          person={person}
          me={data.me}
          tree={tree}
          onSelect={show}
          onOpen={() => {
            if (person.id === data.me) onNavigate('my-profile')
            else {
              handoff.set({ openEmployee: person.id })
              onNavigate('people')
            }
          }}
        />
      )}

      <div className="org-scroll" role="tree" aria-label="Org chart">
        <ul className="org-tree">
          {tree.leaders.map((p) => (
            <Branch key={p.id} person={p} tree={tree} open={open} onToggle={toggle} selected={selected} me={data.me} onSelect={setSelected} />
          ))}
        </ul>
      </div>

      {tree.alone.length > 0 && (
        <section className="panel org-alone">
          <h3>
            No reporting line <span className="hint">{plural(tree.alone.length, 'person', 'people')}</span>
          </h3>
          <p className="hint">They report to nobody and lead nobody. HR sets a manager with Change job on their record.</p>
          <ul className="org-alone-list">
            {tree.alone.map((p) => (
              <li key={p.id}>
                <Card person={p} tree={tree} selected={selected} me={data.me} onSelect={setSelected} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function Branch({ person, tree, open, onToggle, selected, me, onSelect }) {
  const team = tree.reports.get(person.id) || []
  const isOpen = open.has(person.id) && team.length > 0
  return (
    <li role="treeitem" aria-expanded={team.length ? isOpen : undefined} aria-selected={selected === person.id}>
      <Card person={person} tree={tree} selected={selected} me={me} onSelect={onSelect} open={isOpen} onToggle={() => onToggle(person.id)} />
      {isOpen && (
        <ul role="group">
          {team.map((r) => (
            <Branch key={r.id} person={r} tree={tree} open={open} onToggle={onToggle} selected={selected} me={me} onSelect={onSelect} />
          ))}
        </ul>
      )}
    </li>
  )
}

function Card({ person: p, tree, selected, me, onSelect, open, onToggle }) {
  const direct = (tree.reports.get(p.id) || []).length
  const classes = ['org-card', selected === p.id && 'selected', me === p.id && 'is-me'].filter(Boolean).join(' ')
  return (
    <div className={classes} id={`org-${p.id}`} data-person={p.name}>
      <button type="button" className="org-who" onClick={() => onSelect(p.id)} aria-pressed={selected === p.id}>
        <span className="org-avatar" aria-hidden="true">
          {initials(p.name)}
        </span>
        <span className="org-text">
          <span className="org-name">
            {p.name} {me === p.id && <Badge tone="good">You</Badge>}
          </span>
          <span className="org-role">{p.designation || 'No designation'}</span>
          {p.departmentName && <span className="hint">{p.departmentName}</span>}
        </span>
      </button>
      {direct > 0 && onToggle && (
        <button type="button" className="org-toggle" onClick={onToggle} aria-expanded={open}>
          {open ? 'Hide' : 'Show'} {plural(direct, 'report', 'reports')}
        </button>
      )}
    </div>
  )
}

function PersonPanel({ person: p, me, tree, onSelect, onOpen }) {
  const manager = p.managerId ? tree.byId.get(p.managerId) : null
  const team = tree.reports.get(p.id) || []
  const under = tree.under.get(p.id) || 0
  return (
    <section className="panel org-person" aria-live="polite" data-testid="org-person">
      <div className="org-person-head">
        <span className="org-avatar large" aria-hidden="true">
          {initials(p.name)}
        </span>
        <div>
          <h2>
            {p.name} {me === p.id && <Badge tone="good">You</Badge>}
          </h2>
          <p className="muted">{[p.designation, p.departmentName].filter(Boolean).join(' · ') || 'No designation yet'}</p>
        </div>
        <span className="spacer" />
        {p.canOpen && (
          <button type="button" className="outline" onClick={onOpen}>
            {me === p.id ? 'My profile' : 'Open their record'}
          </button>
        )}
      </div>
      <dl className="facts">
        <dt>Reports to</dt>
        <dd>
          {manager ? (
            <button type="button" className="text-link" onClick={() => onSelect(manager.id)}>
              {manager.name}
            </button>
          ) : (
            <span className="hint">Nobody</span>
          )}
        </dd>
        <dt>Team</dt>
        <dd>
          {team.length === 0 ? (
            <span className="hint">{me === p.id ? 'Nobody reports to you' : 'Nobody reports to them'}</span>
          ) : (
            <>
              {plural(team.length, 'direct report', 'direct reports')}
              {under > team.length && `, ${under} in all`}
            </>
          )}
        </dd>
        {p.email && (
          <>
            <dt>Email</dt>
            <dd>
              <a href={`mailto:${p.email}`}>{p.email}</a>
            </dd>
          </>
        )}
      </dl>
    </section>
  )
}
