import { useState } from 'react'
import { usage } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'

/** What a module's state means, in the words the platform team uses. */
const STATES = {
  active: { tone: 'good', label: 'Active', about: 'used in the last 7 days' },
  quiet: { tone: 'warn', label: 'Quiet', about: 'used in the last 30 days, not the last 7' },
  bought_not_used: { tone: 'bad', label: 'Bought, not used', about: 'on, and no use in 30 days' },
  not_bought: { tone: 'neutral', label: 'Not bought', about: 'switched off' },
}

const number = (v) => (v === null || v === undefined ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 }))

/** Twelve weeks as small bars, oldest first. A week with nothing to say is a thin mark, not a zero. */
function Spark({ values, label }) {
  const nums = values.map((v) => (v === null || v === undefined ? null : Number(v)))
  const top = Math.max(1, ...nums.filter((v) => v !== null))
  const w = 9
  const gap = 3
  const h = 28
  const text = `${label}: ${nums.map((v) => (v === null ? 'none' : v)).join(', ')}, oldest week first`
  return (
    <svg className="usage-spark" width={values.length * (w + gap)} height={h} role="img" aria-label={text}>
      <title>{text}</title>
      {nums.map((v, i) => {
        const bar = v === null ? 1 : Math.max(1, Math.round((v / top) * (h - 2)))
        return <rect key={i} x={i * (w + gap)} y={h - bar} width={w} height={bar} rx="1.5" className={v === null ? 'spark-none' : 'spark-bar'} />
      })}
    </svg>
  )
}

/** Platform console → Usage: how each Organization uses each feature, as counts only. Nothing here comes from inside an Organization. */
export default function Usage({ me }) {
  const [view, setView] = useState({ name: 'grid' })
  if (view.name === 'organization') return <OrganizationTrend id={view.id} onBack={() => setView(view.from || { name: 'grid' })} onMetric={(code) => setView({ name: 'metric', code, from: view })} />
  if (view.name === 'metric') return <MetricAcross code={view.code} onBack={() => setView(view.from || { name: 'grid' })} onOrganization={(id) => setView({ name: 'organization', id, from: view })} />
  return <Grid me={me} onOrganization={(id) => setView({ name: 'organization', id })} />
}

