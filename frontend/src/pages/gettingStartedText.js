/** Words on the Getting started page, ready for translation. */

export const CARRY_OPTIONS = [
  ['NONE', 'don’t carry forward'],
  ['UP_TO_15', 'carry forward up to 15 days'],
  ['UP_TO_30', 'carry forward up to 30 days'],
  ['UP_TO_30_PAY_REST', 'carry forward up to 30 days, and pay out the rest'],
]

export const GS = {
  intro: 'Do these in order. Each one takes a minute or two, and everything can be changed later.',
  progress: (done, total) => `${done} of ${total} done`,
  progressLabel: 'Setup progress',
  loading: 'Loading…',
  open: 'Open',
  review: 'Review',
  done: 'Done',

  steps: {
    company: {
      title: 'Your company',
      summary: (name) => `${name} is set up and you are its Org Admin.`,
    },
    starter: {
      title: 'Starting setup',
      todo: 'Pick the setup closest to how you work, and we add the locations, shifts, leave types and holidays.',
      done: (f) => `${f.workLocations} work ${f.workLocations === 1 ? 'location' : 'locations'}, ${f.shifts} ${f.shifts === 1 ? 'shift' : 'shifts'} and ${f.leaveTypes} leave ${f.leaveTypes === 1 ? 'type' : 'types'} are set up.`,
      change: 'Change them under Attendance and Leave',
    },
    people: {
      title: 'Your people',
      todo: 'Import your staff from a spreadsheet saved as CSV. You see what will be added before anything is saved.',
      done: (n) => `${n} ${n === 1 ? 'person is' : 'people are'} in the record.`,
      action: 'Open People',
      where: 'Under People, choose Import.',
      joiners: (n) => `${n} more ${n === 1 ? 'is' : 'are'} joining and ${n === 1 ? 'has' : 'have'} not started yet.`,
    },
    invite: {
      title: 'Invite your team',
      todo: 'Leave and attendance work for a person once they have signed in. Invite them from User management: you copy each link and send it.',
      done: (logins, pending) => `${logins} signed in${pending ? `, ${pending} invitation${pending === 1 ? '' : 's'} waiting` : ''}.`,
      waiting: (pending) => `${pending} invitation${pending === 1 ? '' : 's'} waiting to be accepted.`,
      action: 'Open User management',
    },
    assign: {
      title: 'Locations and shifts for people',
      none: 'Nobody has signed in yet. Once they do, assign each person a location and shift, and their weekly offs.',
      todo: (n) => `${n} ${n === 1 ? 'person has' : 'people have'} signed in and no location or shift yet. Assign several at once.`,
      done: 'Everyone who has signed in has a location and a shift.',
      action: 'Open Attendance',
      actionCalendar: 'Open Work locations',
    },
    leave: {
      title: 'Leave',
      todo: 'No leave types yet. A starting setup adds casual, sick and earned leave.',
      done: (n) => `${n} leave ${n === 1 ? 'type is' : 'types are'} set up. Each person’s opening balance is added under Leave, Balances, Adjust.`,
      action: 'Open Leave',
    },
  },

  picker: {
    legend: 'Choose a starting setup',
    summary: (s) => `${s.shifts.length} ${s.shifts.length === 1 ? 'shift' : 'shifts'}, ${s.leave.length} kinds of leave`,
    placeLegend: 'Your main location',
    placeName: 'Name of the location',
    placeNameHint: 'For example the name of your office or plant. Add others later.',
    placeState: 'State',
    placeStateChoose: 'Choose a state',
    placeStateHint: 'Payroll’s professional tax and your holidays follow the state.',
    whatTitle: (title) => `${title}: what it adds`,
    shifts: 'Shifts',
    shiftLine: (s) => `${s.name}, ${s.start} to ${s.end}. Grace is ${s.graceMinutes} minutes.`,
    punchNote: 'People can punch from anywhere until you set an office network or position under Work locations.',
    leaveTitle: 'Leave',
    leaveHint: 'These are suggestions, not legal advice. The leave you owe depends on your state’s rules, so check them and change any number.',
    leaveName: (name) => `${name}:`,
    leaveDays: (name) => `${name}, days a year`,
    leaveDaysUnit: 'days a year.',
    leaveUnused: 'Unused days',
    leaveCarry: (name) => `${name}, what happens to unused days`,
    holidaysTitle: 'Holidays',
    holidays: 'Republic Day, Independence Day and Gandhi Jayanti for the next twelve months. Add your state’s own holidays under Attendance, Holidays.',
    weekTitle: 'Weekly offs and departments',
    week: 'Weekly offs are set when you assign people to shifts. Departments are created from your staff spreadsheet.',
    leaveOffNote: 'Leave is not part of your subscription, so no leave types are added.',
    needPlace: 'Give your main location a name and choose its state first.',
    apply: 'Set up with this',
    applying: 'Setting up…',
    askTitle: (title) => `Set up with “${title}”?`,
    ask: (what) => `This adds ${what}. You can change any of it afterwards under Attendance and Leave.`,
    cancel: 'Not yet',
    askLabel: 'Set up',
    toast: (title) => `Set up from “${title}”`,
    what: (o, s, withLeave) =>
      [
        o.calendar && `a work location, ${s.shifts.length} ${s.shifts.length === 1 ? 'shift' : 'shifts'} and three holidays`,
        withLeave && `${s.leave.length} leave types`,
      ]
        .filter(Boolean)
        .join(' and '),
    partial: {
      leaveOnly: 'Your work locations and shifts are already set up, so this adds only the leave types.',
      calendarOnly: 'Your leave types are already set up, so this adds only the location, shifts and holidays.',
    },
  },

  ready: {
    title: 'Ready for payroll?',
    notChecked: 'Payroll is not part of your subscription.',
    noPeople: 'Import your people first. Then each person is checked for what payroll needs, and anything missing is listed here.',
    allClear: 'Nothing is stopping payroll.',
    stops: (n) => `${n} ${n === 1 ? 'thing stops' : 'things stop'} payroll.`,
    blocking: 'Blocking',
    warning: 'Warning',
    mustFix: 'We have to fix this one.',
    noBank: (n) => ({ title: `${n} ${n === 1 ? 'person has' : 'people have'} no bank account`, text: 'They are left out of the bank file until one is added. You can accept this for a month.' }),
    noPan: (n) => ({ title: `${n} ${n === 1 ? 'person has' : 'people have'} no PAN`, text: 'Tax is deducted at the higher rate until a PAN is added.' }),
    withoutLocation: (n) => ({
      title: `${n} ${n === 1 ? 'person has' : 'people have'} no work location`,
      text: 'Professional tax follows the state of the work location, so it cannot be worked out for them.',
    }),
    states: (names) => ({
      title: `No professional tax rules for ${names} yet`,
      text: 'Payroll cannot be approved for people working there while their pay includes professional tax. We are adding states, and you cannot fix this yourself.',
    }),
    noState: (n) => ({ title: `${n} work ${n === 1 ? 'location has' : 'locations have'} no state`, text: 'Choose the state under Attendance, Work locations.' }),
    draft: {
      title: 'The tax and PF rules are drafts',
      text: 'They are awaiting the payroll consultant’s sign-off. Check figures before paying real salaries.',
    },
  },
}
