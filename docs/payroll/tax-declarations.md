# Income tax declarations and proofs

Oct 24, 2026 · built in V17 · part of Payroll

An employee declares what they expect to claim for the tax year (Form 124, which replaced Form 12BB from tax year 2026-27), certifies it, and later sends proofs. HR checks each proof. Payroll deducts TDS on the declared amounts until a cut-over month that HR sets, and on the amounts HR accepted from then on, so the last months of the year catch up the tax on anything not proved.

**Only the old regime takes these claims.** Under the new regime (the default) they change nothing; both screens say so, and the engine records them as ignored.

## Who does what

| Who | Where | What |
| --- | --- | --- |
| Employee | My pay → Tax declaration | Add, change and remove claims while declarations are open; submit (certify) once; upload proofs while proofs are open; see each proof's decision |
| HR Admin, payroll operator (anyone with `compensation.read` across the Organization sees; `compensation.write` acts) | Tax declarations (sidebar) | Open the window; see where everyone stands; open an employee; accept a proof at an amount (no more than declared) or return it with a reason |
| Nobody | | Checks their own proofs (409 `tax.own`) |

## The window, per tax year

Set by HR in `app.tax_window`:

- **Declarations open … close.** Claims can be added or changed only between these dates. The first time a window is saved for a year, everyone employed gets a `tax_window_open` notification.
- **Proofs open … close** (optional). Proofs can be uploaded only between these dates, and only for a submitted declaration. Proofs may run into April.
- **Only accepted amounts from** (optional, a month). Payroll months from this one count accepted amounts only; before it, declared amounts. A usual year: declarations in April and May, proofs in January, accepted-only from February.

## Claims

| Claim | Asks for | Limit (old regime, from the rule data) |
| --- | --- | --- |
| Rent (HRA) | Rent a month, from and to (months in the tax year), city, landlord's name and address, landlord's PAN (required above ₹1,00,000 a year), relationship | Exempt: the least of the HRA received, rent above 10% of basic, and 50% of basic in the listed cities (40% elsewhere); worked out per span of rent |
| ₹1.5 lakh group (formerly 80C) | Which: life insurance, PPF, ELSS, NSC, Sukanya Samriddhi, tuition, home-loan principal, five-year deposit, voluntary PF, other | ₹1,50,000 together with the employee's PF |
| NPS, own contribution (formerly 80CCD(1B)) | Amount | ₹50,000 |
| Health insurance: you and family (formerly 80D) | Amount, insurer, senior citizen | ₹25,000; ₹50,000 for a senior citizen |
| Health insurance: parents | Amount, insurer, senior citizen | ₹25,000; ₹50,000 for a senior citizen |
| Home-loan interest | Amount, lender's name, address, PAN | ₹2,00,000 |
| Education-loan interest (formerly 80E) | Amount, lender | None |

Section numbers changed under the Income-tax Act 2025 and public summaries disagree on some of them, so the screens name each claim in words with its former section. All limits, the HRA rates and the list of cities taking 50% are in the old regime's `income_tax` rule data, added by V17 to the **draft** rule only (a signed-off rule is never changed by a migration). They await the payroll consultant's sign-off, like every rule row.

If the rule data has no limits, declared claims count as nothing, PF still counts in the ₹1.5 lakh group, and each payslip carries the warning `declaration_limits_missing`.

## Proof states

`none` → `submitted` (on upload) → `accepted` (with an amount) or `rejected` (with a note; the employee is notified with `tax_proof_returned` and may upload again, which makes it `submitted`). Changing a checked claim's amount or details sends it back to `submitted` (or `none` without files): the earlier decision was about the earlier claim. An accepted proof cannot be added to or removed by the employee.

## What payroll reads

`TaxDeclarations.forPayroll` puts `declarations` in each employee's snapshot when the run is frozen: nothing unless the declaration for that tax year is submitted; otherwise the basis (`declared` or `verified`), the rent spans (monthly rent, months, city) and the sums per kind. `PayrollEngine.declarationClaims` applies the limits; the payslip's tax projection carries `declarationBasis` and `claims` (HRA exempt, group, NPS, health, home loan, education loan, total), and the TDS line's working names each one. Basic and HRA for the HRA exemption are the year's projected amounts (year to date, this month, the months left) spread evenly over the months with pay, using the `BASIC` and `HRA` component codes.

## Storage and data

- `app.tax_window`, `app.tax_declaration` (one per employment and tax year; `submitted_at` is the certificate), `app.tax_declaration_item` (rent is a monthly amount; everything else is for the year), `app.tax_proof` (claim to document).
- Proof files are ordinary encrypted documents of the person (`app.document`), left out of the joiner's "other documents". Opening one is audited as `document.viewed`. Removing a file marks the document superseded.
- Removing a person's data after the retention period, or a withdrawn joiner, removes their declarations and proofs first.
- Audit events: `tax.window_saved`, `tax.claim_added`, `tax.claim_changed`, `tax.claim_removed`, `tax.declaration_submitted`, `tax.proof_added`, `tax.proof_accepted`, `tax.proof_returned`.

## API

| Method and path | Who |
| --- | --- |
| `GET /api/org/me/tax` | The employee |
| `POST /api/org/me/tax/items`, `PUT` and `DELETE /api/org/me/tax/items/{id}` | The employee, while declarations are open |
| `POST /api/org/me/tax/submit` | The employee, while declarations are open |
| `POST /api/org/me/tax/items/{id}/proofs?name=` (the file as the body), `DELETE …/proofs/{document}` | The employee, while proofs are open |
| `GET /api/org/tax/proofs/{document}` | The employee it belongs to, or someone who sees declarations |
| `GET /api/org/payroll/tax`, `GET /api/org/payroll/tax/employees/{employment}` | `compensation.read` or `payroll.read` across the Organization |
| `PUT /api/org/payroll/tax/window`, `POST /api/org/payroll/tax/items/{id}/accept` and `/return` | `compensation.write` across the Organization |

All are Payroll endpoints: refused with 403 `module.off` when the Organization does not have Payroll.

## Not built yet

LTA (needs an LTA component); other deductions claimed only in the return (donations, disability, savings interest); previous employer's income and tax for joiners in the year; perquisites (Form 123); Form 130 and the quarterly Form 138 return; a regime choice the employee makes on screen (HR sets it today); an email when the window opens.

## Tests

- `TaxDeclarationsTest`: each limit and the HRA exemption without a database (the 50% cities, a part year); rules without limits; then against PostgreSQL the closed window, opening it (and the notification), the landlord's PAN, submitting, payroll reading nothing before submission and declared amounts after, the proof window, files only for their owner and reviewers, HR's overview, accepting at less and not more, returning (and the notification), and verified amounts only from the cut-over month, with a changed claim sent back to be checked.
- `e2e/tests/21-tax-declarations.spec.ts` (project `tax`, its own Organization, runs alone): the employee's screen before and after the window opens, declaring rent with and without the PAN, the ₹1.5 lakh group and NPS, submitting, the phone layout, uploading proofs (and a refused text file), and HR opening, accepting and returning them.
