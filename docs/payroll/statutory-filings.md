# PF and ESI returns (release R4)

Built from an approved payroll month; see `docs/roadmap/next-releases.md` section 4 for the design. Status: built and tested, **rules are drafts until the payroll consultant signs them off**.

## What it does

- **PF ECR** (`kind = pf_ecr`): one text file per month, one line per member, 11 fields separated by the rule's `ecrSeparator` (`#~#`). Wages are whole rupees. EPF wages and EPS wages come from the numbers the engine now records on the `PF_EE` and `PF_ER` payslip lines (`epfWages`, `epsWages`, `eps`), not from the words of the working. EDLI wages are EPF wages capped at the rule's `edliCeiling`. NCP days are the payslip's loss-of-pay days, rounded up unless the rule says `ncpHalfDay: down`.
- **ESI contribution file** (`kind = esi`): a CSV with IP number, name, days (rounded up), ESI wages, reason code, last working day. A member with no wages gets the ESIC reason code mapped from how they left (the Organization's `app.esi_reason_map` row, else the rule's default).
- **Pre-checks**, stored with each version. Blocking ones stop the download: missing or malformed UAN or ESI number, EPF wages above gross, EPS wages above EPF wages or the ceiling, a joiner after the month or a leaver before it, a covered member with no contribution, a run still open, and file totals that do not tie to the run's own PF or ESI lines. Warnings: rules still in draft, ESI number held by someone above the wage threshold.
- **Versions**: every build is a new version; earlier versions are kept. The file is stored encrypted (it holds full UANs and ESI numbers) and downloading needs a fresh two-step code where the Organization requires it.
- **Filing log**: after uploading on the portal, one person records the reference (TRRN or challan), date and amount; a different person approves. Days late are shown against the 15th of the following month.

## API

| Method and path | Permission |
| --- | --- |
| `GET /api/org/payroll/filings?month=yyyy-mm` | `payroll.read` or `payroll.prepare` |
| `POST /api/org/payroll/filings/{pf_ecr\|esi}/{yyyy-mm}` (build and check) | `payroll.prepare` |
| `GET /api/org/payroll/filings/{id}/file` | `payroll.prepare`, fresh two-step code |
| `POST /api/org/payroll/filings/{id}/filed` (record) | `payroll.prepare` |
| `POST /api/org/payroll/filings/{id}/approve` | `payroll.approve`, not the person who recorded |
| `GET`, `PUT /api/org/payroll/filings/esi-reasons[/{exitKind}]` | read, `payroll.prepare` |

## Not built yet

- The screens (Payroll → Statutory filing) and a browser test.
- Attaching the portal's receipt PDF to a filing.
- Arrear ECRs (needs revised pay for past months), and ECR refund of advances (always 0).
- Golden ECR and ESI files for a fixed sample Organization; the tests check each field against the payslips instead.
- Open questions for the consultant: the ECR separator and EPS rounding as the portal expects today, the ESIC reason codes, and whether a half day of loss of pay rounds up or down.
