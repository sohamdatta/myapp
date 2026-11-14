import { lazy, Suspense, useState } from 'react'
import { Tabs } from '../ui.jsx'
import { recentTaxYears } from './payrollText.js'

const TdsDeposits = lazy(() => import('./TdsDeposits.jsx'))
const TdsStatements = lazy(() => import('./TdsStatements.jsx'))
const TaxCertificatesPanel = lazy(() => import('./TaxCertificatesPanel.jsx'))

/**
 * Payroll → Tax filing: the tax deposited each month (challans), the quarterly Form 138 statements built from them, and the
 * Form 130 certificates employees get at the year's end.
 */
export default function TaxFiling({ me, onSetup }) {
  const [tab, setTab] = useState('deposits')
  const years = recentTaxYears(3)
  const [taxYear, setTaxYear] = useState(years[0])
  return (
    <section className="panel">
      <div className="section-head">
        <h3>Tax filing</h3>
        <label className="muted">
          Tax year
          <select value={taxYear} onChange={(e) => setTaxYear(e.target.value)} aria-label="Tax year">
            {years.map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        </label>
      </div>
      <Tabs
        tabs={[
          { id: 'deposits', label: 'Deposits' },
          { id: 'statements', label: 'Statements' },
          { id: 'certificates', label: 'Certificates' },
        ]}
        current={tab}
        onChange={setTab}
      />
      <Suspense fallback={<p className="muted">Loading…</p>}>
        {tab === 'deposits' && <TdsDeposits key={taxYear} taxYear={taxYear} me={me} />}
        {tab === 'statements' && <TdsStatements key={taxYear} taxYear={taxYear} me={me} onSetup={onSetup} />}
        {tab === 'certificates' && <TaxCertificatesPanel key={taxYear} taxYear={taxYear} me={me} onSetup={onSetup} />}
      </Suspense>
    </section>
  )
}

