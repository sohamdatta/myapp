/**
 * Small line icons for the sidebar, one per page id. Drawn on a 24 px grid with the text colour,
 * so they follow the current, hover and dark-mode colours of the link they sit in.
 */
const PATHS = {
  // platform
  organizations: ['M3 21h18', 'M5 21V7l7-4 7 4v14', 'M9 21v-5h6v5', 'M9 10h.01M15 10h.01'],
  users: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8', 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
  support: ['M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10'],
  // my work
  'my-onboarding': ['M4 22V4a1 1 0 0 1 1-1h11l-2 4 2 4H5'],
  'my-attendance': ['M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20', 'M12 6v6l4 2'],
  'my-leave': ['M8 2v4M16 2v4', 'M3 10h18', 'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2', 'M9 16l2 2 4-4'],
  'my-tasks': ['M9 11l3 3L22 4', 'M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11'],
  'my-pay': ['M2 7h20v12H2z', 'M12 16a3 3 0 1 0 0-6 3 3 0 0 0 0 6', 'M6 7V5h12v2'],
  'my-profile': ['M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2', 'M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8'],
  'my-exit': ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'],
  // organization
  'org-chart': ['M9 3h6v5H9z', 'M3 16h6v5H3zM15 16h6v5h-6z', 'M12 8v4M6 16v-4h12v4'],
  people: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8', 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
  attendance: ['M3 3v18h18', 'M7 15l4-4 3 3 5-6'],
  'work-calendar': ['M12 22s-7-6.2-7-12a7 7 0 1 1 14 0c0 5.8-7 12-7 12', 'M12 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5'],
  leave: ['M8 2v4M16 2v4', 'M3 10h18', 'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2'],
  exits: ['M14 3h5a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-5', 'M8 7l-5 5 5 5', 'M3 12h12'],
  payroll: ['M12 2v20', 'M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6'],
  'tax-declarations': ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6', 'M8 13h8M8 17h5'],
  settings: ['M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3', 'M1 14h6M9 8h6M17 16h6'],
  audit: ['M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10', 'M9 12l2 2 4-4'],
}

const FALLBACK = ['M4 6h16M4 12h16M4 18h10']

export function NavIcon({ id }) {
  const paths = PATHS[id] || FALLBACK
  return (
    <svg className="nav-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {paths.map((d) => (
        <path key={d} d={d} />
      ))}
    </svg>
  )
}
