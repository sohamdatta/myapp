// Fails when a pull request adds a browser test that steps more than five days from today
// with isoDay(n). Such a date can land in another month or tax year on some days and not
// others, which is how two suites broke at the end of October 2026.
//
// Use withinMonth(n) or withinTaxYear(n) from tests/helpers.ts instead, or, when the date
// is meant to be far away (a refusal for a date years ago), end the line with
// `// date-safe: <why>`. Lines already on main are not checked.
//
// Usage: node check-dates.mjs [base ref, default origin/main]

import { execFileSync } from 'node:child_process';

const base = process.argv[2] || 'origin/main';
const LIMIT = 5;

const diff = execFileSync('git', ['diff', '--unified=0', '--no-color', `${base}...HEAD`, '--', 'tests'], {
  encoding: 'utf8',
  cwd: new URL('.', import.meta.url),
});

const problems = [];
let file = null;
let line = 0;
for (const text of diff.split('\n')) {
  if (text.startsWith('+++ ')) {
    file = text.slice(6);
    continue;
  }
  const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)/.exec(text);
  if (hunk) {
    line = Number(hunk[1]);
    continue;
  }
  if (text.startsWith('+') && !text.startsWith('+++')) {
    const added = text.slice(1);
    if (!added.includes('// date-safe:')) {
      for (const m of added.matchAll(/\bisoDay\(\s*(-?\d+)/g)) {
        if (Math.abs(Number(m[1])) > LIMIT) {
          problems.push(`${file}:${line}  isoDay(${m[1]}) can cross a month or tax-year end`);
        }
      }
    }
    line += 1;
  }
}

if (problems.length) {
  console.error(problems.join('\n'));
  console.error(`\nUse withinMonth(n) or withinTaxYear(n) from tests/helpers.ts, or add "// date-safe: <why>" to the line.`);
  process.exit(1);
}
console.log('Browser-test dates: no unclamped offsets above 5 days in this change.');
