import { useState } from 'react'
import { downloadDocument as download, exits } from '../api.js'
import { formatDate, useAction } from '../hooks.js'
import { Badge, ErrorNote } from '../ui.jsx'

const INTERVIEW = {
  sent: ['warn', 'Sent, waiting for answers'],
  submitted: ['ok', 'Answered'],
  declined: ['neutral', 'Declined'],
  in_person: ['ok', 'Held in person'],
}

function LetterList({ letters, onError }) {
  if (!letters?.length) return null
  return (
    <ul className="letter-list">
      {letters.map((l) => (
        <li key={l.id} data-letter={l.title}>
          <button type="button" className="plain link" onClick={() => onError(() => download(l.documentId, `${l.title}.pdf`))}>
            {l.title}
          </button>{' '}
          <span className="hint">issued {formatDate(l.issuedAt)}</span>
        </li>
      ))}
    </ul>
  )
}

/** HR: the letters issued for this exit, and the ones that can be issued now or later. */
export function ExitLettersPanel({ exit: x, onChanged }) {
  const [preview, setPreview] = useState(null)
  const { busy, error, run } = useAction()
  const templates = x.letterTemplates || []
  if (!x.letters?.length && !templates.length) return null
  return (
    <section className="panel" data-testid="exit-letters">
      <h3>Letters</h3>
      <LetterList letters={x.letters} onError={run} />
      {templates.length > 0 && (
        <div className="rows">
          {templates.map((t) => {
            const done = x.letters.some((l) => l.kind === t.kind)
            return (
              <div key={t.id} className="letter-option" data-template={t.name}>
                <div>
                  <strong>{t.name}</strong> {done && <Badge tone="ok">Issued</Badge>}
                  {t.waitingFor && <div className="hint">{t.waitingFor}</div>}
                </div>
                <div className="form-actions">
                  <button
                    type="button"
                    className="quiet"
                    onClick={async () => {
                      const p = await run(() => exits.previewLetter(x.id, t.id))
                      if (p) setPreview({ name: t.name, text: p.text })
                    }}
                  >
                    Preview
                  </button>
                  <button
                    type="button"
                    className={done ? 'outline' : 'secondary'}
                    disabled={busy || !!t.waitingFor}
                    onClick={async () => {
                      if (await run(() => exits.issueLetter(x.id, t.id))) {
                        setPreview(null)
                        onChanged()
                      }
                    }}
                  >
                    {done ? 'Issue again' : 'Issue'}
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      )}
      <ErrorNote>{error}</ErrorNote>
      {preview && (
        <>
          <p className="hint">
            {preview.name}, filled for this exit:{' '}
            <button type="button" className="quiet plain" onClick={() => setPreview(null)}>
              Hide
            </button>
          </p>
          <pre className="letter-preview">{preview.text}</pre>
        </>
      )}
    </section>
  )
}

/** HR: send the exit interview, read the answers, write notes from one held in person. */
export function InterviewPanel({ exit: x, onChanged }) {
  const [answers, setAnswers] = useState(null)
  const [writing, setWriting] = useState(false)
  const [notes, setNotes] = useState('')
  const { busy, error, run } = useAction()
  const i = x.interview
  if (!i && !x.canSendInterview && !x.canRecordInterview) return null
  const [tone, words] = i ? INTERVIEW[i.status] || ['neutral', i.status] : ['neutral', 'Not sent']

  async function open() {
    const a = await run(() => exits.interview(x.id))
    if (a) {
      setAnswers(a)
      setNotes(a.notes || '')
    }
  }

  return (
    <section className="panel" data-testid="exit-interview">
      <div className="toolbar">
        <h3>
          Exit interview <Badge tone={tone}>{words}</Badge>
        </h3>
        <span className="spacer" />
        {x.canSendInterview && (
          <button
            type="button"
            className="secondary"
            disabled={busy}
            onClick={async () => {
              if (await run(() => exits.sendInterview(x.id))) onChanged()
            }}
          >
            Send the interview
          </button>
        )}
        {i && x.canRecordInterview && !answers && (
          <button type="button" className="outline" onClick={open}>
            Read it
          </button>
        )}
        {x.canRecordInterview && !writing && (
          <button
            type="button"
            className="quiet"
            onClick={async () => {
              if (i && !answers) await open()
              setWriting(true)
            }}
          >
            {i?.status === 'in_person' || answers?.notes ? 'Change the notes' : 'Held in person? Write notes'}
          </button>
        )}
      </div>
      <p className="hint">Only HR reads the answers; the manager sees only whether it happened.</p>
      {answers?.answers && (
        <dl className="facts interview-answers">
          {(answers.questions || []).map((q) => (
            <div key={q.id} className="contents">
              <dt>{q.text}</dt>
              <dd>{answers.answers[q.id] === undefined ? <span className="hint">Not answered</span> : String(answers.answers[q.id]) + (q.type === 'rating' ? ' of 5' : '')}</dd>
            </div>
          ))}
        </dl>
      )}
      {answers?.notes && !writing && <pre className="letter-preview">{answers.notes}</pre>}
      {writing && (
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            const saved = await run(() => exits.interviewNotes(x.id, notes))
            if (saved) {
              setAnswers(saved)
              setWriting(false)
              onChanged()
            }
          }}
        >
          <label htmlFor="interview-notes">Notes from the conversation</label>
          <textarea id="interview-notes" rows={5} value={notes} onChange={(e) => setNotes(e.target.value)} />
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>
              Save the notes
            </button>
            <button type="button" className="quiet" onClick={() => setWriting(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

/** The employee's own letters. */
export function MyLetters({ letters }) {
  const { error, run } = useAction()
  if (!letters?.length) return null
  return (
    <section className="panel" data-testid="my-letters">
      <h3>Your letters</h3>
      <LetterList letters={letters} onError={run} />
      <ErrorNote>{error}</ErrorNote>
    </section>
  )
}

/** The employee answers the exit interview, or declines it. */
export function MyInterview({ interview: i, onChanged }) {
  const [answers, setAnswers] = useState({})
  const { busy, error, run } = useAction()
  if (!i) return null
  if (i.status !== 'sent') {
    return (
      <section className="panel" data-testid="my-interview">
        <h3>Exit interview</h3>
        <p className="muted">{i.status === 'declined' ? 'You chose not to answer. Thank you.' : 'Thank you for your answers.'}</p>
      </section>
    )
  }
  const set = (id, value) => setAnswers((a) => ({ ...a, [id]: value }))
  return (
    <section className="panel" data-testid="my-interview">
      <h3>Exit interview</h3>
      <p className="hint">Only HR reads your answers, not your manager. Answer what you like; every question can be left out.</p>
      <form
        onSubmit={async (e) => {
          e.preventDefault()
          if (await run(() => exits.answerInterview(answers))) onChanged()
        }}
      >
        {i.questions.map((q) => (
          <fieldset key={q.id} className="question">
            <legend>{q.text}</legend>
            {q.type === 'rating' && (
              <div className="rating" role="radiogroup" aria-label={q.text}>
                {[1, 2, 3, 4, 5].map((n) => (
                  <label key={n} className="check">
                    <input type="radio" name={`q-${q.id}`} checked={answers[q.id] === n} onChange={() => set(q.id, n)} />
                    {n}
                  </label>
                ))}
                <span className="hint">1 poor · 5 very good</span>
              </div>
            )}
            {q.type === 'choice' && (
              <select aria-label={q.text} value={answers[q.id] || ''} onChange={(e) => set(q.id, e.target.value)}>
                <option value="">Choose…</option>
                {q.choices.map((ch) => (
                  <option key={ch} value={ch}>
                    {ch}
                  </option>
                ))}
              </select>
            )}
            {q.type === 'text' && <textarea aria-label={q.text} rows={3} value={answers[q.id] || ''} onChange={(e) => set(q.id, e.target.value)} />}
          </fieldset>
        ))}
        <ErrorNote>{error}</ErrorNote>
        <div className="form-actions">
          <button type="submit" className="primary" disabled={busy}>
            Send my answers
          </button>
          <button
            type="button"
            className="quiet"
            disabled={busy}
            onClick={async () => {
              if (await run(() => exits.declineInterview())) onChanged()
            }}
          >
            I would rather not
          </button>
        </div>
      </form>
    </section>
  )
}
