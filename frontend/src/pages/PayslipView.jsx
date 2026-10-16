import { useState } from 'react'
import { Badge } from '../ui.jsx'
import { rupees, signed } from './payrollText.js'

/** One line's explanation: the formula and the values it read, the days, the rule. */
function Explain({ line }) {
  const t = line.trace || {}
  return (
    <div className="explain" data-testid={`explain-${line.code}`}>
      {t.source && <p className="hint">{t.source}</p>}
      {t.formula && (
        <p>
          <code>{t.formula}</code>
        </p>
      )}
      {t.read && Object.keys(t.read).length > 0 && (
        <ul className="explain-reads">
          {Object.entries(t.read).map(([name, value]) => (
            <li key={name}>
              <code>{name}</code> = {typeof value === 'number' || /^-?[0-9.]+$/.test(String(value)) ? rupees(value, true) : String(value)}
            </li>
          ))}
        </ul>
      )}
      {t.fullMonth !== undefined && t.fullMonth !== null && <p>Full month: {rupees(t.fullMonth, true)}</p>}
      {t.prorated && <p>{t.prorated}</p>}
      {t.working && <p>{t.working}</p>}
      {line.unrounded !== undefined && (
        <p className="hint">
          Before rounding {rupees(line.unrounded, true)}
          {t.rounding ? `, rounded ${t.rounding === 'nearest' ? 'to the nearest rupee' : t.rounding === 'up' ? 'up' : t.rounding === 'down' ? 'down' : 'not at all'}` : ''}
        </p>
      )}
      {line.ruleStatus === 'draft' && <Badge tone="warn">Rule awaiting consultant sign-off</Badge>}
    </div>
  )
}

function Lines({ title, lines, explain }) {
  const [open, setOpen] = useState(null)
  if (!lines.length) return null
  return (
    <div className="payslip-block">
      <h4>{title}</h4>
      <table className="payslip-lines">
        <tbody>
          {lines.map((l) => (
            <tr key={l.code} data-line={l.code}>
              <td>
                {l.name}
                {(explain || l.trace?.prorated || l.trace?.working) && (
                  <>
                    {' '}
                    <button type="button" className="text-link small" onClick={() => setOpen(open === l.code ? null : l.code)} aria-expanded={open === l.code}>
                      {open === l.code ? 'Hide' : explain ? 'Explain' : 'Why'}
                    </button>
                  </>
                )}
                {open === l.code && <Explain line={l} />}
              </td>
              <td className="num">{rupees(l.amount, true)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** A payslip: days, earnings and deductions, net, employer contributions, tax and why it changed. */
export default function PayslipView({ slip, explain = false }) {
  const lines = slip.lines || []
  const by = (kind) => lines.filter((l) => l.kind === kind)
  const info = slip.info || {}
  const changes = slip.changes || {}
  return (
    <div className="payslip" data-testid="payslip">
      <dl className="facts payslip-days">
        <dt>Days paid</dt>
        <dd>
          {Number(slip.daysPaid)} of {Number(slip.daysInPeriod)}
          {Number(slip.lopDays) > 0 && <span className="hint"> · {Number(slip.lopDays)} days loss of pay</span>}
        </dd>
        {info.state && (
          <>
            <dt>Work location</dt>
            <dd>
              {info.location || '—'} ({info.state})
            </dd>
          </>
        )}
        <dt>Tax regime</dt>
        <dd>{info.regime === 'old' ? 'Old' : 'New'}</dd>
      </dl>
      <div className="payslip-columns">
        <Lines title="Earnings" lines={by('earning')} explain={explain} />
        <Lines title="Deductions" lines={by('deduction')} explain={explain} />
      </div>
      <div className="payslip-totals">
        <span>Gross {rupees(slip.gross)}</span>
        <span>Deductions {rupees(slip.deductions)}</span>
        <strong data-testid="net">Net pay {rupees(slip.net)}</strong>
      </div>
      {by('employer').length > 0 && (
        <p className="hint">
          Paid by the employer on top: {by('employer').map((l) => `${l.name} ${rupees(l.amount)}`).join(' · ')}
        </p>
      )}
      {changes.items && (
        <section className="why-changed" data-testid="why-changed">
          <h4>
            Why pay changed from {changes.previousLabel}{' '}
            <span className="hint">
              {rupees(changes.previousNet)} → {rupees(slip.net)}
            </span>
          </h4>
          {changes.items.length === 0 ? (
            <p className="hint">Nothing changed.</p>
          ) : (
            <ul>
              {changes.items.map((c) => (
                <li key={c.cause}>
                  <span>{c.cause}</span> <strong>{signed(c.amount)}</strong>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      {info.tax && (
        <p className="hint">
          Income tax for the year, projected: income {rupees(info.tax.income)}, taxable {rupees(info.tax.taxableIncome)}, tax {rupees(info.tax.annualTax)}, deducted
          before this month {rupees(info.tax.paidSoFar)}, spread over {info.tax.monthsLeft} month{info.tax.monthsLeft === 1 ? '' : 's'}.
        </p>
      )}
    </div>
  )
}
