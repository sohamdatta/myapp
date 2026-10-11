import { useState } from 'react'
import { onboarding, people, tasks } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, ErrorNote, StatusBadge } from '../ui.jsx'
import DocumentList from './DocumentList.jsx'
import SectionForm from './SectionForm.jsx'
import { TaskRow } from './TaskList.jsx'
import { SECTION_STATUS } from './onboardingText.js'

/**
 * The joiner's own onboarding: a welcome, the privacy notice to accept, and
 * one form per section to fill in and send for checking.
 */
export default function MyOnboarding() {
  const mine = useLoad(onboarding.mine)
  const states = useLoad(people.states)
  const [open, setOpen] = useState(null)
  const { busy, error, run } = useAction()

  if (mine.loading || states.loading) return <p className="muted">Loading…</p>
  if (mine.error) return <ErrorNote>{mine.error}</ErrorNote>
  const m = mine.data
  const yours = m.sections.filter((s) => s.yours)
  const sent = yours.filter((s) => ['submitted', 'verified'].includes(s.status)).length
  const checked = m.sections.filter((s) => s.status === 'verified').length
  const firstName = m.legalName?.split(' ')[0]

  if (m.status === 'active') {
    return (
      <section className="panel">
        <h2>Welcome aboard, {firstName}</h2>
        <p>Your joining is complete. Sign out and in again to see everything you now have access to.</p>
      </section>
    )
  }

  return (
    <>
      <section className="panel welcome">
        <h2>
          Welcome to {m.organization}, {firstName}
        </h2>
        <p className="muted">
          {[m.designation, m.managerName && `reporting to ${m.managerName}`, m.workLocation].filter(Boolean).join(' · ')}
        </p>
        <p>
          You join on <strong>{formatDay(m.joiningDate)}</strong>. Fill in each section below and send it for checking; HR will
          tell you if anything needs changing.
        </p>
        {m.welcome?.welcomeMessage && <p className="prewrap">{m.welcome.welcomeMessage}</p>}
        {m.welcome?.dayOneNote && (
          <p className="prewrap">
            <strong>On your first day:</strong> {m.welcome.dayOneNote}
          </p>
        )}
        {m.welcome?.hrContact && <p className="hint">Questions? Write to {m.welcome.hrContact}.</p>}
      </section>

      {!m.noticeAccepted ? (
        <section className="panel">
          <h2>Before you start</h2>
          <div className="notice-body prewrap">{m.notice?.body}</div>
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button
              type="button"
              className="primary"
              disabled={busy || !m.notice}
              onClick={async () => {
                if (await run(() => onboarding.acceptNotice(m.notice.id))) mine.reload()
              }}
            >
              I have read this
            </button>
          </div>
        </section>
      ) : (
        <>
          <div className="progress-row" aria-label="Progress">
            <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={yours.length} aria-valuenow={sent}>
              <div className="progress-fill" style={{ width: `${yours.length ? (sent / yours.length) * 100 : 0}%` }} />
            </div>
            <span className="hint">
              {sent} of {yours.length} sent · {checked} checked
            </span>
          </div>
          <YourTasks />
          <div className="section-list">
            {m.sections.map((s) => (
              <MySection
                key={s.section}
                summary={s}
                open={open === s.section}
                onToggle={() => setOpen(open === s.section ? null : s.section)}
                states={states.data || {}}
                me={m}
                onChanged={mine.reload}
                onSent={() => {
                  const next = m.sections.find((x) => x.yours && x.section !== s.section && ['not_started', 'draft', 'returned'].includes(x.status))
                  setOpen(next ? next.section : null)
                  mine.reload()
                }}
              />
            ))}
          </div>
        </>
      )}
    </>
  )
}

function MySection({ summary, open, onToggle, states, me, onChanged, onSent }) {
  if (!summary.yours) {
    return (
      <section className="panel section-card">
        <div className="section-toggle static">
          <span className="section-title">{summary.title}</span>
          <Badge>Filled in by HR</Badge>
        </div>
        <p className="hint">
          {[me.designation, me.managerName && `reporting to ${me.managerName}`, me.workLocation, `joining ${formatDay(me.joiningDate)}`]
            .filter(Boolean)
            .join(' · ')}
        </p>
      </section>
    )
  }
  return (
    <section className={`panel section-card section-${summary.status}`}>
      <button type="button" className="section-toggle" aria-expanded={open} onClick={onToggle}>
        <span className="section-title">{summary.title}</span>
        <span className="section-state">
          <StatusBadge status={summary.status} />
          <span className="sr-only">{SECTION_STATUS[summary.status]}</span>
        </span>
      </button>
      {summary.status === 'returned' && summary.note && (
        <p className="notice">
          <strong>HR asks:</strong> {summary.note}
        </p>
      )}
      {open && summary.section === 'documents' && <MyDocuments onChanged={onChanged} />}
      {open && summary.section !== 'documents' && (
        <MySectionForm section={summary.section} states={states} onChanged={onChanged} onSent={onSent} />
      )}
    </section>
  )
}

function MySectionForm({ section, states, onChanged, onSent }) {
  const detail = useLoad(() => onboarding.section(section))
  if (detail.loading) return <p className="muted">Loading…</p>
  if (detail.error) return <ErrorNote>{detail.error}</ErrorNote>
  const d = detail.data
  return (
    <>
      {!d.canEdit && (
        <p className="hint">{d.status === 'verified' ? 'HR has checked this.' : 'Sent for checking. HR will return it if anything needs changing.'}</p>
      )}
      <SectionForm
        key={`${d.status}-${JSON.stringify(d.values)}`}
        view={d}
        canEdit={d.canEdit}
        editableField={(f) => f.editableBy === 'joiner'}
        lookups={{ states }}
        onSave={async (values) => {
          await onboarding.save(section, values)
          detail.reload()
          onChanged()
        }}
        onSubmit={async () => {
          await onboarding.submit(section)
          onSent()
        }}
      />
    </>
  )
}

function MyDocuments({ onChanged }) {
  const docs = useLoad(onboarding.documents)
  if (docs.loading) return <p className="muted">Loading…</p>
  if (docs.error) return <ErrorNote>{docs.error}</ErrorNote>
  return (
    <>
      <p className="hint">A clear photo or a PDF of each. Each one is checked by HR; you will see here if one needs sending again.</p>
      <DocumentList
        joiner
        data={docs.data}
        canUpload={docs.data.canUpload}
        canOpen
        onUpload={async (typeId, file, onProgress) => {
          await onboarding.upload(typeId, file, onProgress)
          docs.reload()
          onChanged()
        }}
        onOpen={onboarding.documentFile}
      />
    </>
  )
}

/** Checklist tasks that are the joiner's own, such as bringing original documents on the first day. */
function YourTasks() {
  const list = useLoad(tasks.mine)
  if (list.loading || list.error || list.data.length === 0) return null
  return (
    <section className="panel">
      <h3>Your tasks</h3>
      {list.data.map((t) => (
        <TaskRow key={t.id} task={t} onChanged={list.reload} />
      ))}
    </section>
  )
}
