// Read load on an Organization of 2,000 employees (seeded by PayrollScaleTest, which leaves the database in place).
// Budget (roadmap Q1): reads p95 under 300 ms. Run:
//   BASE_URL=http://localhost:8080 EMAIL=hr@scale.test PASSWORD=correct-horse-battery k6 run perf/k6/reads.js
// Each read is tagged by name, so the summary shows which one is slow. Thresholds are set by READ_P95_MS
// (default 300). A breach makes k6 exit non-zero; the workflow ignores that unless run with enforce.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const BUDGET = Number(__ENV.READ_P95_MS || 300);

const reads = {
  'people list': '/api/org/people/employees',
  'people search': '/api/org/people/employees?q=Person%2017',
  'org chart': '/api/org/people/org-chart',
  'payroll runs': '/api/org/payroll/runs',
  'who am I': '/api/auth/me',
};

export const options = {
  scenarios: {
    readers: { executor: 'constant-vus', vus: Number(__ENV.VUS || 30), duration: __ENV.DURATION || '60s' },
  },
  // Always declared, so the summary shows a p95 per read; the workflow only fails on a breach when enforcing.
  thresholds: Object.fromEntries(Object.keys(reads).map((n) => [`http_req_duration{name:${n}}`, [`p(95)<${BUDGET}`]])),
};

export function setup() {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: __ENV.EMAIL, password: __ENV.PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) throw new Error(`sign-in failed: ${res.status} ${res.body}`);
  // sign-in gives an identity token; entering the Organization gives the one the pages need
  const me = http.get(`${BASE}/api/auth/me`, { headers: { Authorization: `Bearer ${res.json('token')}` } });
  const org = me.json('organizations.0.tenantId');
  const entered = http.post(`${BASE}/api/auth/enter`, JSON.stringify({ organizationId: org }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${res.json('token')}` } });
  if (entered.status !== 200) throw new Error(`entering the Organization failed: ${entered.status} ${entered.body}`);
  return { token: entered.json('token') };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  for (const [name, path] of Object.entries(reads)) {
    const res = http.get(`${BASE}${path}`, { headers, tags: { name } });
    check(res, { [`${name} 200`]: (r) => r.status === 200 });
  }
  sleep(1);
}
