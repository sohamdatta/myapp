# Performance management: design, as built

Performance management turns a year of goals, check-ins and reviews into
decisions the HRMS already knows how to carry out: a confirmation, a promotion
as a dated job change, an increment as a dated pay revision, or an improvement
plan that can end in an exit. It reuses what is built (reporting lines, the
rule syntax, letters, the bell, the audit log, pay revisions, the exit module)
and adds no new way of doing any of them.

The live design is the [claude.ai document](https://claude.ai/code/artifact/6a10ae6f-f1cc-40de-9ea5-f7b36d28fad8);
this page records what was built and where the build differs from it.

The schema is six migrations, one per stage:

| Migration | Stage |
|---|---|
| `V16__performance_probation.sql` | P1 probation reviews, settings, the four permissions |
| `V17__performance_goals.sql` | P2 rating scale, competencies, goals, one-to-ones, feedback |
| `V18__performance_reviews.sql` | P3 review forms, cycles, reviews and entries |
| `V19__performance_calibration.sql` | P4 calibration sessions and changes, outcomes, merit table and guide |
| `V20__performance_plans.sql` | P5 improvement plans, objectives and check-ins |
| `V21__performance_peers.sql` | P6 peer requests, skip-level sign-off, feedback requests |

The code is in `core/` (`Probation`, `Goals`, `OneToOnes`, `Feedback`,
`PerformanceSetup`, `PerformanceTeam`, `Reviews`, `Calibration`, `Outcomes`,
`Plans`, with `Perf` holding shared helpers and `PerformanceDefaults` the
defaults), and `web/PerformanceController.java`. The screens are
`MyPerformance.jsx` for everyone and `Performance.jsx` (tabs Team, Reviews,
Probation, Improvement plans, Department goals, Setup) for managers and HR.

## Permissions

| Permission | Lets you | Granted to |
|---|---|---|
| `performance.read` | See goals, reviews, probation reviews and plans | Employee (self), Manager (team), HR Admin and Org Admin (tenant), Auditor (tenant) |
| `performance.write` | Set goals, write reviews, check-ins and recommendations, draft plans | Employee (self), Manager (team), HR Admin and Org Admin (tenant) |
| `performance.manage` | Set up performance, run cycles, calibrate, decide probation and plans, prepare outcomes | HR Admin and Org Admin |
| `performance.approve` | Approve outcomes | HR Admin and Org Admin, never for outcomes they sent |

Nobody writes the manager's part of, calibrates, approves, decides or checks in
on their own review, probation or plan; triggers in the database enforce it as
well as the services. Encrypted with the Organization's data key: review
answers, override and calibration reasons, private one-to-one notes, private
feedback, plan reasons, support, responses, check-in notes and decisions. A
support session sees statuses, never words.

## Probation reviews (P1)

Every employment with a probation end gets a review. The manager writes
check-ins at 30, 60 and 90 days (Setup), each with "On track", "Some concerns"
or "Not on track" and a note the employee reads and can reply to once. 21 days
before the end the manager recommends confirm, extend (1 to 6 months) or not
confirm; HR decides. Confirming issues the confirmation letter; extending
moves the probation end and issues the extension letter; not confirming starts
a termination through the exit module (`ExitService.startIn`). Reminders are
worked out when the bell is opened: check-ins due, recommendations due, and
decisions overdue for HR.

## Goals, one-to-ones and feedback (P2)

Goals belong to a goal year (April or January start, Setup). Weights total 100
when the set is sent; the manager agrees or returns it. After agreement a
change makes a new version and needs agreeing again. Progress is recorded by
either side. HR publishes department goals that goals can link to.

One-to-ones keep shared notes and action items, and private notes for each
side. The manager is nudged after the days set without one.

Feedback is praise (the person reads it; optionally shared with their manager)
or private (only the manager and HR). It can be changed for 24 hours, then only
withdrawn; a withdrawn item keeps its date, not its words.

## Review cycles (P3)

HR sets a cycle up: period, who is in it (the rule syntax, plus a joining
cutoff), the form, the goal weight (competencies take the rest), self review
on or off, phase dates, and from P6 peer reviews and skip-level sign-off.
Starting creates one review per eligible employee with their manager that day.

| Review status | Who acts | Next |
|---|---|---|
| `self_review` | The employee rates goals and answers the form | `manager_review` |
| `manager_review` | The manager rates goals and competencies, answers the form, gives the overall rating and may recommend a promotion | `skip_level` or `calibration` |
| `skip_level` | The manager's manager signs off or sends it back with a note | `calibration`, or back to `manager_review` |
| `calibration` | HR may change the rating in a session | `ready` when the session closes |
| `ready` | Waiting for release | `released` |
| `released` | The employee reads it and acknowledges, with a comment | `acknowledged` |

The overall rating is worked out from the goals' weighted average and the
competencies' average by the cycle's weights, rounded to a level, and shown
with its steps. A manager who gives a different rating writes a reason of at
least 20 characters. A manager returns a self review once. HR adds people,
withdraws reviews and moves a review past the self review, each with a reason.

## Calibration and outcomes (P4)

**Calibration.** While a cycle is calibrating, HR opens sessions: one
department's written reviews, or every written review not yet in a session.
A session shows the spread of ratings now, what managers gave, and the guide
distribution (Setup; shown, never enforced). A change needs a reason of at
least 20 characters, is encrypted and kept as history, and the manager is
told. Closing a session makes its reviews `ready`. An open session holds the
release; a written review not in any session is released at the manager's
rating.

**Outcomes.** After release HR prepares one outcome per released review:

- the increment the merit table suggests for the final rating (Setup), applied
  to the pay in force on the effective date; a different figure needs a note;
- a promotion, prefilled from the manager's recommendation;
- a bonus amount, shown in the letter;
- the effective date, the first of a month (default: next month).

HR sends them for approval; someone other than the sender approves them, or
sends them back with a note. Approving records the pay revision with
`PayrollPay.reviseIn` and the promotion with `EmployeeService.changeJobIn`,
inside the same transaction and with every check those make by hand (no date
in an approved payroll month, nobody's own pay), then issues the increment and
promotion letters. "Send all" and "Approve all" skip the caller's own outcome
and, for approval, the ones they sent.

## Improvement plans (P5)

A manager (or HR) drafts a plan: why (at least 20 characters), one to six
objectives each with how it is measured, the support offered, 14 to 120 days,
and a check-in every 7 to 31 days. HR is told, and starts it, which schedules
the check-ins (and one on the last day), issues the plan letter and tells the
employee. The employee acknowledges reading it (which is not agreeing) and may
respond. At each check-in the manager rates every objective (on track, at
risk, met, not met) with a note; the employee may reply once. Check-ins open a
week before they are due.

HR decides: completed; extended once by 14 to 60 days (the check-ins are
extended too); or not met, which needs the plan to have ended with at least one
check-in written, and starts a termination with the last working day given.
A draft or running plan can be cancelled with a reason; one whose employee has
left is cancelled. Managers are reminded of check-ins due; HR of plans ended
and undecided.

## Peers, skip-level and requested feedback (P6)

**Peers.** A cycle can ask for up to five peer reviews a person. The manager
(or HR) chooses the peers while the review is being written; each is told and
answers the form's peer questions in My performance, or declines with a
reason. The manager and HR read them with names. The employee reads them after
release: without names when the cycle is anonymous (the default), and then
only when two or more were written, in no particular order. Peers who have not
written when the manager sends the review are no longer waited for.

**Skip-level.** With sign-off on, a written review goes to the manager's
manager, who signs it off with an optional comment or sends it back with a
note, which reopens the manager's part. HR can do either. The employee does not
see the sign-off.

**Requested feedback.** Anyone asks up to five colleagues for feedback about
themselves, and a manager about someone in their team. The colleague answers
with the usual feedback form or declines with a reason; the person who asked
sees which.

## Where the build differs from the design

- **Six migrations, not one.** Each stage shipped with its own migration
  (V16 to V21) so each could be released on its own.
- **Merit table and guide in `perf_settings`.** They are JSON by rating value,
  not a versioned payroll value table, and the merit table is one figure per
  rating, not a range by grade or department.
- **Bonus.** Recorded on the outcome and shown in the letter; it is not yet a
  payroll input.
- **Calibration panels.** Sessions are run by HR on screen; there is no list
  of named panel members with their own access.
- **Peers.** Chosen by the manager, not nominated by the employee for the
  manager to approve. Anonymous peer reviews are shown to the employee from
  two written, not three.
- **Improvement plans.** 14 to 120 days with up to six objectives, and the
  manager does not recommend the outcome; HR decides directly. A plan is not
  opened automatically from a low rating.
- **Probation check-ins** use a three-step rating, not the review scale.
- **Arrears** are not paid: an outcome cannot be dated into a month whose
  payroll is approved.

## Open legal points

Unchanged from the design: whether confirmation is deemed when a probation end
passes undecided (nothing happens automatically), what process makes a
performance termination valid for "workers" under the Industrial Relations
Code (an unsuccessful plan starts a termination; HR can issue a show-cause
notice from the exit), and how long review records and plans must be kept.
