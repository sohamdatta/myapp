# Leave Management and Work From Home — Features

Full text, with competitor table and sources, is in the attendance features document: https://claude.ai/code/artifact/61c2baf4-f684-46bf-9e89-8ad4828facae (sections "Leave management" and "Work from home").
Dated: 4 Oct 2026 · Not built. Hours are estimates.

## Leave management

Leave completes the attendance day result ("on leave") and gives payroll its paid days.

**Recommendation:** do not match Keka's number of settings. Compete on:
1. **A balance that explains itself** — leave as an add-only ledger (accrual, taken, cancelled, lapsed, carried forward, encashed); the balance is the sum.
2. **Earned leave that follows the law** — OSH Code: 1 day per 20 days worked after 180 days in the year, 30-day carry-forward cap, excess paid, refused leave carries without limit, paid on leaving. Dated rule data per state. **From one secondary guide; needs consultant confirmation before building.**
3. **A year-end you can preview** before it is committed as ledger entries.
4. **Leave and attendance are one day** — approved leave is the day's result.

**First:** leave types · policy per type (quota, accrual, proration, probation, carry cap) · ledger · apply (range, half day; days counted from shift, weekly offs, holidays) · approve/reject/cancel · `on_leave` day status, unpaid = LOP · insufficient balance rule · HR on-behalf and adjustments.
**Second:** who-is-away calendar · year-end run · comp-off from work on a day off · weekends-inside-leave rule.
**With payroll:** encashment. **After sign-off:** statutory earned-leave rules.

Fit: follows `attendance_request` pattern; `DayCalculator` gains one input and `on_leave`; permissions `leave.apply/read/approve/manage`; same RLS.

Build: types and policies 8h · ledger and accrual 10h · apply/approve/cancel + day result 10h · HR actions and calendar 6h · year-end 8h · comp-off and weekends rule 6h · encashment 6h (with payroll). About 48h before payroll, 54h in all.

Not building: hourly leave, multi-level approval, unlimited leave, gap/monthly limits, attachments.
Open: leave year (calendar vs April–March) · who approves until managers exist · maternity and other statutory leave.

## Work from home

**Built already:** WFH request for today or the last 31 days, approved by HR/Org Admin, counts as a full day, no punch.

**Competitor baseline (Keka help pages):** enable WFH, per-profile eligibility, approval restrictions, request limit, optional clock-in while remote.

**First:** future dates and ranges · eligibility per user · limits per week/month · cancel an approved day · punch from home (optional per Organization; skips the office check, recorded as remote).
**Second:** regular remote days in the assignment (hybrid week) · half day · who-is-where on the board · monthly report.

Compete on: hybrid week as part of the schedule · no monitoring (no screenshots or tracking) · one day calculation for office, remote, on duty and leave.

Build: future dates/ranges/cancel 5h · eligibility and limits 5h · punch from home 6h · regular remote days 8h · half day, board, report 6h. About 30h; the first 10h reach Keka's described baseline.

Open: punches required on remote days by default? · limits per week or month · beyond the limit: refuse or send to HR.
