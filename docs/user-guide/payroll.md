# Payroll and tax declarations: user guide

For customers. How to set up payroll, enter salaries, run a month from inputs to payslips, and collect income tax declarations and proofs. The screenshots come from a demo Organization (Northwind Textiles); your names will differ.

> **Before paying real salaries.** The statutory rules (PF, ESI, professional tax, labour welfare fund, income tax) are shipped as drafts awaiting the payroll consultant's sign-off, and every run says so. Run a month alongside your current payroll and compare before relying on it.

## Overview

A month moves through six steps: **Inputs → Frozen → Calculated → Approved → Paid → Payslips out**. One person prepares and calculates; someone else approves.

| Role | Where | What you can do |
| --- | --- | --- |
| Payroll Operator / HR Admin | **Payroll** (Runs, Wages, Setup), **People → employee → Pay**, **Tax declarations** | Set up, enter pay, run the month, open the declaration window, check proofs |
| Payroll Approver / Org Admin | **Payroll** | Accept exceptions with a reason, approve, mark paid, release payslips |
| Employee | **My pay** (Payslips, Tax declaration) | Read payslips and why each line is what it is; declare tax-saving claims and send proofs |

Nobody sees or sets their own pay, approves a run they calculated, or checks their own proofs. One person cannot be both Payroll Operator and Payroll Approver.

## 1. The employer (Setup → Employer)

Open **Payroll → Setup → Employer**.

![Employer details and how pay is worked out](img/payroll/01-employer.png)

| Setting | Choices | Notes |
| --- | --- | --- |
| Legal name, PAN, TAN, address | Text | PAN like ABCDE1234F; TAN like ABCD12345E. Printed on payslips |
| Salary account (debited) and its IFSC | 6 to 20 digits; 11-character IFSC | Used in the bank file |
| A part month is paid by | Calendar days, working days, or a fixed 30 | |
| Absence without leave or a request is loss of pay | Tick box | |
| PF on wages up to the ceiling only | Tick box | Unless an employee's pay profile says full wages |
| Metro locations, for HRA | Tick work locations | 50% of basic counts in a metro, 40% elsewhere |
| Flag a change in net pay above (%) / or by more than (₹) | Numbers (₹0 turns that off) | Raises an exception to look at |
| Flag a one-time payment above (₹) | Number | |
| Release payslips as soon as a run is marked paid | Tick box | Otherwise you release them by hand |

Choose **Save**; you see **Saved.**

### Registrations

Below the employer, add each registration: **For** (Provident fund, ESI, Professional tax, Labour welfare fund), the **State** for professional tax and the labour welfare fund, and the **Number**. Pick the state of your work location: the state is Karnataka until you change it. A run cannot be approved while employees need a registration you do not have.

![Registrations](img/payroll/02-registrations.png)

### Statutory rules

**Setup → Statutory rules** shows the rules in force, the same for every Organization, each with its dates, source and status. You do not change them; your choices are under Employer.

![Statutory rules](img/payroll/03-rules.png)

## 2. Tables, components and structures

**Tables** hold your own values by a key, read in formulas with `table("name", key)`: a meal card by state, an allowance by designation. Add a row for **every** key that can occur: pay cannot be worked out for someone whose designation (or state) has no row, and you are told so.

![A table by designation](img/payroll/04-table.png)

**Components** are the lines a payslip can carry: earnings, deductions, employer contributions and information lines.

![Components](img/payroll/05-components.png)

Choose **Add a component**:

| Field | Meaning |
| --- | --- |
| Name on the payslip, Code | The code is 2 to 30 capital letters, used in formulas |
| Kind | Earning, deduction, employer contribution, information |
| Applies from | The first of a month; earlier months keep the version they were paid with |
| The amount is | A formula, a fixed amount, entered each month, or worked out by law |
| Formula | Components in capitals (`BASIC`), facts in lower case (`location.state`, `designation`, `days.paid`), and `if`, `case`, `min`, `max`, `round`, `table`, `ytd`. Choose **Check**: a mistake is shown with its position |
| Wages under the Code on Wages | Always, never, or only to bring wages up to half of pay |
| Counts for ESI, Taxable, Prorated, One-time | Tick boxes |
| Rounding, Ledger, Order on the payslip | |

![A component with its formula checked](img/payroll/06-component-form.png)

A **structure** turns a yearly CTC into the monthly components, with one balancing component that takes what is left. Choose **Change** on a structure, pick the month it applies from, **Add a component**, and **Save from …**. A formula loop between components is refused and named.

![The Staff structure](img/payroll/07-structure.png)

## 3. Pay groups

A **pay group** is people paid together, with an input cut-off (day 1 to 28), a pay day and the structure new people get. Most Organizations need one, **Monthly staff**. Choose **People** on the group, then **Add them all to …**.

![The pay group and its people](img/payroll/08-pay-group.png)

## 4. An employee's pay (People → employee → Pay)

Choose **Set pay** (or **Revise pay**): the month it applies from, the structure, the yearly CTC and a reason, then **Preview**. You see what the month would look like (and, for a revision, the month before beside it) before you choose **Save from …**. A pay change cannot go into a month already approved.

