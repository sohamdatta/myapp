import { useState } from 'react'
import { onboarding, people, tasks } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, ErrorNote, StatusBadge, Chevron, SectionBadge } from '../ui.jsx'
import DocumentList from './DocumentList.jsx'
import FileViewer from './FileViewer.jsx'
import SectionForm from './SectionForm.jsx'
import { TaskRow } from './TaskList.jsx'
import { letterState } from './onboardingText.js'

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

  if (m.status === 'offered') {
    return (
      <>
        <section className="panel welcome">
          <h2>
            {m.organization} has an offer for you, {firstName}
          </h2>
          <p className="muted">
            {[m.designation, m.managerName && `reporting to ${m.managerName}`, m.workLocation, `from ${formatDay(m.joiningDate)}`]
              .filter(Boolean)
              .join(' · ')}
          </p>
          <p>Read the offer letter, then accept or decline it. Once you accept, you can fill in your details.</p>
        </section>
        <MyLetters offerOnly onAnswered={mine.reload} />
      </>
    )
  }

  if (m.status === 'offer_declined') {
    return (
      <section className="panel">
        <h2>You declined the offer</h2>
        <p>Thank you for letting us know. If this was a mistake, write to HR.</p>
      </section>
    )
  }

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
          <MyLetters onAnswered={mine.reload} />
          <MyPolicies onChanged={mine.reload} />
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
          <SectionBadge status={summary.status} />
          <Chevron open={open} />
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

/** Letters for the joiner: to read, and to accept (or decline, when the letter allows). */
function MyLetters({ offerOnly, onAnswered }) {
  const list = useLoad(onboarding.letters)
  const [viewing, setViewing] = useState(null)
  const [declining, setDeclining] = useState(null)
  const { busy, error, run } = useAction()
  if (list.loading || list.error) return list.error ? <ErrorNote>{list.error}</ErrorNote> : null
  const letters = list.data.filter((l) => !offerOnly || (l.kind === 'offer' && !l.response))
  if (letters.length === 0) return null

  async function view(l) {
    const blob = await run(() => onboarding.documentFile(l.documentId))
    if (blob) setViewing({ name: `${l.title}.pdf`, type: 'application/pdf', url: URL.createObjectURL(blob) })
  }

  async function answer(l, accept) {
    const done = await run(() => onboarding.answerLetter(l.id, accept))
    if (done) {
      setDeclining(null)
      list.reload()
      onAnswered()
    }
  }

  return (
    <section className="panel letters">
      <h3>{offerOnly ? 'Your offer' : 'Letters'}</h3>
      {letters.map((l) => {
        const state = letterState(l)
        return (
          <div key={l.id} className="doc-row" data-letter={l.title}>
            <div className="doc-head">
              <div>
                <strong>{l.title}</strong>
                <div className="hint">
                  {l.expiresOn && state === 'waiting' && `Please answer by ${formatDay(l.expiresOn)}`}
                  {state === 'expired' && 'This letter has expired; HR will send a new one if needed'}
                  {state === 'accepted' && 'You accepted this letter'}
                  {state === 'declined' && 'You declined this letter'}
                </div>
              </div>
              {state === 'waiting' ? <Badge tone="warn">Waiting for you</Badge> : <StatusBadge status={state === 'none' ? 'issued' : state} />}
            </div>
            <div className="form-actions">
              <button type="button" className="outline" onClick={() => view(l)}>
                Read the letter
              </button>
              {state === 'waiting' && declining !== l.id && (
                <>
                  <button type="button" className="primary" disabled={busy} onClick={() => answer(l, true)}>
                    Accept
                  </button>
                  {l.responseNeeded === 'accept_or_decline' && (
                    <button type="button" className="quiet danger" onClick={() => setDeclining(l.id)}>
                      Decline
                    </button>
                  )}
                </>
              )}
            </div>
            {declining === l.id && (
              <div className="verify-box">
                <p>
                  {l.kind === 'offer'
                    ? 'Declining the offer ends your joining here, and this login stops working.'
                    : 'HR will see that you declined this letter.'}
                </p>
                <div className="form-actions">
                  <button type="button" className="secondary danger" disabled={busy} onClick={() => answer(l, false)}>
                    Yes, decline
                  </button>
                  <button type="button" className="quiet" onClick={() => setDeclining(null)}>
                    Keep it
                  </button>
                </div>
              </div>
            )}
          </div>
        )
      })}
      <ErrorNote>{error}</ErrorNote>
      {viewing && <FileViewer file={viewing} onClose={() => setViewing(null)} />}
    </section>
  )
}

/** Policies to read and acknowledge, each by the version read. */
function MyPolicies({ onChanged }) {
  const list = useLoad(onboarding.policies)
  const [open, setOpen] = useState(null)
  const { busy, error, run } = useAction()
  if (list.loading || list.error || list.data.length === 0) return list.error ? <ErrorNote>{list.error}</ErrorNote> : null
  return (
    <section className="panel policies">
      <h3>Policies</h3>
      {list.data.map((p) => (
        <div key={p.id} className="doc-row" data-policy={p.name}>
          <div className="doc-head">
            <div>
              <strong>{p.name}</strong>
              <div className="hint">{p.due === 'before_activation' ? 'Before you join' : `By ${formatDay(p.dueOn)}`}</div>
            </div>
            {p.current ? <Badge tone="good">Acknowledged</Badge> : <Badge tone="warn">To read</Badge>}
          </div>
          {open === p.id ? (
            <>
              <div className="notice-body prewrap">{p.body}</div>
              {!p.current && (
                <div className="form-actions">
                  <button
                    type="button"
                    className="primary"
                    disabled={busy}
                    onClick={async () => {
                      if (await run(() => onboarding.acknowledge(p.id, p.version))) {
                        setOpen(null)
                        list.reload()
                        onChanged()
                      }
                    }}
                  >
                    I have read and accept this policy
                  </button>
                </div>
              )}
            </>
          ) : (
            <button type="button" className="outline" onClick={() => setOpen(p.id)}>
              Read
            </button>
          )}
        </div>
      ))}
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

