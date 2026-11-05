// Shared by the k6 scenarios: sign in as the person PayrollScaleTest leaves behind, enter the Organization, and find a
// payroll run and an employee in it. Used by mixed.js, spike.js and soak.js.
import http from 'k6/http';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function signIn() {
  const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: __ENV.EMAIL, password: __ENV.PASSWORD }), { headers: JSON_HEADERS });
  if (login.status !== 200) throw new Error(`sign-in failed: ${login.status} ${login.body}`);
  const identity = { Authorization: `Bearer ${login.json('token')}` };
  const me = http.get(`${BASE}/api/auth/me`, { headers: identity });
  const entered = http.post(`${BASE}/api/auth/enter`, JSON.stringify({ organizationId: me.json('organizations.0.tenantId') }),
    { headers: { ...JSON_HEADERS, ...identity } });
  if (entered.status !== 200) throw new Error(`entering the Organization failed: ${entered.status} ${entered.body}`);
  return { Authorization: `Bearer ${entered.json('token')}` };
}

/** The headers plus the ids the scenarios read: the newest payroll run and one employee in it. */
export function setupData() {
  const headers = signIn();
  const runs = http.get(`${BASE}/api/org/payroll/runs`, { headers }).json('runs');
  const run = runs && runs.length ? runs[0].id : null;
  let employment = null;
  if (run) {
    const detail = http.get(`${BASE}/api/org/payroll/runs/${run}`, { headers }).json();
    employment = detail.people && detail.people.length ? detail.people[0].employmentId : null;
  }
  return { headers, run, employment };
}
