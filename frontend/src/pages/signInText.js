/** Words for signing in with a Google account, ready for translation. */

/** What the page says when the provider's round trip ends badly. The code is the one the server put in the address. */
const MESSAGES = {
  'oidc.not_linked': 'No login is connected to that Google account. Sign in with your password, then connect it under Security.',
  'oidc.not_allowed': 'Your organization has not turned on sign-in with that account. Use your password, or ask your administrator.',
  'oidc.cancelled': 'Sign-in with Google was cancelled.',
  'oidc.email_unverified': 'Google has not verified that account’s email address, so it cannot be connected.',
  'oidc.taken': 'That Google account is already connected to another login.',
  'oidc.already_connected': 'A Google account is already connected to this login. Disconnect it first.',
  'oidc.unavailable': 'Sign-in with Google is not available right now. Try again shortly.',
  'request.rate_limited': 'Too many attempts. Try again in a few minutes.',
}

const FALLBACK = 'That sign-in could not be verified. Try again, or use your password.'

export function oidcMessage(code) {
  return MESSAGES[code] || FALLBACK
}

export const signInText = {
  google: 'Sign in with Google',
  or: 'or',
  linkedTitle: 'Connected accounts',
  linkedHint: 'Sign in with a work account instead of typing your password. You are still asked for your authenticator code if you use one.',
  none: 'No account connected.',
  connect: 'Connect a Google account',
  disconnect: 'Disconnect',
  connected: 'Google account connected. You can use it to sign in from now on.',
  orgTitle: 'Sign in with Google',
  orgHint: 'People whose Google Workspace account belongs to this domain can sign in with it, once they have connected it under Security. Everyone keeps their password. Leave it empty to turn this off.',
  orgDomain: 'Google Workspace domain',
  orgPassword: 'Your password',
  orgSave: 'Save',
  orgSaved: 'Saved.',
  orgOff: 'Turned off.',
  orgCleared: 'Company email domain removed.',
  emailDomainTitle: 'Company email domain',
  emailDomainHint: 'The email domain that belongs to your organization, such as acme.com. Once you prove you control it, HR can propose a company address for people who were invited at a personal one. Nothing changes until the person switches themselves.',
  emailDomainProve: 'To prove you control this domain, add this TXT record in its DNS settings, then check. It can take a while to appear.',
  emailDomainName: 'Name',
  emailDomainValue: 'Value',
  emailDomainCheck: 'Check the record',
  emailDomainVerified: 'Verified. HR can now propose addresses in this domain.',
  workEmailHint: (org) => `${org} asks you to sign in with a company address.`,
  workEmailUse: 'Use this address',
}
