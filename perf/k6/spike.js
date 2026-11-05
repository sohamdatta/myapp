// A sudden burst: the request rate jumps to a multiple of normal for 30 s, then drops. Shows whether the database pools
// recover and how long queued requests wait. Run after the read test:
//   BASE_URL=http://localhost:8080 EMAIL=hr@scale.test PASSWORD=correct-horse-battery k6 run perf/k6/spike.js
// Budget: under 2% failures, and 95% of requests under 3 s even at the peak.
import http from 'k6/http';
import { check } from 'k6';
import { BASE, signIn } from './common.js';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 300,
      stages: [
        { duration: '20s', target: 5 },
        { duration: '10s', target: Number(__ENV.PEAK_RPS || 80) },
        { duration: '30s', target: Number(__ENV.PEAK_RPS || 80) },
        { duration: '10s', target: 5 },
        { duration: '30s', target: 5 },   // recovery: latency must be back to normal here
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<3000'],
  },
};

export function setup() {
  return { headers: signIn() };
}

export default function (data) {
  const res = http.get(`${BASE}/api/org/people/employees`, { headers: data.headers, tags: { name: 'people list' } });
  check(res, { 'people list 200': (r) => r.status === 200 });
}
