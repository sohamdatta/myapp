import { useState } from 'react'
import { payroll } from '../api.js'
import { formatDay, useAction, useLoad } from '../hooks.js'
import { Empty, ErrorNote, Tabs } from '../ui.jsx'
import MyTax from './MyTax.jsx'
import PayslipView from './PayslipView.jsx'
import { rupees } from './payrollText.js'

function Slip({ id, onBack }) {
  const slip = useLoad(() => payroll.myPayslip(id))
  const { busy, error, run } = useAction()
  if (slip.loading) return <p className="muted">Loading…</p>
  if (slip.error) return <ErrorNote>{slip.error}</ErrorNote>
  const s = slip.data
  return (
    <>
      <p>
        <button type="button" className="quiet plain" onClick={onBack}>
          ← All payslips
        </button>
      </p>
      <div className="section-head">
        <h2>Payslip for {s.label}</h2>
        <button
          type="button"
          className="outline"
          disabled={busy}
          onClick={() => run(() => payroll.myPayslipPdf(id, `payslip-${s.label.replace(/\s+/g, '-').toLowerCase()}.pdf`))}
        >
          Download PDF
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <PayslipView slip={s} />
      {s.ytd && (
        <p className="hint">
          This tax year so far: gross {rupees(s.ytd.gross)}, deductions {rupees(s.ytd.deductions)}
          {s.ytd.tds !== undefined ? `, income tax ${rupees(s.ytd.tds)}` : ''}.
        </p>
      )}
    </>
  )
}

/** My pay: payslips, and the income tax declaration with its proofs. */
export default function MyPay() {
  const [tab, setTab] = useState('payslips')
  return (
    <>
      <Tabs
        tabs={[
          { id: 'payslips', label: 'Payslips' },
          { id: 'tax', label: 'Tax declaration' },
        ]}
        current={tab}
        onChange={setTab}
      />
      {tab === 'payslips' ? <Payslips /> : <MyTax />}
    </>
  )
}

/** The payslips released to me, newest first, each with why it changed from the month before. */
function Payslips() {
  const mine = useLoad(payroll.myPay)
  const [open, setOpen] = useState(null)
  if (open) return <Slip id={open} onBack={() => setOpen(null)} />
  if (mine.loading) return <p className="muted">Loading…</p>
  if (mine.error) return <ErrorNote>{mine.error}</ErrorNote>
  const m = mine.data
  return (
    <>
      <div className="section-head">
        <div>
          <h2>My pay</h2>
          {m.profile && <p className="muted">Income tax under the {m.profile.taxRegime === 'old' ? 'old' : 'new'} regime. To change it, ask HR. Declare your claims under Tax declaration.</p>}
        </div>
      </div>
      {m.payslips.length === 0 ? (
        <Empty>No payslips yet. They appear here once a month’s pay is released.</Empty>
      ) : (
        <ul className="payslip-list">
          {m.payslips.map((s) => (
            <li key={s.id}>
              <button type="button" className="payslip-row" onClick={() => setOpen(s.id)} data-testid="my-payslip">
                <span>
                  <strong>{s.label}</strong>
                  <span className="hint">
                    {' '}
                    · {Number(s.daysPaid)} of {Number(s.daysInPeriod)} days{s.paidOn ? ` · paid ${formatDay(s.paidOn)}` : ''}
                  </span>
                </span>
                <span className="num">
                  <strong>{rupees(s.net)}</strong>
                  <span className="hint"> net</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
