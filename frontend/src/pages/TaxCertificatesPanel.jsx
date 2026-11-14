import { useState } from 'react'
import { taxCertificates } from '../api.js'
import { useConfirm, useToast } from '../feedbackContext.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Badge, Empty, ErrorNote } from '../ui.jsx'
import { CERTIFICATE_STATES, canDo, rupees } from './payrollText.js'

/** Payroll → Tax filing → Certificates: build each employee's Form 130 Part B for the year, then a second person releases it. */
export default function TaxCertificatesPanel({ taxYear, me, onSetup }) {
  const load = useLoad(() => taxCertificates.year(taxYear))
  const [built, setBuilt] = useState(null)
  const { busy, error, run } = useAction()
  const confirm = useConfirm()
  const toast = useToast()
  const canPrepare = canDo(me, 'payroll.prepare')

  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  const y = load.data

  async function build() {
    const result = await run(() => taxCertificates.build(taxYear))
    if (!result) return
    setBuilt(result)
    load.reload()
  }

  async function release(c) {
    const draft = c.draftRules
    const ok = await confirm({
      title: `Release ${c.name}’s certificate?`,
      body: draft
        ? 'The tax rules behind this certificate are drafts the payroll consultant has not signed off. The employee sees it as provisional.'
        : 'The employee sees it in My pay. A released certificate never changes.',
      confirmLabel: draft ? 'Release on draft rules' : 'Release',
    })
    if (!ok) return
    if (await run(() => taxCertificates.release(c.id, draft))) {
      toast(`Released for ${c.name}`)
      load.reload()
    }
  }

  const name = (c) => `Form 130 Part B ${c.taxYear} ${c.code}.pdf`
  return (
    <div data-testid="tax-certificates">
      <div className="section-head">
        <p className="hint">
          {y.released} of {y.certificates.length} released · layout version {y.layoutVersion}. Part B is built from the year’s approved payslips
          and the checked claims.
        </p>
        {canPrepare && (
          <button type="button" disabled={busy} onClick={build}>
            Build certificates
          </button>
        )}
      </div>
      <ErrorNote>{error}</ErrorNote>
      {error.includes('deductor') && onSetup && (
        <p>
          <button type="button" className="text-link" onClick={onSetup}>
            Open Setup to add the employer’s details
          </button>
        </p>
      )}
      {built && (
        <p className="notice" role="status">
          Built {built.built}; {built.alreadyReleased} already released and left as they were.
          {built.skipped.length > 0 && ` ${built.skipped.length} left out.`}
        </p>
      )}
      {built?.skipped.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Code</th>
                <th>Name</th>
                <th>Why left out</th>
              </tr>
            </thead>
            <tbody>
              {built.skipped.map((s) => (
                <tr key={s.code}>
                  <td>{s.code}</td>
                  <td>{s.name}</td>
                  <td>{s.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {y.certificates.length === 0 ? (
        <Empty>No certificates for {taxYear}. Build them once March’s payroll is approved.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Employee</th>
                <th className="num">Taxable income</th>
                <th className="num">Tax for the year</th>
                <th className="num">Tax deducted</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {y.certificates.map((c) => (
                <tr key={c.id} data-code={c.code}>
                  <td>
                    {c.name} <span className="hint">{c.code}</span>
                  </td>
                  <td className="num">{rupees(c.taxableIncome)}</td>
                  <td className="num">{rupees(c.annualTax)}</td>
                  <td className="num">{rupees(c.taxDeducted)}</td>
                  <td>
                    <Badge tone={CERTIFICATE_STATES[c.status]?.[0]}>{CERTIFICATE_STATES[c.status]?.[1] ?? c.status}</Badge>{' '}
                    {c.draftRules && <Badge tone="warn">Draft rules</Badge>}
                    {c.status === 'released' && c.releasedAt && <span className="hint"> {formatDay(c.releasedAt.slice(0, 10))}</span>}
                    {c.status === 'draft' && c.layoutVersion !== y.layoutVersion && (
                      <span className="hint"> Built with layout v{c.layoutVersion}; build again to use v{y.layoutVersion}</span>
                    )}
                  </td>
                  <td className="form-actions">
                    <button type="button" className="outline" disabled={busy} onClick={() => run(() => taxCertificates.pdf(c.id, name(c)))}>
                      Download
                    </button>
                    {y.canRelease && c.status === 'draft' && !c.builtByMe && (
                      <button type="button" disabled={busy} onClick={() => release(c)}>
                        Release…
                      </button>
                    )}
                    {y.canRelease && c.status === 'draft' && c.builtByMe && <span className="hint">Another person releases what you built.</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