![Setting pay with a preview](img/payroll/09-set-pay.png)

The **Statutory profile** holds PF membership, PF on full wages, the pension scheme, ESI, professional tax and labour welfare fund exemptions, and the **Income tax regime** (new by default). Choose **Save profile** after changing it. **Different amount for a component…** sets one component for a period, with a reason (not for PF or other amounts worked out by law).

![An employee's pay, profile and history](img/payroll/10-employee-pay.png)

## 5. Running the month (Payroll → Runs)

1. **Start** the month. Add one-time **inputs** (bonus, incentive, recovery) with a note; amounts must be above zero.
2. **Hold pay** for anyone whose pay should not go out yet, with a reason. Held pay is still worked out but left out of the bank file, and their problems are warnings.
3. **Freeze inputs.** Nothing more can be added; the configuration and everyone's facts are kept as they are now.

![Inputs and a hold](img/payroll/11-run-inputs.png)

4. **Calculate.** You see the totals, the register and the exceptions. Whoever calculated cannot approve.

![A calculated run with its exceptions](img/payroll/12-run-calculated.png)

Open anyone in the register and choose **Explain** on a line: the formula, the values it read and where each came from, the full-month amount and the rounding.

![Explain on a payslip line](img/payroll/13-explain.png)

### Exceptions

**Blocking** exceptions stop approval: no salary, a formula that fails, no state or rules for a location, a missing registration, negative net pay, no bank account, no PAN. Some can be fixed only (fix and **Recalculate**); others the approver can **Accept…** with a reason, one at a time or several with one reason. **Warnings** (no UAN, zero days paid, a large one-time payment, a big change in net pay) do not block.

![Exceptions accepted by the approver](img/payroll/14-exceptions.png)

### Approve, pay and release (approver)

Open the month, accept what may be accepted, choose **Approve** and confirm **Approve payroll**. Download the **Salary register**, the **Accounting journal** (it must balance) and the **HDFC bank file** (without held pay or people without an account). After paying, choose **Mark paid…** with the bank reference, then **Release payslips**. An approved month cannot be reopened.

![An approved run with its files](img/payroll/15-approved.png)

## 6. My pay (employee)

**My pay → Payslips** lists each released payslip. Open one to see the days paid, every line, and **Why** beside it: how it was worked out. **Download PDF** gives the payslip; a second month says why pay changed from the one before.

![A payslip with Why open](img/payroll/16-my-payslip.png)

## 7. Wages under the Labour Codes (Payroll → Wages)

For an approved month, each person's pay, the wages that count, the wage share, any allowance added back to bring wages up to half of pay and the extra PF it caused. **What if** shows the effect of moving amounts before you change a structure.

![The wage check](img/payroll/17-wages.png)

## 8. Income tax declarations and proofs

Only the **old regime** takes these claims; under the new regime they change nothing, and both screens say so.

### Opening the window (HR)

Open **Tax declarations** and choose **Open declarations…**: the dates declarations are open, optionally when proofs are open, and **Only accepted amounts from** (the month from which payroll counts only proved amounts). Choose **Save**; everyone employed is told.

![Opening the declaration window](img/payroll/18-tax-window.png)

### Declaring (employee)

Open **My pay → Tax declaration** while declarations are open. **Add** each claim: rent (HRA; the landlord's PAN is needed above ₹1,00,000 a year), the ₹1.5 lakh group (PPF, ELSS, life insurance, tuition and others), NPS, health insurance, home-loan and education-loan interest. Then **Submit declaration** and confirm that what you entered is true. Payroll counts it from the next run.

![A submitted declaration](img/payroll/19-my-tax.png)

While proofs are open, upload a PDF, JPEG or PNG on each claim. Each shows **Proof sent, waiting**, then **Proof accepted** (with the amount) or **Proof returned** with the reason, when you can add another file.

### Checking proofs (HR)

**Tax declarations** shows where everyone is: not started, not submitted, submitted, proofs to check. Open a person and, on each proof, **Accept…** at an amount no more than declared, or **Return…** with a reason.

![Where everyone stands](img/payroll/20-tax-overview.png)

## Common questions

| Problem | What to check |
| --- | --- |
| "The PAN should look like ABCDE1234F" (or TAN, IFSC) | Check the format. |
| "A change applies from the first of a month" | Pick the 1st. |
| "… could not be worked out: No row for … in the table …" | Add that key to the table. |
| A run cannot be approved: missing registration | Add it under Setup → Employer, in the right state. |
| "You calculated this run; someone else approves it" | Ask the approver. |
| No **Approve** button | Accept or fix the blocking exceptions first. |
| Someone is missing from the bank file | Their pay is held, or they have no bank account. |
| A pay change is refused for a month | That month is approved; arrears come in a later release. |
| "Declarations are not open yet" | HR opens the window under Tax declarations. |
| A claim needs the landlord's PAN | Rent is above ₹1,00,000 a year. |
| "Only PDF, JPEG and PNG" | Save the proof in one of those formats. |
