// Turns the frontend coverage the browser tests recorded (E2E_COVERAGE=<folder> npm test) into a report:
//   npm run coverage -- <folder> [output folder, default coverage-report]
import { CoverageReport } from 'monocart-coverage-reports';
import { readdirSync, readFileSync } from 'node:fs';

const [dir = 'coverage-raw', out = 'coverage-report'] = process.argv.slice(2);
const report = new CoverageReport({
  name: 'Frontend coverage from the browser tests',
  outputDir: out,
  reports: [['v8'], ['console-summary'], ['json-summary']],
  entryFilter: (e) => e.url.includes('/src/') && !e.url.includes('.css'),
  sourceFilter: (p) => !p.includes('node_modules'),
  sourcePath: (p) => p.replace(/^.*?\/src\//, 'src/').replace(/^localhost-5173\//, ''),
});
for (const f of readdirSync(dir)) await report.add(JSON.parse(readFileSync(`${dir}/${f}`, 'utf8')));
await report.generate();
