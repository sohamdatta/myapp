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
  // An Organization's own sign-in address, such as acme.hr.example.com
  orgWelcome: (org) => `Sign in to ${org}`,
  orgHero: (org) => `Welcome to ${org}`,
  orgWelcomeHint: (org) => `Use your work email. Only people in ${org} can sign in here.`,
  poweredBy: (product) => `Powered by ${product}`,
  unknownTitle: 'Nobody signs in at this address',
  unknownHint: 'This address does not belong to an Organization. Its short name may have changed; ask your administrator for the new address, or use the main sign-in page.',
  unknownGo: 'Go to the main sign-in page',
  addressTitle: 'Sign-in address',
  addressHint: (org) =>
    `People in ${org} can sign in at this address. It shows your name and logo, and lets in only members of ${org}. Bookmark it or share it with your people.`,
  addressLabel: 'Short name',
  addressRule: '3 to 40 lowercase letters, digits or single hyphens.',
  addressWarn: (old) => `The old address, ${old}, stops working as soon as you save. Tell your people the new one.`,
  addressSave: 'Change the address',
  addressSaved: 'Saved. Your sign-in address has changed.',
  addressCopy: 'Copy',
  addressCopied: 'Copied.',
}
