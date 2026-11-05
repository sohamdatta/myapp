// A long, steady load to catch slow leaks: run it against a container with a memory limit and watch the app's memory
// (docker stats) and the response times. If memory keeps climbing or latency drifts upward over the hour, something is
// holding on to data. Default 30 minutes of 10 users; set DURATION=2h for a longer run.
//   BASE_URL=... EMAIL=... PASSWORD=... DURATION=30m k6 run perf/k6/soak.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, setupData } from './common.js';

export const options = {
  scenarios: { soak: { executor: 'constant-vus', vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '30m' } },
  thresholds: { http_req_failed: ['rate<0.005'], http_req_duration: ['p(95)<500'] },
};

export function setup() {
  return setupData();
}

export default function (data) {
  for (const path of ['/api/org/people/employees', '/api/org/people/org-chart', '/api/org/payroll/runs', '/api/auth/me']) {
    check(http.get(`${BASE}${path}`, { headers: data.headers }), { 'ok': (r) => r.status === 200 });
  }
  if (data.run) {
    check(http.get(`${BASE}/api/org/payroll/runs/${data.run}`, { headers: data.headers }), { 'run ok': (r) => r.status === 200 });
  }
  sleep(2);
}
