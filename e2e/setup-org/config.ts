// What to set up. Edit this file, or point SETUP_CONFIG at a JSON file with the same shape (it is merged over these).
// Everything here is made through the screens, in the order a new customer would do it.
// The PAN, TAN, bank and IFSC below are made-up examples that pass the screens' checks: replace them.

const year = new Date().getFullYear();

export const defaults = {
  // Who signs in to create the Organization (the platform Super Admin), and the first Org Admin who will configure it.
  superAdmin: { email: 'admin@example.com', password: 'ChangeMe-12345' },
  org: {
    name: 'Acme Industries',
    adminEmail: 'owner@acme.example',
    adminPassword: 'Change-me-Acme-1',   // chosen when the invitation is accepted
  },

  // Getting started: office | factory | retail. State is the two-letter code (KA, MH, TN ...).
  starter: { kind: 'office' as 'office' | 'factory' | 'retail', locationName: 'Head office', state: 'KA' },

  attendance: {
    locations: [{ name: 'Pune branch', state: 'MH', check: 'none' as 'none' | 'ip' | 'location' | 'either' }],
    shifts: [{ name: 'Night', start: '22:00', end: '06:00', grace: '10' }],
    holidays: [
      { date: `${year}-12-25`, name: 'Christmas' },
      { date: `${year}-05-01`, name: 'Labour Day' },
    ],
    wfh: { enabled: true, perWeek: '2', perMonth: '8' },
  },

  leave: {
    types: [{ name: 'Marriage leave', quota: '5', cap: '0' }],
  },

  people: {
    departments: [
      { name: 'Engineering' },
      { name: 'Platform', parent: 'Engineering' },
      { name: 'Sales' },
      { name: 'Finance' },
      { name: 'Human Resources' },
      { name: 'Operations' },
    ],
  },

  payroll: {
    employer: {
      name: 'Acme Industries Private Limited',
      pan: 'AAACA1234K',
      tan: 'BLRA12345E',
      address: '1st floor, MG Road, Bengaluru 560001',
      debitAccount: '50200012345678',
      ifsc: 'HDFC0000123',
    },
    registrations: [
      { statute: 'pf', number: 'KNBNG0099999000' },
      { statute: 'esi', number: '53000999990001001' },
    ],
    payGroup: { name: 'Contract staff', cutoffDay: '25', payDay: '30' },
  },

  performance: {
    competencies: [
      { name: 'Ownership', description: 'Sees work through to the end' },
      { name: 'Collaboration', description: 'Works well with other teams' },
    ],
    checkinDays: '30, 60',
    recommendDays: '21',
  },

  exits: { noticeDaysPermanent: '60', responseDays: '5', formerAccessMonths: '6' },

  // People to invite. The script collects their links in setup-report/invitations.md; no email is sent.
  users: [
    { email: 'hr@acme.example', roles: ['HR Admin'] },
    { email: 'payroll@acme.example', roles: ['Payroll Operator'] },
    { email: 'approver@acme.example', roles: ['Payroll Approver'] },
    { email: 'manager@acme.example', roles: ['Manager', 'Employee'] },
    { email: 'it@acme.example', roles: ['IT Admin'] },
    { email: 'auditor@acme.example', roles: ['Auditor'] },
  ],
};

export type SetupConfig = typeof defaults;
