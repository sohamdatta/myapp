import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { execFileSync } from 'child_process';

// Shared by the attendance test-case suites (40 to 42), which run as one chain on one Organization,
// Acme Test: the first builds it and writes what the next ones need here.

export function statePath(name: string): string {
  return path.join(os.tmpdir(), `e2e-attendance-cases-${name}.json`);
}

export function readState(name: string): any {
  return JSON.parse(fs.readFileSync(statePath(name), 'utf8'));
}

export function writeState(name: string, value: unknown) {
  fs.writeFileSync(statePath(name), JSON.stringify(value));
}

/**
 * Runs one SQL statement as the database owner, for the few cases the screens cannot set up
 * (a device silent for a day) or read (every audit action at once). E2E_DATABASE_URL names the
 * database; the default is the one CI starts.
 */
export function dbAdmin(sql: string): string {
  const url = process.env.E2E_DATABASE_URL || 'postgresql://postgres:postgres@localhost:5432/hrms';
  return execFileSync('psql', [url, '-At', '-c', sql], { encoding: 'utf8' });
}

/**
 * A holiday date in the current calendar year, months away from today, so the year selector shows it
 * and no day the other cases work out falls on it: the 10th + n of June, or of November in June.
 */
export function holidayDay(n: number, year: number, month: number): string {
  return `${year}-${month === 6 ? '11' : '06'}-${String(10 + n).padStart(2, '0')}`;
}
