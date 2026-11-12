import { useState } from 'react'
import { Badge } from '../ui.jsx'
import { formatDay, formatDays } from '../hooks.js'
import { rupees, signed } from './payrollText.js'

/** A value a formula read: money for pay figures, plain numbers for days, months and the like. */
const readValue = (name, value) => {
  if (/^(days\.|period\.|months_of_service)/.test(name)) return String(Number(value))
  return typeof value === 'number' || /^-?[0-9.]+$/.test(String(value)) ? rupees(value, true) : String(value)
}

/** One line's explanation: the formula and the values it read, the days, the rule. */
function Explain({ line }) {
  const t = line.trace || {}
  return (
    <div className="explain" id={`explain-${line.code}`} data-testid={`explain-${line.code}`}>
      {t.source && <p className="hint">{t.source}</p>}
      {t.overrideReason && <p className="hint">Reason: {t.overrideReason}</p>}
      {t.formula && (
        <p>
          <code>{t.formula}</code>
        </p>
      )}
      {t.read && Object.keys(t.read).length > 0 && (
        <ul className="explain-reads">
          {Object.entries(t.read).map(([name, value]) => (
            <li key={name}>
              <code>{name}</code> = {readValue(name, value)}
              {t.where?.[name] && <span className="hint"> · {t.where[name]}</span>}
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

function Lines({ title, lines, explain, total, totalLabel, slots }) {
  const [open, setOpen] = useState(null)
  return (
    <div className="payslip-block">
      <table className="payslip-lines">
        <thead>
          <tr>
            <th scope="col">{title}</th>
            <th scope="col" className="num">
              Amount
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr key={l.code} data-line={l.code}>
              <td>
                {l.name}
                {(explain || l.trace?.prorated || l.trace?.working || l.trace?.formula) && (
                  <>
                    {' '}
                    <button
                      type="button"
                      className="text-link small"
                      onClick={() => setOpen(open === l.code ? null : l.code)}
                      aria-expanded={open === l.code}
                      aria-controls={`explain-${l.code}`}
                      aria-label={`${explain ? 'Explain' : 'Why'} ${l.name}`}
                    >
                      {open === l.code ? 'Hide' : explain ? 'Explain' : 'Why'}
                    </button>
                  </>
                )}
                {open === l.code && <Explain line={l} />}
              </td>
              <td className="num">{rupees(l.amount, !Number.isInteger(Number(l.amount)))}</td>
            </tr>
          ))}
          {lines.length === 0 && (
            <tr>
              <td colSpan={2} className="hint">
                None this month
              </td>
            </tr>
          )}
          {Array.from({ length: Math.max(0, slots - Math.max(lines.length, 1)) }, (_, i) => (
            <tr key={`pad-${i}`} className="payslip-pad" aria-hidden="true">
              <td colSpan={2} />
            </tr>
          ))}
        </tbody>
        {total !== undefined && (
          <tfoot>
            <tr>
              <th scope="row">{totalLabel}</th>
              <td className="num">{rupees(total)}</td>
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  )
}

/** A payslip laid out like the PDF: who and when, the days, earnings beside deductions, net pay, the year so far, employer contributions, tax and why it changed. */
export default function PayslipView({ slip, explain = false }) {
  const lines = slip.lines || []
  const by = (kind) => lines.filter((l) => l.kind === kind)
  const info = slip.info || {}
  const changes = slip.changes || {}
  const earnings = by('earning')
  const deductions = by('deduction')
  const employer = by('employer')
  const slots = Math.max(earnings.length, deductions.length, 1)
  const ytd = slip.ytd
  const tax = info.tax
  const subtitle = [info.code, info.designation, info.department].filter(Boolean).join(' · ')
  return (
    <div className="payslip" data-testid="payslip">
      <header className="payslip-head">
        <div>
          {info.name && <p className="payslip-name">{info.name}</p>}
          {subtitle && <p className="hint">{subtitle}</p>}
        </div>
        <div className="payslip-when">
          <p className="payslip-kicker">Payslip</p>
          {slip.label && <p className="payslip-month">{slip.label}</p>}
          {slip.paidOn && <p className="hint">Paid on {formatDay(slip.paidOn)}</p>}
        </div>
      </header>

      <dl className="payslip-days">
        <div>
          <dt>Days paid</dt>
          <dd>
            {Number(slip.daysPaid)} <span className="hint">of {Number(slip.daysInPeriod)}</span>
          </dd>
        </div>
        <div>
          <dt>Loss of pay</dt>
          <dd>{Number(slip.lopDays) > 0 ? formatDays(slip.lopDays) : 'None'}</dd>
        </div>
        <div>
          <dt>Work location</dt>
          <dd>{info.location ? `${info.location}${info.state ? ` (${info.state})` : ''}` : info.state || '—'}</dd>
        </div>
        <div>
          <dt>Tax regime</dt>
          <dd>{info.regime === 'old' ? 'Old' : 'New'}</dd>
        </div>
      </dl>
      {(info.lopDates?.length > 0 || Number(info.lopEntered) > 0) && (
        <p className="hint payslip-lop" data-testid="loss-of-pay-days">
          Loss of pay:{' '}
          {(info.lopDates || [])
            .map((d) => `${formatDay(d.date)} (${Number(d.days) < 1 ? 'half day, ' : ''}${d.source === 'leave' ? 'unpaid leave' : 'absent'})`)
            .join('; ')}
          {Number(info.lopEntered) > 0 && `${info.lopDates?.length ? '; and ' : ''}${formatDays(info.lopEntered)} entered by HR`}
        </p>
      )}

      <div className="payslip-columns">
        <Lines title="Earnings" lines={earnings} explain={explain} slots={slots} total={slip.gross} totalLabel="Gross earnings" />
        <Lines title="Deductions" lines={deductions} explain={explain} slots={slots} total={slip.deductions} totalLabel="Total deductions" />
      </div>

      <div className="payslip-net">
        <div>
          <p className="payslip-kicker">Net pay{slip.label ? ` for ${slip.label}` : ''}</p>
          <p className="payslip-net-sum">
            Gross {rupees(slip.gross)} less deductions {rupees(slip.deductions)}
          </p>
        </div>
        <strong data-testid="net" aria-label={`Net pay ${rupees(slip.net)}`}>
          {rupees(slip.net)}
        </strong>
      </div>

      {(ytd || employer.length > 0) && (
        <div className="payslip-columns">
          {ytd && (
            <div className="payslip-block">
              <table className="payslip-lines">
                <thead>
                  <tr>
                    <th scope="col">This tax year so far</th>
                    <th scope="col" className="num">
                      Amount
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Gross earnings</td>
                    <td className="num">{rupees(ytd.gross)}</td>
                  </tr>
                  <tr>
                    <td>Deductions</td>
                    <td className="num">{rupees(ytd.deductions)}</td>
                  </tr>
                  {ytd.tds !== undefined && (
                    <tr>
                      <td>Income tax deducted</td>
                      <td className="num">{rupees(ytd.tds)}</td>
                    </tr>
                  )}
                </tbody>
                {ytd.net !== undefined && (
                  <tfoot>
                    <tr>
                      <th scope="row">Net pay</th>
                      <td className="num">{rupees(ytd.net)}</td>
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
          )}
          {employer.length > 0 && (
            <Lines
              title="Paid by the employer, on top of pay"
              lines={employer}
              explain={explain}
              slots={0}
              total={employer.reduce((sum, l) => sum + Number(l.amount), 0)}
              totalLabel="Total"
            />
          )}
        </div>
      )}

      {tax && (
        <section className="payslip-section">
          <h4>Income tax for the year, projected</h4>
          <dl className="payslip-tax">
            <div>
              <dt>Projected income</dt>
              <dd>{rupees(tax.income)}</dd>
            </div>
            <div>
              <dt>Taxable income</dt>
              <dd>{rupees(tax.taxableIncome)}</dd>
            </div>
            <div>
              <dt>Tax for the year</dt>
              <dd>{rupees(tax.annualTax)}</dd>
            </div>
            <div>
              <dt>Deducted before this month</dt>
              <dd>{rupees(tax.paidSoFar)}</dd>
            </div>
            {tax.monthsLeft !== undefined && (
              <div>
                <dt>Spread over</dt>
                <dd>
                  {tax.monthsLeft} month{tax.monthsLeft === 1 ? '' : 's'}
                </dd>
              </div>
            )}
          </dl>
        </section>
      )}

      {changes.items && (
        <section className="payslip-section why-changed" data-testid="why-changed">
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
                  <span>{c.cause}</span> <strong className={Number(c.amount) >= 0 ? 'up' : 'down'}>{signed(c.amount)}</strong>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  )
}