function Grid({ me, onOrganization }) {
  const loaded = useLoad(usage.grid)
  const [grid, setGrid] = useState(null)
  const [note, setNote] = useState('')
  const { busy, error, run } = useAction()
  const g = grid || loaded.data
  if (loaded.loading && !g) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  const modules = g.organizations[0]?.cells.map((c) => c.name) || []
  return (
    <section className="panel" data-testid="usage-grid">
      <div className="section-head">
        <h3>Usage by Organization</h3>
        <div className="form-actions">
          {me.platformRole === 'super_admin' && (
            <button
              type="button"
              className="secondary"
              disabled={busy}
              onClick={async () => {
                setNote('')
                const done = await run(() => usage.refresh())
                const next = done && (await run(() => usage.grid()))
                if (next) {
                  setGrid(next)
                  setNote(`Counted ${done.days} days for ${done.organizations} Organizations.`)
                }
              }}
            >
              Count again now
            </button>
          )}
          <button type="button" className="secondary" disabled={busy} onClick={() => run(usage.gridCsv)}>
            Export CSV
          </button>
        </div>
      </div>
      <p className="hint">
        Counts only: how many times each feature was used, never who or what. Each night at 01:00 India time every Organization is counted from the
        records the app already keeps.{' '}
        {g.latestDay ? `Counted up to ${formatDay(g.latestDay)}.` : 'Nothing has been counted yet: the first count runs shortly after the server starts.'}
      </p>
      <ErrorNote>{error}</ErrorNote>
      {note && <p className="notice">{note}</p>}
      <ul className="usage-key" aria-label="What the states mean">
        {Object.entries(STATES).map(([id, s]) => (
          <li key={id}>
            <Badge tone={s.tone}>{s.label}</Badge> <span className="hint">{s.about}</span>
          </li>
        ))}
      </ul>
      {g.organizations.length === 0 ? (
        <Empty>No Organizations yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Organization</th>
                <th>People</th>
                {modules.map((m) => (
                  <th key={m}>{m}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {g.organizations.map((o) => (
                <tr key={o.id} data-organization={o.name}>
                  <td>
                    <button type="button" className="name-link" onClick={() => onOrganization(o.id)}>
                      {o.name}
                    </button>
                    {o.status !== 'active' && <Badge tone="warn">{o.status}</Badge>}
                  </td>
                  <td>{number(o.members)}</td>
                  {o.cells.map((c) => (
                    <td key={c.module} data-module={c.module} data-state={c.state}>
                      <Badge tone={STATES[c.state].tone}>{STATES[c.state].label}</Badge>
                      {c.bought && <div className="hint">{number(c.uses30)} in 30 days</div>}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/** Group a list by a key, keeping the order things first appear in. */
function groupBy(list, key) {
  const out = []
  for (const item of list) {
    let group = out.find((x) => x.key === item[key])
    if (!group) out.push((group = { key: item[key], items: [] }))
    group.items.push(item)
  }
  return out
}

function Back({ onBack, children }) {
  return (
    <p>
      <button type="button" className="quiet" onClick={onBack}>
        ← {children}
      </button>
    </p>
  )
}

function OrganizationTrend({ id, onBack, onMetric }) {
  const loaded = useLoad(() => usage.trend(id))
  const { busy, error, run } = useAction()
  const t = loaded.data
  if (loaded.loading && !t) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  return (
    <section className="panel" data-testid="usage-organization">
      <Back onBack={onBack}>All Organizations</Back>
      <div className="section-head">
        <h3>{t.name}: the last 12 weeks</h3>
        <button type="button" className="secondary" disabled={busy} onClick={() => run(() => usage.trendCsv(id))}>
          Export CSV
        </button>
      </div>
      <p className="hint">
        Weeks start on Monday, from {formatDay(t.weekStarts[0])}. Counts add up over the week; people and active people are the last day&apos;s; medians and
        shares are averaged over the days that had one.
      </p>
      <ErrorNote>{error}</ErrorNote>
      {t.series.length === 0 ? (
        <Empty>Nothing has been counted for this Organization yet.</Empty>
      ) : (
        groupBy(t.series, 'area').map((g) => (
          <div key={g.key} className="usage-area">
            <h4>{g.key}</h4>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>What</th>
                    <th>12 weeks</th>
                    <th>This week so far</th>
                  </tr>
                </thead>
                <tbody>
                  {g.items.map((s) => (
                    <tr key={s.code} data-metric={s.code}>
                      <td>
                        <button type="button" className="name-link" onClick={() => onMetric(s.code)}>
                          {s.description}
                        </button>
                      </td>
                      <td>
                        <Spark values={s.weeks} label={s.description} />
                      </td>
                      <td>{number(s.weeks[s.weeks.length - 1])}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))
      )}
    </section>
  )
}

function MetricAcross({ code, onBack, onOrganization }) {
  const loaded = useLoad(() => usage.metric(code))
  const { busy, error, run } = useAction()
  const m = loaded.data
  if (loaded.loading && !m) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  return (
    <section className="panel" data-testid="usage-metric">
      <Back onBack={onBack}>Back</Back>
      <div className="section-head">
        <h3>{m.description}</h3>
        <button type="button" className="secondary" disabled={busy} onClick={() => run(() => usage.metricCsv(code))}>
          Export CSV
        </button>
      </div>
      <p className="hint">
        {m.area}, across every Organization, the last 12 weeks from {formatDay(m.weekStarts[0])}.
      </p>
      <ErrorNote>{error}</ErrorNote>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Organization</th>
              <th>12 weeks</th>
              <th>{m.kind === 'count' ? 'Total' : 'Latest week'}</th>
            </tr>
          </thead>
          <tbody>
            {m.organizations.map((o) => (
              <tr key={o.id} data-organization={o.name}>
                <td>
                  <button type="button" className="name-link" onClick={() => onOrganization(o.id)}>
                    {o.name}
                  </button>
                </td>
                <td>
                  <Spark values={o.weeks} label={`${o.name}, ${m.description}`} />
                </td>
                <td>{number(o.total)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
