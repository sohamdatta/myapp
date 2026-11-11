import { expect, type Browser, type Page } from '@playwright/test';
import { PASSWORD, newPage, signIn } from './helpers';

// Joins an Organization from an invitation without opening its page. The invitation page looks the link up,
// and those looks are limited per address (120 in 5 minutes): suites that make many users at once, running
// beside the main story, would pass that limit in CI. Accepting through the API skips the look; signing in
// then goes through the screen as usual. The invitation page itself is covered by 01-users and 26-password-reset.

/** The token from an invitation link such as "/?invite=abc". */
export function tokenOf(link: string): string {
  const t = new URL(link, 'http://x').searchParams.get('invite');
  if (!t) throw new Error(`No invitation token in ${link}`);
  return t;
}

export async function join(browser: Browser, token: string, email: string, password = PASSWORD): Promise<Page> {
  const page = await newPage(browser);
  const r = await page.request.post('/api/invitations/accept', { data: { token, password } });
  expect(r.status(), await r.text()).toBe(200);
  await page.context().close();
  return signIn(browser, email, password);
}
