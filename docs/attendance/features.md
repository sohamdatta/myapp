# Attendance Module: Features and Competitive Position

Oct 3, 2026

*Updated 4 Oct 2026: added [Leave management](#leave-management) and [Work from home](#work-from-home). The attendance features in the first four build steps and the device import are now built.*

## Recommendation

Do not compete on how employees punch in. Compete on what happens after the punch: correct overtime, a clean month-end, and a paid-days number every employee can trace.

All eight competitors reviewed offer five to eight ways to mark attendance, including face recognition, geofencing and biometric devices. That is parity, and a solo build cannot match it. None of their product pages describes checking overtime against legal limits, recording a worker's consent to overtime, or explaining to an employee how their paid days were calculated.

Five features give an advantage, in this order:

1. **Paid days that explain themselves.** Every paid-day and loss-of-pay figure links back to the punches, the rule and the approvals behind it. This extends the "why did my pay change" feature already planned for payroll.
2. **Overtime that follows the law.** Overtime at twice the ordinary wage rate, a consent record, and warnings before an employee crosses the daily, weekly or quarterly limit for their state. The limits are stored as dated rule data, the same way as the payroll rules.
3. **A month-end that closes cleanly.** One exception list, a cut-off that freezes inputs, and corrections after the cut-off that flow into the next payroll as arrears without manual work.
4. **Device import that does not break.** Import the file any biometric device exports, map its columns once, and show plainly which punches are missing or duplicated. Sync failures are a recurring attendance complaint in Keka and greytHR reviews.
5. **No face data, stated as a feature.** Web and phone-browser punch with location and office-network checks, and no stored face templates or continuous location tracking. This avoids holding biometric data under the DPDP rules and avoids the battery-drain complaint.

The module fits the existing plan (Stage D, after three paying customers). It adds about 30 hours to the 70 already budgeted for attendance and device import.

## What competitors offer

Capture methods are at parity across the market; the differences are in price and in who the product is built for. The table covers what each vendor's own product page states, read on 3 October 2026.

| Vendor | Ways to mark attendance | Shifts and overtime | Compliance and payroll link | Stands out for |
| --- | --- | --- | --- | --- |
| [Keka](https://www.keka.com/attendance-management-system) | Biometric, smart card and RFID devices; mobile selfie; GPS; Wi-Fi; IP restriction; timesheet-based | Shift boards with automatic rotation; overtime calculated automatically | Penalty policies linked to pay; integrated with its payroll | Widest set of capture modes; alerts when a device fails |
| [greytHR](https://www.greythr.com/attendance-management-software/) | Biometric hardware; web sign-in; selfie; face ("Visage"); GPS and geofence; IP control | Shift rotation plans; automatic shift detection; overtime policies and overtime payslips | Muster roll, overtime register and attendance register; automatic month-end finalisation | The only page read that names statutory registers |
| [Zoho People](https://www.zoho.com/people/attendance-management-system.html) | Web and mobile check-in; face verification by AI; kiosk with face recognition; biometric; IP and location limits | Shift management; overtime hours calculated | Feeds working hours to payroll | Kiosk mode for on-site staff |
| [Darwinbox](https://platform.softwareone.com/product/time-attendance/PCP-9808-3868) | Biometric; IP-restricted web and mobile; geo check-in; AI face recognition | Rostering and scheduling; project timesheets | Flexible leave deduction engine; absconding workflows | Enterprise scale; voicebot and WhatsApp notifications |
| [HROne](https://hrone.cloud/attendance-management-software/) | Mobile app; geo-tagged selfie; geofence; biometric (ESSL, ZKTeco, Suprema, BIOMAX); WhatsApp bot; web | Fixed, rotating, split and flexible shifts; overtime and comp-off calculated | Automatic paid-days count; arrear policy for backdated corrections | WhatsApp check-in; paid-days calculation described in detail |
| [Kredily](https://kredily.com/attendance-management-system/) | Web and mobile GPS free; selfie, geofence, face with liveness check, biometric sync and automatic clock-in paid | Shifts and rostering; overtime and comp-off accrual | Late marks and half-day penalties; automatic LOP to payroll | Free for unlimited employees; paid capture add-ons at ₹50 per user per month |
| [PagarBook](https://pagarbook.com/) | Face; GPS; selfie; its own biometric device; works offline | Shift matching; overtime; break tracking; piece-rate entry | Automatic fines; PF, ESI, PT and LWF; payslips on WhatsApp | Small businesses and blue-collar staff; 10+ languages; ₹499 to ₹1,398 per staff per year |
| [Truein](https://truein.com/) | Face recognition with fraud detection; geofence; kiosk; works offline; no hardware needed | Bulk shift creation; custom overtime and break rules; 70+ policies | Payroll-ready exports to ADP, Darwinbox, SAP and others | Contract and multi-site workers; AI flags unusual punches |

Darwinbox's own product page lists no features, so its row comes from a reseller listing. Keka, Darwinbox and Truein do not publish prices.

## Where competitors fall short

The gaps are in reliability and in the law, not in capture features. Three show up in the evidence.

**Reliability of the basics.** Users of the two market leaders complain about the same things: the mobile app, device sync and support.

| Vendor | What reviewers complain about | Source |
| --- | --- | --- |
| Keka | Limited customization in payroll, attendance and reporting (156 mentions); poor customer support (145); slow loading (89). Mobile clock-in fails or does not sync; battery drain from location tracking; a rejected regularization does not apply the penalty automatically | [G2, Keka pros and cons](https://www.g2.com/products/keka/reviews?qs=pros-and-cons) |
| greytHR | Limited customization of reports and workflows (87 mentions); slow loading (87); slow support (83); login problems (58). Attendance from biometric devices not syncing; mobile app described as clunky. Rated 4.4 of 5 across 1,291 reviews | [G2, greytHR pros and cons](https://www.g2.com/products/greythr/reviews?qs=pros-and-cons) |

**Overtime law.** The labour codes set overtime at twice the ordinary rate of wages and allow it only with the worker's consent. Hour limits and the quarterly overtime cap are set by each government, and state rules are still in draft in several states, so the older state Acts continue to apply there. Maharashtra's shops Act, for example, caps overtime at 125 hours in three months. Every vendor page read says it "calculates overtime". None mentions checking a cap, recording consent, or which state's rule applies. Sources: [LKS employment law guide](https://employmentlaw.lkslaw.com/working-hours), [Patron Accounting overtime guide](https://www.patronaccounting.com/blog/overtime-rules-india-2026-calculation-double-pay-compliance).

**Explaining the result.** HROne and greytHR describe calculating paid days automatically. No page describes showing an employee why a day was counted as absent, late or unpaid. Disputes over this are settled by raising a regularization request and waiting.

Two limits on this evidence. A feature missing from a product page may still exist in the product. And review counts are G2's own groupings of complaints, covering the whole product and not attendance alone.

## Feature list

The module has 27 features in seven areas. Core means needed for the first customer, Sell means it helps win a deal, and Later means wait until a customer asks. The priority labels match the payroll feature checklist.

| Area | Feature | Priority |
| --- | --- | --- |
| Capture | Web punch in and out, with office-network (IP) restriction | Core |
| Capture | Phone-browser punch with location, and an allowed radius per work location | Core |
| Capture | Import of punch files exported by biometric devices, with a saved column mapping per device | Core |
| Capture | Manual entry by HR, with a reason, for staff without a device | Core |
| Capture | Offline punch on the phone that uploads when the connection returns | Later |
| Shifts | Fixed shifts with grace minutes, and a weekly-off pattern per employee | Core |
| Shifts | Location holiday calendars | Core |
| Shifts | Rotating shifts and a monthly roster | Sell |
| Shifts | Night-shift flag, with the consent record the law requires for women working nights | Sell |
| Daily result | Each day resolved to present, half day, absent, leave, holiday or weekly off, from punches, shift and approved leave | Core |
| Daily result | Late, early-exit and short-hours rules, with the rule that applied shown on the day | Core |
| Daily result | Penalties that convert repeated lateness into leave or pay deductions | Sell |
| Corrections | Regularization request by the employee, approved by the manager | Core |
| Corrections | Limit on requests per month, and automatic approval of low-risk requests | Sell |
| Corrections | On-duty and work-from-home requests | Core |
| Overtime | Overtime hours calculated from punches against the shift | Core |
| Overtime | Overtime paid at twice the ordinary rate of wages, on the wage base the state rule defines | Core |
| Overtime | Consent record for each overtime period | Sell |
| Overtime | Warnings before daily, weekly and quarterly limits are crossed, by state | Sell |
| Overtime | Compensatory off in place of overtime pay, where policy allows | Later |
| Payroll link | Paid days and loss-of-pay days per employee, with the trail behind each figure | Core |
| Payroll link | Cut-off date that freezes the month, and an exception list to clear before it | Core |
| Payroll link | Corrections after cut-off carried into the next payroll as arrears | Sell |
| Records | Attendance register and muster roll | Core |
| Records | Overtime register and quarterly overtime summary | Sell |
| Records | Import health view: last file, missing days, duplicate punches | Core |
| Records | Monthly summary export for managers | Sell |

## The five advantages in detail

Each one reuses something the payroll engine already has, which is why a small build can offer what larger vendors do not.

| Advantage | What the customer sees | Likely reason competitors lack it (inferred) | What it reuses | Main risk |
| --- | --- | --- | --- | --- |
| Paid days that explain themselves | An employee opens any day or any payslip line and sees the punches, the rule applied and who approved a change. HR stops answering "why was I marked absent" | Their attendance and payroll were built as separate modules joined by a monthly total, so the detail is lost at the join | Payroll lineage: each payroll line already records its inputs | Low. It is a display of data the system must store anyway |
| Overtime that follows the law | Overtime priced at twice the ordinary wage rate, a consent record, and a warning before a state limit is crossed. An overtime register an inspector can be handed | Their overtime is a configurable multiplier with no legal rule behind it, and state limits differ and are changing | The dated, per-state rule store built for PF, ESI and professional tax | Medium. The rules must be written and signed off by the payroll consultant, state by state |
| A month-end that closes cleanly | One list of exceptions to clear, a cut-off, and late corrections that appear in next month's pay as arrears without anyone recalculating | Backdated attendance changes need payroll to be reopened or adjusted by hand | Arrears with recomputation, already planned for payroll | Low |
| Device import that does not break | HR uploads the device's export file; the system shows what it read, what is missing and what is duplicated | They integrate live with many device models, and reviewers report those syncs failing | The register import and column mapper from the CTC report tool | Low. The trade-off is that data arrives when HR uploads it, not live |
| No face data | A plain statement in the sales conversation: no face templates stored, no continuous location tracking | Face recognition is a headline feature for them; removing it is not an option | The DPDP retention and erasure design | Medium. Some buyers with large site workforces will require face recognition and will not buy |

State the overtime coverage precisely, as recommended for professional tax: name the states whose rules are loaded and signed off. A short list that is exact is more credible than "all states supported".

## Build order and effort

The module is about 100 hours: the 70 already in the build plan for attendance (D2) and device import (D3), plus 30 for the overtime rules, the day-level trail and late corrections. The hours are estimates for one developer and have not been tested against real work.

| Step | Hours | In the plan today |
| --- | --- | --- |
| Web and phone-browser punch, with IP and location checks | 10 | D2 |
| Shifts, weekly offs and holiday calendars | 10 | D2 |
| Daily result and the late, early-exit and short-hours rules | 14 | D2 |
| Regularization, on-duty and work-from-home requests | 8 | D2 |
| Paid days and loss of pay into payroll, cut-off and exception list | 8 | D2 |
| Device file import with column mapping, and the import health view | 20 | D3 |
| Overtime: rate, consent, limit warnings and registers, for one state | 18 | New |
| Day-level trail for employees | 6 | New |
| Corrections after cut-off carried forward as arrears | 6 | New |

Build the first six steps in order; they are what a customer needs to stop using a spreadsheet. The three new steps follow, with overtime first because it is the one a buyer can check against their current vendor. Each further state for overtime needs the consultant's sign-off on that state's rule.

### What not to build

- **Face recognition and selfie checks.** Every competitor has them, they need a native app and biometric data handling, and they are the opposite of the privacy position above.
- **Continuous location tracking.** A source of battery-drain complaints and of employee resentment.
- **Native mobile apps.** Already excluded in the build plan; the phone browser covers punching and requests.
- **Live device integrations.** One per device maker, each a support burden. File import covers the same need.
- **WhatsApp check-in, voicebots and AI anomaly detection.** Present at HROne, Darwinbox and Truein; none decides a purchase for a company that mainly wants correct payroll.
- **Roster optimisation and project timesheets.** Separate products for different buyers.

## Leave management

Leave is the piece attendance has been waiting for: today a day can be present, absent, a holiday or worked from home, but never "on leave". Adding it completes the day result and gives payroll its paid days.

**Recommendation.** Do not try to match Keka's number of settings. Build a small set of leave rules that cover an ordinary Indian company, and compete on three things the competitor pages do not show: a balance that explains itself, earned leave that follows the law, and a year-end that can be previewed before it is committed.

### What competitors offer

Read from each vendor's own help pages. A feature not listed may still exist.

|  | Keka | greytHR | Zoho People |
| --- | --- | --- | --- |
| Leave types and plans | Leave plans with types; yearly quota in days or hours, including unlimited | Leave schemes | Leave types, day-based or hour-based |
| Accrual | Monthly, quarterly, half-yearly or yearly; accrual day; proration in the first month; probation rules | Leave grant by admin; balance recalculation | Leave grant requests |
| Applying | Half and quarter day, prior notice, backdating limit, comment required, monthly limit, maximum consecutive days, gap between leaves | Not described on the pages read | Half and quarter day, hourly, partial cancellation of a multi-day leave |
| Weekends inside a leave | Sandwich rule, also across leave types | Not described | Not described |
| Approval | Multi-level chain, auto-approval when approvers are away | Leave reviewer per employee | Not described |
| Comp-off | Yes | Not described | Yes, for work on a weekend or holiday |
| Year end | Carry forward, expire or pay out | Carry forward, lapse or encash; manual or automatic on a set date | Year-end balance projection for the employee |
| Views | Not described | Leave calendar | List, calendar and department views; who is in or away |

### Where to compete

1. **A balance that explains itself.** Keep leave as a ledger: every accrual, leave taken, cancellation, lapse, carry-forward and encashment is one dated entry, and the balance is the sum. The employee opens their balance and sees how it got there, the same way a day already shows its trail. greytHR has a "recalculate balances" action, which suggests balances there can drift from their history.
2. **Earned leave that follows the law.** The OSH Code sets earned leave at one day for every 20 days worked, after 180 days of work in the year, with at most 30 days carried forward; the excess must be paid, leave that was applied for and refused carries forward without limit, and the balance is paid on leaving. Store these as dated rule data per state, as already planned for overtime. Competitors expose the settings and leave the employer to know the law. **This reading comes from one secondary guide and needs the consultant's confirmation before building**, including which state Shops Act terms are more favourable.
3. **A year-end you can preview.** Show what will carry forward, lapse and be encashed for every user before anything changes, then commit it as ledger entries. A wrong run is corrected by reversing entries, not by editing balances.
4. **Leave and attendance are one day.** An approved leave becomes the day's result, so the board, the register and paid days agree without a sync. If someone punches on a leave day, the day shows both and HR decides.

### Feature list

Hours are estimates.

| Feature | What it does | When |
| --- | --- | --- |
| Leave types | Earned, casual, sick, unpaid, comp-off and any the Organization adds; each paid or unpaid | First |
| Leave policy | Per type: yearly quota, monthly or yearly accrual, proration for joiners, whether it accrues on probation, carry-forward cap | First |
| Balance ledger | Dated entries for every change; balance is their sum; visible to the employee | First |
| Apply for leave | Date range, full or half day, reason; days counted from the user's shift, weekly offs and holidays | First |
| Approve, reject, cancel | Same flow as attendance requests; nobody decides their own; cancelling returns the days | First |
| "On leave" day result | Approved leave sets the day's status; unpaid leave counts as loss of pay | First |
| Not enough balance | Refuse, or allow and mark the excess as unpaid, per policy | First |
| HR actions | Apply on someone's behalf; adjust a balance with a reason, audited | First |
| Who is away | Calendar of approved and pending leave for the people the viewer can see | Second |
| Year-end run | Preview, then carry forward, lapse and mark for encashment | Second |
| Comp-off | Work on a weekly off or holiday (attendance already records it) earns a day, with an expiry | Second |
| Weekends inside a leave | Optional rule to count weekly offs and holidays that fall inside a leave | Second |
| Encashment to payroll | Year-end and exit encashment as payroll inputs | With payroll |
| Statutory earned-leave rules | 1 per 20 days worked, 180-day eligibility, 30-day cap, by state | After consultant sign-off |

### How it fits what is built

- A leave request follows the pattern of `attendance_request`: raised by the user, decided by someone else, audited.
- `DayCalculator` gains one input (approved leave for the day) and one status, `on_leave`. Nothing else in the calculation changes.
- Day counting reuses the assignment, shift, weekly offs and holiday tables.
- Four permissions in the same shape as attendance: `leave.apply`, `leave.read`, `leave.approve`, `leave.manage`, with the same scopes. Manager approval still waits for reporting lines.
- New tables carry `tenant_id` and the same row-level security. Ledger entries are add-only, like punches.

### Build order

| Step | Hours |
| --- | --- |
| Leave types and policies | 8 |
| Ledger and accrual | 10 |
| Apply, approve, cancel; "on leave" in the day result | 10 |
| HR actions and the who-is-away calendar | 6 |
| Year-end run with preview | 8 |
| Comp-off and the weekends rule | 6 |
| Encashment into payroll | 6, with payroll |

About 48 hours before payroll exists, 54 in all.

**Not building:** hourly leave, multi-level approval chains, unlimited leave, limits such as a gap between leaves or a monthly cap, and documents attached to a request. Each can be added when a customer asks.

**Open:** whether the leave year is the calendar year or April to March · who approves until managers exist (HR, as for attendance) · maternity and other statutory leave need their own rules and are not covered here.

## Work from home

### What is already built

A user can raise a work-from-home request for today or any of the last 31 days. HR or the Org Admin approves it, and the day then counts as a full day with no punch needed. That covers the occasional day at home, reported the same day or afterwards.

It does not cover planning ahead, regular remote days, or any limit on how often.

### What competitors offer

Keka's help pages describe: switching work from home on, restricting it to employees who have it enabled on their profile, approval restrictions, a limit on requests, and an option for employees to clock in and out while working remotely. Zoho People's employee leave guide does not treat work from home separately. The greytHR pages read do not describe it. A feature not on the pages read may still exist.

So the baseline is: eligibility, a limit, approval, and optional punching from home.

### What to add

| Feature | What it does | When |
| --- | --- | --- |
| Request for future dates | Ask for tomorrow or next week, as a date range | First |
| Eligibility | Work from home is on or off per user | First |
| Limits | Days per week or month; the request screen shows how many are left | First |
| Cancel an approved day | Plans change; cancelling puts the day back to an office day | First |
| Punch from home | Per Organization: a remote day needs no punch (as now), or needs punches. A remote punch skips the office-network and location check and is recorded as remote | First |
| Regular remote days | Part of the user's assignment: for example office Monday to Wednesday, remote Thursday and Friday. No request needed on those days | Second |
| Half day at home | Morning or afternoon | Second |
| Who is where | The daily board shows office, remote and on leave; counts per day | Second |
| Report | Remote days per user per month | Second |

### Where to compete

1. **A hybrid week as part of the schedule.** Most products treat every remote day as a request. A user's regular remote days belong beside their shift and weekly offs, already dated and add-only here, so a hybrid team raises requests only for exceptions.
2. **No monitoring, stated as a feature.** No screenshots, activity tracking or location while at home; a remote day is a planned status and, if the Organization wants it, a punch. This matches the position already taken on face data.
3. **One picture of the day.** Office, remote, on duty and on leave are all results of the same day calculation, each with its trail, so the board and the register agree.

### How it fits what is built

- The request, approval and audit already exist; future dates and ranges extend the existing request window.
- Eligibility, limits and regular remote days are settings beside the assignment.
- A remote punch is a new check result next to `ip_ok` and `location_ok`.
- The day result already has `work_from_home`; with punches required it also carries hours, late and early marks.

### Build order

| Step | Hours |
| --- | --- |
| Future dates, ranges, cancel | 5 |
| Eligibility and limits | 5 |
| Punch from home | 6 |
| Regular remote days in the assignment | 8 |
| Half day, board and report | 6 |

About 30 hours (estimate). The first two steps, about 10 hours, close the gap with what Keka describes.

**Open:** whether a remote day should need punches by default · whether limits are per week or per month · whether a remote day beyond the limit is refused or goes to HR for a decision.

## Sources

All pages were read on 3 October 2026. Vendor pages are marketing and describe features as the vendor chooses to; the overtime rules come from two secondary guides and need the payroll consultant's confirmation before any rule is built.

- [Keka attendance management](https://www.keka.com/attendance-management-system)
- [greytHR attendance management](https://www.greythr.com/attendance-management-software/)
- [Zoho People attendance](https://www.zoho.com/people/attendance-management-system.html)
- [Darwinbox Time and Attendance, reseller listing](https://platform.softwareone.com/product/time-attendance/PCP-9808-3868) and [Darwinbox product page](https://darwinbox.com/en-us/products/time-and-attendance)
- [HROne attendance management](https://hrone.cloud/attendance-management-software/)
- [Kredily attendance management](https://kredily.com/attendance-management-system/)
- [PagarBook](https://pagarbook.com/)
- [Truein](https://truein.com/)
- [G2: Keka pros and cons](https://www.g2.com/products/keka/reviews?qs=pros-and-cons)
- [G2: greytHR pros and cons](https://www.g2.com/products/greythr/reviews?qs=pros-and-cons)
- [LKS: working hours, overtime and night-shift rules in India](https://employmentlaw.lkslaw.com/working-hours)
- [Patron Accounting: overtime rules in India 2026](https://www.patronaccounting.com/blog/overtime-rules-india-2026-calculation-double-pay-compliance)
- Project documents: the solo developer build plan (Stage D hours) and the September 2026 competitive landscape for payroll

Added 4 Oct 2026 for leave and work from home:

- [Keka: Configuring Regular Leave](https://help.keka.com/hc/en-us/articles/39946786434961-Configuring-Regular-Leave)
- [Keka: Work from Home](https://docs.keka.com/faq/time-attendance/work-from-homewfh/)
- [Keka: clock-in and clock-out during WFH](https://help.keka.com/admin/admin-help/how-to-enable-clock-in/clock-out-during-wfh)
- [Techjockey: restricting WFH requests in Keka](https://www.techjockey.com/question/2070/can-we-restrict-number-of-times-an-employee-can-request-for-work-from-home-wfh-in-keka)
- [greytHR: leave year-end activities](https://www.greythr.com/help-admin/leave-management/process-leave-year-end-activities/)
- [Zoho People: employee leave guide](https://help.zoho.com/portal/en/kb/people/employee-guide/employee-leave/articles/zoho-people-employee-leave)
- [EZHRM: leave encashment rules under the OSH Code](https://ezhrm.in/leave-encashment-rules-india-2026-osh-code-carry-forward-cap/) (secondary guide; the only source for the earned-leave rules above)
