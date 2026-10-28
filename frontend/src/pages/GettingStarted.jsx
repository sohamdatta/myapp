import { gettingStarted } from '../api.js'
import { useLoad } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'
import { navFor } from '../nav.js'
import { isPlainClick, pathFor } from '../router.js'
import StarterPicker from './StarterPicker.jsx'
import { GS } from './gettingStartedText.js'
import { checksFor, stepsFor } from './gettingStartedSteps.js'

/** A link to another page of the app that the app itself handles, and the browser can still open in a new tab. */
function PageLink({ page, onNavigate, children }) {
  return (
    <a
      className="outline"
      href={pathFor(page)}
      onClick={(e) => {
        if (isPlainClick(e)) {
          e.preventDefault()
          onNavigate(page)
        }
      }}
    >
      {children}
    </a>
  )
}

/** What payroll would stop on, beside the steps, so a person sees it before the first run rather than during it. */
function Readiness({ status, names }) {
  const t = GS.ready
  const checks = checksFor(status, names)
  const blocking = checks.filter((c) => c.level === 'blocking').length
  const summary = status.facts.people === 0 ? t.noPeople : blocking === 0 ? t.allClear : t.stops(blocking)
  return (
    <aside className="side-panel run-checks" aria-labelledby="ready-title">
      <h3 id="ready-title">{t.title}</h3>
      <p className="hint">{summary}</p>
      <ul className="exceptions">
        {checks.map((c) => (
          <li key={c.id} className={c.level === 'blocking' ? 'exception blocking' : 'exception'} data-check={c.id}>
            <div className="exception-head">
              <Badge tone={c.level === 'blocking' ? 'bad' : 'warn'}>{c.level === 'blocking' ? t.blocking : t.warning}</Badge>
              <span>{c.title}</span>
            </div>
            <p className="exception-text">
              {c.text}
              {!c.fixable && c.level === 'blocking' && <span className="hint"> {t.mustFix}</span>}
            </p>
          </li>
        ))}
      </ul>
    </aside>
  )
}

/**
 * Getting started: the steps from an empty Organization to one people can use, each done when the
 * thing it asks for exists, and what payroll still needs. It reads what the server counted, so it is
 * right whoever did the work and in whatever order.
 */
export default function GettingStarted({ me, onNavigate, onRefresh }) {
  const status = useLoad(gettingStarted.status)
  if (status.loading) return <p className="muted">{GS.loading}</p>
  if (status.error) return <ErrorNote>{status.error}</ErrorNote>
  const s = status.data
  const pages = navFor(me).flatMap((section) => section.items.map((item) => item.id))
  const steps = stepsFor(s, me, pages)
  const done = steps.filter((x) => x.done).length
  const current = steps.find((x) => !x.done)
  const names = Object.fromEntries(s.states.map((x) => [x.code, x.name]))
  return (
    <>
      <p className="muted">{GS.intro}</p>
      <div className="progress-row">
        <div className="progress" role="progressbar" aria-label={GS.progressLabel} aria-valuemin={0} aria-valuemax={steps.length} aria-valuenow={done}>
          <div className="progress-fill" style={{ width: `${(done / steps.length) * 100}%` }} />
        </div>
        <span className="count">{GS.progress(done, steps.length)}</span>
      </div>
      <div className="with-panel">
        <ol className="setup-steps with-panel-main">
          {steps.map((step, i) => (
            <li key={step.id} className={step === current ? 'setup-step setup-step-current' : 'setup-step'} aria-current={step === current ? 'step' : undefined}>
              <div className="setup-step-head">
                <span className={step.done ? 'setup-mark setup-mark-done' : step === current ? 'setup-mark setup-mark-current' : 'setup-mark'} aria-hidden="true">
                  {step.done ? '✓' : i + 1}
                </span>
                <div className="setup-step-text">
                  <h3>{step.title}</h3>
                  <p>{step.summary}</p>
                </div>
                {step.page && (
                  <PageLink page={step.page} onNavigate={onNavigate}>
                    {step.action}
                  </PageLink>
                )}
              </div>
              {step.picker && (
                <div className="setup-step-body">
                  <StarterPicker
                    status={s}
                    onApplied={() => {
                      status.reload()
                      onRefresh?.()
                    }}
                  />
                </div>
              )}
            </li>
          ))}
        </ol>
        {s.payrollOn && <Readiness status={s} names={names} />}
      </div>
    </>
  )
}
