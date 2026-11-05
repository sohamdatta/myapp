// A realistic mix on the 2,000-employee Organization: mostly reads, some people opening a payroll run and a payslip,
// and a few downloading the register. Ramps up, holds, ramps down, so queueing for database connections shows.
//   BASE_URL=http://localhost:8080 EMAIL=hr@scale.test PASSWORD=correct-horse-battery k6 run perf/k6/mixed.js
// Budgets (override with READ_P95_MS, RUN_P95_MS, FILE_P95_MS): reads 300 ms, opening a run 500 ms, a payslip PDF or the
// register 2,000 ms, and under 1% of requests failing.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, setupData } from './common.js';

const READ = Number(__ENV.READ_P95_MS || 300);
const RUN = Number(__ENV.RUN_P95_MS || 500);
const FILE = Number(__ENV.FILE_P95_MS || 2000);

export const options = {
  scenarios: {
    mix: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Number(__ENV.VUS || 30) },
        { duration: __ENV.HOLD || '90s', target: Number(__ENV.VUS || 30) },
        { duration: '15s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:people list}': [`p(95)<${READ}`],
    'http_req_duration{name:people search}': [`p(95)<${READ}`],
    'http_req_duration{name:payroll runs}': [`p(95)<${READ}`],
    'http_req_duration{name:open run}': [`p(95)<${RUN}`],
    'http_req_duration{name:payslip pdf}': [`p(95)<${FILE}`],
    'http_req_duration{name:register}': [`p(95)<${FILE}`],
  },
};

export function setup() {
  return setupData();
}

function get(data, name, path) {
  const res = http.get(`${BASE}${path}`, { headers: data.headers, tags: { name } });
  check(res, { [`${name} 200`]: (r) => r.status === 200 });
}

export default function (data) {
  const roll = Math.random();
  get(data, 'people list', '/api/org/people/employees');
  if (roll < 0.5) get(data, 'people search', `/api/org/people/employees?q=Person%20${1 + Math.floor(Math.random() * 1999)}`);
  if (roll < 0.3) get(data, 'payroll runs', '/api/org/payroll/runs');
  if (data.run && roll < 0.25) get(data, 'open run', `/api/org/payroll/runs/${data.run}`);
  if (data.run && data.employment && roll < 0.1) get(data, 'payslip pdf', `/api/org/payroll/runs/${data.run}/employees/${data.employment}/payslip`);
  if (data.run && roll < 0.04) get(data, 'register', `/api/org/payroll/runs/${data.run}/outputs/register`);
  sleep(1 + Math.random());
}
