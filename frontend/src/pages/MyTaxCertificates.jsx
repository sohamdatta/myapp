import { taxCertificates } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Empty, ErrorNote } from '../ui.jsx'

/** My pay → Tax certificates (and under My exit for people who have left): the Form 130 certificates HR has released. */
export default function MyTaxCertificates() {
  const load = useLoad(taxCertificates.mine)
  const { busy, error, run } = useAction()
  if (load.loading) return <p className="muted">Loading…</p>
  if (load.error) return <ErrorNote>{load.error}</ErrorNote>
  return (
    <section className="panel" data-testid="my-tax-certificates">
      <h3>Tax certificates</h3>
      <ErrorNote>{error}</ErrorNote>
      {load.data.length === 0 ? (
        <Empty>No certificates yet. HR releases each year’s certificate after March, once the year’s pay is final.</Empty>
      ) : (
        <div className="doc-list">
          {load.data.map((c) => (
            <div key={c.id} className="doc-row" data-year={c.taxYear}>
              <div>
                <strong>Form 130 Part B, tax year {c.taxYear}</strong>
                <br />
                <span className="hint">Released {formatDay(c.releasedAt.slice(0, 10))}</span>
              </div>
              <button type="button" className="outline" disabled={busy} onClick={() => run(() => taxCertificates.myPdf(c.id, c.fileName))}>
                Download PDF
              </button>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
