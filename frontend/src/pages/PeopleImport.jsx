import { useState } from 'react'
import { importing, invitationLink } from '../api.js'
import { formatDate, formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, CopyLink, Empty, ErrorNote } from '../ui.jsx'

/**
 * People → Import: existing staff from a spreadsheet saved as CSV. Choose the
 * file, say which column holds what (remembered for next time), check every
 * row, then import those that can be used. Afterwards, invite them to sign in.
 */
export default function PeopleImport() {
  const [step, setStep] = useState('file') // file, map, check, done
  const [content, setContent] = useState('')
  const [fileName, setFileName] = useState('')
  const [read, setRead] = useState(null)
  const [mapping, setMapping] = useState(null)
  const [check, setCheck] = useState(null)
  const [result, setResult] = useState(null)
  const { busy, error, run, clearError } = useAction()

  async function choose(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    clearError()
    if (file.size > 5 * 1024 * 1024) {
      await run(() => Promise.reject(new Error('The file is larger than 5 MB; split it into smaller files.')))
      return
    }
    const text = await file.text()
    const r = await run(() => importing.read(text))
    if (r) {
      setContent(text)
      setFileName(file.name)
      setRead(r)
      setMapping(r.mapping)
      setStep('map')
    }
  }

  async function preview() {
    const r = await run(() => importing.preview(content, mapping))
    if (r) {
      setCheck(r)
      setStep('check')
    }
  }

  async function importNow() {
    const r = await run(() => importing.run(content, mapping))
    if (r) {
      setResult(r)
      setStep('done')
    }
  }

  const setColumn = (key, value) => {
    const columns = { ...mapping.columns }
    if (value === '') delete columns[key]
    else columns[key] = Number(value)
    setMapping({ ...mapping, columns })
  }

  return (
    <>
      <section className="panel">
        <h3>Import existing employees</h3>
        <p className="hint">
          Save your staff list from Excel or Google Sheets as CSV, one person per row. Each row is checked before anything is added,
          and rows already here (the same PAN, employee code, email, or name and joining date) are left out, so importing the same file
          twice adds nothing.
        </p>
        {step === 'file' && (
          <label htmlFor="import-file" className="file-button secondary">
            Choose a CSV file
            <input id="import-file" type="file" accept=".csv,text/csv,text/plain" className="sr-only" onChange={choose} />
          </label>
        )}
        {step !== 'file' && (
          <p>
            <strong>{fileName}</strong> · {read.rowCount - (mapping?.hasHeader ? 1 : 0)} rows{' '}
            <button type="button" className="quiet plain" onClick={() => setStep('file')}>
              Choose another file
            </button>
          </p>
        )}
        <ErrorNote>{error}</ErrorNote>
      </section>

      {step === 'map' && (
        <section className="panel">
          <h3>Which column holds what</h3>
          {read.mappingSaved ? (
            <p className="hint">These are the columns you chose last time, matched by their names.</p>
          ) : (
            <p className="hint">A first guess from the column names; check each one.</p>
          )}
          <label className="check">
            <input type="checkbox" checked={mapping.hasHeader} onChange={(e) => setMapping({ ...mapping, hasHeader: e.target.checked })} />
            The first row holds the column names
          </label>
          <div className="grid mapping-grid">
            {read.fields.map((f) => (
              <div key={f.key}>
                <label htmlFor={`map-${f.key}`}>
                  {f.label}
                  {f.required && <span className="hint"> (needed)</span>}
                </label>
                <select id={`map-${f.key}`} value={mapping.columns[f.key] ?? ''} onChange={(e) => setColumn(f.key, e.target.value)}>
                  <option value="">Not in the file</option>
                  {read.columns.map((c, i) => (
                    <option key={i} value={i}>
                      {mapping.hasHeader ? c : `Column ${i + 1} (${c})`}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
          <label className="check">
            <input
              type="checkbox"
              checked={mapping.createDepartments !== false}
              onChange={(e) => setMapping({ ...mapping, createDepartments: e.target.checked })}
            />
            Add departments the file names that do not exist yet
          </label>
          <h4>The first rows</h4>
          <div className="table-wrap">
            <table>
              {mapping.hasHeader && (
                <thead>
                  <tr>
                    {read.columns.map((c, i) => (
                      <th key={i}>{c}</th>
                    ))}
                  </tr>
                </thead>
              )}
              <tbody>
                {read.sample.map((row, i) => (
                  <tr key={i}>
                    {row.map((cell, j) => (
                      <td key={j}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="form-actions">
            <button type="button" className="primary" disabled={busy} onClick={preview}>
              {busy ? 'Checking…' : 'Check every row'}
            </button>
          </div>
        </section>
      )}

      {step === 'check' && check && (
        <section className="panel">
          <h3>
            {check.ready} ready to import
            {check.rows.filter((r) => !r.ok && alreadyHere(r)).length > 0 && ` · ${check.rows.filter((r) => !r.ok && alreadyHere(r)).length} already here`}
            {check.rows.filter((r) => !r.ok && !alreadyHere(r)).length > 0 && ` · ${check.rows.filter((r) => !r.ok && !alreadyHere(r)).length} cannot be used`}
          </h3>
          <RowTable rows={check.rows} />
          <div className="form-actions">
            <button type="button" className="primary" disabled={busy || check.ready === 0} onClick={importNow}>
              {busy ? 'Importing…' : `Import ${check.ready} employee${check.ready === 1 ? '' : 's'}`}
            </button>
            <button type="button" className="quiet" onClick={() => setStep('map')}>
              Change the columns
            </button>
          </div>
          {check.refused > 0 && <p className="hint">Fix the refused rows in your spreadsheet and import the file again; rows already added are skipped.</p>}
        </section>
      )}

      {step === 'done' && result && (
        <section className="panel">
          <div role="status">
            <h3>
              Imported {result.imported} employee{result.imported === 1 ? '' : 's'}
              {result.refused > 0 && `; ${result.refused} row${result.refused === 1 ? '' : 's'} left out`}
            </h3>
          </div>
          <RowTable rows={result.rows.filter((r) => !r.ok)} />
        </section>
      )}

      <Invitations key={step} />
    </>
  )
}

/** A row that is the same person, joining the same day, as someone already recorded: left out, not wrong. */
const alreadyHere = (r) => r.problems.some((p) => / joining on .* is already recorded$/.test(p))

function RowTable({ rows }) {
  if (rows.length === 0) return null
  return (
    <div className="table-wrap">
      <table className="import-rows">
        <thead>
          <tr>
            <th>Row</th>
            <th>Name</th>
            <th>Result</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.line} data-line={r.line} className={r.ok || alreadyHere(r) ? undefined : 'row-refused'}>
              <td>{r.line}</td>
              <td>{r.name || '—'}</td>
              <td>
                {r.ok ? (
                  <Badge tone="good">Ready</Badge>
                ) : alreadyHere(r) ? (
                  <Badge>Already here: imported before</Badge>
                ) : (
                  r.problems.map((p) => <div key={p}>{p}</div>)
                )}
                {r.notes.filter(Boolean).map((n) => (
                  <div key={n} className="hint">
                    {n}
                  </div>
                ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Employees with a personal email and no login, to invite to sign in. */
function Invitations() {
  const list = useLoad(importing.uninvited)
  const [chosen, setChosen] = useState([])
  const [sent, setSent] = useState(null)
  const { busy, error, run } = useAction()
  if (list.loading) return null
  if (list.error) return <ErrorNote>{list.error}</ErrorNote>
  const all = list.data.map((e) => e.id)
  return (
    <section className="panel">
      <h3>Invite employees to sign in</h3>
      <p className="hint">
        Employees with a personal email and no login. Each gets a link to set a password; no email is sent yet, so send each link
        yourself.
      </p>
      {sent && (
        <div className="invite-links">
          {sent.map((s) =>
            s.token ? (
              <CopyLink
                key={s.employmentId}
                label={`${s.name}, ${s.email}.`}
                link={invitationLink(s.token)}
                note={`It works once and stays valid until ${formatDate(s.expiresAt)}.`}
              />
            ) : (
              <p key={s.employmentId} className="hint">
                {s.name}: {s.problem}
              </p>
            ),
          )}
          <button type="button" className="quiet" onClick={() => setSent(null)}>
            Done
          </button>
        </div>
      )}
      {list.data.length === 0 ? (
        <Empty>Everyone with a personal email has been invited.</Empty>
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>
                    <input
                      type="checkbox"
                      aria-label="Choose everyone"
                      checked={chosen.length === all.length}
                      onChange={(e) => setChosen(e.target.checked ? all : [])}
                    />
                  </th>
                  <th>Name</th>
                  <th>Code</th>
                  <th>Personal email</th>
                  <th>Imported</th>
                </tr>
              </thead>
              <tbody>
                {list.data.map((e) => (
                  <tr key={e.id} data-invitee={e.legalName}>
                    <td>
                      <input
                        type="checkbox"
                        aria-label={`Invite ${e.legalName}`}
                        checked={chosen.includes(e.id)}
                        onChange={(ev) => setChosen(ev.target.checked ? [...chosen, e.id] : chosen.filter((x) => x !== e.id))}
                      />
                    </td>
                    <td>{e.preferredName || e.legalName}</td>
                    <td>{e.employeeCode || '—'}</td>
                    <td>{e.personalEmail}</td>
                    <td>{e.importedAt ? formatDay(e.importedAt.slice(0, 10)) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ErrorNote>{error}</ErrorNote>
          <div className="form-actions">
            <button
              type="button"
              className="secondary"
              disabled={busy || chosen.length === 0}
              onClick={async () => {
                const r = await run(() => importing.invite(chosen))
                if (r) {
                  setSent(r)
                  setChosen([])
                  list.reload()
                }
              }}
            >
              Invite {chosen.length || ''} chosen
            </button>
          </div>
        </>
      )}
    </section>
  )
}
